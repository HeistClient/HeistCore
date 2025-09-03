// ============================================================================
// FILE: IdleController.java
// PATH: core-rl/src/main/java/ht/heist/corerl/input/IdleController.java
// PACKAGE: ht.heist.corerl.input
// -----------------------------------------------------------------------------
// TITLE
//   IdleController — Tiny scheduler for occasional idle waits + fatigue drift
//
// WHAT THIS CLASS DOES
//   • maybeIdle(): randomly suggests brief idle pauses (human-like dead time).
//   • nextFatigue(): slowly raises fatigue; call periodically to simulate longer
//     sessions. You can also drop fatigue when large idles occur.
//
// USAGE
//   In your plugin tick:
//     long idleMs = idleController.maybeIdle();
//     if (idleMs > 0) Thread.sleep(idleMs);
//     humanizer.setFatigueLevel( idleController.nextFatigue(humanizer.fatigueLevel()) );
//
// EXTENSIONS
//   • Add "micro wiggles" during idle: dispatch a couple small move events.
//   • Add "long idle" after certain actions to simulate attention lapses.
// ============================================================================

package ht.heist.corerl.input;

import java.util.Random;

import javax.inject.Singleton;

@Singleton
public final class IdleController
{
    private final Random rng = new Random();
    private long nextIdleAt = 0L;

    /** Suggest a short idle wait (ms) occasionally; 0 if not right now. */
    public long maybeIdle() {
        long now = System.currentTimeMillis();
        if (now < nextIdleAt) return 0L;

        // ~10% chance to idle a short random duration
        if (rng.nextDouble() < 0.10) {
            long d = 120 + rng.nextInt(380); // 120..500ms
            // don't idle again for ~1.5-3.0s
            nextIdleAt = now + 1_500 + rng.nextInt(1_500);
            return d;
        }
        return 0L;
    }

    /** Slowly raise fatigue; clamp to [0..1]. */
    public double nextFatigue(double current) {
        double f = current + 0.002; // grows ~0.2 per 100 ticks; tune per game rate
        if (f > 1.0) f = 1.0;
        if (f < 0.0) f = 0.0;
        return f;
    }
}
