// ============================================================================
// FILE: MouseService.java
// MODULE: core-java (pure Java; NO RuneLite)
// PACKAGE: ht.heist.corejava.api.input
// -----------------------------------------------------------------------------
// CHANGES IN THIS VERSION
//   • Timings now includes keyLeadMs/keyLagMs so HumanMouse can use the
//     Humanizer-provided waits around Shift.
//   • New default accessors on the interface expose approachEaseIn, drift,
//     and overshoot knobs to HumanMouse without forcing implementors to change.
//     MouseServiceImpl overrides these to pull from Humanizer.
// ============================================================================

package ht.heist.corejava.api.input;

import java.awt.Point;
import java.awt.Shape;
import java.util.List;

public interface MouseService
{
    // -------------------------------------------------------------------------
    // POINT PICKING
    // -------------------------------------------------------------------------
    Point pickPointInShape(Shape shape);

    // -------------------------------------------------------------------------
    // PATH PLANNING
    // -------------------------------------------------------------------------
    MousePlan planPath(Point from, Point to);

    // -------------------------------------------------------------------------
    // TIMINGS (dwell/reaction/hold/step pacing + Shift lead/lag)
    // -------------------------------------------------------------------------
    Timings planTimings();

    // -------------------------------------------------------------------------
    // OPTIONAL KNOBS (defaults are safe no-ops; impl may override)
    // -------------------------------------------------------------------------

    /** 0..1 ease-in strength; HumanMouse uses this to slow down near target. */
    default double approachEaseIn() { return 0.0; }

    /** Tiny sticky drift per step (bx,by) in canvas px; default none. */
    default double[] stepBiasDrift() { return new double[]{0.0, 0.0}; }

    /** Optional overshoot flavor before pressing. */
    default double overshootProb() { return 0.0; }        // chance in [0..1]
    default double overshootMaxPx() { return 0.0; }       // cap in px
    default int    correctionPauseMeanMs() { return 0; }  // pause between out/back
    default int    correctionPauseStdMs()  { return 0; }

    // -------------------------------------------------------------------------
    // DATA: MousePlan
    // -------------------------------------------------------------------------
    final class MousePlan
    {
        private final List<Point> path;
        private final int stepDelayMinMs;
        private final int stepDelayMaxMs;

        public MousePlan(List<Point> path, int stepDelayMinMs, int stepDelayMaxMs)
        {
            this.path = path;
            this.stepDelayMinMs = stepDelayMinMs;
            this.stepDelayMaxMs = stepDelayMaxMs;
        }
        /** Intermediate points (excludes the final target; HumanMouse adds it). */
        public List<Point> path() { return path; }
        public int stepDelayMinMs() { return stepDelayMinMs; }
        public int stepDelayMaxMs() { return stepDelayMaxMs; }
    }

    // -------------------------------------------------------------------------
    // DATA: Timings (now with keyLeadMs/keyLagMs)
    // -------------------------------------------------------------------------
    final class Timings
    {
        private final int dwellMinMs, dwellMaxMs;
        private final int reactMeanMs, reactStdMs;
        private final int holdMinMs,  holdMaxMs;
        private final int keyLeadMs,  keyLagMs;

        public Timings(int dwellMinMs, int dwellMaxMs,
                       int reactMeanMs, int reactStdMs,
                       int holdMinMs,  int holdMaxMs,
                       int keyLeadMs,  int keyLagMs)
        {
            this.dwellMinMs = dwellMinMs;
            this.dwellMaxMs = dwellMaxMs;
            this.reactMeanMs = reactMeanMs;
            this.reactStdMs  = reactStdMs;
            this.holdMinMs   = holdMinMs;
            this.holdMaxMs   = holdMaxMs;
            this.keyLeadMs   = keyLeadMs;
            this.keyLagMs    = keyLagMs;
        }

        public int dwellMinMs() { return dwellMinMs; }
        public int dwellMaxMs() { return dwellMaxMs; }
        public int reactMeanMs() { return reactMeanMs; }
        public int reactStdMs()  { return reactStdMs;  }
        public int holdMinMs()   { return holdMinMs;   }
        public int holdMaxMs()   { return holdMaxMs;   }
        public int keyLeadMs()   { return keyLeadMs;   }
        public int keyLagMs()    { return keyLagMs;    }
    }
}
