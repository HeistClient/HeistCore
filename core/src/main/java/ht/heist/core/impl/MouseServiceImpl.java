// ============================================================================
// MouseServiceImpl.java
// -----------------------------------------------------------------------------
// Title
//   Legacy red-click behavior with fully humanized movement & timing.
//
// What’s new
//   • Elliptical Gaussian point selection inside target bounds (with AR drift)
//   • Curved Bezier mouse moves + smooth noise (no teleport)
//   • Gaussian reaction & press-hold timing; occasional exponential waits
//   • Optional overshoot (move past target) and rare misclick + correction
//   • Heatmap tap hook reports the ACTUAL click point
//
// Notes
//   • Java 11, @Singleton
//   • Keeps the same public MouseService API
//   • Minimal reliance on config to avoid bloat (sensible constants here)
// ============================================================================

package ht.heist.core.impl;

import ht.heist.core.config.HeistCoreConfig;
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
    // === Dependencies ========================================================
    private final Client client;
    private final HumanizerService human;
    private final HeistCoreConfig cfg;

    // Worker thread (legacy single-thread behavior)
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Heist-MouseService");
        t.setDaemon(true);
        return t;
    });

    // === Humanization state ===================================================
    private final Random rng = new Random();
    private final long sessionStartMs = System.currentTimeMillis();

    // Elliptical sampling drift (AR(1) bias in pixels)
    private double driftX = 0.0;
    private double driftY = 0.0;

    // Heatmap tap listener
    private volatile Consumer<java.awt.Point> clickTapListener = null;
    public void setClickTapListener(Consumer<java.awt.Point> listener) { this.clickTapListener = listener; }

    // === Tunables (kept local to avoid config bloat) =========================
    // Movement
    private static final double SAMPLE_RX = 0.32;   // ellipse “radius” X (% of width)
    private static final double SAMPLE_RY = 0.36;   // ellipse “radius” Y (% of height)
    private static final double DRIFT_ALPHA = 0.86; // AR stickiness
    private static final double DRIFT_NOISE  = 0.9; // AR noise std (px)

    private static final double PATH_CURVATURE = 0.55; // 0..1
    private static final double PATH_NOISE_PX  = 1.6;  // small wobble
    private static final int    PATH_STEP_MS_MIN = 6;  // per-step pause
    private static final int    PATH_STEP_MS_MAX = 12;

    // Timing
    private static final int REACT_MEAN = 120;  // ms
    private static final int REACT_STD  = 40;   // ms
    private static final int HOLD_MIN   = 30;   // ms
    private static final int HOLD_MAX   = 55;   // ms

    // Overshoot & Misclick
    private static final int  OVERSHOOT_CHANCE_PCT = 12;
    private static final int  MISCLICK_CHANCE_PCT  = 3;   // very rare
    private static final int  MISCLICK_OFFSET_MIN  = 8;   // px
    private static final int  MISCLICK_OFFSET_MAX  = 16;  // px
    private static final int  CORRECTION_DELAY_MIN = 80;  // ms
    private static final int  CORRECTION_DELAY_MAX = 160; // ms

    @Inject
    public MouseServiceImpl(Client client, HumanizerService human, HeistCoreConfig cfg)
    {
        this.client = client;
        this.human = human;
        this.cfg = cfg;
    }

    // === Public API ==========================================================
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
            moveAlongPathTo(p); // <-- catch InterruptedException
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // tiny settle using existing core knob
        human.sleep(cfg.canvasMoveSleepMinMs(), cfg.canvasMoveSleepMaxMs());
    }

    @Override
    public void clickAt(java.awt.Point p, boolean shift)
    {
        if (p == null) return;
        humanClick(new Rectangle(p.x, p.y, 1, 1), shift);
    }

    // === Internals: Point selection =========================================
    private java.awt.Point pickPoint(Rectangle r)
    {
        // Update AR drift in px (sticky bias for where inside the rect we click)
        driftX = human.ar1Step(driftX, DRIFT_ALPHA, DRIFT_NOISE);
        driftY = human.ar1Step(driftY, DRIFT_ALPHA, DRIFT_NOISE);

        // Clamp drift to small range so we don’t escape small slots
        double bx = Math.max(-3.0, Math.min(3.0, driftX));
        double by = Math.max(-3.0, Math.min(3.0, driftY));

        return human.sampleEllipticalInRect(r, SAMPLE_RX, SAMPLE_RY, bx, by);
    }

    // === Internals: Curved move + click =====================================
    private void dispatchClickWithPath(java.awt.Point target, boolean shift)
    {
        executor.submit(() -> {
            try
            {
                final Canvas canvas = client.getCanvas();
                if (canvas == null) return;

                // Rare MISCLICK: click near target, then correct
                if (roll(MISCLICK_CHANCE_PCT))
                {
                    java.awt.Point miss = offsetAround(target, MISCLICK_OFFSET_MIN, MISCLICK_OFFSET_MAX);
                    moveAlongPathTo(miss);
                    Thread.sleep(human.gaussianDelay(REACT_MEAN, REACT_STD)); // <-- was human.sleep(oneArg)

                    fireClick(canvas, miss, shift, false); // quick mistaken tap (no heatmap)

                    Thread.sleep(human.randomDelay(CORRECTION_DELAY_MIN, CORRECTION_DELAY_MAX));
                    // fall through to real target
                }
                else if (roll(OVERSHOOT_CHANCE_PCT))
                {
                    // Overshoot: go past the target, then settle back
                    java.awt.Point over = overshootPoint(target);
                    moveAlongPathTo(over);
                    Thread.sleep(human.randomDelay(18, 35));
                }

                // Final approach
                moveAlongPathTo(target);
                Thread.sleep(human.gaussianDelay(REACT_MEAN, REACT_STD)); // <-- was human.sleep(oneArg)

                // Heatmap hook: actual click point
                if (clickTapListener != null) clickTapListener.accept(target);

                // Actual click
                fireClick(canvas, target, shift, true);
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void moveAlongPathTo(java.awt.Point to) throws InterruptedException
    {
        final Point cur = client.getMouseCanvasPosition(); // RuneLite point (canvas coords)
        java.awt.Point from = (cur != null) ? new java.awt.Point(cur.getX(), cur.getY()) : null;

        // If unknown, just synthesize a tiny pre-step
        if (from == null) {
            dispatchMouse(client.getCanvas(), MouseEvent.MOUSE_MOVED, to.x, to.y, 0, 0, 0);
            return;
        }

        int dist = (int) Math.round(from.distance(to));
        int steps = clampInt(dist / 12, 6, 28); // distance-based granularity

        List<java.awt.Point> path = human.curvedPath(from, to, steps, PATH_CURVATURE, PATH_NOISE_PX);
        for (java.awt.Point p : path)
        {
            dispatchMouse(client.getCanvas(), MouseEvent.MOUSE_MOVED, p.x, p.y, 0, 0, 0);
            Thread.sleep(human.randomDelay(PATH_STEP_MS_MIN, PATH_STEP_MS_MAX));
        }
    }

    private void fireClick(Canvas canvas, java.awt.Point at, boolean shift, boolean includeHeatmap) throws InterruptedException
    {
        int mods = 0;
        if (shift) {
            mods = InputEvent.SHIFT_DOWN_MASK;
            canvas.dispatchEvent(new KeyEvent(canvas, KeyEvent.KEY_PRESSED, now(), mods, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED));
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

        if (shift) {
            Thread.sleep(human.randomDelay(12, 22));
            canvas.dispatchEvent(new KeyEvent(canvas, KeyEvent.KEY_RELEASED, now(), 0, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED));
        }
    }

    // === AWT helpers =========================================================
    private void dispatchMouse(Canvas canvas, int id, int x, int y, int button, int modifiersEx, int clickCount)
    {
        canvas.dispatchEvent(new MouseEvent(canvas, id, now(), modifiersEx, x, y, clickCount, false, button));
    }

    private static long now() { return System.currentTimeMillis(); }

    private boolean roll(int pct)
    {
        if (pct <= 0) return false;
        if (pct >= 100) return true;
        return rng.nextInt(100) < pct;
    }

    private java.awt.Point overshootPoint(java.awt.Point tgt)
    {
        int dx = (rng.nextBoolean() ? 1 : -1) * (4 + rng.nextInt(8)); // 4..11 px
        int dy = (rng.nextBoolean() ? 1 : -1) * (3 + rng.nextInt(6)); // 3..8 px
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

    private int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
