// ============================================================================
// FILE: WoodcutterConfig.java
// PACKAGE: ht.heist.plugins.woodcutter
// -----------------------------------------------------------------------------
// TITLE
//   Minimal, focused config for Woodcutter (no Core/HUD options here).
//
// WHY THIS FILE EXISTS
//   • Keeps the woodcutter side-panel “clean” and independent from Heist HUD.
//   • Lets you choose which tree to cut and what to do with logs.
//
// ABOUT OBJECT IDS (IMPORTANT!)
//   • RuneLite’s ObjectID class exposes MANY constants like TREE, TREE_1276,
//     etc., but the exact names can vary by RL version.
//   • To avoid “cannot resolve symbol” across versions, we use RAW integers.
//   • If a specific tree id changes, just update the int here.
//
// HOW TO FIND TREE IDS (when you want others):
//   1) Turn on a dev plugin/ID inspector (e.g., Object Inspector) if available.
//   2) Or print object.getId() for a hovered GameObject.
//   3) Update the numbers below and rebuild.
// ============================================================================

package ht.heist.plugins.woodcutter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("heistwoodcutter")
public interface WoodcutterConfig extends Config
{
    // ----- Tree types (raw numeric IDs with exhaustive comments) -------------
    enum TreeType
    {
        // Regular tree — common “Tree” object
        // Known vanilla ID(s): 1276 (classic), others exist too; if nothing clicks,
        // use a debug read to confirm and change this number.
        REGULAR(1276, "Regular"),

        // Oak tree — often 10820
        OAK(10820, "Oak"),

        // Willow tree — often 10819
        WILLOW(10819, "Willow");

        // ====== INTERNAL STORAGE =============================================
        private final int id;         // raw object id used by RuneLite Scene tiles
        private final String pretty;  // shown in the config dropdown

        TreeType(int id, String pretty)
        {
            this.id = id;
            this.pretty = pretty;
        }

        /** Return the raw object id this enum represents. */
        public int getId()
        {
            return id;
        }

        /** What appears to the user in the config combo box. */
        @Override
        public String toString()
        {
            return pretty;
        }
    }

    // ----- What to do with the logs -----------------------------------------
    enum LogHandling
    {
        // Just discard logs quickly
        DROP,
        // Try to burn logs using a tinderbox (simple demo logic)
        BURN
    }

    // ----- CONFIG FIELDS -----------------------------------------------------
    @ConfigItem(
            keyName = "treeType",
            name = "Tree Type",
            description = "Which tree to chop (raw IDs documented inline).",
            position = 1
    )
    default TreeType treeType()
    {
        return TreeType.REGULAR;
    }

    @ConfigItem(
            keyName = "logHandling",
            name = "Log Handling",
            description = "Choose DROP or BURN.",
            position = 2
    )
    default LogHandling logHandling()
    {
        return LogHandling.DROP;
    }
}
