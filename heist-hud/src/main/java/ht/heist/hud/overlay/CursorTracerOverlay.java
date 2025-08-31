// ============================================================================
// FILE: CursorTracerOverlay.java
// PACKAGE: ht.heist.hud.overlay
// -----------------------------------------------------------------------------
// TITLE
//   Cursor tracer — draws a small ring at the cursor and a short trail behind.
//
// IMPLEMENTATION
//   • Pulls canvas mouse position each render()
//   • Keeps a small in-memory trail for cfg.tracerTrailMs()
// ============================================================================
package ht.heist.hud.overlay;

import ht.heist.hud.HeistHUDConfig;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;
import java.util.ArrayDeque;
import java.util.Deque;

public class CursorTracerOverlay extends Overlay
{
    private final Client client;
    private final HeistHUDConfig cfg;

    private static final class TrailPoint {
        final int x, y;
        final long ts;
        TrailPoint(int x, int y, long ts){ this.x = x; this.y = y; this.ts = ts; }
    }
    private final Deque<TrailPoint> trail = new ArrayDeque<>(256);

    @Inject
    public CursorTracerOverlay(Client client, HeistHUDConfig cfg)
    {
        this.client = client;
        this.cfg = cfg;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(60);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!cfg.showCursorTracer()) return null;

        final Point mp = client.getMouseCanvasPosition();
        if (mp != null)
        {
            long now = System.currentTimeMillis();
            trail.addLast(new TrailPoint(mp.getX(), mp.getY(), now));

            // prune old
            final long cutoff = now - Math.max(100, cfg.tracerTrailMs());
            while (!trail.isEmpty() && trail.peekFirst().ts < cutoff)
                trail.removeFirst();

            // draw trail (fade with age)
            for (TrailPoint tp : trail)
            {
                double t = 1.0 - Math.min(1.0, (now - tp.ts) / (double) cfg.tracerTrailMs());
                int a = (int)Math.round(30 + 200 * t);
                g.setColor(new Color(0, 255, 255, Math.min(255, a)));
                g.fillOval(tp.x - 2, tp.y - 2, 4, 4);
            }

            // draw current ring
            int r = Math.max(2, cfg.tracerRingRadiusPx());
            g.setColor(new Color(0, 255, 255, 220));
            g.setStroke(new BasicStroke(2f));
            g.drawOval(mp.getX() - r, mp.getY() - r, r * 2, r * 2);
        }
        return null;
    }
}
