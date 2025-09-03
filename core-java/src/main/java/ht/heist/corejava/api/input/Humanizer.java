// ============================================================================
// FILE: Humanizer.java
// PATH: core-java/src/main/java/ht/heist/corejava/api/input/Humanizer.java
// PACKAGE: ht.heist.corejava.api.input
// -----------------------------------------------------------------------------
// TITLE
//   Humanizer — Centralized "human feel" parameters and samplers
//
// WHAT THIS INTERFACE DEFINES
//   This is the SINGLE source of truth for all "human-like" behavior knobs that
//   affect mouse/timing dynamics. Implementations (e.g., HumanizerImpl) provide
//   concrete numbers and sampling behavior.
//
// WHY THIS EXISTS
//   Previously, multiple classes (MouseServiceImpl, HumanMouse) each contained
//   their own scattered knobs. Consolidating all knobs/samplers here:
//     • avoids duplicated constants,
//     • makes it easy to load/learn a per-user "profile",
//     • ensures plugins remain thin and depend on ONE feel provider.
//
// THREADING
//   Implementations may maintain tiny state (e.g., sticky drift). Calls are
//   expected from a single input thread, but implementations should be simple
//   and robust.
//
// BACKWARD COMPATIBILITY
//   Existing getters are preserved (dwellMinMs(), etc.). New getters are added
//   for curvature jitter, overshoot/correction, ease-in pacing, fatigue, and
//   session lifecycle.
// ============================================================================

package ht.heist.corejava.api.input;

public interface Humanizer
{
    // ------------------------------------------------------------------------
    // TIMING KNOBS (RANGES and STATS)
    // These inform the executor's sleeps. They should reflect human-like
    // distributions and minimum floors to avoid robotic behavior.
    // ------------------------------------------------------------------------

    /** Minimum and maximum hover time BEFORE mouse press (ms). */
    int dwellMinMs();
    int dwellMaxMs();

    /** Reaction delay mean/std (ms) BEFORE the press (Gaussian-ish). */
    int reactionMeanMs();
    int reactionStdMs();

    /** Minimum and maximum button hold duration (ms). */
    int holdMinMs();
    int holdMaxMs();

    /** Optional Shift key waits around press (lead = after Shift down, before press; lag = before Shift up). */
    int keyLeadMs();   // can be sampled internally; return a single value for this call
    int keyLagMs();    // can be sampled internally; return a single value for this call

    // ------------------------------------------------------------------------
    // PATH & PACING KNOBS
    // ------------------------------------------------------------------------

    /** Mean/Std for per-path curvature jitter (0.0 = straight; ~0.5 moderate arc). */
    double pathCurvatureMean();
    double pathCurvatureStd();

    /** Legacy curvature value for compatibility; implementations may return mean. */
    double pathCurvature();

    /** Amplitude of micro-wobble noise (pixels) along the path. */
    double pathWobblePx();

    /** Per-step pacing bounds while moving (ms between MOUSE_MOVED points). */
    int stepMinMs();
    int stepMaxMs();

    /** Tiny AR(1) drift that evolves across calls; returns [biasX, biasY] px. */
    double[] stepBiasDrift();

    /** Ease-in near the target (0..1). 0=constant speed; 1=strong slow-down near end. */
    double approachEaseIn();

    /** Convenience sampler: per-path curvature draw honoring mean/std. */
    double samplePathCurvature();

    // ------------------------------------------------------------------------
    // OVERSHOOT / CORRECTION (make approaches less robotic)
    // ------------------------------------------------------------------------

    /** Probability of performing a small overshoot before correcting to target. */
    double overshootProb();

    /** Maximum overshoot distance in pixels along the movement direction. */
    double overshootMaxPx();

    /** Pause between overshoot and correction (Gaussian mean/std, ms). */
    int correctionPauseMeanMs();
    int correctionPauseStdMs();

    // ------------------------------------------------------------------------
    // FATIGUE & SESSION LIFECYCLE
    // ------------------------------------------------------------------------

    /** Fatigue in [0..1]. Implementations decide how it modulates timings. */
    double fatigueLevel();
    void   setFatigueLevel(double f);

    /** Called when a session starts: reset drift or other state to "fresh". */
    void   onSessionStart();
}
