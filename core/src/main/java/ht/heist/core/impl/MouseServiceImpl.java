// ============================================================================
// MouseServiceImpl.java
// -----------------------------------------------------------------------------
// Purpose
//   EXACTLY mimic the previous “working” click path used in your old plugin:
//     • Use a single-thread ExecutorService (no client-thread work).
//     • Dispatch AWT events directly on the Canvas: MOUSE_MOVED -> sleep
//       -> MOUSE_PRESSED -> sleep -> MOUSE_RELEASED.
//     • Optional SHIFT key press/release around the mouse press.
//   No menu polling, no EDT flushes, no tick waiting — just the old behavior.
// ============================================================================

package ht.heist.core.impl;

import ht.heist.core.config.HeistCoreConfig;
import ht.heist.core.services.HumanizerService;
import ht.heist.core.services.MouseService;
import net.runelite.api.Client;

import javax.inject.Inject;
import java.awt.Canvas;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MouseServiceImpl implements MouseService
{
    // ------------------------------------------------------------------------
    // Dependencies
    // ------------------------------------------------------------------------
    private final Client client;
    private final HumanizerService human;
    private final HeistCoreConfig cfg;

    // ------------------------------------------------------------------------
    // Worker thread (mirrors the old HumanBehavior executor)
    // ------------------------------------------------------------------------
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r ->
    {
        Thread t = new Thread(r, "Heist-MouseService");
        t.setDaemon(true);
        return t;
    });

    // ------------------------------------------------------------------------
    // Random for gaussian selection inside target shapes
    // ------------------------------------------------------------------------
    private final Random rng = new Random();

    // ------------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------------
    @Inject
    public MouseServiceImpl(Client client, HumanizerService human, HeistCoreConfig cfg)
    {
        this.client = client;
        this.human = human;
        this.cfg = cfg;
    }

    // ------------------------------------------------------------------------
    // Public API: click by Shape
    // ------------------------------------------------------------------------
    @Override
    public void humanClick(Shape targetShape, boolean shift)
    {
        if (targetShape == null) return;
        Rectangle bounds = targetShape.getBounds();
        humanClick(bounds, shift);
    }

    // ------------------------------------------------------------------------
    // Public API: click by Rectangle
    // ------------------------------------------------------------------------
    @Override
    public void humanClick(Rectangle bounds, boolean shift)
    {
        if (bounds == null) return;
        final java.awt.Point clickPoint = gaussianPointIn(bounds);
        dispatchClick(clickPoint, shift);
    }

    // ------------------------------------------------------------------------
    // Public API: move-only
    // ------------------------------------------------------------------------
    @Override
    public void moveMouseTo(java.awt.Point p)
    {
        final Canvas canvas = client.getCanvas();
        if (canvas == null || p == null) return;

        dispatchMouse(canvas, MouseEvent.MOUSE_MOVED, p.x, p.y, 0, 0, 0);
        human.sleep(cfg.canvasMoveSleepMinMs(), cfg.canvasMoveSleepMaxMs());
    }

    // ------------------------------------------------------------------------
    // Public API: click at a specific Point
    // (required by MouseService interface)
    // ------------------------------------------------------------------------
    @Override
    public void clickAt(java.awt.Point p, boolean shift)
    {
        if (p == null) return;
        // Wrap a single-point rectangle so we reuse gaussian logic
        humanClick(new Rectangle(p.x, p.y, 1, 1), shift);
    }

    // ------------------------------------------------------------------------
    // Internal: schedule the actual click task on executor
    // ------------------------------------------------------------------------
    private void dispatchClick(java.awt.Point clickPoint, boolean shift)
    {
        executor.submit(() -> {
            try
            {
                final Canvas canvas = client.getCanvas();
                if (canvas == null) return;

                // --- Move ----------------------------------------------------
                dispatchMouse(canvas, MouseEvent.MOUSE_MOVED, clickPoint.x, clickPoint.y, 0, 0, 0);
                sleepGaussian(cfg.reactionMeanMs(), cfg.reactionStdMs());

                // --- Optional Shift -----------------------------------------
                int mods = 0;
                if (shift)
                {
                    mods = InputEvent.SHIFT_DOWN_MASK;
                    KeyEvent shiftDown = new KeyEvent(canvas, KeyEvent.KEY_PRESSED, now(), mods, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED);
                    canvas.dispatchEvent(shiftDown);
                    Thread.sleep(20);
                }

                // --- Press ---------------------------------------------------
                int pressMods = mods | InputEvent.BUTTON1_DOWN_MASK;
                MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, now(), pressMods, clickPoint.x, clickPoint.y, 1, false, MouseEvent.BUTTON1);
                canvas.dispatchEvent(press);

                Thread.sleep(human.randomDelay(cfg.pressHoldMinMs(), cfg.pressHoldMaxMs()));

                // --- Release -------------------------------------------------
                MouseEvent release = new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, now(), mods, clickPoint.x, clickPoint.y, 1, false, MouseEvent.BUTTON1);
                canvas.dispatchEvent(release);

                // --- Optional Shift up --------------------------------------
                if (shift)
                {
                    Thread.sleep(20);
                    KeyEvent shiftUp = new KeyEvent(canvas, KeyEvent.KEY_RELEASED, now(), 0, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED);
                    canvas.dispatchEvent(shiftUp);
                }
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
            }
        });
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------
    private static long now() { return System.currentTimeMillis(); }

    private void dispatchMouse(Canvas canvas, int id, int x, int y, int button, int modifiersEx, int clickCount)
    {
        MouseEvent e = new MouseEvent(canvas, id, now(), modifiersEx, x, y, clickCount, false, button);
        canvas.dispatchEvent(e);
    }

    private java.awt.Point gaussianPointIn(Rectangle r)
    {
        double meanX = r.getCenterX();
        double meanY = r.getCenterY();
        double stdX  = Math.max(1.0, r.getWidth() / 4.0);
        double stdY  = Math.max(1.0, r.getHeight() / 4.0);

        java.awt.Point p = new java.awt.Point((int)meanX, (int)meanY);
        for (int i = 0; i < 10; i++)
        {
            int x = (int)Math.round(rng.nextGaussian() * stdX + meanX);
            int y = (int)Math.round(rng.nextGaussian() * stdY + meanY);
            if (r.contains(x, y))
            {
                p.x = x;
                p.y = y;
                break;
            }
        }
        return p;
    }

    private void sleepGaussian(int meanMs, int stdMs) throws InterruptedException
    {
        int dur = (int)Math.max(0, Math.round(Math.abs(rng.nextGaussian() * stdMs + meanMs)));
        Thread.sleep(dur);
    }
}
