// ============================================================================
// FILE: ClickDotsOverlay.java
// MODULE: heist-hud
// PACKAGE: ht.heist.hud.overlay
// -----------------------------------------------------------------------------
// TITLE
//   ClickDotsOverlay — draws a solid dot for each TapEvent in the store
//
// PURPOSE
//   - Provide immediate visual confirmation of clicks on the canvas.
//   - Uses the persistent store (no fade). Typically skip UP events.
//
// LAYERING
//   - ABOVE_WIDGETS so dots appear over the inventory UI.
//
// PERFORMANCE
//   - O(n) over the current store each frame. With small dot radius, it’s cheap.
// ============================================================================

package ht.heist.hud.overlay;

import ht.heist.corejava.api.input.TapEvent;
import ht.heist.hud.HeistHUDConfig;
import ht.heist.hud.service.ClickStore;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;
import java.util.List;

public final class ClickDotsOverlay extends Overlay
{
    private final HeistHUDConfig cfg;
    private final ClickStore store;

    @Inject
    public ClickDotsOverlay(HeistHUDConfig cfg, ClickStore store) {
        this.cfg = cfg;
        this.store = store;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(PRIORITY_MED);
        setMovable(false);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!cfg.showDots()) return null;

        final int r = Math.max(1, cfg.dotRadiusPx());
        final int d = r * 2;

        final Color prev = g.getColor();
        g.setColor(new Color(255, 200, 0, 200)); // warm amber

        final List<TapEvent> events = store.unmodifiableView();
        for (TapEvent e : events) {
            if (e.type == TapEvent.Type.UP) continue; // usually not interesting for density
            g.fillOval(e.xCanvas - r, e.yCanvas - r, d, d);
        }

        g.setColor(prev);
        return null;
    }
}
