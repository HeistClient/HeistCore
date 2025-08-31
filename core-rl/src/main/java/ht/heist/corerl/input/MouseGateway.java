// ============================================================================
// FILE: MouseGateway.java
// PACKAGE: ht.heist.corerl.input
// -----------------------------------------------------------------------------
// TITLE
//   RuneLite Canvas AWT Dispatcher (thin).
//   NOTE: This class ONLY sends AWT events. No timing logic besides the
//   small per-step sleep inside moveAlong. Higher-level logic lives in HumanMouse.
// ============================================================================
package ht.heist.corerl.input;

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
    public MouseGateway(Client client) { this.client = client; }

    public void moveAlong(List<Point> path, int stepMinMs, int stepMaxMs) throws InterruptedException
    {
        final Canvas canvas = client.getCanvas();
        if (canvas == null || path == null || path.isEmpty()) return;

        for (Point p : path)
        {
            canvas.dispatchEvent(new MouseEvent(
                    canvas, MouseEvent.MOUSE_MOVED, now(),
                    0, p.x, p.y, 0, false, 0
            ));
            Thread.sleep(rand(stepMinMs, stepMaxMs));
        }
    }

    public void press(Point at, boolean shift) throws InterruptedException
    {
        final Canvas canvas = client.getCanvas();
        if (canvas == null || at == null) return;

        int mods = 0;
        if (shift)
        {
            mods = InputEvent.SHIFT_DOWN_MASK;
            canvas.dispatchEvent(new KeyEvent(
                    canvas, KeyEvent.KEY_PRESSED, now(),
                    mods, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED
            ));
            Thread.sleep(15);
        }

        final int pressMods = mods | InputEvent.BUTTON1_DOWN_MASK;
        canvas.dispatchEvent(new MouseEvent(
                canvas, MouseEvent.MOUSE_PRESSED, now(),
                pressMods, at.x, at.y, 1, false, MouseEvent.BUTTON1
        ));
    }

    public void release(Point at, boolean shift) throws InterruptedException
    {
        final Canvas canvas = client.getCanvas();
        if (canvas == null || at == null) return;

        int mods = shift ? InputEvent.SHIFT_DOWN_MASK : 0;

        canvas.dispatchEvent(new MouseEvent(
                canvas, MouseEvent.MOUSE_RELEASED, now(),
                mods, at.x, at.y, 1, false, MouseEvent.BUTTON1
        ));

        if (shift)
        {
            Thread.sleep(15);
            canvas.dispatchEvent(new KeyEvent(
                    canvas, KeyEvent.KEY_RELEASED, now(),
                    0, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED
            ));
        }
    }

    public void clickAt(Point at, int holdMinMs, int holdMaxMs, boolean shift) throws InterruptedException
    {
        press(at, shift);
        Thread.sleep(rand(holdMinMs, holdMaxMs));
        release(at, shift);
    }

    private static int rand(int a, int b) {
        if (a > b) { int t = a; a = b; b = t; }
        return ThreadLocalRandom.current().nextInt(Math.max(1, b - a + 1)) + a;
    }
    private static long now() { return System.currentTimeMillis(); }
}
