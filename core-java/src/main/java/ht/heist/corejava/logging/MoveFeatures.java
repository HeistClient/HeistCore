// ============================================================================
// FILE: MoveFeatures.java
// MODULE: core-java (logging DTO)
// PACKAGE: ht.heist.corejava.logging
// -----------------------------------------------------------------------------
// TITLE
//   MoveFeatures — Per-gesture movement & micro-timing features.
//
// PURPOSE
//   A compact, append-only record written *alongside* TapEvent JSONL lines,
//   keyed by 'event_id'. Captures the "feel" parameters we want to learn
//   for human-like synthesis (dwell, hold, curvature, etc.).
//
// NOTES
//   • 'source' typically "manual" or "synthetic".
//   • Fields are nullable; write what you know for each gesture.
//   • This does NOT require high-rate mouse move logging.
// ============================================================================

package ht.heist.corejava.logging;

public final class MoveFeatures
{
    // ---- Correlation / provenance ------------------------------------------
    public final String kind = "move_features";
    public final String sessionId;
    public final String eventId;
    public final String source;         // "manual" | "synthetic" | other

    // ---- Timing (micro) -----------------------------------------------------
    public final Integer dwellBeforePressMs; // hover near target before press
    public final Integer reactionJitterMs;   // small jitter you inject pre-press (synthetic)
    public final Integer holdMs;             // UP.ts - DOWN.ts

    // ---- Movement / path texture -------------------------------------------
    public final Integer pathLengthPx;      // sum of segment lengths
    public final Integer durationMs;        // first micro-move -> press
    public final Integer stepCount;         // number of micro-steps
    public final Double  curvatureSigned;   // +right / -left arc
    public final Double  wobbleAmpPx;       // small noise amplitude

    // ---- Optional (AR(1) drift) --------------------------------------------
    public final Double driftAlpha;
    public final Double driftNoiseStd;
    public final Double driftStateX;
    public final Double driftStateY;

    public MoveFeatures(
            String sessionId, String eventId, String source,
            Integer dwellBeforePressMs, Integer reactionJitterMs, Integer holdMs,
            Integer pathLengthPx, Integer durationMs, Integer stepCount,
            Double curvatureSigned, Double wobbleAmpPx,
            Double driftAlpha, Double driftNoiseStd, Double driftStateX, Double driftStateY)
    {
        this.sessionId = sessionId;
        this.eventId   = eventId;
        this.source    = source;

        this.dwellBeforePressMs = dwellBeforePressMs;
        this.reactionJitterMs   = reactionJitterMs;
        this.holdMs             = holdMs;

        this.pathLengthPx   = pathLengthPx;
        this.durationMs     = durationMs;
        this.stepCount      = stepCount;
        this.curvatureSigned= curvatureSigned;
        this.wobbleAmpPx    = wobbleAmpPx;

        this.driftAlpha     = driftAlpha;
        this.driftNoiseStd  = driftNoiseStd;
        this.driftStateX    = driftStateX;
        this.driftStateY    = driftStateY;
    }
}
