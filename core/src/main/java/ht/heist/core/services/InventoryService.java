// ============================================================================
// FILE: InventoryService.java
// PACKAGE: ht.heist.core.services
// -----------------------------------------------------------------------------
// PURPOSE
//   Minimal interface Woodcutter relies on.
// ============================================================================
package ht.heist.core.services;

import java.util.List;

public interface InventoryService
{
    boolean isOpen();
    void open();
    boolean dropOne(List<Integer> itemIds);
}
