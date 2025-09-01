// ============================================================================
// FILE: HeatmapOverlay.java
// MODULE: heist-hud
// PACKAGE: ht.heist.hud.overlay
// -----------------------------------------------------------------------------
// TITLE
//   Heatmap Overlay — draws LIVE (fading) + PERSISTENT (no fade) points
//
// HOW IT WORKS
//   • LIVE: points from this session; fade over cfg.liveDecayMs()
//   • PERSISTENT: all-time points; no fade (keeps “hot zones” visible)
//
// PERFORMANCE
//   • For thousands of persistent points this is fine. If you reach millions,
//     consider downsampling or baking a cached density bitmap.
// ============================================================================
package ht.heist.hud.overlay;

import ht.heist.hud.HeistHUDConfig;
import ht.heist.hud.service.HeatmapService;
import ht.heist.hud.service.HeatmapService.HeatPoint;
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
        setPriority(Overlay.PRIORITY_LOW);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!cfg.showHeatmap())
            return null;

        final long now = System.currentTimeMillis();
        final long window = cfg.liveDecayMs();

        final List<HeatPoint> live = cfg.showLiveLayer() ? svc.getLive(now - window, now) : List.of();
        final List<HeatPoint> per  = cfg.showPersistentLayer() ? svc.getPersistent() : List.of();

        // Diagnostic banner
        g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
        g.setColor(new Color(255, 255, 255, 160));
        String diag = String.format("Heist Heatmap — live=%d persistent=%d palette=%s",
                live.size(), per.size(), cfg.palette());
        g.drawString(diag, 12, 24);

        final int r = cfg.heatmapDotRadiusPx();
        final HeistHUDConfig.Palette pal = cfg.palette();

        // Draw persistent first (background)
        if (!per.isEmpty())
        {
            for (HeatPoint p : per)
            {
                // Basic visibility check; skip off-canvas points
                if (p.x < -1000 || p.x > 4000 || p.y < -1000 || p.y > 4000) continue;
                g.setColor((pal == HeistHUDConfig.Palette.INFRARED) ? irRamp(0.6f) : redFade(0.6f));
                g.fillOval(p.x - r, p.y - r, r * 2, r * 2);
            }
        }

        // Draw live on top (foreground), with age-based fade
        if (!live.isEmpty())
        {
            for (HeatPoint p : live)
            {
                long age = now - p.ts;
                if (age < 0 || age > window) continue;
                float fresh = 1f - (float) age / (float) window; // 1 → new
                g.setColor((pal == HeistHUDConfig.Palette.INFRARED) ? irRamp(fresh) : redFade(fresh));
                g.fillOval(p.x - r, p.y - r, r * 2, r * 2);
            }
        }

        return null;
    }

    // Mono red fade
    private static Color redFade(float strength)
    {
        strength = clamp01(strength);
        int a = (int)(40 + 215 * strength); // 40..255
        return new Color(255, 50, 50, a);
    }

    // IR ramp: blue→green→yellow→orange→red→white
    private static Color irRamp(float x)
    {
        x = clamp01(x);
        if (x < 0.20f) { float t = x / 0.20f;       return new Color(0, (int)(64 + 191*t), 255); }
        if (x < 0.40f) { float t = (x-0.20f)/0.20f; return new Color(0, 255, (int)(255 - 255*t)); }
        if (x < 0.60f) { float t = (x-0.40f)/0.20f; return new Color((int)(255*t), 255, 0); }
        if (x < 0.80f) { float t = (x-0.60f)/0.20f; return new Color(255, (int)(255 - 155*t), 0); }
        float t = (x-0.80f)/0.20f;                  return new Color(255, (int)(100*t), (int)(100*t));
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
}
