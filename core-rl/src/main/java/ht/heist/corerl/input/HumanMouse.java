// ============================================================================
// FILE: HumanMouse.java
// PATH: core-rl/src/main/java/ht/heist/corerl/input/HumanMouse.java
// PACKAGE: ht.heist.corerl.input
// -----------------------------------------------------------------------------
// TITLE
//   HumanMouse — unified "humanized click" executor for RuneLite Canvas
//
// OVERVIEW
//   This class performs a complete human-like click sequence by asking the
//   PURE-JAVA core for *all* "feel":
//     • WHERE to move and *how*: via MouseService.planPath(...) -> MousePlan
//     • WHEN to pause and press: via MouseService.planTimings() -> Timings
//
//   HumanMouse itself does *no* humanization math. It simply:
//     1) Asks MouseService to choose a target point in a shape/rect (if needed).
//     2) Asks MouseService to plan a curved path and per-move step pacing.
//     3) Dispatches MOUSE_MOVED events along that path using MouseGateway.
//     4) Sleeps a Gaussian "reaction" BEFORE pressing (from Timings).
//     5) Optionally handles Shift modifier (tiny fixed waits — see notes).
//     6) Delegates PRESS/HOLD/RELEASE to MouseGateway.clickAt(...).
//
//   RESULT
//     • You can tune all humanization (drift, curvature, wobble, timing)
//       centrally in core-java (Humanizer/HumanizerImpl/MouseServiceImpl).
//     • core-rl stays thin and focused on RuneLite I/O only.
//
// DEPENDENCIES
//   - ht.heist.corejava.api.input.MouseService (core-java API; pure Java)
//   - ht.heist.corejava.input.MouseServiceImpl (default implementation)
//   - MouseGateway (this module; dispatches AWT events to RuneLite Canvas)
//
// THREADING
//   - Uses a single-threaded executor so plugin code never blocks the game
//     thread. Each click request is submitted as a task to that worker.
// ============================================================================

package ht.heist.corerl.input;

import ht.heist.corejava.api.input.MouseService;
import ht.heist.corejava.input.MouseServiceImpl;
import net.runelite.api.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Canvas;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

@Singleton
public final class HumanMouse
{
    // -------------------------------------------------------------------------
    // Logger (for diagnostics and guardrails)
    // -------------------------------------------------------------------------
    private static final Logger log = LoggerFactory.getLogger(HumanMouse.class);

    // -------------------------------------------------------------------------
    // IO bridge that talks to the RuneLite Canvas (dispatches AWT events)
    // -------------------------------------------------------------------------
    private final MouseGateway io;

    // -------------------------------------------------------------------------
    // Pure-Java planner providing all "feel" (path & timings)
    //   • This centralizes drift/curvature/wobble/timing in core-java.
    // -------------------------------------------------------------------------
    private final MouseService mouse;

    // -------------------------------------------------------------------------
    // Worker so we do not block the game thread while sleeping/moving/clicking
    // -------------------------------------------------------------------------
    private final ExecutorService exec;

    // -------------------------------------------------------------------------
    // Optional: tap sink callback (e.g., JSONL logger or HUD listener)
    // Called right before we press the mouse button at the final target.
    // -------------------------------------------------------------------------
    private volatile Consumer<Point> tapSink = null;

    // -------------------------------------------------------------------------
    // State: we remember the last target used so consecutive moves feel smooth
    // -------------------------------------------------------------------------
    private volatile Point lastMouse = null;

    // rng used only for small internal choices (e.g., rare fallbacks)
    private final Random rng = new Random();

    // -------------------------------------------------------------------------
    // CONSTRUCTION
    //   • We inject MouseGateway from DI (RuneLite provides Client).
    //   • We internally construct MouseServiceImpl (pure Java).
    //     If you later want to inject MouseService as well, that's fine.
    // -------------------------------------------------------------------------
    @Inject
    public HumanMouse(MouseGateway io)
    {
        this.io = io;
        this.mouse = new MouseServiceImpl(); // centralizes "feel"
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Heist-HumanMouse");
            t.setDaemon(true);
            return t;
        });
    }

    // =========================================================================
    // PUBLIC API — High-level click helpers
    // =========================================================================

    /**
     * Title: Click inside an arbitrary shape (e.g., convex hull of a GameObject).
     * Behavior: Delegates the sampling of a human-like point-in-shape to the
     *           MouseService (which applies sticky drift / elliptical Gaussian),
     *           plans a path, performs a human-like reaction delay, and clicks.
     *
     * @param hull  shape to click inside (e.g., GameObject.getConvexHull()).
     * @param shift if true, we hold Shift around the click.
     */
    public void clickHull(Shape hull, boolean shift)
    {
        if (hull == null) return;

        // Ask the pure-Java core to pick a point inside the shape (humanized).
        final Point target = mouse.pickPointInShape(hull);
        submitMoveReactClick(target, shift);
    }

    /**
     * Title: Click inside a rectangle (e.g., inventory slot bounds).
     * Delegates point picking to MouseService for humanized sampling.
     */
    public void clickRect(Rectangle rect, boolean shift)
    {
        if (rect == null) return;

        final Point target = mouse.pickPointInShape(rect); // rect is a Shape
        submitMoveReactClick(target, shift);
    }

    /**
     * Title: Click a specific canvas point "as human".
     * If you already have a precise target, we won't re-sample inside a shape.
     */
    public void clickPoint(Point target, boolean shift)
    {
        if (target == null) return;
        submitMoveReactClick(target, shift);
    }

    /**
     * Title: Tap sink setter (optional).
     * If set, this callback is invoked right before we press at the target.
     * Useful for logging heatmaps or building datasets.
     */
    public void setTapSink(Consumer<Point> sink) { this.tapSink = sink; }

    // =========================================================================
    // INTERNAL — Sequence: move along path -> react -> (optional Shift) click
    // =========================================================================

    /**
     * Title: Submit the full "human click" sequence to the worker.
     * Logic:
     *   • Build a path via MouseService.planPath(lastMouse, target).
     *   • Dispatch MOUSE_MOVED along that path with the step delays from plan.
     *   • Sleep a Gaussian "reaction" time BEFORE press (from planTimings()).
     *   • If requested: hold Shift around the press with small safety waits.
     *   • Delegate PRESS/HOLD/RELEASE to MouseGateway.clickAt(...).
     */
    private void submitMoveReactClick(Point target, boolean shift)
    {
        exec.submit(() -> {
            try
            {
                // -----------------------------------------------------------------
                // 1) PLAN PATH (pure Java) using prior target as "from" if available
                // -----------------------------------------------------------------
                final Point from = (lastMouse != null ? lastMouse : target);
                final MouseService.MousePlan plan = mouse.planPath(from, target);

                // The plan provides:
                //   • path():           List<Point> intermediate waypoints (excludes final target)
                //   • stepDelayMinMs(): min sleep between move points
                //   • stepDelayMaxMs(): max sleep between move points
                final List<Point> waypoints = plan.path();
                final int stepMinMs = plan.stepDelayMinMs();
                final int stepMaxMs = plan.stepDelayMaxMs();

                // -----------------------------------------------------------------
                // 2) MOVE ALONG THE PATH (dispatch MOUSE_MOVED for each waypoint)
                //     We pace each move with a uniform sleep in [min..max] ms.
                //     Note: final target is *also* moved to before clicking.
                // -----------------------------------------------------------------
                if (waypoints != null && !waypoints.isEmpty())
                {
                    for (Point p : waypoints)
                    {
                        dispatchMove(p);
                        sleepMillis(uniform(stepMinMs, stepMaxMs));
                    }
                }

                // Ensure we end at the final target (so press happens exactly there)
                dispatchMove(target);
                sleepMillis(uniform(stepMinMs, stepMaxMs));

                // -----------------------------------------------------------------
                // 3) REACTION BEFORE PRESS
                //    Ask core for human timing ranges (reaction, hold, dwell min/max).
                //    We only need reaction + hold for this executor.
                // -----------------------------------------------------------------
                final MouseService.Timings timings = mouse.planTimings();

                // Reaction: Gaussian around mean/std; clamped to >= 0
                final int reactMs = gaussianNonNegative(timings.reactMeanMs(), timings.reactStdMs());
                sleepMillis(reactMs);

                // Tap sink (optional) — e.g., write a heatmap point just before press
                final Consumer<Point> sink = this.tapSink;
                if (sink != null) {
                    try { sink.accept(target); } catch (Throwable ignored) {}
                }

                // -----------------------------------------------------------------
                // 4) CLICK (optionally with Shift)
                //    Hold duration is sampled uniformly in [holdMin..holdMax] by gateway.
                //    For Shift, we add tiny "safety waits" around the click to ensure
                //    the client registers the modifier; these are intentionally small
                //    because the centralized timing model doesn't expose keyLead/keyLag
                //    in Timings. If you later add them, wire them here instead.
                // -----------------------------------------------------------------
                final int holdMin = timings.holdMinMs();
                final int holdMax = timings.holdMaxMs();

                if (shift)
                {
                    io.shiftDown();
                    sleepMillis(30); // small lead safety
                    io.clickAt(target, holdMin, holdMax, true);
                    sleepMillis(30); // small lag safety
                    io.shiftUp();
                }
                else
                {
                    io.clickAt(target, holdMin, holdMax, false);
                }

                // -----------------------------------------------------------------
                // 5) UPDATE lastMouse for smoother subsequent arcs
                // -----------------------------------------------------------------
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

    // =========================================================================
    // SMALL HELPERS
    // =========================================================================

    /**
     * Title: Dispatch a single MOUSE_MOVED to the current RuneLite Canvas.
     * Notes:
     *   - Uses MouseGateway.dispatchMove(...) (no sleep inside).
     *   - Guarded against null canvas or null point.
     */
    private void dispatchMove(Point p)
    {
        if (p == null) return;
        final Canvas c = io.canvasOrNull();
        if (c == null) return;
        io.dispatchMove(p);
    }

    /**
     * Title: Sleep helper that accepts an int (never negative).
     * The rest of the code casts to int before calling this, which keeps
     * behavior consistent with Thread.sleep(...) expectations.
     */
    private static void sleepMillis(int ms) throws InterruptedException
    {
        if (ms <= 0) return;
        Thread.sleep(ms);
    }

    /**
     * Title: Inclusive uniform int in [min..max].
     * Guards against swapped or non-positive ranges.
     */
    private static int uniform(int min, int max)
    {
        if (max < min) { int t = min; min = max; max = t; }
        if (max <= 0) return 0;
        if (min < 0) min = 0;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /**
     * Title: Gaussian sample clamped to >= 0.
     * @param mean mean in ms
     * @param std  std dev in ms
     * @return non-negative int milliseconds
     */
    private static int gaussianNonNegative(int mean, int std)
    {
        if (mean <= 0 && std <= 0) return 0;
        double g = ThreadLocalRandom.current().nextGaussian(); // mean=0, std=1
        long v = Math.round(mean + g * std);
        return (int)Math.max(0, v);
    }
}
