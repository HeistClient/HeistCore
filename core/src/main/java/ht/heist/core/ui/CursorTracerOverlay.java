// ============================================================================
// FILE: CursorTracerOverlay.java
// PACKAGE: ht.heist.core.ui
// -----------------------------------------------------------------------------
// PURPOSE
//   Draw a cursor ring (and a tiny trail) above ALL UI.
//   • Reads ring size + trail lifetime from HeistCoreConfig.
//   • Checks CursorTracerService.isEnabled() to decide whether to render.
//   • Maintains a small in-overlay trail buffer (no need for service→overlay).
//
// CRITICAL
//   setLayer(OverlayLayer.ALWAYS_ON_TOP) => ring/trail are visible over widgets.
// API EXTRAS
//   public void clear() => lets other code reset the on-screen trail without
//   introducing a service→overlay dependency.
// ============================================================================
package ht.heist.core.ui;

import ht.heist.core.config.HeistCoreConfig;
import ht.heist.core.services.CursorTracerService;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.ArrayDeque;
import java.util.Deque;

@Singleton
public class CursorTracerOverlay extends Overlay
{
    // --- Injected dependencies ----------------------------------------------
    private final Client client;
    private final CursorTracerService tracer;
    private final HeistCoreConfig cfg;

    // --- In-overlay trail buffer (no service dependency) ---------------------
    private static final class TrailPt {
        final int x, y;
        final long ts;
        TrailPt(int x, int y, long ts) { this.x = x; this.y = y; this.ts = ts; }
    }
    private final Deque<TrailPt> trail = new ArrayDeque<>(64);

    @Inject
    public CursorTracerOverlay(Client client, CursorTracerService tracer, HeistCoreConfig cfg)
    {
        this.client = client;
        this.tracer = tracer;
        this.cfg = cfg;

        setPosition(OverlayPosition.DYNAMIC);
        setPriority(OverlayPriority.HIGH);
        setLayer(OverlayLayer.ALWAYS_ON_TOP); // draw over inventory/chat/etc.
    }

    /** Optional helper: reset the visible trail (does not affect service). */
    public void clear()
    {
        trail.clear();
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!cfg.showCursorTracer() || !tracer.isEnabled())
            return null;

        final Point p = client.getMouseCanvasPosition();
        if (p == null) return null;

        // --- Update trail buffer --------------------------------------------
        final long now = System.currentTimeMillis();
        final int keepMs = Math.max(0, cfg.tracerTrailMs());
        trail.addLast(new TrailPt(p.getX(), p.getY(), now));
        // Drop old points outside of lifetime
        while (!trail.isEmpty() && (now - trail.peekFirst().ts) > keepMs)
            trail.removeFirst();

        // --- Draw trail (subtle) ---------------------------------------------
        if (keepMs > 0 && trail.size() > 1)
        {
            final Stroke old = g.getStroke();
            final Composite oldComp = g.getComposite();

            g.setStroke(new BasicStroke(1f));
            // Fade older samples: newest ~opaque, oldest ~transparent
            int idx = 0, n = trail.size();
            TrailPt prev = null;
            for (TrailPt tp : trail)
            {
                if (prev != null)
                {
                    float alpha = (float)idx / (float)(n - 1);
                    alpha = Math.max(0.05f, Math.min(1f, alpha)); // clamp
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                    g.setColor(Color.WHITE);
                    g.drawLine(prev.x, prev.y, tp.x, tp.y);
                }
                prev = tp;
                idx++;
            }
            g.setComposite(oldComp);
            g.setStroke(old);
        }

        // --- Draw ring at current mouse pos ---------------------------------
        final int r = Math.max(2, cfg.tracerRingRadiusPx());
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(255, 255, 255, 220));
        g.drawOval(p.getX() - r, p.getY() - r, r * 2, r * 2);

        // Subtle center pixel for visibility
        g.fillRect(p.getX(), p.getY(), 1, 1);
        return null;
    }
}
