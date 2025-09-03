// ============================================================================
// FILE: HumanMouse.java
// PATH: core-rl/src/main/java/ht/heist/corerl/input/HumanMouse.java
// PACKAGE: ht.heist.corerl.input
// -----------------------------------------------------------------------------
// TITLE
//   HumanMouse — Unified "human feel" executor (curved move → reaction → click)
// -----------------------------------------------------------------------------
package ht.heist.corerl.input;

import ht.heist.corejava.api.input.Humanizer;
import ht.heist.corejava.input.HumanizerImpl;
import ht.heist.corejava.logging.HeistLog;

import org.slf4j.Logger;  // <-- IMPORTANT: SLF4J (not java.util)

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

@Singleton
public class HumanMouse
{
    private static final Logger log = HeistLog.get(HumanMouse.class);

    private final MouseGateway io;
    private final Humanizer humanizer;
    private final ExecutorService exec;

    private volatile Consumer<Point> tapSink = null;
    private volatile Point lastMouse = null;

    @Inject
    public HumanMouse(MouseGateway io)
    {
        this.io = io;
        this.humanizer = new HumanizerImpl();
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Heist-HumanMouse");
            t.setDaemon(true);
            return t;
        });
    }

    /** Click inside a polygonal hull with human-like motion + reaction. */
    public void clickHull(Shape hull, boolean shift)
    {
        if (hull == null) return;
        Rectangle b = hull.getBounds();
        Point target = pickPointInShapeGaussian(hull);
        submitMoveReactClick(target, shift);
        HeistLog.diag(log, "clickHull", "x", target.x, "y", target.y, "w", b.width, "h", b.height);
    }

    /** Click inside a rectangle (widgets, inventory slots, minimap). */
    public void clickRect(Rectangle rect, boolean shift)
    {
        if (rect == null) return;
        final int cx = (int)Math.round(rect.getCenterX());
        final int cy = (int)Math.round(rect.getCenterY());
        Point target = new Point(
                clampGaussianWithin(cx, rect.x, rect.x + rect.width  - 1, rect.width  / 4.0),
                clampGaussianWithin(cy, rect.y, rect.y + rect.height - 1, rect.height / 4.0)
        );
        submitMoveReactClick(target, shift);
        HeistLog.diag(log, "clickRect", "x", target.x, "y", target.y, "w", rect.width, "h", rect.height);
    }

    /** Click exactly at a canvas point. */
    public void clickPoint(Point target, boolean shift)
    {
        if (target == null) return;
        submitMoveReactClick(target, shift);
        HeistLog.diag(log, "clickPoint", "x", target.x, "y", target.y);
    }

    public void setTapSink(Consumer<Point> sink) { this.tapSink = sink; }
    public Humanizer getHumanizer() { return humanizer; }

    // -- main sequence (curved move → ease-in → reaction → click) ------------
    private void submitMoveReactClick(Point target, boolean shift)
    {
        exec.submit(() -> {
            try {
                final Point from = (lastMouse != null ? lastMouse : target);
                List<Point> path = planPathLocally(from, target);

                if (ThreadLocalRandom.current().nextDouble() < humanizer.overshootProb()) {
                    Point over = overshootPoint(from, target, humanizer.overshootMaxPx());
                    path.addAll(planPathLocally(path.isEmpty()? from : path.get(path.size()-1), over));
                    sleepGaussian(humanizer.correctionPauseMeanMs(), humanizer.correctionPauseStdMs());
                    path.addAll(planPathLocally(over, target));
                }

                final int stepMin = humanizer.stepMinMs();
                final int stepMax = humanizer.stepMaxMs();
                moveWithEaseIn(path, stepMin, stepMax, humanizer.approachEaseIn());

                final int reactMean = applyFatigueToMean(
                        humanizer.reactionMeanMs(), humanizer.fatigueLevel(), 0.25);
                final int reactStd  = humanizer.reactionStdMs();
                sleepGaussian(reactMean, reactStd);

                Consumer<Point> sink = this.tapSink;
                if (sink != null) {
                    try { sink.accept(target); } catch (Throwable ignored) {}
                }

                final int holdMin = humanizer.holdMinMs();
                final int holdMax = humanizer.holdMaxMs();
                if (shift) {
                    io.shiftDown();
                    sleepMillis(humanizer.keyLeadMs());
                    io.clickAt(target, holdMin, holdMax, true);
                    sleepMillis(humanizer.keyLagMs());
                    io.shiftUp();
                } else {
                    io.clickAt(target, holdMin, holdMax, false);
                }

                lastMouse = target;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                HeistLog.diag(log, "humanmouse_error", "reason", e.getClass().getSimpleName());
                log.warn("HumanMouse sequence error (continuing)", e);
            }
        });
    }

    // -- path planning (Bezier-ish curve + micro wobble) ---------------------
    private List<Point> planPathLocally(Point from, Point to)
    {
        final ArrayList<Point> out = new ArrayList<>();
        if (to == null) return out;
        if (from == null) { out.add(new Point(to)); return out; }

        final int dist  = (int)Math.round(from.distance(to));
        final int steps = clamp(dist / 12, 6, 28);

        final double curvature = humanizer.samplePathCurvature();
        final double noisePx   = humanizer.pathWobblePx();

        final double dx = to.x - from.x, dy = to.y - from.y;
        final double len = Math.hypot(dx, dy);
        final double ux  = (len == 0) ? 0 : (-dy / len);
        final double uy  = (len == 0) ? 0 : ( dx / len);

        final double side = (ThreadLocalRandom.current().nextBoolean() ? 1.0 : -1.0);
        final double cx = from.x + dx * 0.5 + side * curvature * 0.25 * len * ux;
        final double cy = from.y + dy * 0.5 + side * curvature * 0.25 * len * uy;

        for (int i = 1; i <= steps; i++)
        {
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

    private Point pickPointInShapeGaussian(Shape shape)
    {
        final Rectangle r = shape.getBounds();
        if (r == null) return new Point(0, 0);

        final double[] bias = humanizer.stepBiasDrift();
        final double bx = (bias != null && bias.length >= 2) ? bias[0] : 0.0;
        final double by = (bias != null && bias.length >= 2) ? bias[1] : 0.0;

        final double cx = r.getCenterX(), cy = r.getCenterY();
        final double sx = Math.max(1.0, r.getWidth()  * 0.32 / 2.0);
        final double sy = Math.max(1.0, r.getHeight() * 0.36 / 2.0);

        for (int i = 0; i < 10; i++) {
            final double gx = cx + bx + sx * gauss();
            final double gy = cy + by + sy * gauss();
            final int px = (int)Math.round(gx), py = (int)Math.round(gy);
            if (shape.contains(px, py)) return new Point(px, py);
        }
        return new Point((int)cx, (int)cy);
    }

    /** Move along path with slight slow-down near the end (ease-in). */
    private void moveWithEaseIn(List<Point> path, int minMs, int maxMs, double easeIn01) throws InterruptedException
    {
        if (path == null || path.isEmpty()) return;

        final int n = path.size();
        for (int i = 0; i < n; i++) {
            final Point p = path.get(i);
            io.dispatchMove(p); // helper in MouseGateway (see patch below)

            final double t = (i + 1) / (double) n;
            final double ease = 1.0 + Math.max(0.0, Math.min(1.0, easeIn01)) * t * t;
            final int base = uniform(minMs, maxMs);
            final int d = (int)Math.round(base * ease);
            if (d > 0) Thread.sleep(d);
        }
    }

    private Point overshootPoint(Point from, Point to, double maxPx) {
        double dx = to.x - from.x, dy = to.y - from.y;
        double len = Math.hypot(dx, dy);
        if (len < 1e-6) return new Point(to);
        double ux = dx / len, uy = dy / len;
        double m  = ThreadLocalRandom.current().nextDouble(Math.max(1.0, maxPx * 0.35), Math.max(maxPx, 1.0));
        return new Point((int)Math.round(to.x + ux * m), (int)Math.round(to.y + uy * m));
    }

    // -- small utils ----------------------------------------------------------
    private static int uniform(int min, int max) {
        if (max < min) { int t = min; min = max; max = t; }
        if (min <= 0 && max <= 0) return 0;
        return ThreadLocalRandom.current().nextInt(Math.max(0, min), Math.max(0, max) + 1);
    }

    private static void sleepMillis(int ms) throws InterruptedException {
        if (ms > 0) Thread.sleep(ms);
    }

    private static void sleepGaussian(int meanMs, int stdMs) throws InterruptedException {
        int d = (int)Math.max(0, Math.round(meanMs + gauss() * stdMs));
        if (d > 0) Thread.sleep(d);
    }

    private static double gauss() {
        double u = Math.max(1e-9, ThreadLocalRandom.current().nextDouble());
        double v = Math.max(1e-9, ThreadLocalRandom.current().nextDouble());
        return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static int clampGaussianWithin(int center, int lo, int hi, double std) {
        for (int i = 0; i < 6; i++) {
            int v = (int)Math.round(center + gauss() * std);
            if (v >= lo && v <= hi) return v;
        }
        return Math.max(lo, Math.min(hi, center));
    }

    private static int applyFatigueToMean(int mean, double fatigue01, double maxPct) {
        double mult = 1.0 + Math.max(0.0, Math.min(1.0, fatigue01)) * Math.max(0.0, maxPct);
        return (int)Math.round(mean * mult);
    }
}
