// ============================================================================
// FILE: HumanizerImpl.java
// PATH: core-java/src/main/java/ht/heist/corejava/input/HumanizerImpl.java
// PACKAGE: ht.heist.corejava.input
// -----------------------------------------------------------------------------
// TITLE
//   HumanizerImpl — Default "feel" impl for the core-java Humanizer API
//
// ROLE
//   Pure-Java provider of human-like timing + path parameters used by
//   MouseServiceImpl. This implements ht.heist.corejava.api.input.Humanizer,
//   so it can be passed directly to MouseServiceImpl(..).
// ============================================================================

package ht.heist.corejava.input;

import ht.heist.corejava.api.input.Humanizer;

import java.util.concurrent.ThreadLocalRandom;

public final class HumanizerImpl implements Humanizer
{
    // ---- Timing knobs (ms) --------------------------------------------------
    private final int dwellMinMs;
    private final int dwellMaxMs;

    private final int reactionMeanMs;
    private final int reactionStdMs;

    private final int holdMinMs;
    private final int holdMaxMs;

    // Key waits (lead/lag) around Shift — sampled per call
    private final int keyMinMs;
    private final int keyMeanMs;
    private final int keyStdMs;

    // ---- Path & pacing knobs ------------------------------------------------
    private final double pathCurvMean;    // mean curvature
    private final double pathCurvStd;     // std for curvature jitter
    private final double pathWobblePx;

    private final int stepMinMs;
    private final int stepMaxMs;

    private final double approachEaseIn;  // 0..1 slowdown near the end

    // ---- Sticky drift (AR(1)) ----------------------------------------------
    private final double driftAlpha;      // e.g., 0.86
    private final double driftNoiseStd;   // e.g., 0.90
    private final double driftClampAbs;   // e.g., 3.0 (px)

    private double driftX = 0.0;          // tiny bias state
    private double driftY = 0.0;

    // ---- Overshoot/correction knobs ----------------------------------------
    private final double overshootProb;
    private final double overshootMaxPx;
    private final int    correctionPauseMeanMs;
    private final int    correctionPauseStdMs;

    // ---- Fatigue ------------------------------------------------------------
    private volatile double fatigue01 = 0.0; // 0 = fresh, 1 = fatigued

    // ---- Constructors -------------------------------------------------------

    /** Default profile aligned with your previous feel. */
    public HumanizerImpl() {
        this(
                // timing (dwell, reaction, hold)
                240, 360,    // dwell min/max
                150, 45,     // reaction mean/std
                42,  65,     // hold min/max
                // key waits
                25, 30, 8,   // key min/mean/std (lead & lag symmetric)
                // path & pacing
                0.55, 0.12,  // curvature mean/std (per-path jitter)
                1.6,         // micro wobble px
                6, 12,       // step min/max ms
                0.35,        // approach ease-in [0..1]
                // drift
                0.86, 0.90, 3.0, // alpha, noise, clampAbs
                // overshoot
                0.10, 12.0,  // prob, max overshoot px
                70,  25      // correction pause mean/std ms
        );
    }

    /** Full-control constructor for custom/learned profiles. */
    public HumanizerImpl(
            int dwellMinMs, int dwellMaxMs,
            int reactionMeanMs, int reactionStdMs,
            int holdMinMs, int holdMaxMs,
            int keyMinMs, int keyMeanMs, int keyStdMs,
            double pathCurvMean, double pathCurvStd,
            double pathWobblePx,
            int stepMinMs, int stepMaxMs,
            double approachEaseIn,
            double driftAlpha, double driftNoiseStd, double driftClampAbs,
            double overshootProb, double overshootMaxPx,
            int correctionPauseMeanMs, int correctionPauseStdMs)
    {
        this.dwellMinMs     = dwellMinMs;
        this.dwellMaxMs     = Math.max(dwellMinMs, dwellMaxMs);
        this.reactionMeanMs = reactionMeanMs;
        this.reactionStdMs  = Math.max(0, reactionStdMs);
        this.holdMinMs      = holdMinMs;
        this.holdMaxMs      = Math.max(holdMinMs, holdMaxMs);

        this.keyMinMs  = Math.max(0, keyMinMs);
        this.keyMeanMs = Math.max(0, keyMeanMs);
        this.keyStdMs  = Math.max(0, keyStdMs);

        this.pathCurvMean   = Math.max(0.0, pathCurvMean);
        this.pathCurvStd    = Math.max(0.0, pathCurvStd);
        this.pathWobblePx   = Math.max(0.0, pathWobblePx);
        this.stepMinMs      = Math.max(1, stepMinMs);
        this.stepMaxMs      = Math.max(this.stepMinMs, stepMaxMs);
        this.approachEaseIn = clamp(approachEaseIn, 0.0, 1.0);

        this.driftAlpha    = clamp(driftAlpha, 0.0, 0.9999);
        this.driftNoiseStd = Math.max(0.0, driftNoiseStd);
        this.driftClampAbs = Math.max(0.1, driftClampAbs);

        this.overshootProb         = clamp(overshootProb, 0.0, 1.0);
        this.overshootMaxPx        = Math.max(0.0, overshootMaxPx);
        this.correctionPauseMeanMs = Math.max(0, correctionPauseMeanMs);
        this.correctionPauseStdMs  = Math.max(0, correctionPauseStdMs);
    }

    // ---- Humanizer API: timings --------------------------------------------

    @Override public int dwellMinMs()     { return dwellMinMs; }
    @Override public int dwellMaxMs()     { return dwellMaxMs; }

    @Override public int reactionMeanMs() { return reactionMeanMs; }
    @Override public int reactionStdMs()  { return reactionStdMs; }

    @Override public int holdMinMs()      { return holdMinMs; }
    @Override public int holdMaxMs()      { return holdMaxMs; }

    @Override public int keyLeadMs()      { return sampleKeyMs(); }
    @Override public int keyLagMs()       { return sampleKeyMs(); }

    // ---- Humanizer API: path & pacing --------------------------------------

    @Override public double pathCurvatureMean() { return pathCurvMean; }
    @Override public double pathCurvatureStd()  { return pathCurvStd;  }

    @Override public double pathCurvature()     { return pathCurvMean; }

    @Override public double pathWobblePx()      { return pathWobblePx; }

    @Override public int stepMinMs()            { return stepMinMs; }
    @Override public int stepMaxMs()            { return stepMaxMs; }

    @Override public double approachEaseIn()    { return approachEaseIn; }

    @Override
    public double[] stepBiasDrift() {
        // AR(1): drift <- alpha*drift + noise*gaussian
        driftX = driftAlpha * driftX + driftNoiseStd * gauss();
        driftY = driftAlpha * driftY + driftNoiseStd * gauss();

        // clamp to a tiny bias radius (±driftClampAbs)
        final double bx = clamp(driftX, -driftClampAbs, driftClampAbs);
        final double by = clamp(driftY, -driftClampAbs, driftClampAbs);
        return new double[] { bx, by };
    }

    @Override
    public double samplePathCurvature() {
        // sample curvature per path: N(mean, std), clamped to >= 0
        double v = pathCurvMean + gauss() * pathCurvStd;
        return Math.max(0.0, v);
    }

    // ---- Humanizer API: overshoot/correction --------------------------------

    @Override public double overshootProb()         { return overshootProb; }
    @Override public double overshootMaxPx()        { return overshootMaxPx; }
    @Override public int    correctionPauseMeanMs() { return correctionPauseMeanMs; }
    @Override public int    correctionPauseStdMs()  { return correctionPauseStdMs;  }

    // ---- Humanizer API: fatigue & session -----------------------------------

    @Override public double fatigueLevel() { return fatigue01; }
    @Override public void   setFatigueLevel(double f) { fatigue01 = clamp(f, 0.0, 1.0); }

    @Override
    public void onSessionStart() {
        driftX = 0.0;
        driftY = 0.0;
        fatigue01 = 0.0;
    }

    // ---- Helpers ------------------------------------------------------------

    /** Sample a (positive) "key wait" ms value with a minimum floor. */
    private int sampleKeyMs() {
        double g = Math.abs(gauss());
        int v = (int)Math.round(keyMeanMs + g * keyStdMs);
        return Math.max(keyMinMs, v);
    }

    /** Standard normal using Box–Muller. */
    private static double gauss() {
        double u = Math.max(1e-9, ThreadLocalRandom.current().nextDouble());
        double v = Math.max(1e-9, ThreadLocalRandom.current().nextDouble());
        return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }

    private static double clamp(double v, double lo, double hi) {
        return (v < lo) ? lo : (v > hi ? hi : v);
    }
}
