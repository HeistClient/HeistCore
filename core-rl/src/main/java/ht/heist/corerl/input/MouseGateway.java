// ============================================================================
// FILE: MouseGateway.java
// PATH: core-rl/src/main/java/ht/heist/corerl/input/MouseGateway.java
// PACKAGE: ht.heist.corerl.input
// -----------------------------------------------------------------------------
// TITLE
//   MouseGateway — Thin AWT dispatcher that talks to RuneLite's Canvas
//
// WHAT THIS CLASS IS
//   • A minimal, **stateless** bridge that turns higher-level intents
//     (move, click, hold, shift modifiers) into **synthetic AWT events**
//     targeting RuneLite's game Canvas.
//
// WHAT THIS CLASS DOES (intentionally simple)
//   • moveAlong(path, stepMinMs, stepMaxMs):
//       - Iterates a list of canvas Points
//       - Dispatches a MOUSE_MOVED event for each point
//       - Sleeps a small randomized delay between points (pacing supplied
//         by the caller; here we only sample uniformly)
//   • clickAt(point, holdMinMs, holdMaxMs, shiftAlreadyDown):
//       - Emits a PRESS → (sleep hold) → RELEASE → CLICKED sequence at `point`
//       - If `shiftAlreadyDown` is true, it sets the SHIFT modifier mask
//         on the events (HumanMouse controls *when* Shift is down).
//   • shiftDown() / shiftUp():
//       - Sends KEY_PRESSED / KEY_RELEASED for VK_SHIFT to the Canvas.
//       - Timing is **not** handled here (HumanMouse sleeps around these).
//   • (NEW) canvasOrNull():
//       - Exposes the actual Canvas the client is currently using, or null
//         if not available (e.g., not logged in yet)
//   • (NEW) dispatchMove(Point):
//       - Dispatches a single MOUSE_MOVED (no sleep). HumanMouse uses this to
//         implement its own "ease-in" pacing logic across the path.
//
// WHAT THIS CLASS DOES **NOT** DO
//   • No "humanization": no curves, no timing profiles, no overshoots.
//     All of that lives in HumanMouse/Humanizer so you have **one** place
//     to tune the "feel".
//   • No Background Threads: all sleeps occur **in the caller thread**.
//     HumanMouse already runs on its own single-threaded executor.
//
// THREADING & SAFETY
//   • Canvas access is read-only and null-checked each time.
//   • All AWT events are synchronously dispatched to the Canvas via
//     Canvas.dispatchEvent(...). RuneLite is built to tolerate this for
//     synthetic input; we keep it **light** and **deterministic** here.
//
// COORDINATE SYSTEM
//   • All Points are expected to be **canvas coordinates** (pixels) in the
//     current RuneLite client window. Make sure upstream code computes them
//     appropriately when targeting UI or game objects.
//
// LOGGING
//   • SLF4J logger is present but used sparingly to avoid hot-path overhead.
//     Most diagnostics should be handled at the controller layer.
// ============================================================================

package ht.heist.corerl.input;

import net.runelite.api.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Canvas;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Singleton
public class MouseGateway
{
    /** SLF4J logger; avoid heavy logging inside hot paths. */
    private static final Logger log = LoggerFactory.getLogger(MouseGateway.class);

    /** RuneLite client (provides access to the active Canvas). */
    private final Client client;

    // -------------------------------------------------------------------------
    // CONSTRUCTION
    // -------------------------------------------------------------------------

    @Inject
    public MouseGateway(Client client)
    {
        this.client = client;
    }

    // -------------------------------------------------------------------------
    // CANVAS HELPERS (SAFE ACCESS)
    // -------------------------------------------------------------------------

    /**
     * Return the current RuneLite Canvas or null if none is available.
     *
     * WHY THIS EXISTS
     *  - Higher layers may want to check availability once per sequence
     *    and skip actions if the canvas has not been created yet, or if
     *    the client is transitioning states.
     */
    public Canvas canvasOrNull() {
        return client.getCanvas();
    }

    /**
     * Dispatch a single MOUSE_MOVED event at the given canvas point.
     *
     * NOTES
     *  - No sleeping is performed here (HumanMouse controls pacing).
     *  - Null-safe: if canvas or point is null, this is a no-op.
     */
    public void dispatchMove(Point p) {
        final Canvas canvas = client.getCanvas();
        if (canvas == null || p == null) return;

        // Synthesize a standard "mouse moved" event with no modifiers.
        canvas.dispatchEvent(new MouseEvent(
                canvas,
                MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(),
                0,                      // no modifiers
                p.x, p.y,
                0,                      // clickCount
                false,                  // popupTrigger
                0                       // button (unused for move)
        ));
    }

    // -------------------------------------------------------------------------
    // MOVE: dispatch a series of MOUSE_MOVED events along the provided path
    // -------------------------------------------------------------------------

    /**
     * Moves the mouse along a precomputed path by dispatching a sequence of
     * MOUSE_MOVED events and sleeping between points.
     *
     * @param path      ordered list of canvas Points; if empty or null → no-op
     * @param stepMinMs minimum sleep between points (inclusive)
     * @param stepMaxMs maximum sleep between points (inclusive)
     *
     * THREADING
     *  - Sleeps in the **calling thread**. HumanMouse calls this from its
     *    dedicated single-threaded executor to avoid blocking the game thread.
     */
    public void moveAlong(List<Point> path, int stepMinMs, int stepMaxMs) throws InterruptedException
    {
        if (path == null || path.isEmpty()) return;
        final Canvas canvas = client.getCanvas();
        if (canvas == null) return;

        for (Point p : path)
        {
            // Use the helper so single-point logic stays in one place.
            dispatchMove(p);

            // Pacing: uniform random in [min..max] (caller chooses the range).
            final int d = uniform(stepMinMs, stepMaxMs);
            if (d > 0) Thread.sleep(d);
        }
    }

    // -------------------------------------------------------------------------
    // CLICK: PRESS (hold) RELEASE at a point (LEFT BUTTON by design)
    // -------------------------------------------------------------------------

    /**
     * Emits a PRESS → HOLD → RELEASE → CLICKED sequence at the given point.
     *
     * @param at               canvas coordinate where the click occurs
     * @param holdMinMs        minimum hold duration (inclusive)
     * @param holdMaxMs        maximum hold duration (inclusive)
     * @param shiftAlreadyDown if true, the SHIFT modifier mask is set on the
     *                         events (HumanMouse decides timing of Shift key)
     *
     * BEHAVIOR
     *  - Uses BUTTON1 (left button) only. If you ever need right-click support,
     *    consider adding a separate method (to avoid changing existing call sites).
     *  - The CLICKED synthetic event is sent because some UI paths expect it.
     *
     * THREADING
     *  - Sleeps in the **calling thread** (during the "hold" interval).
     */
    public void clickAt(Point at, int holdMinMs, int holdMaxMs, boolean shiftAlreadyDown) throws InterruptedException
    {
        if (at == null) return;
        final Canvas canvas = client.getCanvas();
        if (canvas == null) return;

        // Modifiers observed by the PRESS/RELEASE pair
        final int baseMods = shiftAlreadyDown ? InputEvent.SHIFT_DOWN_MASK : 0;

        // PRESS (button1 down)
        canvas.dispatchEvent(new MouseEvent(
                canvas,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                baseMods | InputEvent.BUTTON1_DOWN_MASK,
                at.x, at.y,
                1,                      // clickCount
                false,
                MouseEvent.BUTTON1
        ));

        // HOLD for a human-like duration (range supplied by HumanMouse/Humanizer)
        final int hold = uniform(holdMinMs, holdMaxMs);
        if (hold > 0) Thread.sleep(hold);

        // RELEASE (button1 up)
        canvas.dispatchEvent(new MouseEvent(
                canvas,
                MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(),
                baseMods,
                at.x, at.y,
                1,
                false,
                MouseEvent.BUTTON1
        ));

        // CLICKED (optional; some UI listeners rely on this consolidated event)
        canvas.dispatchEvent(new MouseEvent(
                canvas,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                baseMods,
                at.x, at.y,
                1,
                false,
                MouseEvent.BUTTON1
        ));
    }

    // -------------------------------------------------------------------------
    // SHIFT MODIFIER: key down / key up (timing is handled by HumanMouse)
    // -------------------------------------------------------------------------

    /**
     * Sends KEY_PRESSED for VK_SHIFT to the canvas.
     *
     * NOTE
     *  - This does **not** sleep; HumanMouse is responsible for adding
     *    pre/post delays around Shift (keyLeadMs/keyLagMs).
     */
    public void shiftDown()
    {
        final Canvas canvas = client.getCanvas();
        if (canvas == null) return;

        canvas.dispatchEvent(new KeyEvent(
                canvas,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                InputEvent.SHIFT_DOWN_MASK,
                KeyEvent.VK_SHIFT,
                KeyEvent.CHAR_UNDEFINED
        ));
    }

    /**
     * Sends KEY_RELEASED for VK_SHIFT to the canvas.
     */
    public void shiftUp()
    {
        final Canvas canvas = client.getCanvas();
        if (canvas == null) return;

        canvas.dispatchEvent(new KeyEvent(
                canvas,
                KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(),
                0,
                KeyEvent.VK_SHIFT,
                KeyEvent.CHAR_UNDEFINED
        ));
    }

    // -------------------------------------------------------------------------
    // SMALL UTILS
    // -------------------------------------------------------------------------

    /**
     * Uniform integer sample in [min..max], inclusive.
     * If both are ≤ 0, return 0 to avoid negative sleeps.
     * If min > max, the bounds are swapped.
     */
    private static int uniform(int min, int max)
    {
        if (max < min) { int t = min; min = max; max = t; }
        if (min <= 0 && max <= 0) return 0;
        final int lo = Math.max(0, min);
        final int hi = Math.max(0, max);
        return ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }
}
