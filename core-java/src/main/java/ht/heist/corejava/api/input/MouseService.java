// ============================================================================
// FILE: MouseService.java
// MODULE: core-java (pure Java; NO RuneLite)
// PACKAGE: ht.heist.corejava.api.input
// -----------------------------------------------------------------------------
// TITLE
//   MouseService (API) — pure planning & timing, zero AWT dispatch.
//
// WHAT THIS INTERFACE PROVIDES
//   • pickPointInShape(shape): pick a biased-to-center pixel *inside* a shape.
//   • planPath(from, to):      produce a CURVED path (Bezier-ish) w/ step pacing.
//   • planTimings():           dwell (hover), reaction jitter, hold, step ranges.
//
// IMPORTANT
//   • This layer is reusable and unit-testable (no RuneLite or AWT).
//   • Execution/dispatch happens in core-rl’s MouseGateway/HumanMouse.
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
    /** Choose a biased-to-center point INSIDE a polygon; never returns null. */
    Point pickPointInShape(Shape shape);

    // -------------------------------------------------------------------------
    // PATH PLANNING
    // -------------------------------------------------------------------------
    /** Build a curved path with small wobble; includes the final target point. */
    MousePlan planPath(Point from, Point to);

    // -------------------------------------------------------------------------
    // TIMINGS (dwell/reaction/hold/step pacing)
    // -------------------------------------------------------------------------
    Timings planTimings();

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
        /** Intermediate points including the final target. */
        public List<Point> path() { return path; }
        /** Per-step delay bounds while moving along the path. */
        public int stepDelayMinMs() { return stepDelayMinMs; }
        public int stepDelayMaxMs() { return stepDelayMaxMs; }
    }

    // -------------------------------------------------------------------------
    // DATA: Timings (PRECISELY the pieces needed to avoid “yellow clicks”)
    // -------------------------------------------------------------------------
    final class Timings
    {
        private final int dwellMinMs, dwellMaxMs;
        private final int reactMeanMs, reactStdMs;
        private final int holdMinMs,  holdMaxMs;

        public Timings(int dwellMinMs, int dwellMaxMs,
                       int reactMeanMs, int reactStdMs,
                       int holdMinMs,  int holdMaxMs)
        {
            this.dwellMinMs = dwellMinMs;
            this.dwellMaxMs = dwellMaxMs;
            this.reactMeanMs = reactMeanMs;
            this.reactStdMs = reactStdMs;
            this.holdMinMs = holdMinMs;
            this.holdMaxMs = holdMaxMs;
        }

        public int dwellMinMs() { return dwellMinMs; }
        public int dwellMaxMs() { return dwellMaxMs; }
        public int reactMeanMs() { return reactMeanMs; }
        public int reactStdMs()  { return reactStdMs;  }
        public int holdMinMs()   { return holdMinMs;   }
        public int holdMaxMs()   { return holdMaxMs;   }
    }
}
