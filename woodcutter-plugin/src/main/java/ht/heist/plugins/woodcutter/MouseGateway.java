// ============================================================================
// FILE: MouseGateway.java
// PACKAGE: ht.heist.plugins.woodcutter
// -----------------------------------------------------------------------------
// TITLE
//   RuneLite Canvas AWT Dispatcher (thin)
// -----------------------------------------------------------------------------
// PURPOSE
//   Dispatches AWT mouse/key events onto RuneLite’s Canvas. NO dwell/reaction
//   logic lives here — timing is fully controlled by the plugin to match your
//   original sequence exactly.
// ============================================================================

package ht.heist.plugins.woodcutter;

import net.runelite.api.Client;

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
    private final Client client;

    @Inject
    public MouseGateway(Client client)
    {
        this.client = client;
    }

    // -------------------------------------------------------------------------
    // MOVE: dispatch a series of MOUSE_MOVED events along the provided path
    // -------------------------------------------------------------------------
    public void moveAlong(List<Point> path, int stepMinMs, int stepMaxMs, boolean unusedDwellFlag)
            throws InterruptedException
    {
        final Canvas canvas = client.getCanvas();
        if (canvas == null || path == null || path.isEmpty()) return;

        for (Point p : path)
        {
            canvas.dispatchEvent(new MouseEvent(
                    canvas, MouseEvent.MOUSE_MOVED, now(),
                    0, p.x, p.y, 0, false, 0
            ));
            sleepRandom(stepMinMs, stepMaxMs);
        }
        // NOTE: Dwell is intentionally not handled here to keep logic identical
        // to the original sequence where the “reaction” delay occurs *before* press.
    }

    // -------------------------------------------------------------------------
    // CLICK: simple PRESS (hold) RELEASE at a point, optional Shift modifier
    // -------------------------------------------------------------------------
    public void clickAt(Point at, int holdMinMs, int holdMaxMs, boolean shift)
            throws InterruptedException
    {
        final Canvas canvas = client.getCanvas();
        if (canvas == null || at == null) return;

        int mods = 0;
        if (shift)
        {
            mods = InputEvent.SHIFT_DOWN_MASK;
            canvas.dispatchEvent(new java.awt.event.KeyEvent(
                    canvas, KeyEvent.KEY_PRESSED, now(),
                    mods, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED
            ));
            sleepRandom(12, 22);
        }

        // PRESS
        final int pressMods = mods | InputEvent.BUTTON1_DOWN_MASK;
        canvas.dispatchEvent(new MouseEvent(
                canvas, MouseEvent.MOUSE_PRESSED, now(),
                pressMods, at.x, at.y, 1, false, MouseEvent.BUTTON1
        ));

        // HOLD
        sleepRandom(holdMinMs, holdMaxMs);

        // RELEASE
        canvas.dispatchEvent(new MouseEvent(
                canvas, MouseEvent.MOUSE_RELEASED, now(),
                mods, at.x, at.y, 1, false, MouseEvent.BUTTON1
        ));

        if (shift)
        {
            sleepRandom(12, 22);
            canvas.dispatchEvent(new java.awt.event.KeyEvent(
                    canvas, KeyEvent.KEY_RELEASED, now(),
                    0, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED
            ));
        }
    }

    // ---- helpers ------------------------------------------------------------
    private static long now() { return System.currentTimeMillis(); }

    private static void sleepRandom(int minMs, int maxMs) throws InterruptedException
    {
        if (minMs > maxMs) { int t = minMs; minMs = maxMs; maxMs = t; }
        int d = ThreadLocalRandom.current().nextInt(minMs, maxMs + 1);
        Thread.sleep(d);
    }
}
