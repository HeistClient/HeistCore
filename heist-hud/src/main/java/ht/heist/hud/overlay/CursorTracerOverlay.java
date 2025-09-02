// ============================================================================
// FILE: CursorTracerOverlay.java
// MODULE: heist-hud
// PACKAGE: ht.heist.hud.overlay
// -----------------------------------------------------------------------------
// TITLE
//   CursorTracerOverlay — lightweight cursor ring + short fading trail
//
// PURPOSE
//   Provide a minimal visual cursor aid on top of the game:
//     • A short-lived "trail" of 1×1 white pixels behind the mouse
//     • A white ring around the current cursor position
//   Drawn ABOVE_WIDGETS so it appears over inventory and UI.
//
// RENDERING LOGIC
//   - Every frame, read current canvas-mouse position (client.getMouseCanvasPosition()).
//   - Append the point with a timestamp to a small ring buffer.
//   - During render, draw only the points newer than "trailMs" with a fading alpha.
//   - Draw the ring at the current position with a slightly stronger alpha.
//
// CONFIG
//   - showCursorTracer() gates rendering on/off (overlay is always registered).
//   - tracerTrailMs() controls how long a trail point is visible.
//   - tracerRingRadiusPx() sets the ring radius.
//
// PERFORMANCE
//   - MAX_TRAIL is kept moderate (128) to cap cost. Drawing is O(points).
//   - All state is local; no allocations in the hot path except queue churn.
// ============================================================================

package ht.heist.hud.overlay;

import ht.heist.hud.HeistHUDConfig;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import java.awt.*;
import java.util.ArrayDeque;

public final class CursorTracerOverlay extends Overlay
{
    private final Client client;
    private final HeistHUDConfig cfg;

    // Bounded buffer of recent cursor samples (FIFO)
    private static final int MAX_TRAIL = 128;
    private final ArrayDeque<TrailPoint> trail = new ArrayDeque<>(MAX_TRAIL);

    // Simple immutable sample (canvas x,y + time in ms)
    private static final class TrailPoint {
        final int x, y; final long ts;
        TrailPoint(int x, int y, long ts) { this.x = x; this.y = y; this.ts = ts; }
    }

    @Inject
    public CursorTracerOverlay(Client client, HeistHUDConfig cfg)
    {
        this.client = client;
        this.cfg = cfg;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);      // ensures draw over inventory/ui
        setPriority(OverlayPriority.HIGH);         // render earlier than most overlays
        setMovable(false);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        // 1) Quick gate: do nothing if the tracer is disabled
        if (!cfg.showCursorTracer()) return null;

        final long now = System.currentTimeMillis();
        final int ringR   = Math.max(2,   cfg.tracerRingRadiusPx());
        final int trailMs = Math.max(100, cfg.tracerTrailMs());

        // 2) Sample current mouse position in canvas space
        final Point mp = client.getMouseCanvasPosition();
        if (mp != null) {
            // Append latest point
            trail.addLast(new TrailPoint(mp.getX(), mp.getY(), now));
            // Ensure capacity
            while (trail.size() > MAX_TRAIL) trail.removeFirst();
        }

        // 3) Draw trail with a linear fade by age (newer → more opaque)
        final Composite saved = g.getComposite();
        g.setColor(Color.WHITE);
        for (TrailPoint p : trail) {
            long age = now - p.ts;
            if (age > trailMs) continue;                         // too old, skip
            float alpha = 1f - (float) age / trailMs;            // 1..0
            if (alpha < 0.05f) alpha = 0.05f;                    // keep a faint spark
            g.setComposite(AlphaComposite.SrcOver.derive(alpha));
            g.fillRect(p.x, p.y, 1, 1);                          // single pixel
        }

        // 4) Draw the ring at the current position
        if (mp != null) {
            g.setComposite(AlphaComposite.SrcOver.derive(0.9f));
            g.setColor(Color.WHITE);
            g.drawOval(mp.getX() - ringR, mp.getY() - ringR, ringR * 2, ringR * 2);
        }

        // 5) Restore composite and return
        g.setComposite(saved);
        return null;
    }
}
