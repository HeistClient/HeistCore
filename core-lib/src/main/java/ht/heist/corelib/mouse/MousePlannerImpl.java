// ============================================================================
// FILE: MousePlannerImpl.java
// PACKAGE: ht.heist.corelib.mouse
// -----------------------------------------------------------------------------
// TITLE
//   Humanized Mouse Planner (pure logic; no event dispatch).
//
// WHAT IT DOES
//   - Chooses click pixels inside rects/shapes using elliptical Gaussian
//     sampling + tiny AR(1) drift to feel “sticky-but-human”.
//   - Returns a MousePlan containing:
//       • Curved path (intermediate points including final target)
//       • Per-step delays
//       • Dwell (hover) time BEFORE press — fixes “yellow/no-action” clicks
//       • Reaction mean/std
//       • Hold min/max
//
// WHAT IT DOES **NOT** DO
//   - It does not send AWT events. Your RuneLite-side MouseGateway executes
//     the plan on a background single-thread executor.
// ============================================================================

package ht.heist.corelib.mouse;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MousePlannerImpl implements MousePlanner
{
    // ----- Random + tiny drift (AR1) -----------------------------------------
    private final Random rng = new Random();
    private double driftX = 0.0;
    private double driftY = 0.0;

    // ----- Tuning constants (identical “feel” to previous engine) ------------
    private static final double SAMPLE_RX = 0.32;
    private static final double SAMPLE_RY = 0.36;

    private static final double DRIFT_ALPHA = 0.86;
    private static final double DRIFT_NOISE  = 0.9;

    private static final double PATH_CURVATURE = 0.55;
    private static final double PATH_NOISE_PX  = 1.6;
    private static final int    PATH_STEP_MS_MIN = 6;
    private static final int    PATH_STEP_MS_MAX = 12;

    // Dwell (hover) BEFORE pressing — this is the key to avoid yellow clicks
    private static final int DWELL_MIN_MS = 240;
    private static final int DWELL_MAX_MS = 360;

    private static final int REACT_MEAN = 150;
    private static final int REACT_STD  = 45;

    private static final int HOLD_MIN   = 42;
    private static final int HOLD_MAX   = 65;

    // =========================================================================
    // MousePlanner API
    // =========================================================================

    @Override
    public Point pickPointInRect(Rectangle r)
    {
        if (r == null) return null;

        // Sticky bias update
        driftX = ar1Step(driftX, DRIFT_ALPHA, DRIFT_NOISE);
        driftY = ar1Step(driftY, DRIFT_ALPHA, DRIFT_NOISE);

        final double bx = clamp(driftX, -3.0, 3.0);
        final double by = clamp(driftY, -3.0, 3.0);

        return sampleEllipticalInRect(r, SAMPLE_RX, SAMPLE_RY, bx, by);
    }

    @Override
    public Point pickPointInShape(Shape shape, Rectangle fallbackBounds)
    {
        if (shape == null || fallbackBounds == null) return null;

        // Sticky bias update
        driftX = ar1Step(driftX, DRIFT_ALPHA, DRIFT_NOISE);
        driftY = ar1Step(driftY, DRIFT_ALPHA, DRIFT_NOISE);

        final double bx = clamp(driftX, -3.0, 3.0);
        final double by = clamp(driftY, -3.0, 3.0);

        // Try sampling within the polygon a few times
        for (int i = 0; i < 10; i++)
        {
            Point p = sampleEllipticalInRect(fallbackBounds, SAMPLE_RX, SAMPLE_RY, bx, by);
            if (p != null && shape.contains(p)) return p;
        }
        // Fallback to the center of bounds
        return new Point((int)fallbackBounds.getCenterX(), (int)fallbackBounds.getCenterY());
    }

    @Override
    public MousePlan plan(Point from, Point to)
    {
        if (to == null)
            return new MousePlan(List.of(), PATH_STEP_MS_MIN, PATH_STEP_MS_MAX,
                    DWELL_MIN_MS, DWELL_MAX_MS, REACT_MEAN, REACT_STD, HOLD_MIN, HOLD_MAX);

        // If we don't know where the mouse is, create a trivial path with just the target.
        if (from == null)
            return new MousePlan(List.of(new Point(to)), PATH_STEP_MS_MIN, PATH_STEP_MS_MAX,
                    DWELL_MIN_MS, DWELL_MAX_MS, REACT_MEAN, REACT_STD, HOLD_MIN, HOLD_MAX);

        // Curved path with small noise, number of steps from distance
        final int dist  = (int)Math.round(from.distance(to));
        final int steps = clamp(dist / 12, 6, 28);

        final List<Point> path = curvedPath(from, to, steps, PATH_CURVATURE, PATH_NOISE_PX);
        path.add(new Point(to)); // ensure we end at the exact target

        return new MousePlan(path,
                PATH_STEP_MS_MIN, PATH_STEP_MS_MAX,
                DWELL_MIN_MS, DWELL_MAX_MS,
                REACT_MEAN, REACT_STD,
                HOLD_MIN, HOLD_MAX);
    }

    // =========================================================================
    // Internals: geometry & randomness
    // =========================================================================

    private static double ar1Step(double prev, double alpha, double noiseStd) {
        return alpha * prev + noiseStd * randomNormal();
    }

    private static double randomNormal() {
        double u = Math.max(1e-9, Math.random());
        double v = Math.max(1e-9, Math.random());
        return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Elliptical Gaussian sampling bounded to the rectangle. */
    private static Point sampleEllipticalInRect(Rectangle r, double rx, double ry, double biasX, double biasY)
    {
        final double cx = r.getCenterX(), cy = r.getCenterY();
        final double sx = (r.getWidth()  * rx) / 2.0;
        final double sy = (r.getHeight() * ry) / 2.0;

        for (int i = 0; i < 8; i++) {
            final double gx = cx + biasX + sx * randomNormal();
            final double gy = cy + biasY + sy * randomNormal();
            final int px = (int)Math.round(gx), py = (int)Math.round(gy);
            if (r.contains(px, py)) return new Point(px, py);
        }
        return new Point((int)cx, (int)cy);
    }

    /** Quadratic Bezier-ish curve with tiny wobble; returns intermediate points only. */
    private List<Point> curvedPath(Point from, Point to, int steps, double curvature, double noise)
    {
        final List<Point> out = new ArrayList<>(Math.max(steps, 1));
        final double dx = to.x - from.x, dy = to.y - from.y;
        final double len = Math.hypot(dx, dy);

        final double ux = (len == 0) ? 0 : (-dy / len);
        final double uy = (len == 0) ? 0 : ( dx / len);

        final double side = (Math.random() < 0.5 ? 1.0 : -1.0);
        final double cx = from.x + dx * 0.5 + side * curvature * 0.25 * len * ux;
        final double cy = from.y + dy * 0.5 + side * curvature * 0.25 * len * uy;

        for (int i = 1; i <= steps; i++) {
            final double t = i / (double)(steps + 1);
            final double omt = 1.0 - t;
            double bx = omt*omt*from.x + 2.0*omt*t*cx + t*t*to.x;
            double by = omt*omt*from.y + 2.0*omt*t*cy + t*t*to.y;
            bx += (Math.random() * 2 - 1) * noise;
            by += (Math.random() * 2 - 1) * noise;
            out.add(new Point((int)Math.round(bx), (int)Math.round(by)));
        }
        return out;
    }
}
