// ============================================================================
// FILE: WoodcutterConfig.java
// PACKAGE: ht.heist.plugins.woodcutter
// -----------------------------------------------------------------------------
// TITLE
//   Heist Woodcutter Config — minimal keys the plugin uses right now.
// ============================================================================

package ht.heist.plugins.woodcutter;

import ht.heist.corerl.object.TreeType;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("heist-woodcutter")
public interface WoodcutterConfig extends Config
{
    enum LogHandling { DROP, BURN }

    @ConfigItem(
            keyName = "treeType",
            name = "Tree type",
            description = "Which trees to chop.",
            position = 0
    )
    default TreeType treeType()
    {
        return TreeType.ANY;
    }

    @ConfigItem(
            keyName = "logHandling",
            name = "When inv is full",
            description = "Drop logs or burn them with tinderbox.",
            position = 1
    )
    default LogHandling logHandling()
    {
        return LogHandling.DROP;
    }
}
