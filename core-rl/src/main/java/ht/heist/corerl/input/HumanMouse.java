// ============================================================================
// FILE: HumanMouse.java
// PACKAGE: ht.heist.corerl.input
// -----------------------------------------------------------------------------
// TITLE
//   HumanMouse — curved move → reaction-before-press → short-hold click
//
// WHAT THIS CLASS DOES
//   • Runs clicks on a dedicated single-thread executor (non-blocking plugin).
//   • Builds a smooth Bezier-ish path with tiny wobble; steps paced 6..12 ms.
//   • Sleeps a Gaussian *reaction* BEFORE press (prevents yellow clicks).
//   • Optional Shift modifier around the press.
//   • Publishes each synthetic tap to a caller-provided sink (JSONL logger).
//
// PUBLIC API
//   - setTapSink(Consumer<Point> sink)
//   - clickHull(Shape hull, boolean shift)      // e.g., GameObject convex hull
//   - clickRect(Rectangle rect, boolean shift)  // e.g., inventory slot, minimap
//   - clickPoint(Point p, boolean shift)        // direct canvas click
//
// DEPENDENCY EXPECTATIONS
//   - MouseGateway provides low-level AWT dispatch:
//       • moveAlong(List<Point> path, int stepMinMs, int stepMaxMs)    // 3 args
//       • clickAt(Point where, int holdMinMs, int holdMaxMs, boolean shift)
//   - NO direct RuneLite Client needed here.
//
// CURVE VISIBILITY NOTE
//   • First-ever click after startup may “start” at the target (we don’t yet
//     know the mouse position). From the 2nd click onward, we remember the last
//     canvas point and produce a visible curved approach.
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@Singleton
public class HumanMouse
{
    // ---- Low-level mouse I/O (AWT dispatcher) --------------------------------
    private final MouseGateway io;

    // ---- Single-thread worker: all mouse sequences run here -------------------
    private final ExecutorService exec;

    // ---- Tap sink: called once per synthetic click (optional) -----------------
    private volatile Consumer<Point> tapSink = null;

    // ---- Remember last canvas point to avoid “teleport” on subsequent clicks --
    private volatile Point lastCanvas = null;

    // ---- Random & feel/tuning -------------------------------------------------
    private final Random rng = new Random();

    /** Per-step pacing while moving along the path. */
    private static final int STEP_MIN_MS = 6;
    private static final int STEP_MAX_MS = 12;

    /** Human reaction BEFORE press (prevents yellow clicks). */
    private static final int REACT_MEAN  = 120;
    private static final int REACT_STD   = 40;

    /** Mouse button hold duration. */
    private static final int HOLD_MIN    = 30;
    private static final int HOLD_MAX    = 55;

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
    // TAP SINK
    // =========================================================================
    /**
     * Provide a consumer that receives every synthetic tap point.
     * Typical usage: write JSONL lines so the HUD tailer can ingest them.
     */
    public void setTapSink(Consumer<Point> sink)
    {
        this.tapSink = sink;
    }

    // =========================================================================
    // PUBLIC CLICK HELPERS
    // =========================================================================

    /** Click somewhere *inside* a polygon (e.g., a GameObject convex hull). */
    public void clickHull(Shape hull, boolean shift)
    {
        if (hull == null) return;
        final Rectangle b = hull.getBounds();
        final Point target = gaussianPointInShape(hull, b);
        submitMoveReactClick(target, shift);
    }

    /** Click the inside/center of a rectangle (inventory slot, minimap, widgets). */
    public void clickRect(Rectangle rect, boolean shift)
    {
        if (rect == null) return;
        final Point target = gaussianPointInRect(rect);
        submitMoveReactClick(target, shift);
    }

    /** Click a direct canvas point. */
    public void clickPoint(Point target, boolean shift)
    {
        if (target == null) return;
        submitMoveReactClick(target, shift);
    }

    // =========================================================================
    // WORKER SEQUENCE
    // =========================================================================
    private void submitMoveReactClick(Point target, boolean shift)
    {
        exec.submit(() -> {
            try
            {
                // Where are we “coming from”? Use last known canvas point if present.
                final Point from = (lastCanvas != null) ? new Point(lastCanvas) : new Point(target);

                // Build a smooth curved path (Bezier-ish with tiny wobble).
                final List<Point> path = planPath(from, target);

                // Dispatch the path at human pace (3-arg version).
                io.moveAlong(path, STEP_MIN_MS, STEP_MAX_MS);

                // REAL blocking reaction BEFORE press (prevents yellow clicks).
                Thread.sleep(gaussianMs(REACT_MEAN, REACT_STD));

                // Publish the tap (optional) before the press; HUD tailer can show it.
                if (tapSink != null) {
                    try { tapSink.accept(new Point(target)); } catch (Throwable ignored) {}
                }

                // Press/hold/release at the target.
                io.clickAt(target, HOLD_MIN, HOLD_MAX, shift);

                // Remember where we ended so the next path starts here.
                lastCanvas = new Point(target);
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
            }
            catch (Throwable t)
            {
                // Never crash the game loop due to mouse issues.
            }
        });
    }

    // =========================================================================
    // PATH PLANNING — Bezier-ish curve with subtle noise
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

        final double dx = to.x - from.x;
        final double dy = to.y - from.y;
        final double len = Math.hypot(dx, dy);

        final double ux = (len == 0) ? 0 : (-dy / len);
        final double uy = (len == 0) ? 0 : ( dx / len);

        // Control point off the midline to create a natural arc.
        final double side = (rng.nextBoolean() ? 1.0 : -1.0);
        final double cx = from.x + dx * 0.5 + side * curvature * 0.25 * len * ux;
        final double cy = from.y + dy * 0.5 + side * curvature * 0.25 * len * uy;

        for (int i = 1; i <= steps; i++)
        {
            final double t = i / (double)(steps + 1);
            final double omt = 1.0 - t;

            double bx = omt*omt*from.x + 2.0*omt*t*cx + t*t*to.x;
            double by = omt*omt*from.y + 2.0*omt*t*cy + t*t*to.y;

            // Tiny jitter so the line isn’t mechanically perfect.
            bx += (rng.nextDouble() * 2 - 1) * noisePx;
            by += (rng.nextDouble() * 2 - 1) * noisePx;

            out.add(new Point((int)Math.round(bx), (int)Math.round(by)));
        }

        // Ensure we end exactly on the target (last point).
        out.add(new Point(to));
        return out;
    }

    // =========================================================================
    // POINT SAMPLERS — Gaussian bias toward center, clamped to target area
    // =========================================================================
    private Point gaussianPointInRect(Rectangle r)
    {
        final double meanX = r.getCenterX();
        final double meanY = r.getCenterY();
        final double stdX  = Math.max(1.0, r.getWidth()  / 4.0);
        final double stdY  = Math.max(1.0, r.getHeight() / 4.0);

        for (int i = 0; i < 10; i++)
        {
            final int x = (int)Math.round(rng.nextGaussian() * stdX + meanX);
            final int y = (int)Math.round(rng.nextGaussian() * stdY + meanY);
            if (r.contains(x, y)) return new Point(x, y);
        }
        return new Point((int)meanX, (int)meanY);
    }

    private Point gaussianPointInShape(Shape shape, Rectangle bounds)
    {
        if (shape == null || bounds == null) return new Point(0, 0);

        final double meanX = bounds.getCenterX();
        final double meanY = bounds.getCenterY();
        final double stdX  = Math.max(1.0, bounds.getWidth()  / 4.0);
        final double stdY  = Math.max(1.0, bounds.getHeight() / 4.0);

        for (int i = 0; i < 12; i++)
        {
            final int x = (int)Math.round(rng.nextGaussian() * stdX + meanX);
            final int y = (int)Math.round(rng.nextGaussian() * stdY + meanY);
            if (shape.contains(x, y)) return new Point(x, y);
        }
        return new Point((int)meanX, (int)meanY);
    }

    // =========================================================================
    // SMALL UTILS
    // =========================================================================
    private static int gaussianMs(int mean, int std) {
        return Math.max(0, (int)Math.round(mean + std * new Random().nextGaussian()));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
