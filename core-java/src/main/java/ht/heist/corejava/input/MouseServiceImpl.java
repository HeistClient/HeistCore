// ============================================================================
// FILE: MouseServiceImpl.java
// PACKAGE: ht.heist.corejava.input
// -----------------------------------------------------------------------------
// CHANGES IN THIS VERSION
//   • planTimings() now supplies keyLeadMs/keyLagMs (sampled from Humanizer).
//   • approachEaseIn()/stepBiasDrift()/overshoot* pass-throughs are implemented
//     so HumanMouse can use them without touching Humanizer directly.
//   • Everything else is your proven path/point logic.
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

    public MouseServiceImpl(Humanizer humanizer) {
        if (humanizer == null) throw new IllegalArgumentException("humanizer == null");
        this.humanizer = humanizer;
    }

    /** Convenience default: use the core-java HumanizerImpl profile. */
    public MouseServiceImpl() {
        this(new HumanizerImpl()); // same package: ht.heist.corejava.input.HumanizerImpl
    }

    // -------------------- MouseService API -----------------------------------

    @Override
    public Point pickPointInShape(Shape shape)
    {
        if (shape == null) return new Point(0, 0);

        final double[] bias = humanizer.stepBiasDrift();
        final double bx = (bias != null && bias.length >= 2) ? bias[0] : 0.0;
        final double by = (bias != null && bias.length >= 2) ? bias[1] : 0.0;

        final Rectangle r = shape.getBounds();
        for (int i = 0; i < 10; i++) {
            Point p = sampleEllipticalInRect(r, 0.32, 0.36, bx, by);
            if (shape.contains(p)) return p;
        }
        return new Point((int) r.getCenterX(), (int) r.getCenterY());
    }

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

        final double curvature = humanizer.samplePathCurvature();
        final double wobblePx  = humanizer.pathWobblePx();

        final List<Point> path = curvedPath(from, to, steps, curvature, wobblePx);
        path.add(new Point(to)); // ensure final target included

        return new MousePlan(path, minStep, maxStep);
    }

    @Override
    public Timings planTimings()
    {
        return new Timings(
                humanizer.dwellMinMs(), humanizer.dwellMaxMs(),
                humanizer.reactionMeanMs(), humanizer.reactionStdMs(),
                humanizer.holdMinMs(), humanizer.holdMaxMs(),
                humanizer.keyLeadMs(), humanizer.keyLagMs()
        );
    }

    // -------------------- Optional knobs (expose from Humanizer) -------------

    @Override public double   approachEaseIn()        { return humanizer.approachEaseIn(); }
    @Override public double[] stepBiasDrift()         { return humanizer.stepBiasDrift(); }
    @Override public double   overshootProb()         { return humanizer.overshootProb(); }
    @Override public double   overshootMaxPx()        { return humanizer.overshootMaxPx(); }
    @Override public int      correctionPauseMeanMs() { return humanizer.correctionPauseMeanMs(); }
    @Override public int      correctionPauseStdMs()  { return humanizer.correctionPauseStdMs(); }

    // -------------------- Internals ------------------------------------------

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

    private static List<Point> curvedPath(Point from, Point to, int steps, double curvature, double noisePx)
    {
        final List<Point> out = new ArrayList<>(Math.max(steps, 1));
        final double dx = to.x - from.x, dy = to.y - from.y;
        final double len = Math.hypot(dx, dy);

        final double ux = (len == 0) ? 0 : (-dy / len);
        final double uy = (len == 0) ? 0 : ( dx / len);
        final double side = (ThreadLocalRandom.current().nextBoolean() ? 1.0 : -1.0);

        final double cx = from.x + dx * 0.5 + side * curvature * 0.25 * len * ux;
        final double cy = from.y + dy * 0.5 + side * curvature * 0.25 * len * uy;

        for (int i = 1; i <= steps; i++) {
            final double t  = i / (double)(steps + 1);
            final double omt= 1.0 - t;

            double bx = omt*omt*from.x + 2.0*omt*t*cx + t*t*to.x;
            double by = omt*omt*from.y + 2.0*omt*t*cy + t*t*to.y;

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
