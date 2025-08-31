// ============================================================================
// FILE: MousePlanner.java
// PACKAGE: ht.heist.corelib.mouse
// -----------------------------------------------------------------------------
// TITLE
//   Pure planning interface (NO RuneLite / NO AWT dispatch).
//
// PURPOSE
//   - The planner chooses click points (inside rects/shapes) and computes a
//     human-like movement plan (curved path with per-step timing) plus
//     dwell/reaction/hold timings.
//
// DESIGN
//   - This interface is 100% platform-agnostic. It does not know about RL's
//     Client/Canvas or dispatching events. It only returns data structures.
//   - Your RuneLite plugin (MouseGateway) *executes* the plan on a single
//     worker thread so game thread is not blocked.
// ============================================================================

package ht.heist.corelib.mouse;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.List;

public interface MousePlanner
{
    // -------------------------------------------------------------------------
    // POINT PICKING (for targets)
    // -------------------------------------------------------------------------

    /**
     * Pick a click pixel inside a rectangle using elliptical Gaussian sampling
     * plus a tiny sticky drift (feels less robotic).
     */
    Point pickPointInRect(Rectangle r);

    /**
     * Prefer sampling INSIDE the polygonal shape (e.g., a convex hull).
     * If repeated attempts fail (degenerate shapes), fall back to the bounds.
     *
     * @param shape           the clickable polygon (e.g., GameObject hull)
     * @param fallbackBounds  shape.getBounds() typically; used only if needed
     */
    Point pickPointInShape(Shape shape, Rectangle fallbackBounds);

    // -------------------------------------------------------------------------
    // PATH PLANNING (from current mouse to target)
    // -------------------------------------------------------------------------

    /**
     * Compute a human-like path + timings from the current pixel to the target.
     *
     * NOTE: The returned path includes the final target as the last point.
     * The executor (MouseGateway) should iterate the path and move to each
     * point in order, sleeping between points using [stepDelayMinMs..MaxMs].
     *
     * Before clicking, you should:
     *   1) dwell for [dwellMinMs..dwellMaxMs] at the target to let OSRS
     *      highlight (prevents “yellow/no-action” clicks),
     *   2) optionally sleep a Gaussian reaction time (reactMeanMs/reactStdMs),
     *   3) then press/hold/release for [holdMinMs..holdMaxMs].
     */
    MousePlan plan(Point from, Point to);

    // -------------------------------------------------------------------------
    // DATA CLASS: MousePlan
    // -------------------------------------------------------------------------
    final class MousePlan
    {
        private final List<Point> path;
        private final int stepDelayMinMs;
        private final int stepDelayMaxMs;

        private final int dwellMinMs;
        private final int dwellMaxMs;

        private final int reactMeanMs;
        private final int reactStdMs;

        private final int holdMinMs;
        private final int holdMaxMs;

        public MousePlan(
                List<Point> path,
                int stepDelayMinMs, int stepDelayMaxMs,
                int dwellMinMs, int dwellMaxMs,
                int reactMeanMs, int reactStdMs,
                int holdMinMs, int holdMaxMs)
        {
            this.path = path;
            this.stepDelayMinMs = stepDelayMinMs;
            this.stepDelayMaxMs = stepDelayMaxMs;
            this.dwellMinMs = dwellMinMs;
            this.dwellMaxMs = dwellMaxMs;
            this.reactMeanMs = reactMeanMs;
            this.reactStdMs  = reactStdMs;
            this.holdMinMs = holdMinMs;
            this.holdMaxMs = holdMaxMs;
        }

        /** Full list of intermediate points, including the final target. */
        public List<Point> path() { return path; }

        /** Per-step delay bounds while moving along the path. */
        public int stepDelayMinMs() { return stepDelayMinMs; }
        public int stepDelayMaxMs() { return stepDelayMaxMs; }

        /** Hover dwell at target BEFORE pressing (prevents yellow clicks). */
        public int dwellMinMs() { return dwellMinMs; }
        public int dwellMaxMs() { return dwellMaxMs; }

        /** Human reaction jitter BEFORE press. */
        public int reactMeanMs() { return reactMeanMs; }
        public int reactStdMs()  { return reactStdMs;  }

        /** Press/hold duration range. */
        public int holdMinMs() { return holdMinMs; }
        public int holdMaxMs() { return holdMaxMs; }
    }
}
