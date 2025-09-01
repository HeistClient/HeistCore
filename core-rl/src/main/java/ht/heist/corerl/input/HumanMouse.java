// ============================================================================
// FILE: HumanMouse.java
// PACKAGE: ht.heist.corerl.input
// -----------------------------------------------------------------------------
// TITLE
//   HumanMouse — full "human" sequence (curved move → reaction → click)
//   Runs on its own single-thread executor; plugin remains thin.
//
// WHAT THIS CLASS DOES
//   • Builds a curved path from last-known mouse position to target,
//     then asks MouseGateway to dispatch MOUSE_MOVED events along it.
//   • Sleeps a Gaussian “reaction” BEFORE pressing — prevents yellow clicks.
//   • Optionally presses Shift around the click with configurable waits.
//   • Emits a tap sink callback so you can log to JSONL or HUD.
//   • Provides hull/rect/point helpers for common targets.
//
// RUNTIME TUNING (from your plugin startUp()):
//   humanMouse.setTapSink(p -> SyntheticTapLogger.log(p.x, p.y));
//   humanMouse.setShiftWaitsMs(30, 30); // or setShiftGaussian(...)
//   humanMouse.setStepRange(6, 12);
//   humanMouse.setReaction(120, 40);
//   humanMouse.setHoldRange(30, 55);
// ============================================================================

package ht.heist.corerl.input;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Singleton
public class HumanMouse
{
    // ---- IO bridge that actually dispatches AWT events onto RL canvas -------
    private final MouseGateway io;

    // ---- Worker thread so we never block the game thread --------------------
    private final ExecutorService exec;

    // ---- Random source -------------------------------------------------------
    private final Random rng = new Random();

    // ---- Tap sink (optional) -------------------------------------------------
    private volatile Consumer<Point> tapSink = null;

    // ---- “Feel” defaults (tunable) ------------------------------------------
    // Step pacing while moving along the curve
    private volatile int stepMinMs = 6;
    private volatile int stepMaxMs = 12;

    // Reaction pause BEFORE pressing (Gaussian)
    private volatile int reactMeanMs = 120;
    private volatile int reactStdMs  = 40;

    // Mouse button hold duration range
    private volatile int holdMinMs = 30;
    private volatile int holdMaxMs = 55;

    // Shift timing (either FIXED waits or GAUSSIAN with minimums)
    private volatile boolean useShiftGaussian = false;
    // Fixed mode
    private volatile int shiftLeadFixedMs = 20; // wait after Shift down, before press
    private volatile int shiftLagFixedMs  = 20; // wait after release, before Shift up
    // Gaussian mode (with minimums)
    private volatile int shiftLeadMinMs = 10, shiftLeadMeanMs = 25, shiftLeadStdMs = 8;
    private volatile int shiftLagMinMs  = 10, shiftLagMeanMs  = 25, shiftLagStdMs  = 8;

    // We don’t query RL for current mouse; track last target so consecutive moves feel smooth
    private volatile Point lastMouse = null;

    @Inject
    public HumanMouse(MouseGateway io)
    {
        this.io = io;
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Heist-HumanMouse");
            t.setDaemon(true);
            return t;
        });
    }

    // =========================================================================
    // PUBLIC API — TARGET HELPERS
    // =========================================================================

    /** Click inside a convex hull (or any Shape polygon) with human timing. */
    public void clickHull(Shape hull, boolean shift)
    {
        if (hull == null) return;
        Rectangle b = hull.getBounds();
        Point target = gaussianPointInShape(hull, b);
        submitMoveReactClick(target, shift);
    }

    /** Click within a UI rectangle (widgets, minimap, inventory slots). */
    public void clickRect(Rectangle rect, boolean shift)
    {
        if (rect == null) return;
        Point target = gaussianPointInRect(rect);
        submitMoveReactClick(target, shift);
    }

    /** Click a specific canvas point. */
    public void clickPoint(Point target, boolean shift)
    {
        if (target == null) return;
        submitMoveReactClick(target, shift);
    }

    // =========================================================================
    // RUNTIME TUNING
    // =========================================================================

    /** Sink for synthetic taps (e.g., JSONL logger or HUD). Called before press. */
    public void setTapSink(Consumer<Point> sink) { this.tapSink = sink; }

    /** Fixed waits around Shift in milliseconds (lead = after Shift down, lag = before Shift up). */
    public void setShiftWaitsMs(int leadMs, int lagMs)
    {
        this.useShiftGaussian = false;
        this.shiftLeadFixedMs = Math.max(0, leadMs);
        this.shiftLagFixedMs  = Math.max(0, lagMs);
    }

    /**
     * Gaussian waits with per-side minimums.
     * leadMs = max(minLead, N(meanLead, stdLead)), lagMs = max(minLag, N(meanLag, stdLag)).
     */
    public void setShiftGaussian(int minLead, int meanLead, int stdLead,
                                 int minLag,  int meanLag,  int stdLag)
    {
        this.useShiftGaussian = true;
        this.shiftLeadMinMs = Math.max(0, minLead);
        this.shiftLeadMeanMs = Math.max(0, meanLead);
        this.shiftLeadStdMs  = Math.max(0, stdLead);
        this.shiftLagMinMs = Math.max(0, minLag);
        this.shiftLagMeanMs = Math.max(0, meanLag);
        this.shiftLagStdMs  = Math.max(0, stdLag);
    }

    /** Step pacing while moving along the curve. */
    public void setStepRange(int minMs, int maxMs)
    {
        this.stepMinMs = Math.max(1, Math.min(minMs, maxMs));
        this.stepMaxMs = Math.max(this.stepMinMs, maxMs);
    }

    /** Reaction pause BEFORE pressing (Gaussian). */
    public void setReaction(int meanMs, int stdMs)
    {
        this.reactMeanMs = Math.max(0, meanMs);
        this.reactStdMs  = Math.max(0, stdMs);
    }

    /** Mouse button hold range. */
    public void setHoldRange(int minMs, int maxMs)
    {
        this.holdMinMs = Math.max(1, Math.min(minMs, maxMs));
        this.holdMaxMs = Math.max(this.holdMinMs, maxMs);
    }

    // =========================================================================
    // INTERNAL — WORKER SEQUENCE
    // =========================================================================

    private void submitMoveReactClick(Point target, boolean shift)
    {
        exec.submit(() -> {
            try
            {
                // Build a curved path from lastKnown -> target
                Point from = (lastMouse != null ? lastMouse : target);
                List<Point> path = planPath(from, target);

                // Move along curve (MouseGateway will dispatch MOUSE_MOVED events)
                io.moveAlong(path, stepMinMs, stepMaxMs);

                // Reaction BEFORE press (prevents yellow clicks)
                sleepGaussian(reactMeanMs, reactStdMs);

                // Emit tap to sink just before pressing
                Consumer<Point> sink = this.tapSink;
                if (sink != null) {
                    try { sink.accept(target); } catch (Throwable ignored) {}
                }

                // Optional Shift around the click
                if (shift)
                {
                    io.shiftDown();
                    sleepMillis(sampleShiftLeadMs());   // let OSRS register Shift
                    io.clickAt(target, holdMinMs, holdMaxMs, true);
                    sleepMillis(sampleShiftLagMs());    // small settle before releasing Shift
                    io.shiftUp();
                }
                else
                {
                    io.clickAt(target, holdMinMs, holdMaxMs, false);
                }

                // Track last mouse for next arc
                lastMouse = target;
            }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // =========================================================================
    // PATH PLANNING — quadratic curve + tiny wobble
    // =========================================================================

    private List<Point> planPath(Point from, Point to)
    {
        final ArrayList<Point> out = new ArrayList<>();
        if (to == null) return out;
        if (from == null) { out.add(new Point(to)); return out; }

        final int dist  = (int)Math.round(from.distance(to));
        final int steps = clamp(dist / 12, 6, 28);

        final double curvature = 0.55;
        final double noisePx   = 1.6;

        final double dx = to.x - from.x, dy = to.y - from.y;
        final double len = Math.hypot(dx, dy);
        final double ux = (len == 0) ? 0 : (-dy / len);
        final double uy = (len == 0) ? 0 : ( dx / len);

        final double side = (rng.nextBoolean() ? 1.0 : -1.0);
        final double cx = from.x + dx * 0.5 + side * curvature * 0.25 * len * ux;
        final double cy = from.y + dy * 0.5 + side * curvature * 0.25 * len * uy;

        for (int i = 1; i <= steps; i++)
        {
            final double t = i / (double)(steps + 1);
            final double omt = 1.0 - t;
            double bx = omt*omt*from.x + 2.0*omt*t*cx + t*t*to.x;
            double by = omt*omt*from.y + 2.0*omt*t*cy + t*t*to.y;
            bx += (rng.nextDouble() * 2 - 1) * noisePx;
            by += (rng.nextDouble() * 2 - 1) * noisePx;
            out.add(new Point((int)Math.round(bx), (int)Math.round(by)));
        }
        out.add(new Point(to));
        return out;
    }

    // =========================================================================
    // POINT SAMPLERS — 2D Gaussian with containment checks
    // =========================================================================

    private Point gaussianPointInRect(Rectangle r)
    {
        double meanX = r.getCenterX();
        double meanY = r.getCenterY();
        double stdX  = Math.max(1.0, r.getWidth()  / 4.0);
        double stdY  = Math.max(1.0, r.getHeight() / 4.0);

        for (int i = 0; i < 10; i++) {
            int x = (int)Math.round(rng.nextGaussian() * stdX + meanX);
            int y = (int)Math.round(rng.nextGaussian() * stdY + meanY);
            if (r.contains(x, y)) return new Point(x, y);
        }
        return new Point((int)meanX, (int)meanY);
    }

    private Point gaussianPointInShape(Shape shape, Rectangle bounds)
    {
        if (shape == null || bounds == null) return new Point(0, 0);
        double meanX = bounds.getCenterX();
        double meanY = bounds.getCenterY();
        double stdX  = Math.max(1.0, bounds.getWidth()  / 4.0);
        double stdY  = Math.max(1.0, bounds.getHeight() / 4.0);

        for (int i = 0; i < 10; i++) {
            int x = (int)Math.round(rng.nextGaussian() * stdX + meanX);
            int y = (int)Math.round(rng.nextGaussian() * stdY + meanY);
            if (shape.contains(x, y)) return new Point(x, y);
        }
        return new Point((int)meanX, (int)meanY);
    }

    // =========================================================================
    // SMALL UTILS
    // =========================================================================

    private int sampleShiftLeadMs()
    {
        if (!useShiftGaussian) return shiftLeadFixedMs;
        int g = (int)Math.round(reactSample(shiftLeadMeanMs, shiftLeadStdMs));
        return Math.max(shiftLeadMinMs, g);
    }

    private int sampleShiftLagMs()
    {
        if (!useShiftGaussian) return shiftLagFixedMs;
        int g = (int)Math.round(reactSample(shiftLagMeanMs, shiftLagStdMs));
        return Math.max(shiftLagMinMs, g);
    }

    private void sleepGaussian(int meanMs, int stdMs) throws InterruptedException
    {
        int d = (int)Math.max(0, Math.round(reactSample(meanMs, stdMs)));
        Thread.sleep(d);
    }

    private double reactSample(int mean, int std)
    {
        // Absolute to avoid accidental negatives on rare large negative draws.
        return Math.abs(mean + rng.nextGaussian() * std);
    }

    private static void sleepMillis(int ms) throws InterruptedException
    {
        if (ms <= 0) return;
        Thread.sleep(ms);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
