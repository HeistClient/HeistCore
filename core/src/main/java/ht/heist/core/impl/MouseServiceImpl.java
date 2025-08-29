/**
 * MouseServiceImpl*
 * Provides a "human-like" mouse interaction service for RuneLite plugins.
 * - Uses configurable random delays and offsets to simulate natural clicking.
 * - Relies on HeistCoreConfig for settings like hover–click delay.*
 * Part of the HeistCore framework.
 */
package ht.heist.core.impl;

import ht.heist.core.services.HumanizerService;
import ht.heist.core.services.MouseService;
import ht.heist.core.config.HeistCoreConfig;
import net.runelite.api.Client;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Canvas;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

@Singleton
public class MouseServiceImpl implements MouseService {

    private final Client client;
    private final HumanizerService humanizer;
    private final HeistCoreConfig config;

    @Inject
    public MouseServiceImpl(Client client, HumanizerService humanizer, HeistCoreConfig config) {
        this.client = client;
        this.humanizer = humanizer;
        this.config = config;
    }

    @Override
    public void humanClick(Shape targetShape, boolean shift) {
        if (targetShape == null || client == null || client.getCanvas() == null) {
            return;
        }
        // Center of the target in canvas coordinates
        Rectangle b = targetShape.getBounds();
        Point logical = new Point((int)Math.round(b.getCenterX()), (int)Math.round(b.getCenterY()));

        moveMouseTo(logical);

        // Critical hover → click delay (configurable)
        humanizer.sleep(
                clamp(config.hoverClickDelayMin(), 0, 500),
                clamp(config.hoverClickDelayMax(), config.hoverClickDelayMin(), 1000)
        );

        clickAt(logical, shift);
    }

    @Override
    public void humanClick(Rectangle bounds, boolean shift) {
        if (bounds == null || client == null || client.getCanvas() == null) {
            return;
        }
        Point logical = new Point((int)Math.round(bounds.getCenterX()), (int)Math.round(bounds.getCenterY()));

        moveMouseTo(logical);

        humanizer.sleep(
                clamp(config.hoverClickDelayMin(), 0, 500),
                clamp(config.hoverClickDelayMax(), config.hoverClickDelayMin(), 1000)
        );

        clickAt(logical, shift);
    }

    @Override
    public void moveMouseTo(Point p) {
        if (p == null || client == null || client.getCanvas() == null) {
            return;
        }
        Canvas canvas = client.getCanvas();

        // Single “human-like” move event (you can add pathing later if you want smooth curves)
        MouseEvent move = new MouseEvent(
                canvas,
                MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(),
                0,
                p.x,
                p.y,
                0,
                false
        );
        canvas.dispatchEvent(move);
    }

    @Override
    public void clickAt(Point p, boolean shift) {
        if (p == null || client == null || client.getCanvas() == null) {
            return;
        }
        Canvas canvas = client.getCanvas();

        int modifiers = shift ? InputEvent.SHIFT_DOWN_MASK : 0;

        try {
            if (shift) {
                // Press Shift
                KeyEvent shiftDown = new KeyEvent(
                        canvas,
                        KeyEvent.KEY_PRESSED,
                        System.currentTimeMillis(),
                        modifiers,
                        KeyEvent.VK_SHIFT,
                        KeyEvent.CHAR_UNDEFINED
                );
                canvas.dispatchEvent(shiftDown);

                // tiny dwell to register modifier
                humanizer.sleep(10, 20);
            }

            // Press mouse
            MouseEvent press = new MouseEvent(
                    canvas,
                    MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(),
                    modifiers,
                    p.x,
                    p.y,
                    1,
                    false,
                    MouseEvent.BUTTON1
            );
            canvas.dispatchEvent(press);

            // Human-ish press length
            humanizer.sleep(40, 80);

            // Release mouse
            MouseEvent release = new MouseEvent(
                    canvas,
                    MouseEvent.MOUSE_RELEASED,
                    System.currentTimeMillis(),
                    modifiers,
                    p.x,
                    p.y,
                    1,
                    false,
                    MouseEvent.BUTTON1
            );
            canvas.dispatchEvent(release);

        } finally {
            if (shift) {
                // Release Shift
                KeyEvent shiftUp = new KeyEvent(
                        canvas,
                        KeyEvent.KEY_RELEASED,
                        System.currentTimeMillis(),
                        0,
                        KeyEvent.VK_SHIFT,
                        KeyEvent.CHAR_UNDEFINED
                );
                canvas.dispatchEvent(shiftUp);
            }
        }
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
