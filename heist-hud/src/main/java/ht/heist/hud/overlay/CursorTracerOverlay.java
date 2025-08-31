// ============================================================================
// FILE: CursorTracerOverlay.java
// PACKAGE: ht.heist.hud.overlay
// -----------------------------------------------------------------------------
// TITLE
//   Cursor ring + subtle trail, drawn above all UI.
// ============================================================================
package ht.heist.hud.overlay;

import ht.heist.hud.HeistHUDConfig;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.ArrayDeque;
import java.util.Deque;

@Singleton
public class CursorTracerOverlay extends Overlay
{
    private final Client client;
    private final HeistHUDConfig cfg;

    private static final class TrailPt { final int x,y; final long ts; TrailPt(int x,int y,long ts){this.x=x;this.y=y;this.ts=ts;} }
    private final Deque<TrailPt> trail = new ArrayDeque<>(128);

    @Inject
    public CursorTracerOverlay(Client client, HeistHUDConfig cfg)
    {
        this.client = client;
        this.cfg = cfg;

        setPosition(OverlayPosition.DYNAMIC);
        setPriority(OverlayPriority.HIGH);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!cfg.showCursorTracer()) return null;

        final Point p = client.getMouseCanvasPosition();
        if (p == null) return null;

        final long now = System.currentTimeMillis();
        final int keepMs = Math.max(0, cfg.tracerTrailMs());
        trail.addLast(new TrailPt(p.getX(), p.getY(), now));
        while (!trail.isEmpty() && (now - trail.peekFirst().ts) > keepMs) trail.removeFirst();

        // Trail
        if (keepMs > 0 && trail.size() > 1)
        {
            final Composite oc = g.getComposite();
            final Stroke     os = g.getStroke();
            g.setStroke(new BasicStroke(1f));
            int idx = 0, n = trail.size();
            TrailPt prev = null;
            for (TrailPt tp : trail) {
                if (prev != null) {
                    float alpha = (float)idx / (float)(n - 1);
                    alpha = Math.max(0.05f, Math.min(1f, alpha));
                    g.setComposite(AlphaComposite.SrcOver.derive(alpha));
                    g.setColor(Color.WHITE);
                    g.drawLine(prev.x, prev.y, tp.x, tp.y);
                }
                prev = tp; idx++;
            }
            g.setComposite(oc); g.setStroke(os);
        }

        // Ring
        final int r = Math.max(2, cfg.tracerRingRadiusPx());
        g.setColor(new Color(255,255,255,220));
        g.setStroke(new BasicStroke(2f));
        g.drawOval(p.getX() - r, p.getY() - r, r*2, r*2);
        g.fillRect(p.getX(), p.getY(), 1, 1);
        return null;
    }
}
