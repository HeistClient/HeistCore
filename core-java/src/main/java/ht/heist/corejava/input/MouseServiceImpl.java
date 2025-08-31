// ============================================================================
// FILE: MouseServiceImpl.java
// MODULE: core-java (pure Java; NO RuneLite)
// PACKAGE: ht.heist.corejava.input
// -----------------------------------------------------------------------------
// TITLE
//   MouseServiceImpl — Human-like path planner + timing sampler.
//
// WHAT THIS IMPLEMENTATION DOES
//   • Elliptical Gaussian sampling biased to polygon center (+ tiny AR(1) drift).
//   • Curved (quadratic Bezier-ish) path with slight wobble.
//   • Step pacing per segment (6..12 ms) to look smooth.
//   • Dwell BEFORE press, reaction jitter BEFORE press, short hold — the
//     exact sequence that prevents “yellow clicks” in OSRS.
//
// IMPORTANT CONSTANTS
//  If you want to tweak later, do it here — plugins do not need to know about the details.
// ============================================================================

package ht.heist.corejava.input;

import ht.heist.corejava.api.input.MouseService;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class MouseServiceImpl implements MouseService
{
    // ----- Sticky drift (AR(1)) so sampling feels “sticky-but-human” ----------
    private double driftX = 0.0;
    private double driftY = 0.0;
    private static final double DRIFT_ALPHA = 0.86;
    private static final double DRIFT_NOISE = 0.90; // std-dev per step

    // ----- Sampling ellipse (fraction of bounds) ------------------------------
    private static final double SAMPLE_RX = 0.32;
    private static final double SAMPLE_RY = 0.36;

    // ----- Path & pacing ------------------------------------------------------
    private static final double PATH_CURVATURE = 0.55;   // how “arched” the curve is
    private static final double PATH_NOISE_PX  = 1.6;    // tiny wobble
    private static final int    STEP_MIN_MS    = 6;      // per-move sleep bounds
    private static final int    STEP_MAX_MS    = 12;

    // ----- Timing that avoids “yellow clicks” --------------------------------
    private static final int DWELL_MIN_MS  = 240;  // hover BEFORE press (OSRS highlights target)
    private static final int DWELL_MAX_MS  = 360;
    private static final int REACT_MEAN_MS = 150;  // reaction jitter BEFORE press
    private static final int REACT_STD_MS  = 45;
    private static final int HOLD_MIN_MS   = 42;   // short press/hold
    private static final int HOLD_MAX_MS   = 65;

    // =========================================================================
    // API: pickPointInShape
    // =========================================================================
    @Override
    public Point pickPointInShape(Shape shape)
    {
        if (shape == null) return new Point(0, 0);

        // update sticky drift
        driftX = ar1Step(driftX, DRIFT_ALPHA, DRIFT_NOISE);
        driftY = ar1Step(driftY, DRIFT_ALPHA, DRIFT_NOISE);
        final double bx = clamp(driftX, -3.0, 3.0);
        final double by = clamp(driftY, -3.0, 3.0);

        final Rectangle r = shape.getBounds();

        // try to pick *inside* polygon a few times
        for (int i = 0; i < 10; i++)
        {
            Point p = sampleEllipticalInRect(r, SAMPLE_RX, SAMPLE_RY, bx, by);
            if (shape.contains(p)) return p;
        }
        // fallback center
        return new Point((int) r.getCenterX(), (int) r.getCenterY());
    }

    // =========================================================================
    // API: planPath
    // =========================================================================
    @Override
    public MousePlan planPath(Point from, Point to)
    {
        if (to == null)
            return new MousePlan(List.of(), STEP_MIN_MS, STEP_MAX_MS);

        // no “from”? create trivial “just go to target” path
        if (from == null)
            return new MousePlan(List.of(new Point(to)), STEP_MIN_MS, STEP_MAX_MS);

        // steps based on distance (bounded)
        final int dist  = (int)Math.round(from.distance(to));
        final int steps = clamp(dist / 12, 6, 28);

        final List<Point> path = curvedPath(from, to, steps, PATH_CURVATURE, PATH_NOISE_PX);
        path.add(new Point(to)); // ensure final target is last

        return new MousePlan(path, STEP_MIN_MS, STEP_MAX_MS);
    }

    // =========================================================================
    // API: planTimings
    // =========================================================================
    @Override
    public Timings planTimings()
    {
        return new Timings(
                DWELL_MIN_MS, DWELL_MAX_MS,
                REACT_MEAN_MS, REACT_STD_MS,
                HOLD_MIN_MS, HOLD_MAX_MS
        );
    }

    // =========================================================================
    // Internals: geometry & randomness
    // =========================================================================
    private static double ar1Step(double prev, double alpha, double noiseStd)
    {
        return alpha * prev + noiseStd * gauss();
    }

    private static double gauss()
    {
        double u = Math.max(1e-9, ThreadLocalRandom.current().nextDouble());
        double v = Math.max(1e-9, ThreadLocalRandom.current().nextDouble());
        return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Elliptical Gaussian sampling bounded to a rectangle. */
    private static Point sampleEllipticalInRect(Rectangle r, double rx, double ry, double biasX, double biasY)
    {
        final double cx = r.getCenterX(), cy = r.getCenterY();
        final double sx = Math.max(1.0, (r.getWidth()  * rx) / 2.0);
        final double sy = Math.max(1.0, (r.getHeight() * ry) / 2.0);

        for (int i = 0; i < 8; i++)
        {
            final double gx = cx + biasX + sx * gauss();
            final double gy = cy + biasY + sy * gauss();
            final int px = (int)Math.round(gx), py = (int)Math.round(gy);
            if (r.contains(px, py)) return new Point(px, py);
        }
        return new Point((int)cx, (int)cy);
    }

    /** Quadratic Bezier-ish curve with tiny wobble; returns intermediate points only. */
    private static List<Point> curvedPath(Point from, Point to, int steps, double curvature, double noise)
    {
        final List<Point> out = new ArrayList<>(Math.max(steps, 1));
        final double dx = to.x - from.x, dy = to.y - from.y;
        final double len = Math.hypot(dx, dy);

        final double ux = (len == 0) ? 0 : (-dy / len);
        final double uy = (len == 0) ? 0 : ( dx / len);

        final double side = (ThreadLocalRandom.current().nextBoolean() ? 1.0 : -1.0);
        final double cx = from.x + dx * 0.5 + side * curvature * 0.25 * len * ux;
        final double cy = from.y + dy * 0.5 + side * curvature * 0.25 * len * uy;

        for (int i = 1; i <= steps; i++)
        {
            final double t = i / (double)(steps + 1);
            final double omt = 1.0 - t;
            double bx = omt*omt*from.x + 2.0*omt*t*cx + t*t*to.x;
            double by = omt*omt*from.y + 2.0*omt*t*cy + t*t*to.y;
            bx += (ThreadLocalRandom.current().nextDouble() * 2 - 1) * noise;
            by += (ThreadLocalRandom.current().nextDouble() * 2 - 1) * noise;
            out.add(new Point((int)Math.round(bx), (int)Math.round(by)));
        }
        return out;
    }
}
