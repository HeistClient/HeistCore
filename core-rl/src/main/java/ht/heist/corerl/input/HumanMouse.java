// ============================================================================
// FILE: HumanMouse.java
// PATH: core-rl/src/main/java/ht/heist/corerl/input/HumanMouse.java
// PACKAGE: ht.heist.corerl.input
// -----------------------------------------------------------------------------
// TITLE
//   HumanMouse — unified "humanized click" executor for RuneLite Canvas
//
// WHAT THIS CLASS DOES (mirrors your old working method)
//   • Delegates ALL "feel" (where to move, how to arc, pacing, timings)
//     to the pure-Java core (MouseService/MouseServiceImpl).
//   • Uses MouseGateway to dispatch pure AWT events (no Robot / no OS cursor).
//   • CRITICAL: path planning **always** starts from the *actual* canvas
//     pointer (from RuneLite), never from the target → no teleports.
//   • If RL doesn’t have a pointer yet (very first click), it synthesizes
//     a tiny pre-approach behind the target so the first move still looks
//     like a real approach.
// ============================================================================

package ht.heist.corerl.input;

import ht.heist.corejava.api.input.MouseService;
import ht.heist.corejava.input.MouseServiceImpl;
import ht.heist.corejava.input.HumanizerImpl; // <-- pass this into MouseServiceImpl
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
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

    // IO bridge that talks to the RuneLite Canvas (dispatches AWT events)
    private final MouseGateway io;

    // Pure-Java planner providing all "feel" (path & timings)
    private final MouseService mouse;

    // Worker so we do not block the game thread
    private final ExecutorService exec;

    // Optional: notified right before we press at target (e.g., logger/HUD)
    private volatile Consumer<Point> tapSink = null;

    // We remember the last point we moved to for smoother next arcs
    private volatile Point lastMouse = null;

    @Inject
    public HumanMouse(MouseGateway io)
    {
        this.io = io;

        // *** FIX: your MouseServiceImpl expects a Humanizer argument. ***
        // If you later add a no-arg ctor, you can revert to new MouseServiceImpl().
        this.mouse = new MouseServiceImpl(new HumanizerImpl());

        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Heist-HumanMouse");
            t.setDaemon(true);
            return t;
        });
    }

    // -------------------------------------------------------------------------
    // Session lifecycle
    // -------------------------------------------------------------------------

    /**
     * Call at the start of a run/session.
     *  • Resets gateway “entered” guard so first move primes hover.
     *  • Seeds lastMouse from actual RL pointer if available.
     */
    public void onSessionStart()
    {
        io.resetEnteredFlag();
        Point cur = io.currentMouseCanvasPoint();
        lastMouse = (cur != null ? new Point(cur) : null);
    }

    public void setTapSink(Consumer<Point> sink) { this.tapSink = sink; }

    // -------------------------------------------------------------------------
    // Public click helpers
    // -------------------------------------------------------------------------

    /** Click somewhere inside an arbitrary shape (e.g., convex hull). */
    public void clickHull(Shape hull, boolean shift)
    {
        if (hull == null) return;
        final Point target = mouse.pickPointInShape(hull); // humanized point-in-shape
        submitMoveReactClick(target, shift);
    }

    /** Click somewhere inside a rectangle (rect is-a Shape). */
    public void clickRect(Rectangle rect, boolean shift)
    {
        if (rect == null) return;
        final Point target = mouse.pickPointInShape(rect);
        submitMoveReactClick(target, shift);
    }

    /** Click an explicit canvas point. */
    public void clickPoint(Point target, boolean shift)
    {
        if (target == null) return;
        submitMoveReactClick(target, shift);
    }

    // -------------------------------------------------------------------------
    // Internal: plan path from REAL pointer → move along → react → click
    // -------------------------------------------------------------------------

    private void submitMoveReactClick(Point target, boolean shift)
    {
        exec.submit(() -> {
            try
            {
                // 1) Resolve a real starting point — prefer RL’s actual pointer
                Point from = null;
                Point cur = io.currentMouseCanvasPoint();
                if (cur != null && cur.x >= 0 && cur.y >= 0) {
                    from = new Point(cur);
                } else if (lastMouse != null) {
                    from = lastMouse;
                }
                // Last resort: a tiny pre-approach behind the target
                if (from == null) {
                    int backX = Math.max(0, target.x - 14);
                    int backY = Math.max(0, target.y - 10);
                    from = new Point(backX, backY);
                }

                // 2) Ask pure-Java core to plan a curved path and step pacing
                final MouseService.MousePlan plan = mouse.planPath(from, target);
                final List<Point> waypoints = plan.path();
                final int stepMinMs = plan.stepDelayMinMs();
                final int stepMaxMs = plan.stepDelayMaxMs();

                // 3) Move along the path (AWT events)
                if (waypoints != null && !waypoints.isEmpty()) {
                    log.debug("path_steps n={}", waypoints.size());
                    for (int i = 0; i < waypoints.size(); i++) {
                        Point p = waypoints.get(i);
                        io.dispatchMove(p);
                        lastMouse = p;
                        if ((i & 3) == 0) { // light debug every ~4 steps
                            log.debug("mv i={} x={} y={}", i, p.x, p.y);
                        }
                        sleepMillis(uniform(stepMinMs, stepMaxMs));
                    }
                }

                // Ensure we end at the final target
                if (lastMouse == null || !lastMouse.equals(target)) {
                    io.dispatchMove(target);
                    lastMouse = target;
                    sleepMillis(uniform(stepMinMs, stepMaxMs));
                }

                // 4) Reaction BEFORE press (Gaussian from Timings)
                final MouseService.Timings timings = mouse.planTimings();
                final int reactMs = gaussianNonNegative(timings.reactMeanMs(), timings.reactStdMs());
                sleepMillis(reactMs);

                // Tap sink (optional) — just before press
                final Consumer<Point> sink = this.tapSink;
                if (sink != null) {
                    try { sink.accept(target); } catch (Throwable ignored) {}
                }

                // 5) Click (optionally with Shift); hold sampled uniformly by gateway
                final int holdMin = timings.holdMinMs();
                final int holdMax = timings.holdMaxMs();

                if (shift) {
                    io.shiftDown();
                    sleepMillis(30); // small safety lead
                    io.clickAt(target, holdMin, holdMax, true);
                    sleepMillis(30); // small safety lag
                    io.shiftUp();
                } else {
                    io.clickAt(target, holdMin, holdMax, false);
                }
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
    // Small helpers
    // -------------------------------------------------------------------------

    /** Inclusive uniform int in [min..max]; clamps to ≥0 and swaps if needed. */
    private static int uniform(int min, int max)
    {
        if (max < min) { int t = min; min = max; max = t; }
        if (max <= 0) return 0;
        if (min < 0) min = 0;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /** Gaussian sample clamped to ≥ 0. */
    private static int gaussianNonNegative(int mean, int std)
    {
        if (mean <= 0 && std <= 0) return 0;
        double g = ThreadLocalRandom.current().nextGaussian(); // mean=0, std=1
        long v = Math.round(mean + g * std);
        return (int)Math.max(0, v);
    }

    /** Simple sleep helper. */
    private static void sleepMillis(int ms) throws InterruptedException
    {
        if (ms > 0) Thread.sleep(ms);
    }
}
