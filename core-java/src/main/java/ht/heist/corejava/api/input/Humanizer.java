// ============================================================================
// FILE: Humanizer.java
// MODULE: core-java (PURE Java; NO RuneLite)
// PACKAGE: ht.heist.corejava.api.input
// -----------------------------------------------------------------------------
// TITLE
//   Humanizer — single source of truth for "human feel" knobs + randomization.
//
// WHAT THIS INTERFACE IS
//   • A tiny API that *owns all randomness and behavioral knobs* your input
//     system uses (timing, path shape, micro-wobble, drift bias, step pacing).
//   • Implementations can be:
//       - Static (fixed distributions/knobs)
//       - Profile-driven (learned from your JSON logs later)
//       - Context-aware (e.g., different style for inventory vs scene clicks)
//
// WHY THIS EXISTS
//   • Plugins/controllers should NOT decide "how human" feels.
//   • Mouse path planners and executors should NOT hard-code constants.
//   • By centralizing here, you can swap implementations without touching
//     movement or higher-level logic.
//
// LIFECYCLE / THREADING
//   • Implementations may keep small mutable state (e.g., AR(1) drift).
//   • Expected to be used from a single input thread, but the API itself is
//     simple enough to remain thread-safe in practice.
//
// API SHAPE NOTES
//   • We expose *knobs* (e.g., min/max, means/std) rather than single samples,
//     because some planners prefer to sample internally while others just need
//     ranges. You can extend with sampleX() helpers later if desired.
//   • For the sticky "bias" drift used when sampling inside a shape, we expose
//     a step() that returns the *current clamped bias* each call.
// ============================================================================

package ht.heist.corejava.api.input;

public interface Humanizer
{
    // ---- TIMING KNOBS -------------------------------------------------------
    // All values in milliseconds. These are "design intents" rather than
    // hard guarantees — your executor may still clamp for safety.

    /** Hover BEFORE press (lets OSRS highlight the target), e.g. 240..360 ms. */
    int dwellMinMs();
    int dwellMaxMs();

    /** Reaction jitter BEFORE press; mean/std for a Gaussian sample, e.g. 150±45 ms. */
    int reactionMeanMs();
    int reactionStdMs();

    /** Press/hold duration (mouse down time), e.g. 42..65 ms. */
    int holdMinMs();
    int holdMaxMs();

    // ---- PATH & PACING KNOBS -----------------------------------------------
    /** Curvature amount for the arc toward target (0 = straight line). */
    double pathCurvature();

    /** Tiny micro-wobble amplitude along the curve (in pixels). */
    double pathWobblePx();

    /** Per-move sleep bounds (smoothness). Typical: 6..12 ms per step. */
    int stepMinMs();
    int stepMaxMs();

    // ---- STICKY BIAS DRIFT --------------------------------------------------
    // We often want shape sampling to feel "sticky" toward the center with a
    // tiny inertia. This AR(1)-style bias is advanced each call and returns
    // the current clamped bias used when picking a point inside a shape.

    /**
     * Advance the 2D drift state and return the current clamped bias (bx, by).
     * Units are pixels, but *very* small (e.g., -3..+3) — meant to be added as
     * a tiny center bias when picking a point inside a polygon.
     *
     * Implementations typically do: drift = alpha*drift + noise*Gaussian().
     *
     * @return double[2] of { biasX, biasY }, already clamped.
     */
    double[] stepBiasDrift();
}
