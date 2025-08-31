// ============================================================================
// FILE: CoreHeatmapOverlay.java
// PACKAGE: ht.heist.core.ui
// -----------------------------------------------------------------------------
// PURPOSE
//   Draws click heatmap OVER EVERYTHING (scene + widgets + inventory).
//   - Reads points from HeatmapService.snapshot()
//   - Respects HeistCoreConfig (radius, color mode, fade/infinite)
//
// CRITICAL
//   setLayer(OverlayLayer.ALWAYS_ON_TOP) → makes dots visible over inventory.
// ============================================================================
package ht.heist.core.ui;

import ht.heist.core.config.HeistCoreConfig;
import ht.heist.core.services.HeatmapService;
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
public class CoreHeatmapOverlay extends Overlay
{
    private final Client client;
    private final HeatmapService heatmap;
    private final HeistCoreConfig cfg;

    @Inject
    public CoreHeatmapOverlay(Client client, HeatmapService heatmap, HeistCoreConfig cfg)
    {
        this.client = client;
        this.heatmap = heatmap;
        this.cfg = cfg;

        // --- CRITICAL FOR INVENTORY VISIBILITY --------------------------------
        setPosition(OverlayPosition.DYNAMIC);
        setPriority(OverlayPriority.HIGH);
        setLayer(OverlayLayer.ALWAYS_ON_TOP); // draw above widgets
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        // Guard: must be enabled AND toggled on
        if (!cfg.showHeatmap() || !heatmap.isEnabled()) return null;

        // Snapshot of current points
        List<HeatmapService.Sample> pts = heatmap.snapshot();
        if (pts.isEmpty()) return null;

        final int r = Math.max(0, cfg.heatmapDotRadiusPx());

        // Choose color mode (very lightweight demo; feel free to enhance)
        switch (cfg.heatmapColorMode())
        {
            case MONO:
                g.setColor(Color.WHITE);
                break;
            case HOT_COLD:
            case INFRARED:
                // We'll color per-point later; no static color here
                break;
        }

        // Render each dot — keep it fast/simple
        for (HeatmapService.Sample s : pts)
        {
            // Optionally color per-point density/age here; for simplicity we use static color
            if (cfg.heatmapColorMode() == HeistCoreConfig.HeatmapColorMode.MONO) {
                drawDot(g, s.x, s.y, r);
            } else {
                // Simple "age-based" coloring to hint density/recency
                long age = System.currentTimeMillis() - s.tsMillis;
                Color c = cfg.heatmapColorMode() == HeistCoreConfig.HeatmapColorMode.HOT_COLD
                        ? ageToHotCold(age) : ageToInfrared(age);
                g.setColor(c);
                drawDot(g, s.x, s.y, r);
            }
        }

        return null;
    }

    // --- Helpers: drawing + basic palettes -----------------------------------
    private static void drawDot(Graphics2D g, int x, int y, int r)
    {
        if (r <= 0) {
            g.fillRect(x, y, 1, 1);
        } else {
            g.fillOval(x - r, y - r, r * 2 + 1, r * 2 + 1);
        }
    }

    private static Color ageToHotCold(long ageMs)
    {
        // 0ms → red; 2s+ → blue (very rough ramp)
        float t = (float)Math.max(0, Math.min(1, ageMs / 2000f));
        // interpolate red(1,0,0) → blue(0,0,1)
        return new Color(1f - t, 0f, t);
    }

    private static Color ageToInfrared(long ageMs)
    {
        // 0ms → yellow-white; 2s+ → dark red
        float t = (float)Math.max(0, Math.min(1, ageMs / 2000f));
        float r = 1f;
        float g = 1f - 0.8f * t;
        float b = 0.2f * (1f - t);
        return new Color(r, g, b);
    }
}
