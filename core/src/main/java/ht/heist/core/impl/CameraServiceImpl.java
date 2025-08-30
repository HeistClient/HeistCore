// ============================================================================
// CameraServiceImpl.java
// -----------------------------------------------------------------------------
// Title
//   Human-like camera control with realistic randomness.
//
// What this does
//   • Emits AWT key taps on RuneLite canvas for camera yaw/pitch
//   • Interval scheduler (Exponential/Poisson timing) for spontaneous rotates
//   • Truncated-Gaussian yaw magnitudes (mostly small, occasional nudge)
//   • AR(1) directional bias so yaw “drifts” a bit instead of ping-ponging
//   • Refractory window to prevent machine-gun rotations
//   • Light session fatigue (longer intervals / smaller moves over time)
//
// Integration
//   • Marked @Singleton so all plugins share one instance
//   • Call camera.onTick() every GameTick
//   • Sprinkle hooks: onFailFindTree(), onRecoveryPulse(),
//                     onPreObjectClick(), onPostObjectClick(), onIdleTick()
//
// Java 11
//   • No pattern matching, no Lombok, plain javax.inject
// ============================================================================

package ht.heist.core.impl;

// === Core deps ===============================================================
import ht.heist.core.services.CameraService;
import ht.heist.core.services.HumanizerService;
import net.runelite.api.Client;

// === JSR-330 / AWT / JDK ====================================================
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.util.Random;

@Singleton
public class CameraServiceImpl implements CameraService
{
    // =========================================================================
    // === Dependencies ========================================================
    // =========================================================================
    private final Client client;
    private final HumanizerService human;
    private final Random rng = new Random();

    // =========================================================================
    // === Heuristics / Tunables ==============================================
    // =========================================================================

    // -- Mapping key taps to degrees (approximate; tune for your setup) -------
    private static final int DEG_PER_YAW_TAP = 8;     // ~8° per LEFT/RIGHT tap
    private static final int PITCH_TAPS_FULL = 24;    // DOWN x24 -> UP xN covers full range

    // -- Tap pacing (ms) ------------------------------------------------------
    private static final int TAP_SLEEP_MIN = 18;      // between press/release and taps
    private static final int TAP_SLEEP_MAX = 38;

    // -- Interval scheduler (Exponential/Poisson) -----------------------------
    // “Spontaneous” rotations fire around this average spacing; randomized.
    private static final double INTERVAL_MEAN_SEC = 12.0;   // mean seconds between rotations
    private static final long   REFRACTORY_MS     = 900L;   // minimum gap between rotations
    private static final int    INTERVAL_YAW_CHANCE_PCT = 55;   // chance to rotate when interval elapses
    private static final int    INTERVAL_YAW_MAX_DEG    = 28;   // clamp for interval rotations

    // -- Gaussian yaw (truncated) ---------------------------------------------
    private static final double YAW_MEAN_DEG   = 10.0;      // average spontaneous nudge
    private static final double YAW_STD_DEG    = 6.0;       // spread (more small moves than big)
    private static final int    YAW_MAX_ABS    = 34;        // global hard safety clamp

    // -- AR(1) directional bias (keep direction “sticky”) ---------------------
    private static final double AR_ALPHA     = 0.82;        // 0..1 (closer to 1 = stickier)
    private static final double AR_NOISE_STD = 3.5;         // wiggle in the bias per action
    private double yawBiasDeg = 0.0;                        // evolving bias state

    // -- Contextual chances (percent) & caps ---------------------------------
    private static final int FAIL_FIND_CHANCE_PCT  = 85;    // likely rotate if we failed a find
    private static final int FAIL_FIND_MAX_DEG     = 34;

    private static final int RECOVERY_CHANCE_PCT   = 65;
    private static final int RECOVERY_MAX_DEG      = 30;

    private static final int PRE_CLICK_CHANCE_PCT  = 10;    // subtle tweak before a click
    private static final int PRE_CLICK_MAX_DEG     = 10;

    private static final int POST_CLICK_CHANCE_PCT = 12;    // tiny re-aim after a click
    private static final int POST_CLICK_MAX_DEG    = 12;

    private static final int IDLE_CHANCE_PCT       = 25;    // sometimes look around on idle
    private static final int IDLE_MAX_DEG          = 22;

    // -- Micro pitch taps (rare, life-like) -----------------------------------
    private static final boolean ENABLE_MICRO_PITCH    = true;
    private static final int     MICRO_PITCH_CHANCE_PCT = 20;  // 1-in-5 after a yaw

    // =========================================================================
    // === Scheduler / Session State ==========================================
    // =========================================================================
    private long nextIntervalAt = System.currentTimeMillis() + sampleIntervalMs();
    private long lastRotationAt = 0L;
    private final long sessionStartMs = System.currentTimeMillis();

    // =========================================================================
    // === Construction ========================================================
    // =========================================================================
    @Inject
    public CameraServiceImpl(Client client, HumanizerService human)
    {
        this.client = client;
        this.human = human;
    }

    // =========================================================================
    // === Public API: Direct Controls ========================================
    // =========================================================================

    // -- Nudge yaw by degrees -------------------------------------------------
    @Override
    public void nudgeYaw(int degrees)
    {
        if (degrees == 0) return;
        final Canvas canvas = client.getCanvas();
        if (canvas == null) return;

        final int taps = Math.max(1, Math.abs(degrees) / DEG_PER_YAW_TAP);
        final int key  = degrees > 0 ? KeyEvent.VK_RIGHT : KeyEvent.VK_LEFT;

        for (int i = 0; i < taps; i++)
        {
            tapKey(canvas, key);
        }
    }

    // -- Set pitch to percentage ---------------------------------------------
    @Override
    public void setPitch(double percent)
    {
        final Canvas canvas = client.getCanvas();
        if (canvas == null) return;

        if (percent < 0.0) percent = 0.0;
        if (percent > 1.0) percent = 1.0;

        // Phase 1: drive pitch fully down (baseline)
        for (int i = 0; i < PITCH_TAPS_FULL; i++)
        {
            tapKey(canvas, KeyEvent.VK_DOWN);
        }

        // Phase 2: raise to target
        final int upTaps = (int)Math.round(percent * PITCH_TAPS_FULL);
        for (int i = 0; i < upTaps; i++)
        {
            tapKey(canvas, KeyEvent.VK_UP);
        }
    }

    // =========================================================================
    // === Public API: Randomized / Context Hooks ==============================
    // =========================================================================

    // -- Tick scheduler (call every game tick) --------------------------------
    @Override
    public void onTick()
    {
        final long now = System.currentTimeMillis();
        if (now < nextIntervalAt) return;

        // Respect refractory window
        if (!tryRespectRefractory(now))
        {
            nextIntervalAt = now + sampleIntervalMs();
            return;
        }

        // Interval hit: roll chance to rotate, then reschedule
        if (roll(INTERVAL_YAW_CHANCE_PCT))
        {
            doRandomYawGaussian(INTERVAL_YAW_MAX_DEG);
        }
        nextIntervalAt = now + sampleIntervalMs();
    }

    // -- Context: failed to find a target ------------------------------------
    @Override
    public void onFailFindTree()
    {
        rotateWithChance(FAIL_FIND_CHANCE_PCT, FAIL_FIND_MAX_DEG);
    }

    // -- Context: during recovery --------------------------------------------
    @Override
    public void onRecoveryPulse()
    {
        rotateWithChance(RECOVERY_CHANCE_PCT, RECOVERY_MAX_DEG);
    }

    // -- Context: right before a world-object click ---------------------------
    @Override
    public void onPreObjectClick()
    {
        rotateWithChance(PRE_CLICK_CHANCE_PCT, PRE_CLICK_MAX_DEG);
    }

    // -- Context: right after a world-object click ----------------------------
    @Override
    public void onPostObjectClick()
    {
        rotateWithChance(POST_CLICK_CHANCE_PCT, POST_CLICK_MAX_DEG);
    }

    // -- Context: idle tick (no actions) --------------------------------------
    @Override
    public void onIdleTick()
    {
        rotateWithChance(IDLE_CHANCE_PCT, IDLE_MAX_DEG);
    }

    // =========================================================================
    // === Internals: Rotation Orchestration ==================================
    // =========================================================================

    // -- Gate + Gaussian yaw path ---------------------------------------------
    private void rotateWithChance(int chancePct, int maxAbsDeg)
    {
        if (!roll(chancePct)) return;
        final long now = System.currentTimeMillis();
        if (!tryRespectRefractory(now)) return;
        doRandomYawGaussian(maxAbsDeg);
    }

    // -- Perform a Gaussian-sampled yaw, with optional micro-pitch ------------
    private void doRandomYawGaussian(int maxAbsDeg)
    {
        int yaw = sampleTruncatedGaussianDegrees(YAW_MEAN_DEG, YAW_STD_DEG, Math.min(maxAbsDeg, YAW_MAX_ABS));
        nudgeYaw(yaw);

        // Rare micro-pitch tap to add “life”
        if (ENABLE_MICRO_PITCH && roll(MICRO_PITCH_CHANCE_PCT))
        {
            final Canvas canvas = client.getCanvas();
            if (canvas != null)
            {
                tapKey(canvas, rng.nextBoolean() ? KeyEvent.VK_UP : KeyEvent.VK_DOWN);
            }
        }
    }

    // =========================================================================
    // === Internals: Probabilistic Building Blocks ============================
    // =========================================================================

    // -- Exponential inter-arrival (Poisson process) --------------------------
    private long sampleIntervalMs()
    {
        // U in (0,1]; T = -ln(U) * mean
        double u = Math.max(1e-9, rng.nextDouble());
        double seconds = -Math.log(u) * INTERVAL_MEAN_SEC;

        // Light fatigue: longer intervals later in session (max +25%)
        double fatigue = 1.0 + Math.min(0.25,
                (System.currentTimeMillis() - sessionStartMs) / (60_000.0 * 60.0) * 0.25);

        return (long) (seconds * 1000.0 * fatigue);
    }

    // -- Refractory window ----------------------------------------------------
    private boolean tryRespectRefractory(long now)
    {
        if (now - lastRotationAt < REFRACTORY_MS) return false;
        lastRotationAt = now;
        return true;
    }

    // -- Truncated Gaussian yaw + AR(1) bias + fatigue ------------------------
    private int sampleTruncatedGaussianDegrees(double mean, double std, int maxAbs)
    {
        // Base Gaussian around mean
        double g = rng.nextGaussian() * std + mean;

        // AR(1) bias: keep some directional drift between consecutive moves
        yawBiasDeg = AR_ALPHA * yawBiasDeg + rng.nextGaussian() * AR_NOISE_STD;
        g += yawBiasDeg;

        // Flip sign sometimes to avoid infinite drift one way
        if (rng.nextInt(5) == 0) g = -g;

        // Fatigue: small reduction of magnitude over long sessions (<= 15%)
        double fatig = 1.0 - Math.min(0.15,
                (System.currentTimeMillis() - sessionStartMs) / (60_000.0 * 60.0) * 0.15);
        g *= fatig;

        // Truncate to ±maxAbs; avoid returning 0
        int yaw = (int) Math.round(Math.max(-maxAbs, Math.min(maxAbs, g)));
        if (yaw == 0) yaw = (rng.nextBoolean() ? 1 : -1);
        return yaw;
    }

    // -- Bernoulli helper -----------------------------------------------------
    private boolean roll(int pct)
    {
        if (pct <= 0) return false;
        if (pct >= 100) return true;
        return rng.nextInt(100) < pct;
    }

    // -- Key tap w/ small randomized sleeps ----------------------------------
    private void tapKey(Canvas canvas, int keyCode)
    {
        try
        {
            long now = System.currentTimeMillis();
            canvas.dispatchEvent(new java.awt.event.KeyEvent(
                    canvas, java.awt.event.KeyEvent.KEY_PRESSED, now, 0, keyCode, java.awt.event.KeyEvent.CHAR_UNDEFINED));
            human.sleep(TAP_SLEEP_MIN, TAP_SLEEP_MAX);

            canvas.dispatchEvent(new java.awt.event.KeyEvent(
                    canvas, java.awt.event.KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, keyCode, java.awt.event.KeyEvent.CHAR_UNDEFINED));
            human.sleep(TAP_SLEEP_MIN, TAP_SLEEP_MAX);
        }
        catch (Exception ignored)
        {
            // Do not re-interrupt; keep loops smooth.
        }
    }
}
