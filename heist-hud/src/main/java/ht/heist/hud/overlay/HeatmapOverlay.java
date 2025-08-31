// ============================================================================
// FILE: HeatmapOverlay.java
// PACKAGE: ht.heist.hud.overlay
// -----------------------------------------------------------------------------
// TITLE
//   Heatmap overlay — draws persistent + live taps. Supports infrared palette.
//
// DRAWING MODEL
//   • Persistent: small semi-opaque base dots
//   • Live: stronger dots with color by intensity (age-based)
// ============================================================================
package ht.heist.hud.overlay;

import ht.heist.hud.HeistHUDConfig;
import ht.heist.hud.service.HeatPoint;
import ht.heist.hud.service.HeatmapService;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;
import java.util.List;

public class HeatmapOverlay extends Overlay
{
    private final Client client;
    private final HeistHUDConfig cfg;
    private final HeatmapService svc;

    @Inject
    public HeatmapOverlay(Client client, HeistHUDConfig cfg, HeatmapService svc)
    {
        this.client = client;
        this.cfg = cfg;
        this.svc = svc;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(50);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!cfg.showHeatmap()) return null;

        final int r = Math.max(1, cfg.heatmapDotRadiusPx());

        // Persistent layer (optional)
        if (cfg.showPersistentLayer())
        {
            final List<HeatPoint> all = svc.getPersistent();
            final Color base = new Color(255, 0, 0, 50); // pale red base
            for (HeatPoint p : all)
                fillCircle(g, base, p.getX(), p.getY(), r);
        }

        // Live layer (age → intensity color)
        final long now = System.currentTimeMillis();
        final List<HeatPoint> live = svc.getLive(now, cfg.liveDecayMs());
        for (HeatPoint p : live)
        {
            double t = 1.0 - Math.min(1.0, (now - p.getTs()) / (double) cfg.liveDecayMs());
            Color c = heatColor(t);
            fillCircle(g, c, p.getX(), p.getY(), r + 1);
        }

        return null;
    }

    // ---- Helpers ------------------------------------------------------------

    private static void fillCircle(Graphics2D g, Color c, int x, int y, int r)
    {
        g.setColor(c);
        g.fillOval(x - r, y - r, r * 2, r * 2);
    }

    /** Infrared palette if enabled; otherwise alpha-scaled red. */
    private Color heatColor(double intensity)
    {
        if (!cfg.heatmapInfraredPalette())
        {
            int a = clamp((int) Math.round(40 + 180 * intensity), 0, 255);
            return new Color(255, 0, 0, a);
        }

        double t = Math.max(0, Math.min(1, intensity));
        Color[] stops = {
                new Color(  0,   0, 255), // blue
                new Color(  0, 255, 255), // cyan
                new Color(  0, 255,   0), // green
                new Color(255, 255,   0), // yellow
                new Color(255,   0,   0)  // red
        };
        double pos = t * (stops.length - 1);
        int i = (int) Math.floor(pos);
        int j = Math.min(stops.length - 1, i + 1);
        double u = pos - i;

        int r = (int) Math.round(stops[i].getRed()   * (1 - u) + stops[j].getRed()   * u);
        int g = (int) Math.round(stops[i].getGreen() * (1 - u) + stops[j].getGreen() * u);
        int b = (int) Math.round(stops[i].getBlue()  * (1 - u) + stops[j].getBlue()  * u);
        int a = (int) Math.round(60 + 195 * t);
        return new Color(r, g, b, clamp(a, 0, 255));
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
