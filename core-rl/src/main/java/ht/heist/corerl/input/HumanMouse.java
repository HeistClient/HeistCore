// ============================================================================
// FILE: HumanMouse.java
// PACKAGE: ht.heist.corerl.input
// -----------------------------------------------------------------------------
// CHANGES IN THIS VERSION (ALL REQUESTED ENHANCEMENTS)
//   • Guaranteed pre-press settle: choose target dwell in [dwellMin..dwellMax],
//     then sleep enough so (lastMove→DOWN) ≥ target dwell (includes keyLead).
//   • Ease-out pacing on the final quarter of the path using approachEaseIn().
//   • Shift timing uses keyLeadMs/keyLagMs from MouseService.Timings.
//   • Optional overshoot (+ swift correction) before dwell/press.
//   • Per-step drift: applies tiny AR(1) bias from MouseService.stepBiasDrift().
//   • Instrumentation: logs per-click metrics and rolling histograms for
//     pressLag (settle), reaction, and hold. Summarized every N clicks.
// -----------------------------------------------------------------------------

package ht.heist.corerl.input;

import ht.heist.corejava.api.input.MouseService;
import ht.heist.corejava.input.MouseServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Canvas;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

@Singleton
public final class HumanMouse
{
    private static final Logger log = LoggerFactory.getLogger(HumanMouse.class);

    private final MouseGateway io;          // Canvas event injector (no Robot)
    private final MouseService mouse;       // Pure-Java planner / timing provider
    private final ExecutorService exec;     // Single worker thread

    private volatile Consumer<Point> tapSink = null; // optional logging callback
    private volatile Point lastMouse = null;         // previous target

    // Ease-out parameters
    private static final double EASE_OUT_START_FRAC = 0.75; // last 25%
    private static final double EASE_OUT_MAX_GAIN   = 0.45; // up to +45% delay

    // Overshoot guards
    private static final int OVERSHOOT_MIN_PX = 2;
    private static final int OVERSHOOT_MAX_PX_HARD = 6;

    // Instrumentation (rolling histograms)
    private final Hist pressLagHist = new Hist(new int[]{  0,  50, 100, 150, 200, 250, 300, 400, 600, 1000});
    private final Hist reactionHist = new Hist(new int[]{  0,  50, 100, 150, 200, 300, 450, 600});
    private final Hist holdHist     = new Hist(new int[]{  0,  40,  60,  80, 120, 180, 260});
    private int metricCount = 0;
    private static final int METRIC_SUMMARY_EVERY = 25;

    @Inject
    public HumanMouse(MouseGateway io)
    {
        this.io = io;
        this.mouse = new MouseServiceImpl(); // centralize "feel"
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Heist-HumanMouse");
            t.setDaemon(true);
            return t;
        });
    }

    public void setTapSink(Consumer<Point> sink) { this.tapSink = sink; }

    // -------------------------------------------------------------------------
    // Public helpers
    // -------------------------------------------------------------------------
    public void clickHull(Shape hull, boolean shift)
    {
        if (hull == null) return;
        final Point target = mouse.pickPointInShape(hull);
        submitMoveReactClick(target, shift);
    }

    public void clickRect(Rectangle rect, boolean shift)
    {
        if (rect == null) return;
        final Point target = mouse.pickPointInShape(rect);
        submitMoveReactClick(target, shift);
    }

    public void clickPoint(Point target, boolean shift)
    {
        if (target == null) return;
        submitMoveReactClick(target, shift);
    }

    // -------------------------------------------------------------------------
    // Core sequence
    // -------------------------------------------------------------------------
    private void submitMoveReactClick(Point target, boolean shift)
    {
        exec.submit(() -> {
            try
            {
                // 1) Plan main path
                final Point from = (lastMouse != null ? lastMouse : target);
                final MouseService.MousePlan plan = mouse.planPath(from, target);
                final List<Point> waypoints = plan.path();
                final int stepMinMs = plan.stepDelayMinMs();
                final int stepMaxMs = plan.stepDelayMaxMs();
                final double easeIn = clamp(mouse.approachEaseIn(), 0.0, 1.0);

                // 2) Move along path with per-step drift + ease-out pacing
                long lastMoveTs = System.currentTimeMillis();
                if (waypoints != null && !waypoints.isEmpty())
                {
                    for (int i = 0; i < waypoints.size(); i++)
                    {
                        Point p = waypoints.get(i);

                        // apply tiny drift (rounded) for organic feel
                        double[] drift = mouse.stepBiasDrift();
                        int dx = (int)Math.round((drift != null && drift.length >= 2) ? drift[0] : 0.0);
                        int dy = (int)Math.round((drift != null && drift.length >= 2) ? drift[1] : 0.0);
                        Point q = (dx == 0 && dy == 0) ? p : new Point(p.x + dx, p.y + dy);

                        dispatchMove(q);

                        // ease-out multiplier in last quarter
                        final double frac = (i + 1) / (double)(waypoints.size() + 1);
                        int d = uniform(stepMinMs, stepMaxMs);
                        if (frac >= EASE_OUT_START_FRAC && easeIn > 0.0) {
                            double t = (frac - EASE_OUT_START_FRAC) / (1.0 - EASE_OUT_START_FRAC);
                            double gain = easeIn * EASE_OUT_MAX_GAIN * t; // grows toward target
                            d = (int)Math.round(d * (1.0 + gain));
                        }

                        if (d > 0) Thread.sleep(d);
                    }
                }

                // Ensure we end exactly on the target (no drift here)
                dispatchMove(target);
                int finalPause = uniform(stepMinMs, stepMaxMs);
                if (finalPause > 0) Thread.sleep(finalPause);
                lastMoveTs = System.currentTimeMillis();

                // 3) Optional overshoot+correction BEFORE dwell/press
                //    (We will re-ensure dwell after we return to target.)
                if (ThreadLocalRandom.current().nextDouble() < clamp(mouse.overshootProb(), 0.0, 1.0))
                {
                    // pick distance (2..min(6, overshootMax))
                    int maxPx = (int)Math.round(Math.min(mouse.overshootMaxPx(), OVERSHOOT_MAX_PX_HARD));
                    maxPx = Math.max(OVERSHOOT_MIN_PX, maxPx);
                    int distPx = uniform(OVERSHOOT_MIN_PX, maxPx);

                    // compute beyond point from 'from'→target direction
                    Point beyond = moveBeyond(target, from, distPx);

                    // quick out
                    playLinearFast(lastMouseOrTarget(from, target), beyond, 3);
                    // small correction pause
                    int corrPause = gaussianNonNegative(mouse.correctionPauseMeanMs(), mouse.correctionPauseStdMs());
                    if (corrPause > 0) Thread.sleep(corrPause);
                    // quick back to target (exact)
                    playLinearFast(beyond, target, 3);

                    // we just moved again; reset lastMoveTs so dwell accounts from now
                    lastMoveTs = System.currentTimeMillis();
                }

                // 4) Timing plan (dwell/react/hold + key lead/lag)
                final MouseService.Timings timings = mouse.planTimings();
                final int dwellMin = timings.dwellMinMs();
                final int dwellMax = timings.dwellMaxMs();
                final int dwellTarget = uniform(dwellMin, dwellMax);

                final int reactMs = gaussianNonNegative(timings.reactMeanMs(), timings.reactStdMs());
                if (reactMs > 0) Thread.sleep(reactMs);

                int keyLeadMs = shift ? Math.max(0, timings.keyLeadMs()) : 0;
                int keyLagMs  = shift ? Math.max(0, timings.keyLagMs())  : 0;

                // Guarantee dwell: ensure (now + keyLeadMs) - lastMoveTs >= dwellTarget
                long elapsedAfterReaction = System.currentTimeMillis() - lastMoveTs;
                long extra = dwellTarget - (elapsedAfterReaction + keyLeadMs);
                if (extra > 0) Thread.sleep((int)Math.min(extra, Integer.MAX_VALUE));

                // 5) Tap sink (optional) — just before we press
                final Consumer<Point> sink = this.tapSink;
                if (sink != null) { try { sink.accept(target); } catch (Throwable ignored) {} }

                // 6) Press/hold/release (LEFT) with model-driven SHIFT timing
                final int holdMs = uniform(timings.holdMinMs(), timings.holdMaxMs());

                // measure (press_ts - last_move_ts) precisely: just before press
                if (shift) {
                    io.shiftDown();
                    if (keyLeadMs > 0) Thread.sleep(keyLeadMs);
                }
                long pressLagMs = System.currentTimeMillis() - lastMoveTs;

                io.clickAt(target, holdMs, holdMs, shift); // pass exact hold via equal min/max

                if (shift && keyLagMs > 0) Thread.sleep(keyLagMs);
                if (shift) io.shiftUp();

                // 7) Metrics / histograms
                pressLagHist.add((int)pressLagMs);
                reactionHist.add(reactMs);
                holdHist.add(holdMs);
                metricCount++;
                if (metricCount % METRIC_SUMMARY_EVERY == 0) {
                    log.info("[humanmouse] metrics{} pressLag={} reaction={} hold={}",
                            metricCount,
                            pressLagHist.summary("ms"),
                            reactionHist.summary("ms"),
                            holdHist.summary("ms"));
                } else {
                    log.debug("[humanmouse] pressLag={}ms react={}ms hold={}ms", pressLagMs, reactMs, holdMs);
                }

                // 8) Book-keeping for next arc
                lastMouse = target;
            }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            catch (Throwable t) {
                log.warn("HumanMouse: recoverable error during click sequence", t);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void dispatchMove(Point p)
    {
        if (p == null) return;
        final Canvas c = io.canvasOrNull();
        if (c == null) return;
        io.dispatchMove(p);
    }

    private void playLinearFast(Point from, Point to, int steps) throws InterruptedException
    {
        if (from == null || to == null) return;
        for (int i = 0; i < steps; i++)
        {
            double t = i / (double)(steps - 1);
            int x = (int)Math.round(from.x + (to.x - from.x) * t);
            int y = (int)Math.round(from.y + (to.y - from.y) * t);

            // add tiny drift so even corrections look organic
            double[] drift = mouse.stepBiasDrift();
            x += (int)Math.round((drift != null && drift.length >= 2) ? drift[0] : 0.0);
            y += (int)Math.round((drift != null && drift.length >= 2) ? drift[1] : 0.0);

            dispatchMove(new Point(x, y));
            Thread.sleep(uniform(2, 5));
        }
        // land exactly on 'to' (no drift)
        dispatchMove(to);
        Thread.sleep(uniform(2, 5));
    }

    private static Point moveBeyond(Point target, Point start, int pixels)
    {
        if (start == null || target == null) return target;
        double dx = target.x - start.x;
        double dy = target.y - start.y;
        double len = Math.max(1e-6, Math.hypot(dx, dy));
        double ux = dx / len;
        double uy = dy / len;
        return new Point(
                (int)Math.round(target.x + ux * pixels),
                (int)Math.round(target.y + uy * pixels)
        );
    }

    private static Point lastMouseOrTarget(Point last, Point fallback) {
        return (last != null) ? last : fallback;
    }

    private static int uniform(int min, int max)
    {
        if (max < min) { int t = min; min = max; max = t; }
        if (min <= 0 && max <= 0) return 0;
        final int lo = Math.max(0, min);
        final int hi = Math.max(0, max);
        return ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }

    private static int gaussianNonNegative(int mean, int std)
    {
        if (mean <= 0 && std <= 0) return 0;
        double g = ThreadLocalRandom.current().nextGaussian();
        long v = Math.round(mean + g * std);
        return (int)Math.max(0, v);
    }

    private static double clamp(double v, double lo, double hi) {
        return (v < lo) ? lo : (v > hi ? hi : v);
    }

    // ---------------------- tiny histogram for instrumentation ---------------
    private static final class Hist {
        private final int[] edges; // inclusive lower bounds; last is ">= last"
        private final int[] bins;  // counts

        Hist(int[] edges) {
            this.edges = edges.clone();
            this.bins  = new int[edges.length];
        }

        void add(int v) {
            int idx = edges.length - 1;
            for (int i = 0; i < edges.length; i++) {
                if (v < edges[i]) { idx = Math.max(0, i - 1); break; }
            }
            bins[idx]++;
        }

        String summary(String unit) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < edges.length; i++) {
                String range = (i == edges.length - 1)
                        ? (edges[i] + "+")
                        : (edges[i] + "-" + (edges[i + 1] - 1));
                sb.append(range).append(unit).append("=").append(bins[i]);
                if (i < edges.length - 1) sb.append(" ");
            }
            return sb.toString();
        }
    }
}
