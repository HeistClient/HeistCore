// ============================================================================
// FILE: CursorTracerOverlay.java
// MODULE: heist-hud
// PACKAGE: ht.heist.hud.overlay
// -----------------------------------------------------------------------------
// TITLE
//   CursorTracerOverlay — draws a fading trail and a ring at the current cursor
//   canvas position. This is a self-contained sanity check: if you see the ring,
//   the HUD is definitely rendering.
//
// HOW IT WORKS
//   - Each render() grabs the current mouse canvas position and appends a sample.
//   - Samples older than cfg.tracerTrailMs() are dropped.
//   - A ring is drawn at the latest point; a faint trail is drawn behind.
//
// LAYER/POSITION
//   - ABOVE_SCENE + DYNAMIC so it follows the game camera correctly.
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

    private static final class Sample {
        final int x, y; final long ts;
        Sample(int x, int y, long ts) { this.x = x; this.y = y; this.ts = ts; }
    }
    private final Deque<Sample> trail = new ArrayDeque<>(256);

    @Inject
    public CursorTracerOverlay(Client client, HeistHUDConfig cfg)
    {
        this.client = client;
        this.cfg = cfg;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(PRIORITY_LOW);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!cfg.showCursorTracer())
            return null;

        final Point mp = client.getMouseCanvasPosition();
        final long now = System.currentTimeMillis();
        final int ringR = Math.max(2, cfg.tracerRingRadiusPx());

        // Append new sample if available
        if (mp != null)
        {
            trail.addLast(new Sample(mp.getX(), mp.getY(), now));
        }

        // Drop old samples
        final long cutoff = now - cfg.tracerTrailMs();
        while (!trail.isEmpty() && trail.peekFirst().ts < cutoff)
        {
            trail.removeFirst();
        }

        // Draw trail (faded)
        Sample last = null;
        for (Sample s : trail)
        {
            if (last != null)
            {
                float ageRatio = (float)Math.min(1.0, (s.ts - cutoff) / (double)cfg.tracerTrailMs());
                float alpha = Math.max(0.05f, ageRatio * 0.35f);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g.setStroke(new BasicStroke(2f));
                g.setColor(Color.WHITE);
                g.drawLine(last.x, last.y, s.x, s.y);
            }
            last = s;
        }

        // Draw ring at current mouse point
        if (mp != null)
        {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));
            g.setColor(new Color(255, 255, 255, 200));
            g.setStroke(new BasicStroke(2f));
            g.drawOval(mp.getX() - ringR, mp.getY() - ringR, ringR * 2, ringR * 2);
        }

        // Reset composite
        g.setComposite(AlphaComposite.SrcOver);
        return null;
    }
}
