// ============================================================================
// FILE: WoodcutterConfig.java
// PACKAGE: ht.heist.plugins.woodcutter
// -----------------------------------------------------------------------------
// TITLE
//   Heist Woodcutter — plugin-specific knobs (CLEANED)
//   • Behavior only: which tree to cut, how to handle full inventory
//
// WHAT THIS FILE DOES
//   • Declares the @ConfigGroup used by this plugin (“heistwoodcutter”).
//   • Defines a small, focused set of options used by WoodcutterPlugin.
//   • ALL overlay/cursor/heatmap controls have been removed from here, since
//     they belong to Heist Core global panel.
// ============================================================================

package ht.heist.plugins.woodcutter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("heistwoodcutter") // <-- This id MUST match what the plugin expects
public interface WoodcutterConfig extends Config
{
    // ===== Section: Behavior =================================================
    @ConfigSection(
            name = "Behavior",
            description = "Woodcutter behavior settings.",
            position = 0
    )
    String behavior = "behavior";

    // -- Tree selection -------------------------------------------------------
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
        NORMAL(1276),    // Regular tree
        OAK(10820),
        WILLOW(10819),
        MAPLE(10832);

        private final int treeId;
        TreeType(int id) { this.treeId = id; }
        public int getTreeId() { return treeId; }
    }

    // -- What to do when inventory is full -----------------------------------
    @ConfigItem(
            keyName = "logHandling",
            name = "Log Handling",
            description = "What to do when inventory is full.",
            position = 2,
            section = behavior
    )
    default LogHandling logHandling() { return LogHandling.DROP; }

    enum LogHandling { DROP, BURN }
}
