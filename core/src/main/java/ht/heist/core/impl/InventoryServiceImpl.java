// ============================================================================
// FILE: InventoryServiceImpl.java
// PACKAGE: ht.heist.core.impl
// -----------------------------------------------------------------------------
// PURPOSE
//   Inventory interactions that play nicely with the heatmap:
//     - Prefer real mouse clicks via MouseService (heatmap tap hook sees them).
//     - If you *must* use menu API, manually add a dot first for visibility.
// METHODS
//   • isOpen()    : whether inventory is visible
//   • open()      : open inventory tab (minimal impl)
//   • dropOne(..) : drop ONE item matching the provided IDs (shift-click)
// ============================================================================
package ht.heist.core.impl;

import ht.heist.core.services.HeatmapService;
import ht.heist.core.services.InventoryService;
import ht.heist.core.services.MouseService;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.List;

@Singleton
public class InventoryServiceImpl implements InventoryService
{
    private final Client client;
    private final MouseService mouse;
    private final HeatmapService heatmap;

    @Inject
    public InventoryServiceImpl(Client client, MouseService mouse, HeatmapService heatmap)
    {
        this.client = client;
        this.mouse = mouse;
        this.heatmap = heatmap;
    }

    @Override
    public boolean isOpen()
    {
        // Use the canonical INVENTORY root; INVENTORY_CONTAINER may not exist on your version.
        return client.getWidget(WidgetInfo.INVENTORY) != null;
    }

    @Override
    public void open()
    {
        // Minimal example: press ESC to close panels; adapt to your preferred hotkey/tab click.
        if (client.getCanvas() != null)
        {
            client.getCanvas().dispatchEvent(new KeyEvent(
                    client.getCanvas(), KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED
            ));
            client.getCanvas().dispatchEvent(new KeyEvent(
                    client.getCanvas(), KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED
            ));
        }
    }

    @Override
    public boolean dropOne(List<Integer> itemIds)
    {
        Widget inv = client.getWidget(WidgetInfo.INVENTORY);
        if (inv == null) return false;

        Widget target = Arrays.stream(inv.getDynamicChildren())
                .filter(w -> itemIds.contains(w.getItemId()))
                .findFirst()
                .orElse(null);

        if (target == null) return false;

        Rectangle r = target.getBounds();

        // Preferred: real mouse click (heatmap automatically logs via MouseService tap hook)
        mouse.humanClick(r, true /* shift for drop if enabled in client */);
        return true;

        // If you must use menu API instead, uncomment to add a dot first:
        // heatmap.addClick(r.x + r.width / 2, r.y + r.height / 2);
        // client.invokeMenuAction("Drop", "", -1, MenuAction.CC_OP.getId(), target.getId(), target.getIndex());
        // return true;
    }
}
