// ============================================================================
// FILE: HumanizerImpl.java
// MODULE: core-java (PURE Java; NO RuneLite)
// PACKAGE: ht.heist.corejava.input
// -----------------------------------------------------------------------------
// TITLE
//   HumanizerImpl — default "human feel" implementation (stateful).
//
// WHAT THIS IMPLEMENTATION PROVIDES (defaults picked to match your current feel)
//   • Dwell BEFORE press:              240..360 ms
//   • Reaction jitter (pre-press):     mean 150 ms, std 45 ms
//   • Hold (press down) duration:      42..65 ms
//   • Path curvature:                  0.55 (moderate arc)
//   • Path wobble amplitude:           1.6 px (tiny micro-noise)
//   • Step pacing per move:            6..12 ms (smoothness)
//   • Sticky AR(1) drift for shape bias: alpha 0.86, noise 0.90, clamp ±3.0 px
//
// WHY STATEFUL?
//   • The drift has a small internal state (driftX/driftY) that evolves across
//     calls (gives that "sticky-but-human" sampling sensation).
//
// CUSTOMIZATION
//   • Use the no-arg constructor for sensible defaults.
//   • Or use the full constructor to plug your own numbers (or learned profile).
//
// THREADING
//   • Extremely light state; if you call from multiple threads, you may want to
//     synchronize reads of drift. In practice, this is used from a single input
//     thread, so we keep it simple.
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

    // ---- Path & pacing knobs ------------------------------------------------
    private final double pathCurvature;
    private final double pathWobblePx;

    private final int stepMinMs;
    private final int stepMaxMs;

    // ---- Sticky drift (AR(1)) ----------------------------------------------
    private final double driftAlpha;      // e.g., 0.86
    private final double driftNoiseStd;   // e.g., 0.90
    private final double driftClampAbs;   // e.g., 3.0 (px)

    private double driftX = 0.0;          // mutable, tiny bias state
    private double driftY = 0.0;

    // ---- Constructors -------------------------------------------------------

    /** Default: matches your current MouseServiceImpl feel. */
    public HumanizerImpl() {
        this(
                // timing
                240, 360,            // dwell min/max
                150, 45,             // reaction mean/std
                42, 65,              // hold min/max
                // path & pacing
                0.55, 1.6,           // curvature, wobble(px)
                6, 12,               // step min/max (ms)
                // drift
                0.86, 0.90, 3.0      // alpha, noiseStd, clampAbs(px)
        );
    }

    /** Full-control constructor for custom profiles. */
    public HumanizerImpl(
            int dwellMinMs, int dwellMaxMs,
            int reactionMeanMs, int reactionStdMs,
            int holdMinMs, int holdMaxMs,
            double pathCurvature, double pathWobblePx,
            int stepMinMs, int stepMaxMs,
            double driftAlpha, double driftNoiseStd, double driftClampAbs)
    {
        this.dwellMinMs = dwellMinMs;
        this.dwellMaxMs = Math.max(dwellMinMs, dwellMaxMs);

        this.reactionMeanMs = reactionMeanMs;
        this.reactionStdMs  = Math.max(0, reactionStdMs);

        this.holdMinMs = holdMinMs;
        this.holdMaxMs = Math.max(holdMinMs, holdMaxMs);

        this.pathCurvature = Math.max(0.0, pathCurvature);
        this.pathWobblePx  = Math.max(0.0, pathWobblePx);

        this.stepMinMs = stepMinMs;
        this.stepMaxMs = Math.max(stepMinMs, stepMaxMs);

        this.driftAlpha    = clamp(driftAlpha, 0.0, 0.9999);
        this.driftNoiseStd = Math.max(0.0, driftNoiseStd);
        this.driftClampAbs = Math.max(0.1, driftClampAbs);
    }

    // ---- Humanizer API (getters/steppers) ----------------------------------

    @Override public int dwellMinMs()      { return dwellMinMs; }
    @Override public int dwellMaxMs()      { return dwellMaxMs; }

    @Override public int reactionMeanMs()  { return reactionMeanMs; }
    @Override public int reactionStdMs()   { return reactionStdMs; }

    @Override public int holdMinMs()       { return holdMinMs; }
    @Override public int holdMaxMs()       { return holdMaxMs; }

    @Override public double pathCurvature(){ return pathCurvature; }
    @Override public double pathWobblePx() { return pathWobblePx; }

    @Override public int stepMinMs()       { return stepMinMs; }
    @Override public int stepMaxMs()       { return stepMaxMs; }

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

    // ---- Helpers ------------------------------------------------------------

    private static double gauss() {
        // Box–Muller with safety clamps
        double u = Math.max(1e-9, ThreadLocalRandom.current().nextDouble());
        double v = Math.max(1e-9, ThreadLocalRandom.current().nextDouble());
        return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }

    private static double clamp(double v, double lo, double hi) {
        return (v < lo) ? lo : (v > hi ? hi : v);
    }
}
