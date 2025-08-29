package ht.heist.core.impl;

import ht.heist.core.services.HumanizerService;
import ht.heist.core.services.MouseService;
import net.runelite.api.Client;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Canvas;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

@Singleton
public class MouseServiceImpl implements MouseService {

    private final Client client;
    private final HumanizerService humanizer;

    @Inject
    public MouseServiceImpl(Client client, HumanizerService humanizer) {
        this.client = client;
        this.humanizer = humanizer;
    }

    @Override
    public void humanClick(Shape targetShape, boolean shift) {
        if (targetShape == null) return;
        Rectangle b = targetShape.getBounds();
        Point p = new Point((int) Math.round(b.getCenterX()), (int) Math.round(b.getCenterY()));
        moveMouseTo(p);
        humanizer.sleep(40, 120);
        clickAt(p, shift);
    }

    @Override
    public void humanClick(Rectangle bounds, boolean shift) {
        if (bounds == null) return;
        Point p = new Point((int) Math.round(bounds.getCenterX()), (int) Math.round(bounds.getCenterY()));
        moveMouseTo(p);
        humanizer.sleep(40, 120);
        clickAt(p, shift);
    }

    @Override
    public void moveMouseTo(Point p) {
        Canvas canvas = client.getCanvas();
        if (canvas == null || p == null) return;

        // Basic move; you can add micro-steps/jitter later
        MouseEvent move = new MouseEvent(
                canvas, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0,
                p.x, p.y, 0, false
        );
        canvas.dispatchEvent(move);
    }

    @Override
    public void clickAt(Point p, boolean shift) {
        Canvas canvas = client.getCanvas();
        if (canvas == null || p == null) return;

        int mods = shift ? java.awt.event.InputEvent.SHIFT_DOWN_MASK : 0;

        if (shift) {
            canvas.dispatchEvent(new KeyEvent(
                    canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), mods,
                    KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED
            ));
            humanizer.sleep(10, 30);
        }

        canvas.dispatchEvent(new MouseEvent(
                canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), mods,
                p.x, p.y, 1, false, MouseEvent.BUTTON1
        ));
        humanizer.sleep(35, 80);
        canvas.dispatchEvent(new MouseEvent(
                canvas, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), mods,
                p.x, p.y, 1, false, MouseEvent.BUTTON1
        ));

        if (shift) {
            humanizer.sleep(10, 30);
            canvas.dispatchEvent(new KeyEvent(
                    canvas, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0,
                    KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED
            ));
        }
    }
}
