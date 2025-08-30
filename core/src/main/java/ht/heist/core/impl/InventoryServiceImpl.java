// ============================================================================
// InventoryServiceImpl.java
// -----------------------------------------------------------------------------
// Purpose
//   Concrete implementation of InventoryService (singleton recommended).
// ============================================================================

package ht.heist.core.impl;

import ht.heist.core.services.InventoryService;
import ht.heist.core.services.MouseService;
import ht.heist.core.services.HumanizerService;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.stream.Collectors;

@Singleton
public class InventoryServiceImpl implements InventoryService
{
    private final Client client;
    private final MouseService mouse;
    private final HumanizerService human;

    @Inject
    public InventoryServiceImpl(Client client, MouseService mouse, HumanizerService human) {
        this.client = client;
        this.mouse = mouse;
        this.human = human;
    }

    @Override public boolean isOpen() { return client.getWidget(WidgetInfo.INVENTORY) != null; }

    @Override
    public void open()
    {
        if (client.getCanvas() == null) return;
        try {
            client.getCanvas().dispatchEvent(
                    new java.awt.event.KeyEvent(
                            client.getCanvas(), KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                            0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED
                    )
            );
            human.sleep(40, 80);
            client.getCanvas().dispatchEvent(
                    new java.awt.event.KeyEvent(
                            client.getCanvas(), KeyEvent.KEY_RELEASED, System.currentTimeMillis(),
                            0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED
                    )
            );
        } catch (Exception ignored) { }
    }

    @Override
    public boolean isFull()
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        return inv != null && inv.count() >= 28;
    }

    @Override
    public Optional<Widget> firstItem(int... itemIds)
    {
        Set<Integer> ids = Arrays.stream(itemIds).boxed().collect(Collectors.toSet());
        Widget inv = client.getWidget(WidgetInfo.INVENTORY);
        if (inv == null) return Optional.empty();

        for (Widget w : inv.getDynamicChildren()) {
            if (w != null && ids.contains(w.getItemId())) return Optional.of(w);
        }
        return Optional.empty();
    }

    @Override
    public List<Widget> allItems(int... itemIds)
    {
        Set<Integer> ids = Arrays.stream(itemIds).boxed().collect(Collectors.toSet());
        Widget inv = client.getWidget(WidgetInfo.INVENTORY);
        if (inv == null) return Collections.emptyList();

        List<Widget> out = new ArrayList<>();
        for (Widget w : inv.getDynamicChildren()) {
            if (w != null && ids.contains(w.getItemId())) out.add(w);
        }

        out.sort(Comparator
                .comparingInt((Widget w) -> w.getBounds().y)
                .thenComparingInt(w -> w.getBounds().x)
        );
        return out;
    }

    @Override
    public void clickItem(Widget item, boolean shift)
    {
        if (item == null) return;
        Rectangle r = item.getBounds();
        mouse.humanClick(r, shift);
    }

    @Override
    public boolean dropOne(List<Integer> itemIds)
    {
        if (itemIds == null || itemIds.isEmpty()) return false;
        List<Widget> items = allItems(itemIds.stream().mapToInt(i -> i).toArray());
        if (items.isEmpty()) return false;

        clickItem(items.get(0), true);
        return true;
    }

    @Override
    public boolean dropAll(List<Integer> itemIds)
    {
        if (itemIds == null || itemIds.isEmpty()) return false;
        boolean dropped = false;
        while (true) {
            List<Widget> items = allItems(itemIds.stream().mapToInt(i -> i).toArray());
            if (items.isEmpty()) break;
            clickItem(items.get(0), true);
            dropped = true;
        }
        return dropped;
    }
}