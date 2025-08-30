// ============================================================================
// WoodcutterConfig.java
// -----------------------------------------------------------------------------
package ht.heist.plugins.woodcutter;

import net.runelite.client.config.*;

@ConfigGroup("heistwoodcutter") // <— CHANGED: unique, no hyphen
public interface WoodcutterConfig extends Config
{
    // ===== Section: Behavior =====
    @ConfigSection(
            name = "Behavior",
            description = "Woodcutter behavior settings.",
            position = 0
    )
    String behavior = "behavior";

    // Tree type (enum dropdown)
    @ConfigItem(
            keyName = "treeType",
            name = "Tree Type",
            description = "Which tree to cut.",
            position = 1,
            section = behavior
    )
    default TreeType treeType() { return TreeType.NORMAL; }

    enum TreeType
    {
        NORMAL(1276),  // Regular tree
        OAK(10820),
        WILLOW(10819),
        MAPLE(10832);

        private final int treeId;
        TreeType(int id) { this.treeId = id; }
        public int getTreeId() { return treeId; }
    }

    // Log handling (enum dropdown)
    @ConfigItem(
            keyName = "logHandling",
            name = "Log Handling",
            description = "What to do when inventory is full.",
            position = 2,
            section = behavior
    )
    default LogHandling logHandling() { return LogHandling.DROP; }

    enum LogHandling { DROP, BURN }

    // ===== Section: Overlay =====
    @ConfigSection(
            name = "Overlay",
            description = "Debug drawing / heatmap.",
            position = 10
    )
    String overlay = "overlay";

    @ConfigItem(
            keyName = "drawHeatmap",
            name = "Draw click heatmap",
            description = "Show where clicks landed.",
            position = 11,
            section = overlay
    )
    default boolean drawHeatmap() { return true; }
}
