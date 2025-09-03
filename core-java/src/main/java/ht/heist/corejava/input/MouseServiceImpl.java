// ============================================================================
// FILE: MouseServiceImpl.java
// PATH: core-java/src/main/java/ht/heist/corejava/input/MouseServiceImpl.java
// PACKAGE: ht.heist.corejava.input
// -----------------------------------------------------------------------------
// TITLE
//   MouseServiceImpl — Human-like path planner driven entirely by Humanizer
//
// WHAT THIS CLASS DOES
//   • Provides target-point sampling inside shapes (center-biased).
//   • Plans curved paths with micro-wobble; per-path curvature JITTER (NEW).
//   • Exposes timing ranges via planTimings(), all sourced from Humanizer.
//
// WHY THIS MATTERS
//   Curvature is now sampled per path using Humanizer.samplePathCurvature(),
//   which gives natural arc variety and avoids identical-looking moves.
//
// THREADING
//   Stateless aside from reading the Humanizer (which holds tiny state).
// ============================================================================

package ht.heist.corejava.input;

import ht.heist.corejava.api.input.Humanizer;
import ht.heist.corejava.api.input.MouseService;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class MouseServiceImpl implements MouseService
{
    private final Humanizer humanizer;

    /** Preferred: supply the Humanizer you want (static profile, learned, etc.). */
    public MouseServiceImpl(Humanizer humanizer) {
        if (humanizer == null) throw new IllegalArgumentException("humanizer == null");
        this.humanizer = humanizer;
    }

    /** Convenience: builds a default HumanizerImpl with your prior feel. */
    public MouseServiceImpl() {
        this(new HumanizerImpl());
    }

    // ------------------------------------------------------------------------
    // pickPointInShape — Elliptical Gaussian sampling with tiny sticky bias
    // ------------------------------------------------------------------------
    @Override
    public Point pickPointInShape(Shape shape)
    {
        if (shape == null) return new Point(0, 0);

        // Tiny sticky bias from Humanizer AR(1) drift (already clamped)
        final double[] bias = humanizer.stepBiasDrift();
        final double bx = (bias != null && bias.length >= 2) ? bias[0] : 0.0;
        final double by = (bias != null && bias.length >= 2) ? bias[1] : 0.0;

        final Rectangle r = shape.getBounds();

        // Elliptical Gaussian sampling within bounds, nudged by bias.
        for (int i = 0; i < 10; i++) {
            Point p = sampleEllipticalInRect(r, 0.32, 0.36, bx, by);
            if (shape.contains(p)) return p;
        }
        // Fallback to center if numeric/pathological cases occur.
        return new Point((int) r.getCenterX(), (int) r.getCenterY());
    }

    // ------------------------------------------------------------------------
    // planPath — Quadratic arc w/ micro wobble; curvature sampled per path (NEW)
    // ------------------------------------------------------------------------
    @Override
    public MousePlan planPath(Point from, Point to)
    {
        final int minStep = humanizer.stepMinMs();
        final int maxStep = Math.max(minStep, humanizer.stepMaxMs());

        if (to == null)
            return new MousePlan(List.of(), minStep, maxStep);

        if (from == null)
            return new MousePlan(List.of(new Point(to)), minStep, maxStep);

        final int dist  = (int)Math.round(from.distance(to));
        final int steps = clamp(dist / 12, 6, 28);

        // ---- NEW: curvature jitter per path --------------------------------
        final double curvature = humanizer.samplePathCurvature();
        final double wobblePx  = humanizer.pathWobblePx();

        final List<Point> path = curvedPath(from, to, steps, curvature, wobblePx);
        path.add(new Point(to)); // ensure final target included

        return new MousePlan(path, minStep, maxStep);
    }

    // ------------------------------------------------------------------------
    // planTimings — expose Humanizer's timing knobs to the executor
    // ------------------------------------------------------------------------
    @Override
    public Timings planTimings()
    {
        return new Timings(
                humanizer.dwellMinMs(), humanizer.dwellMaxMs(),
                humanizer.reactionMeanMs(), humanizer.reactionStdMs(),
                humanizer.holdMinMs(), humanizer.holdMaxMs()
        );
    }

    // ------------------------------------------------------------------------
    // Internals: geometry & randomness
    // ------------------------------------------------------------------------

    /** Elliptical Gaussian sampling bounded to a rectangle; bias nudges the mean. */
    private static Point sampleEllipticalInRect(Rectangle r, double rx, double ry, double biasX, double biasY)
    {
        final double cx = r.getCenterX(), cy = r.getCenterY();
        final double sx = Math.max(1.0, (r.getWidth()  * rx) / 2.0);
        final double sy = Math.max(1.0, (r.getHeight() * ry) / 2.0);

        for (int i = 0; i < 8; i++) {
            final double gx = cx + biasX + sx * gauss();
            final double gy = cy + biasY + sy * gauss();
            final int px = (int)Math.round(gx), py = (int)Math.round(gy);
            if (r.contains(px, py)) return new Point(px, py);
        }
        return new Point((int)cx, (int)cy);
    }

    /** Quadratic Bezier-ish curve with tiny wobble; returns intermediate points only. */
    private static List<Point> curvedPath(Point from, Point to, int steps, double curvature, double noisePx)
    {
        final List<Point> out = new ArrayList<>(Math.max(steps, 1));
        final double dx = to.x - from.x, dy = to.y - from.y;
        final double len = Math.hypot(dx, dy);

        // unit normal (perpendicular) to create an arc
        final double ux = (len == 0) ? 0 : (-dy / len);
        final double uy = (len == 0) ? 0 : ( dx / len);

        // pick a random side for the arc so paths don't all bow the same way
        final double side = (ThreadLocalRandom.current().nextBoolean() ? 1.0 : -1.0);

        // control point midway, offset sideways by curvature * distance
        final double cx = from.x + dx * 0.5 + side * curvature * 0.25 * len * ux;
        final double cy = from.y + dy * 0.5 + side * curvature * 0.25 * len * uy;

        for (int i = 1; i <= steps; i++) {
            final double t  = i / (double)(steps + 1);
            final double omt= 1.0 - t;

            // quadratic bezier blend
            double bx = omt*omt*from.x + 2.0*omt*t*cx + t*t*to.x;
            double by = omt*omt*from.y + 2.0*omt*t*cy + t*t*to.y;

            // tiny micro-wobble (looks less robotic)
            bx += (ThreadLocalRandom.current().nextDouble() * 2 - 1) * noisePx;
            by += (ThreadLocalRandom.current().nextDouble() * 2 - 1) * noisePx;

            out.add(new Point((int)Math.round(bx), (int)Math.round(by)));
        }
        return out;
    }

    private static double gauss() {
        double u = Math.max(1e-9, ThreadLocalRandom.current().nextDouble());
        double v = Math.max(1e-9, ThreadLocalRandom.current().nextDouble());
        return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }

    private static int clamp(int v, int lo, int hi) { return (v < lo) ? lo : (v > hi ? hi : v); }
}
