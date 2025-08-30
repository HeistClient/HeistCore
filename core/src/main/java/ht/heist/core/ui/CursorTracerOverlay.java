// ============================================================================
// CursorTracerOverlay.java (CORE)
// -----------------------------------------------------------------------------
// Purpose
//   Draw a "fake cursor" ring at the current canvas mouse position and a
//   fading trail of recent positions. Reads global toggles from HeistCoreConfig.
//
// Notes
//   - Overlay lives in CORE so every plugin can reuse it.
//   - Enabled/disabled via CursorTracerService (adds/removes from OverlayManager).
//   - Java 11; no Lombok.
// ============================================================================

package ht.heist.core.ui;

import ht.heist.core.config.HeistCoreConfig;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.ArrayDeque;
import java.util.Deque;

@Singleton
public class CursorTracerOverlay extends Overlay
{
    // === Deps ================================================================
    private final Client client;
    private final HeistCoreConfig cfg;

    // === Trail buffer ========================================================
    private static final class Sample {
        final int x, y;
        final long t;
        Sample(int x, int y, long t) { this.x = x; this.y = y; this.t = t; }
    }
    private final Deque<Sample> trail = new ArrayDeque<>(512);

    // === Look & feel =========================================================
    private static final Color TRAIL_COLOR = new Color(0, 200, 255, 180);
    private static final Color RING_COLOR  = new Color(0, 200, 255, 220);
    private static final Stroke TRAIL_STROKE = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke RING_STROKE  = new BasicStroke(1.6f);

    @Inject
    public CursorTracerOverlay(Client client, HeistCoreConfig cfg)
    {
        this.client = client;
        this.cfg = cfg;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
    }

    /** Clear the trail externally if desired. */
    public void clear() { trail.clear(); }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!cfg.showCursorTracer()) return null;

        final Point mp = client.getMouseCanvasPosition();
        final long now = System.currentTimeMillis();

        // Add current point
        if (mp != null)
        {
            if (trail.isEmpty() || (trail.getLast().x != mp.getX() || trail.getLast().y != mp.getY()))
            {
                trail.addLast(new Sample(mp.getX(), mp.getY(), now));
                if (trail.size() > 512) trail.removeFirst();
            }
        }

        // Drop old samples
        final long ttlMs = Math.max(100L, cfg.tracerTrailMs());
        while (!trail.isEmpty() && (now - trail.getFirst().t) > ttlMs) trail.removeFirst();

        // Draw trail (older = more transparent)
        g.setStroke(TRAIL_STROKE);
        Sample prev = null;
        for (Sample s : trail)
        {
            if (prev != null)
            {
                float age = (float)(now - s.t) / (float)ttlMs;    // 0..1
                float alpha = Math.max(0f, 1f - age);
                Color c = new Color(TRAIL_COLOR.getRed(), TRAIL_COLOR.getGreen(), TRAIL_COLOR.getBlue(),
                        Math.min(255, (int)(alpha * TRAIL_COLOR.getAlpha())));
                g.setColor(c);
                g.drawLine(prev.x, prev.y, s.x, s.y);
            }
            prev = s;
        }

        // Draw ring
        if (mp != null)
        {
            int r = Math.max(2, cfg.tracerRingRadiusPx());
            int d = r * 2;
            g.setColor(RING_COLOR);
            g.setStroke(RING_STROKE);
            g.drawOval(mp.getX() - r, mp.getY() - r, d, d);
            g.fillOval(mp.getX() - 1, mp.getY() - 1, 2, 2);
        }
        return null;
    }
}
