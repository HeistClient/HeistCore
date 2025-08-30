package ht.heist.core.ui;

import ht.heist.core.config.HeistCoreConfig;
import ht.heist.core.services.HeatmapService;
import ht.heist.core.services.HeatmapService.PointSample;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;
import java.util.List;

/**
 * Global heatmap overlay. Tiny dots; optional HOT_COLD coloring.
 * Reads config keys present in HeistCoreConfig:
 *   - showHeatmap, heatmapDotRadiusPx, heatmapFadeMs, heatmapColorMode, heatmapMaxPoints
 */
public class CoreHeatmapOverlay extends Overlay
{
    private final Client client;
    private final HeistCoreConfig cfg;
    private final HeatmapService heatmap;

    @Inject
    public CoreHeatmapOverlay(Client client, HeistCoreConfig cfg, HeatmapService heatmap)
    {
        this.client = client;
        this.cfg = cfg;
        this.heatmap = heatmap;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
        setPriority(Overlay.PRIORITY_HIGH);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!cfg.showHeatmap() || !heatmap.isEnabled())
        {
            return null;
        }

        // Visual params (respect exact config; radius=0 => 1px)
        final int radius = Math.max(0, cfg.heatmapDotRadiusPx());
        final int fadeMs = cfg.heatmapFadeMs(); // <=0 means "no fade"
        final boolean hotCold = (cfg.heatmapColorMode() == HeistCoreConfig.HeatmapColorMode.HOT_COLD);

        final long now = System.currentTimeMillis();
        final List<PointSample> samples = heatmap.snapshot();
        if (samples.isEmpty())
        {
            return null;
        }

        // Simple neighborhood density for HOT_COLD: neighbors within 10px
        final int n = samples.size();
        final int neighborR2 = 10 * 10;

        int[] density = null;
        if (hotCold)
        {
            density = new int[n];
            for (int i = 0; i < n; i++)
            {
                int cx = samples.get(i).x, cy = samples.get(i).y;
                int d = 0;
                for (int j = 0; j < n; j++)
                {
                    int dx = cx - samples.get(j).x;
                    int dy = cy - samples.get(j).y;
                    if (dx * dx + dy * dy <= neighborR2) d++;
                }
                density[i] = d;
            }
        }

        int maxD = 1;
        if (hotCold)
        {
            for (int d : density) if (d > maxD) maxD = d;
        }

        // Draw dots
        Composite oldComp = g.getComposite();
        for (int i = 0; i < n; i++)
        {
            PointSample s = samples.get(i);

            // Alpha handling (fade or not)
            int alpha;
            if (fadeMs <= 0)
            {
                // No fade: full opacity
                alpha = 255;
            }
            else
            {
                long age = now - s.ts;
                if (age >= fadeMs) continue;            // fully faded
                float life = 1f - (age / (float) fadeMs); // 1..0 over fade
                alpha = (int) (life * 255);
                alpha = Math.max(20, Math.min(alpha, 255));
            }

            Color c;
            if (hotCold)
            {
                float heat = density[i] / (float) maxD; // 0..1
                // cold→hot: blue(0,0,255) → red(255,0,0)
                int r = (int) (255 * heat);
                int b = (int) (255 * (1f - heat));
                c = new Color(r, 0, b, alpha);
            }
            else
            {
                c = new Color(255, 255, 255, alpha); // mono: white
            }

            g.setColor(c);
            if (radius <= 0)
            {
                // 1px dot
                g.fillRect(s.x, s.y, 1, 1);
            }
            else
            {
                int d = radius * 2 + 1;
                g.fillOval(s.x - radius, s.y - radius, d, d);
            }
        }
        g.setComposite(oldComp);
        return null;
    }
}
