// ============================================================================
// FILE: MouseGateway.java
// PACKAGE: ht.heist.corerl.input
// -----------------------------------------------------------------------------
// TITLE
//   MouseGateway — the *thin* AWT dispatcher that talks to RuneLite's Canvas.
//
// WHAT IT DOES
//   • moveAlong(path, stepMinMs, stepMaxMs):
//       Dispatches a sequence of MOUSE_MOVED events with small sleeps between
//       points. Timings come from HumanMouse.
//   • clickAt(point, holdMinMs, holdMaxMs, shiftAlreadyDown):
//       Press → hold → release at the given point. Hold duration is sampled
//       uniformly in [min..max]. If shiftAlreadyDown is true, the press is
//       sent with SHIFT_DOWN_MASK (HumanMouse manages Shift waits).
//   • shiftDown() / shiftUp():
//       Presses/releases Shift on the Canvas. HumanMouse controls *when* to
//       call these and how long to wait around them.
//
// DESIGN NOTES
//   • No “humanization” lives here — this class just performs exactly what it’s
//     told. Curves, reaction, Gaussian, overshoots, etc. are handled by HumanMouse.
//   • All sleeps are in the calling thread; HumanMouse runs them on its own
//     single-thread executor so the game thread isn’t blocked.
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
    private static final Logger log = LoggerFactory.getLogger(MouseGateway.class);

    private final Client client;

    @Inject
    public MouseGateway(Client client)
    {
        this.client = client;
    }

    // -------------------------------------------------------------------------
    // MOVE: dispatch a series of MOUSE_MOVED events along the provided path
    // -------------------------------------------------------------------------
    public void moveAlong(List<Point> path, int stepMinMs, int stepMaxMs) throws InterruptedException
    {
        if (path == null || path.isEmpty()) return;
        final Canvas canvas = client.getCanvas();
        if (canvas == null) return;

        for (Point p : path)
        {
            // MOUSE_MOVED
            canvas.dispatchEvent(new MouseEvent(
                    canvas,
                    MouseEvent.MOUSE_MOVED,
                    System.currentTimeMillis(),
                    0,                      // no modifiers
                    p.x, p.y,
                    0,                      // clickCount
                    false,                  // popupTrigger
                    0                       // button
            ));

            // step pacing
            int d = uniform(stepMinMs, stepMaxMs);
            if (d > 0) Thread.sleep(d);
        }
    }

    // -------------------------------------------------------------------------
    // CLICK: PRESS (hold) RELEASE at a point
    // -------------------------------------------------------------------------
    public void clickAt(Point at, int holdMinMs, int holdMaxMs, boolean shiftAlreadyDown) throws InterruptedException
    {
        if (at == null) return;
        final Canvas canvas = client.getCanvas();
        if (canvas == null) return;

        // modifiers for press/release
        final int baseMods = shiftAlreadyDown ? InputEvent.SHIFT_DOWN_MASK : 0;

        // PRESS (with left button mask)
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

        // HOLD for human-like duration (HumanMouse decided the range)
        int hold = uniform(holdMinMs, holdMaxMs);
        if (hold > 0) Thread.sleep(hold);

        // RELEASE
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

        // Optional: emit a CLICKED event, some UIs expect it.
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
    // SHIFT MODIFIER: down / up (timing handled by HumanMouse)
    // -------------------------------------------------------------------------
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
    // helpers
    // -------------------------------------------------------------------------
    private static int uniform(int min, int max)
    {
        if (max < min) { int t = min; min = max; max = t; }
        if (min <= 0 && max <= 0) return 0;
        return ThreadLocalRandom.current().nextInt(Math.max(0, min), Math.max(0, max) + 1);
    }
}
