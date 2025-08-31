// ============================================================================
// FILE: MouseServiceImpl.java
// PACKAGE: ht.heist.core.impl
// -----------------------------------------------------------------------------
// TITLE
//   Humanized mouse with curved moves & jitter, no Core-config dependency.
//
// WHY THIS VERSION
//   • Removed HeistCoreConfig from the constructor — no extra config needed.
//   • Keeps your curved paths / gaussian delays / overshoot / misclick logic.
//   • Exposes a click-tap hook so other plugins can publish the final click.
//   • Safe when no listener is attached.
//
// LABELS
//   - Dependencies: Client + HumanizerService only
//   - Hook: setClickTapListener(Consumer<Point>) — called with actual click pt
// ============================================================================

package ht.heist.core.impl;

import ht.heist.core.services.HumanizerService;
import ht.heist.core.services.MouseService;
import net.runelite.api.Client;
import net.runelite.api.Point;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Canvas;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@Singleton
public class MouseServiceImpl implements MouseService
{
    // ===== Dependencies =======================================================
    private final Client client;
    private final HumanizerService human;

    // ===== Worker thread (single-thread behavior) ============================
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Heist-MouseService");
        t.setDaemon(true);
        return t;
    });

    // ===== Humanization state =================================================
    private final Random rng = new Random();
    private double driftX = 0.0;
    private double driftY = 0.0;

    // ===== Hook for reporting the actual click point ==========================
    private volatile Consumer<java.awt.Point> clickTapListener = null;
    public void setClickTapListener(Consumer<java.awt.Point> listener) { this.clickTapListener = listener; }

    // ===== Tunables (local constants; no config dependency) ===================
    private static final double SAMPLE_RX = 0.32;
    private static final double SAMPLE_RY = 0.36;
    private static final double DRIFT_ALPHA = 0.86;
    private static final double DRIFT_NOISE  = 0.9;

    private static final double PATH_CURVATURE = 0.55;
    private static final double PATH_NOISE_PX  = 1.6;
    private static final int    PATH_STEP_MS_MIN = 6;
    private static final int    PATH_STEP_MS_MAX = 12;

    private static final int REACT_MEAN = 120;
    private static final int REACT_STD  = 40;
    private static final int HOLD_MIN   = 30;
    private static final int HOLD_MAX   = 55;

    private static final int  OVERSHOOT_CHANCE_PCT = 12;
    private static final int  MISCLICK_CHANCE_PCT  = 3;
    private static final int  MISCLICK_OFFSET_MIN  = 8;
    private static final int  MISCLICK_OFFSET_MAX  = 16;
    private static final int  CORRECTION_DELAY_MIN = 80;
    private static final int  CORRECTION_DELAY_MAX = 160;

    @Inject
    public MouseServiceImpl(Client client, HumanizerService human)
    {
        this.client = client;
        this.human = human;
    }

    // ===== API ================================================================
    @Override
    public void humanClick(Shape targetShape, boolean shift)
    {
        if (targetShape == null) return;
        humanClick(targetShape.getBounds(), shift);
    }

    @Override
    public void humanClick(Rectangle bounds, boolean shift)
    {
        if (bounds == null) return;
        final java.awt.Point clickPoint = pickPoint(bounds);
        dispatchClickWithPath(clickPoint, shift);
    }

    @Override
    public void moveMouseTo(java.awt.Point p)
    {
        final Canvas canvas = client.getCanvas();
        if (canvas == null || p == null) return;

        try {
            moveAlongPathTo(p);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // tiny settle
        human.sleep(14, 28);
    }

    @Override
    public void clickAt(java.awt.Point p, boolean shift)
    {
        if (p == null) return;
        humanClick(new Rectangle(p.x, p.y, 1, 1), shift);
    }

    // ===== Internals: sampling + movement ====================================
    private java.awt.Point pickPoint(Rectangle r)
    {
        driftX = human.ar1Step(driftX, DRIFT_ALPHA, DRIFT_NOISE);
        driftY = human.ar1Step(driftY, DRIFT_ALPHA, DRIFT_NOISE);

        final double bx = Math.max(-3.0, Math.min(3.0, driftX));
        final double by = Math.max(-3.0, Math.min(3.0, driftY));

        return human.sampleEllipticalInRect(r, SAMPLE_RX, SAMPLE_RY, bx, by);
    }

    private void dispatchClickWithPath(java.awt.Point target, boolean shift)
    {
        executor.submit(() -> {
            try
            {
                final Canvas canvas = client.getCanvas();
                if (canvas == null) return;

                // Rare misclick + correction
                if (roll(MISCLICK_CHANCE_PCT))
                {
                    java.awt.Point miss = offsetAround(target, MISCLICK_OFFSET_MIN, MISCLICK_OFFSET_MAX);
                    moveAlongPathTo(miss);
                    Thread.sleep(human.gaussianDelay(REACT_MEAN, REACT_STD));
                    fireClick(canvas, miss, shift);
                    Thread.sleep(human.randomDelay(CORRECTION_DELAY_MIN, CORRECTION_DELAY_MAX));
                }
                else if (roll(OVERSHOOT_CHANCE_PCT))
                {
                    java.awt.Point over = overshootPoint(target);
                    moveAlongPathTo(over);
                    Thread.sleep(human.randomDelay(18, 35));
                }

                // Final approach
                moveAlongPathTo(target);
                Thread.sleep(human.gaussianDelay(REACT_MEAN, REACT_STD));

                // Report actual click point (heatmap/recorder)
                final Consumer<java.awt.Point> hook = clickTapListener;
                if (hook != null) hook.accept(target);

                // Click
                fireClick(canvas, target, shift);
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void moveAlongPathTo(java.awt.Point to) throws InterruptedException
    {
        final Point cur = client.getMouseCanvasPosition();
        java.awt.Point from = (cur != null) ? new java.awt.Point(cur.getX(), cur.getY()) : null;

        if (from == null)
        {
            dispatchMouse(client.getCanvas(), MouseEvent.MOUSE_MOVED, to.x, to.y, 0, 0, 0);
            return;
        }

        int dist = (int)Math.round(from.distance(to));
        int steps = clamp(dist / 12, 6, 28);
        List<java.awt.Point> path = human.curvedPath(from, to, steps, PATH_CURVATURE, PATH_NOISE_PX);

        for (java.awt.Point p : path)
        {
            dispatchMouse(client.getCanvas(), MouseEvent.MOUSE_MOVED, p.x, p.y, 0, 0, 0);
            Thread.sleep(human.randomDelay(PATH_STEP_MS_MIN, PATH_STEP_MS_MAX));
        }
    }

    private void fireClick(Canvas canvas, java.awt.Point at, boolean shift) throws InterruptedException
    {
        int mods = 0;
        if (shift)
        {
            mods = InputEvent.SHIFT_DOWN_MASK;
            canvas.dispatchEvent(new KeyEvent(canvas, KeyEvent.KEY_PRESSED,  now(), mods, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED));
            Thread.sleep(human.randomDelay(12, 22));
        }

        // PRESS
        int pressMods = mods | InputEvent.BUTTON1_DOWN_MASK;
        canvas.dispatchEvent(new MouseEvent(
                canvas, MouseEvent.MOUSE_PRESSED, now(), pressMods, at.x, at.y, 1, false, MouseEvent.BUTTON1
        ));

        // HOLD
        Thread.sleep(human.randomDelay(HOLD_MIN, HOLD_MAX));

        // RELEASE
        canvas.dispatchEvent(new MouseEvent(
                canvas, MouseEvent.MOUSE_RELEASED, now(), mods, at.x, at.y, 1, false, MouseEvent.BUTTON1
        ));

        if (shift)
        {
            Thread.sleep(human.randomDelay(12, 22));
            canvas.dispatchEvent(new KeyEvent(canvas, KeyEvent.KEY_RELEASED, now(), 0, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED));
        }
    }

    // ===== AWT helpers ========================================================
    private void dispatchMouse(Canvas c, int id, int x, int y, int button, int modifiersEx, int clicks)
    {
        c.dispatchEvent(new MouseEvent(c, id, now(), modifiersEx, x, y, clicks, false, button));
    }
    private static long now() { return System.currentTimeMillis(); }

    // ===== small utils ========================================================
    private boolean roll(int pct) { return pct > 0 && (pct >= 100 || new Random().nextInt(100) < pct); }
    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private java.awt.Point overshootPoint(java.awt.Point tgt)
    {
        int dx = (rng.nextBoolean() ? 1 : -1) * (4 + rng.nextInt(8));
        int dy = (rng.nextBoolean() ? 1 : -1) * (3 + rng.nextInt(6));
        return new java.awt.Point(tgt.x + dx, tgt.y + dy);
    }
    private java.awt.Point offsetAround(java.awt.Point src, int min, int max)
    {
        int r = min + rng.nextInt(Math.max(1, max - min + 1));
        double ang = rng.nextDouble() * Math.PI * 2.0;
        int dx = (int)Math.round(r * Math.cos(ang));
        int dy = (int)Math.round(r * Math.sin(ang));
        return new java.awt.Point(src.x + dx, src.y + dy);
    }
}
