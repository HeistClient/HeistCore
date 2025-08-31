// ============================================================================
// FILE: HeatmapOverlay.java
// PACKAGE: ht.heist.hud.overlay
// -----------------------------------------------------------------------------
// TITLE
//   Infrared Heatmap Overlay — draws two layers on top of all UI.
//   • Persistent layer: infinite memory, filtered by ingest toggles.
//   • Live layer: last N ms (cfg.liveDecayMs), also filtered by ingest toggles.
//
// Z-ORDER
//   ALWAYS_ON_TOP so it remains visible over inventory/widgets.
// ============================================================================

package ht.heist.hud.overlay;

import ht.heist.hud.HeistHUDConfig;
import ht.heist.hud.service.HeatmapService;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.List;

@Singleton
public class HeatmapOverlay extends Overlay
{
    private final Client client;
    private final HeatmapService heatmap;
    private final HeistHUDConfig cfg;

    @Inject
    public HeatmapOverlay(Client client, HeatmapService heatmap, HeistHUDConfig cfg)
    {
        this.client  = client;
        this.heatmap = heatmap;
        this.cfg     = cfg;

        setPosition(OverlayPosition.DYNAMIC);
        setPriority(OverlayPriority.HIGH);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        final int r   = Math.max(0, cfg.heatmapDotRadiusPx());
        final long now = System.currentTimeMillis();

        // Persistent (infinite)
        if (cfg.showPersistentLayer())
        {
            List<HeatmapService.Sample> ps = heatmap.snapshotPersistent();
            for (HeatmapService.Sample s : ps) {
                g.setColor(infraredColor(now - s.ts, Math.max(1, cfg.liveDecayMs())));
                drawDot(g, s.x, s.y, r);
            }
        }

        // Live (decayed window)
        if (cfg.showLiveLayer())
        {
            List<HeatmapService.Sample> ls = heatmap.snapshotLive(now, cfg.liveDecayMs());
            for (HeatmapService.Sample s : ls) {
                g.setColor(infraredColor(now - s.ts, Math.max(1, cfg.liveDecayMs())));
                drawDot(g, s.x, s.y, r);
            }
        }
        return null;
    }

    private static void drawDot(Graphics2D g, int x, int y, int r)
    {
        if (r <= 0) g.fillRect(x, y, 1, 1);
        else        g.fillOval(x - r, y - r, r * 2 + 1, r * 2 + 1);
    }

    private static Color infraredColor(long ageMs, int decayMs)
    {
        if (decayMs <= 0) decayMs = 1;
        float t = (float)Math.max(0, Math.min(1, (double)ageMs / (double)decayMs));
        float r = 1.0f;
        float g = 1.0f - 0.8f * t;     // 1 → 0.2
        float b = 0.2f * (1.0f - t);   // 0.2 → 0
        return new Color(r, g, b);
    }
}
