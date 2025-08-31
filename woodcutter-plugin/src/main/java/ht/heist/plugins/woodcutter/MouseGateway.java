// ============================================================================
// FILE: MouseGateway.java
// PACKAGE: ht.heist.plugins.woodcutter
// -----------------------------------------------------------------------------
// TITLE
//   RuneLite AWT dispatcher (executor-based) — with proper sleep try/catch.
//
// PURPOSE
//   Execute a MousePlanner.MousePlan on a single worker thread so the OSRS
//   game thread is never blocked. Provides high-level steps the plugin can
//   chain in order:
//     • moveAlong(path, stepDelayMin, stepDelayMax)
//     • dwell(min, max)    ← HOVER before pressing (prevents yellow clicks)
//     • reactGaussian(mean, std)
//     • clickAt(point, holdMin, holdMax, shift)
//
// IMPORTANT FIX IN THIS VERSION
//   Every Thread.sleep(...) is wrapped in try/catch, and on interrupt we
//   restore the thread’s interrupt flag via Thread.currentThread().interrupt().
//   This resolves the “Unhandled exception: InterruptedException” errors.
// ============================================================================

package ht.heist.plugins.woodcutter;

import net.runelite.api.Client;

import javax.inject.Inject;
import java.awt.Canvas;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MouseGateway
{
    private final Client client;

    // Single worker; serialized, human-like cadence
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Heist-MouseGateway");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public MouseGateway(Client client)
    {
        this.client = client;
    }

    // -------------------------------------------------------------------------
    // HIGH-LEVEL ACTIONS (scheduled / asynchronous)
    // -------------------------------------------------------------------------

    /** Move along a list of canvas points with per-step delays. */
    public void moveAlong(List<java.awt.Point> path, int stepDelayMinMs, int stepDelayMaxMs)
    {
        if (path == null || path.isEmpty()) return;
        exec.submit(() -> {
            Canvas c = client.getCanvas();
            if (c == null) return;
            try {
                for (java.awt.Point p : path)
                {
                    dispatchMouse(c, MouseEvent.MOUSE_MOVED, p.x, p.y, 0, 0, 0);
                    Thread.sleep(randBetween(stepDelayMinMs, stepDelayMaxMs));
                }
            } catch (InterruptedException ie) {
                // Preserve interrupt status and exit gracefully
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Hover dwell at current cursor position (prevents yellow clicks). */
    public void dwell(int minMs, int maxMs)
    {
        exec.submit(() -> {
            try {
                Thread.sleep(randBetween(minMs, maxMs));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Gaussian reaction time (mean/std). */
    public void reactGaussian(int meanMs, int stdMs)
    {
        exec.submit(() -> {
            try {
                Thread.sleep(gaussianDelay(meanMs, stdMs));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Press/hold/release at a point (optionally with Shift). */
    public void clickAt(java.awt.Point at, int holdMinMs, int holdMaxMs, boolean shift)
    {
        if (at == null) return;
        exec.submit(() -> {
            Canvas c = client.getCanvas();
            if (c == null) return;

            try {
                int mods = 0;
                if (shift) {
                    mods = InputEvent.SHIFT_DOWN_MASK;
                    c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_PRESSED,  now(), mods, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED));
                    Thread.sleep(randBetween(12, 22));
                }

                // PRESS
                int pressMods = mods | InputEvent.BUTTON1_DOWN_MASK;
                c.dispatchEvent(new MouseEvent(
                        c, MouseEvent.MOUSE_PRESSED, now(), pressMods, at.x, at.y, 1, false, MouseEvent.BUTTON1
                ));

                // HOLD
                Thread.sleep(randBetween(holdMinMs, holdMaxMs));

                // RELEASE
                c.dispatchEvent(new MouseEvent(
                        c, MouseEvent.MOUSE_RELEASED, now(), mods, at.x, at.y, 1, false, MouseEvent.BUTTON1
                ));

                if (shift) {
                    Thread.sleep(randBetween(12, 22));
                    c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_RELEASED, now(), 0, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED));
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // -------------------------------------------------------------------------
    // AWT helpers + timing utilities
    // -------------------------------------------------------------------------
    private static void dispatchMouse(Canvas c, int id, int x, int y, int button, int modifiersEx, int clicks)
    {
        c.dispatchEvent(new MouseEvent(c, id, now(), modifiersEx, x, y, clicks, false, button));
    }

    private static long now() { return System.currentTimeMillis(); }

    private static int randBetween(int lo, int hi) {
        if (lo > hi) { int t = lo; lo = hi; hi = t; }
        return lo + (int)(Math.random() * Math.max(1, (hi - lo + 1)));
    }

    private static int gaussianDelay(int mean, int std) {
        double u = Math.max(1e-9, Math.random());
        double v = Math.max(1e-9, Math.random());
        double z = Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2 * Math.PI * v);
        return (int)Math.max(0, Math.round(mean + std * z));
    }
}
