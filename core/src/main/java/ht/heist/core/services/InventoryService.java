// ============================================================================
// InventoryService.java
// -----------------------------------------------------------------------------
// Purpose
//   Core service for reading and interacting with the inventory.
//   - Keeps "how to click" centralized (uses MouseService).
//   - Provides natural, stable ordering of items (top→down, left→right).
//   - Exposes stepwise (dropOne) and bulk (dropAll) helpers.
//
// Notes
//   - Marked with @ImplementedBy to let RuneLite/Guice bind without a module.
//   - Java 11 friendly.
// ============================================================================

package ht.heist.core.services;

import com.google.inject.ImplementedBy;
import ht.heist.core.impl.InventoryServiceImpl;
import net.runelite.api.widgets.Widget;

import java.util.List;
import java.util.Optional;

@ImplementedBy(InventoryServiceImpl.class)
public interface InventoryService
{
    // === Read / open =========================================================

    /** @return true if the Inventory panel widget is present. */
    boolean isOpen();

    /** Try to open the inventory (ESC fallback). */
    void open();

    /** @return true if inventory has 28 or more occupied slots. */
    boolean isFull();

    // === Item queries ========================================================

    /** First item widget whose id matches any in itemIds. */
    Optional<Widget> firstItem(int... itemIds);

    /** All item widgets whose id matches any in itemIds, natural order (y,x). */
    List<Widget> allItems(int... itemIds);

    // === Actions =============================================================

    /** Click a specific item widget (shift means shift-click). */
    void clickItem(Widget item, boolean shift);

    // === High-level helpers ==================================================

    /**
     * Drop exactly one item of any of the given ids (if found).
     * @return true if an item was dropped on this call.
     */
    boolean dropOne(List<Integer> itemIds);

    /**
     * Drop all items matching ids (blocking, loops until none remain).
     * Prefer {@link #dropOne(List)} inside tick loops to pace clicks naturally.
     * @return true if any item was dropped.
     */
    boolean dropAll(List<Integer> itemIds);
}
