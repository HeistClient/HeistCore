package ht.heist.core.config;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("heistcore")
public interface HeistCoreConfig extends Config {
    @ConfigItem(
            keyName = "hoverClickDelayMin",
            name = "Hover-Click Delay Min (ms)",
            description = "Minimum delay between hover and click",
            position = 1
    )
    default int hoverClickDelayMin() { return 30; }

    @ConfigItem(
            keyName = "hoverClickDelayMax",
            name = "Hover-Click Delay Max (ms)",
            description = "Maximum delay between hover and click",
            position = 2
    )
    default int hoverClickDelayMax() { return 120; }
}
