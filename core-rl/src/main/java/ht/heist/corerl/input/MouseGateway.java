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
//   • 100% Robot-free (does not move the OS cursor).
//
// WHAT'S NEW (compared to your last attempt)
//   • currentMouseCanvasPoint(): read RL's current canvas position so paths
//     always start from the *real* pointer → no teleports.
//   • dispatchMove(...): posts a one-time MOUSE_ENTERED before the first move,
//     ensuring hover is “live” (some builds ignore moves until ENTERED).
//   • resetEnteredFlag(): lets HumanMouse re-prime ENTERED on session start.
// -----------------------------------------------------------------------------

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
import java.util.concurrent.ThreadLocalRandom;

@Singleton
public class MouseGateway
{
    /** SLF4J logger; keep light in hot paths. */
    private static final Logger log = LoggerFactory.getLogger(MouseGateway.class);

    /** RuneLite client (for access to the active Canvas). */
    private final Client client;

    /** Guard so we send MOUSE_ENTERED once per session (optional, but helpful). */
    private volatile boolean entered = false;

    @Inject
    public MouseGateway(Client client)
    {
        this.client = client;
    }

    // -------------------------------------------------------------------------
    // CANVAS HELPERS
    // -------------------------------------------------------------------------

    /** Return the current RuneLite Canvas or null if unavailable. */
    public Canvas canvasOrNull() {
        return client.getCanvas();
    }

    /** Reset the ENTERED guard (call at session start). */
    public void resetEnteredFlag() { entered = false; }

    /**
     * Read RL's current mouse position in canvas coordinates.
     * @return java.awt.Point or null if unknown.
     */
    public Point currentMouseCanvasPoint()
    {
        try {
            net.runelite.api.Point rp = client.getMouseCanvasPosition();
            if (rp == null) return null;
            return new Point(rp.getX(), rp.getY());
        } catch (Throwable t) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // MOVE: single MOUSE_MOVED (no sleep; pacing is done by caller)
    // -------------------------------------------------------------------------

    /**
     * Dispatch a single MOUSE_MOVED at the given canvas point.
     * On the first call after a reset, also sends MOUSE_ENTERED to prime hover.
     */
    public void dispatchMove(Point p) {
        final Canvas canvas = client.getCanvas();
        if (canvas == null || p == null) return;

        final long now = System.currentTimeMillis();

        // Ensure hover is active before we start moving.
        if (!entered) {
            canvas.dispatchEvent(new MouseEvent(
                    canvas, MouseEvent.MOUSE_ENTERED, now,
                    0, p.x, p.y, 0, false, MouseEvent.NOBUTTON));
            entered = true;
        }

        canvas.dispatchEvent(new MouseEvent(
                canvas, MouseEvent.MOUSE_MOVED, now,
                0, p.x, p.y, 0, false, MouseEvent.NOBUTTON));
    }

    // -------------------------------------------------------------------------
    // CLICK: PRESS (hold) RELEASE (and CLICKED) at a point (left button)
    // -------------------------------------------------------------------------

    /**
     * Emits a PRESS → HOLD → RELEASE → CLICKED sequence at the given point.
     * All sleeps happen on the calling thread.
     */
    public void clickAt(Point at, int holdMinMs, int holdMaxMs, boolean shiftAlreadyDown) throws InterruptedException
    {
        if (at == null) return;
        final Canvas canvas = client.getCanvas();
        if (canvas == null) return;

        final long now = System.currentTimeMillis();
        final int baseMods = shiftAlreadyDown ? InputEvent.SHIFT_DOWN_MASK : 0;

        // PRESS
        canvas.dispatchEvent(new MouseEvent(
                canvas, MouseEvent.MOUSE_PRESSED, now,
                baseMods | InputEvent.BUTTON1_DOWN_MASK,
                at.x, at.y, 1, false, MouseEvent.BUTTON1));

        // HOLD
        final int hold = uniform(holdMinMs, holdMaxMs);
        if (hold > 0) Thread.sleep(hold);

        // RELEASE
        canvas.dispatchEvent(new MouseEvent(
                canvas, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                baseMods,
                at.x, at.y, 1, false, MouseEvent.BUTTON1));

        // CLICKED (some listeners expect this consolidated event)
        canvas.dispatchEvent(new MouseEvent(
                canvas, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                baseMods,
                at.x, at.y, 1, false, MouseEvent.BUTTON1));
    }

    // -------------------------------------------------------------------------
    // SHIFT MODIFIER (timing is handled by the caller)
    // -------------------------------------------------------------------------

    public void shiftDown()
    {
        final Canvas canvas = client.getCanvas();
        if (canvas == null) return;

        canvas.dispatchEvent(new KeyEvent(
                canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED));
    }

    public void shiftUp()
    {
        final Canvas canvas = client.getCanvas();
        if (canvas == null) return;

        canvas.dispatchEvent(new KeyEvent(
                canvas, KeyEvent.KEY_RELEASED, System.currentTimeMillis(),
                0, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED));
    }

    // -------------------------------------------------------------------------
    // SMALL UTILS
    // -------------------------------------------------------------------------

    /** Inclusive uniform int in [min..max], clamped to ≥0; swaps if min>max. */
    private static int uniform(int min, int max)
    {
        if (max < min) { int t = min; min = max; max = t; }
        if (min <= 0 && max <= 0) return 0;
        final int lo = Math.max(0, min);
        final int hi = Math.max(0, max);
        return ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }
}
