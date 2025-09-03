// ============================================================================
// FILE: MouseServiceImpl.java
// PATH: core-java/src/main/java/ht/heist/corejava/input/MouseServiceImpl.java
// PACKAGE: ht.heist.corejava.input
// -----------------------------------------------------------------------------
// TITLE
//   MouseServiceImpl — Pure path/timing planner (no implementations here)
//
// WHAT THIS CLASS DOES (EXCRUCIATING DETAIL)
//   • Provides *basic* point sampling and path planning helpers that read knobs
//     from a Humanizer *interface* (API) but does NOT construct any concrete
//     humanizer here. That keeps core-java free of "feel" implementations.
//   • Exposes timing ranges via planTimings(), reading them from the provided
//     Humanizer. Callers in core-rl should pass their HumanizerImpl.
//   • If you want to keep core-rl as the single source of truth: construct
//     MouseServiceImpl(humanizerFromCoreRl) there and use it; OR skip this
//     class entirely and use HumanMouse directly.
//
// IMPORTANT
//   • There is NO no-arg constructor. Nothing in core-java should instantiate
//     a Humanizer implementation; those live in core-rl.
//   • If some code was calling new MouseServiceImpl(), update it to pass a
//     Humanizer from core-rl, or stop using this class in favor of HumanMouse.
//
// THREADING
//   • Stateless aside from holding the provided Humanizer (which may keep
//     small per-session state like drift).
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
    // ------------------------------------------------------------------------
    // Dependencies
    // ------------------------------------------------------------------------
    private final Humanizer humanizer;

    /**
     * Preferred constructor.
     * @param humanizer a Humanizer (from core-rl or another module) that
     *                  provides all timing/shape parameters. Must not be null.
     */
    public MouseServiceImpl(final Humanizer humanizer)
    {
        if (humanizer == null)
            throw new IllegalArgumentException("humanizer == null (provide from core-rl)");
        this.humanizer = humanizer;
    }

    // ------------------------------------------------------------------------
    // pickPointInShape — center-biased sampling with optional tiny bias
    // ------------------------------------------------------------------------
    @Override
    public Point pickPointInShape(final Shape shape)
    {
        if (shape == null)
            return new Point(0, 0);

        final Rectangle r = shape.getBounds();

        // Optional tiny “sticky” bias from the humanizer (e.g., AR(1) drift).
        // If your Humanizer returns null or empty, we treat bias as zero.
        final double[] bias = humanizer.stepBiasDrift();
        final double bx = (bias != null && bias.length >= 2) ? bias[0] : 0.0;
        final double by = (bias != null && bias.length >= 2) ? bias[1] : 0.0;

        // Try a handful of samples that are likely to land inside the shape.
        for (int i = 0; i < 10; i++)
        {
            Point p = sampleEllipticalInRect(r, 0.32, 0.36, bx, by);
            if (shape.contains(p))
                return p;
        }

        // Fallback: center
        return new Point((int) r.getCenterX(), (int) r.getCenterY());
    }

    // ------------------------------------------------------------------------
    // planPath — simple quadratic-arc path using per-path curvature from API
    // ------------------------------------------------------------------------
    @Override
    public MousePlan planPath(final Point from, final Point to)
    {
        final int minStep = humanizer.stepMinMs();
        final int maxStep = Math.max(minStep, humanizer.stepMaxMs());

        if (to == null)
            return new MousePlan(List.of(), minStep, maxStep);

        if (from == null)
            return new MousePlan(List.of(new Point(to)), minStep, maxStep);

        final int dist  = (int) Math.round(from.distance(to));
        final int steps = clamp(dist / 12, 6, 28);

        // Curvature + micro-wobble come from the Humanizer *interface*.
        final double curvature = humanizer.samplePathCurvature();
        final double wobblePx  = humanizer.pathWobblePx();

        final List<Point> pts = curvedPath(from, to, steps, curvature, wobblePx);
        pts.add(new Point(to)); // ensure the final target is included

        return new MousePlan(pts, minStep, maxStep);
    }

    // ------------------------------------------------------------------------
    // planTimings — straight pass-through from Humanizer API
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

    // ========================================================================
    // Internal helpers (geometry + randomness)
    // ========================================================================

    /**
     * Elliptical Gaussian sampling bounded to a rectangle; bias nudges the mean.
     * rx/ry are relative radii (0..1) controlling spread in x/y.
     */
    private static Point sampleEllipticalInRect(
            final Rectangle r, final double rx, final double ry, final double biasX, final double biasY)
    {
        final double cx = r.getCenterX(), cy = r.getCenterY();
        final double sx = Math.max(1.0, (r.getWidth()  * rx) / 2.0);
        final double sy = Math.max(1.0, (r.getHeight() * ry) / 2.0);

        for (int i = 0; i < 8; i++)
        {
            final double gx = cx + biasX + sx * gauss();
            final double gy = cy + biasY + sy * gauss();
            final int px = (int) Math.round(gx), py = (int) Math.round(gy);
            if (r.contains(px, py))
                return new Point(px, py);
        }
        return new Point((int) cx, (int) cy);
    }

    /**
     * Quadratic Bezier-like curve from 'from' to 'to' with a sideways control
     * point to create an arc. Adds tiny uniform noise per step.
     */
    private static List<Point> curvedPath(
            final Point from, final Point to, final int steps, final double curvature, final double noisePx)
    {
        final List<Point> out = new ArrayList<>(Math.max(steps, 1));
        final double dx = to.x - from.x, dy = to.y - from.y;
        final double len = Math.hypot(dx, dy);

        // Unit normal (perpendicular)
        final double nx = (len == 0) ? 0 : (-dy / len);
        final double ny = (len == 0) ? 0 : ( dx / len);

        // Random side so paths don’t always bow the same way
        final double side = ThreadLocalRandom.current().nextBoolean() ? 1.0 : -1.0;

        // Control point midway, offset by curvature * distance along the normal
        final double cx = from.x + dx * 0.5 + side * curvature * 0.25 * len * nx;
        final double cy = from.y + dy * 0.5 + side * curvature * 0.25 * len * ny;

        for (int i = 1; i <= steps; i++)
        {
            final double t   = i / (double) (steps + 1);
            final double omt = 1.0 - t;

            // Quadratic Bezier blend
            double bx = omt * omt * from.x + 2.0 * omt * t * cx + t * t * to.x;
            double by = omt * omt * from.y + 2.0 * omt * t * cy + t * t * to.y;

            // Tiny micro-wobble (looks less robotic)
            bx += (ThreadLocalRandom.current().nextDouble() * 2 - 1) * noisePx;
            by += (ThreadLocalRandom.current().nextDouble() * 2 - 1) * noisePx;

            out.add(new Point((int) Math.round(bx), (int) Math.round(by)));
        }
        return out;
    }

    /** Box–Muller Gaussian. */
    private static double gauss()
    {
        double u = Math.max(1e-9, ThreadLocalRandom.current().nextDouble());
        double v = Math.max(1e-9, ThreadLocalRandom.current().nextDouble());
        return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }

    private static int clamp(int v, int lo, int hi)
    {
        return (v < lo) ? lo : (v > hi ? hi : v);
    }
}
