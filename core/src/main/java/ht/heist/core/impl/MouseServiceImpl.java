// ============================================================================
// MouseServiceImpl.java
// -----------------------------------------------------------------------------
// Purpose
//   EXACT legacy red-click path plus:
//     • 4×4 stratified anti-repeat sampling per bounds (visible variety even
//       if you always click the same inventory slot repeatedly)
//     • Optional overshoot + fatigue scaling
//     • Optional heatmap tap hook to draw the ACTUAL click point
//   NOW SINGLETON so every consumer (plugins + core services) shares the same
//   instance, ensuring the heatmap tap listener covers all clicks.
// ============================================================================

package ht.heist.core.impl;

// ===== Imports: core =====
import ht.heist.core.config.HeistCoreConfig;
import ht.heist.core.services.HumanizerService;
import ht.heist.core.services.MouseService;
import net.runelite.api.Client;

// ===== Imports: JSR-330 / AWT / JDK =====
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Canvas;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@Singleton
public class MouseServiceImpl implements MouseService
{
    // --- Dependencies --------------------------------------------------------
    private final Client client;
    private final HumanizerService human;
    private final HeistCoreConfig cfg;

    // --- Worker thread (legacy single-thread behavior) -----------------------
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Heist-MouseService");
        t.setDaemon(true);
        return t;
    });

    // --- Humanization state --------------------------------------------------
    private final Random rng = new Random();
    private final long sessionStartMs = System.currentTimeMillis();

    // --- Per-bounds stratified cycle state ----------------------------------
    private static final class ClickPattern {
        List<int[]> cells;  // each {cx, cy}, with cx,cy in [0..3]
        int idx = 0;
        java.awt.Point last = null;
    }
    private final ConcurrentHashMap<Integer, ClickPattern> patternByBounds = new ConcurrentHashMap<>();

    // --- Optional: external listener to tap actual click point (heatmap) -----
    private volatile Consumer<java.awt.Point> clickTapListener = null;
    public void setClickTapListener(Consumer<java.awt.Point> listener) { this.clickTapListener = listener; }

    // --- Ctor ----------------------------------------------------------------
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
        dispatchClick(clickPoint, shift);
    }

    @Override
    public void moveMouseTo(java.awt.Point p)
    {
        final Canvas canvas = client.getCanvas();
        if (canvas == null || p == null) return;

        dispatchMouse(canvas, MouseEvent.MOUSE_MOVED, p.x, p.y, 0, 0, 0);
        human.sleep(cfg.canvasMoveSleepMinMs(), cfg.canvasMoveSleepMaxMs());
    }

    @Override
    public void clickAt(java.awt.Point p, boolean shift)
    {
        if (p == null) return;
        humanClick(new Rectangle(p.x, p.y, 1, 1), shift);
    }

    // === Internals: Click task ==============================================
    private void dispatchClick(java.awt.Point target, boolean shift)
    {
        executor.submit(() -> {
            try
            {
                final Canvas canvas = client.getCanvas();
                if (canvas == null) return;

                // Optional overshoot (move past target, settle)
                if (cfg.enableOvershoot() && rng.nextInt(100) < Math.max(0, Math.min(100, cfg.overshootChancePct())))
                {
                    java.awt.Point over = overshootPoint(target);
                    dispatchMouse(canvas, MouseEvent.MOUSE_MOVED, over.x, over.y, 0, 0, 0);
                    Thread.sleep(20 + rng.nextInt(25));
                }

                // Final MOVE to target
                dispatchMouse(canvas, MouseEvent.MOUSE_MOVED, target.x, target.y, 0, 0, 0);
                Thread.sleep(scaledGaussian(cfg.reactionMeanMs(), cfg.reactionStdMs()));

                // Heatmap hook: reveal the actual click point
                if (clickTapListener != null) clickTapListener.accept(target);

                // Optional SHIFT down
                int mods = 0;
                if (shift)
                {
                    mods = InputEvent.SHIFT_DOWN_MASK;
                    canvas.dispatchEvent(new KeyEvent(canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), mods, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED));
                    Thread.sleep(18 + rng.nextInt(10));
                }

                // PRESS
                int pressMods = mods | InputEvent.BUTTON1_DOWN_MASK;
                canvas.dispatchEvent(new MouseEvent(
                        canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), pressMods, target.x, target.y, 1, false, MouseEvent.BUTTON1
                ));

                // HOLD
                Thread.sleep(scaledUniform(cfg.pressHoldMinMs(), cfg.pressHoldMaxMs()));

                // RELEASE
                canvas.dispatchEvent(new MouseEvent(
                        canvas, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), mods, target.x, target.y, 1, false, MouseEvent.BUTTON1
                ));

                // Optional SHIFT up
                if (shift)
                {
                    Thread.sleep(15 + rng.nextInt(10));
                    canvas.dispatchEvent(new KeyEvent(canvas, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED));
                }
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
            }
        });
    }

    // === Internals: Sampling =================================================
    private java.awt.Point pickPoint(Rectangle r)
    {
        final int key = Objects.hash(r.x, r.y, r.width, r.height);

        ClickPattern pat = patternByBounds.computeIfAbsent(key, k -> {
            ClickPattern cp = new ClickPattern();
            cp.cells = new ArrayList<>(16);
            for (int cx = 0; cx < 4; cx++)
                for (int cy = 0; cy < 4; cy++)
                    cp.cells.add(new int[]{cx, cy});
            Collections.shuffle(cp.cells, rng); // unique cell order per bounds
            return cp;
        });

        // Next cell (cycles 0..15)
        int[] cell = pat.cells.get(pat.idx);
        pat.idx = (pat.idx + 1) % pat.cells.size();

        // Use inner 60% of rect, split into 4x4 cells; click near cell center
        final double inner = 0.60;                   // central 60%
        final double margin = (1.0 - inner) / 2.0;   // 0.20 each side
        final double cellSpan = inner / 4.0;         // each cell is 15%

        double baseX = margin + (cell[0] + 0.5) * cellSpan;
        double baseY = margin + (cell[1] + 0.5) * cellSpan;
        double jx = (rng.nextDouble() - 0.5) * cellSpan * 0.4; // ±20% of a cell
        double jy = (rng.nextDouble() - 0.5) * cellSpan * 0.4;

        int x = clamp((int)Math.round(r.x + (baseX + jx) * r.width),  r.x + 1, r.x + r.width  - 2);
        int y = clamp((int)Math.round(r.y + (baseY + jy) * r.height), r.y + 1, r.y + r.height - 2);
        java.awt.Point p = new java.awt.Point(x, y);

        // Avoid micro-repeats
        if (pat.last != null && pat.last.distance(p) < 2.0)
        {
            x = clamp(x + (rng.nextBoolean() ? 2 : -2), r.x + 1, r.x + r.width - 2);
            y = clamp(y + (rng.nextBoolean() ? 2 : -2), r.y + 1, r.y + r.height - 2);
            p.setLocation(x, y);
        }

        pat.last = p;
        return p;
    }

    private java.awt.Point overshootPoint(java.awt.Point tgt)
    {
        int dx = (rng.nextBoolean() ? 1 : -1) * (4 + rng.nextInt(8)); // 4..11 px
        int dy = (rng.nextBoolean() ? 1 : -1) * (3 + rng.nextInt(6)); // 3..8 px
        return new java.awt.Point(tgt.x + dx, tgt.y + dy);
    }

    // === Internals: Humanization ============================================
    private int scaledGaussian(int mean, int std)
    {
        double base = Math.abs(rng.nextGaussian() * std + mean);
        return (int)Math.max(0, Math.round(applyFatigue(base)));
    }

    private int scaledUniform(int min, int max)
    {
        int span = Math.max(0, max - min);
        double base = min + rng.nextInt(span + 1);
        return (int)Math.round(applyFatigue(base));
    }

    private double applyFatigue(double base)
    {
        if (!cfg.enableFatigue()) return base;
        long elapsedMin = Math.max(0, (System.currentTimeMillis() - sessionStartMs) / 60000L);
        double ramp = Math.min(1.0, elapsedMin / 60.0);         // hit max at ~60min
        double scale = 1.0 + (cfg.fatigueMaxPct() / 100.0) * ramp;
        return base * scale;
    }

    // === Internals: AWT helper ==============================================
    private void dispatchMouse(Canvas canvas, int id, int x, int y, int button, int modifiersEx, int clickCount)
    {
        canvas.dispatchEvent(new MouseEvent(canvas, id, System.currentTimeMillis(), modifiersEx, x, y, clickCount, false, button));
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
