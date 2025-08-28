package ht.heist;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("heistwoodcutter")
public interface WoodcutterConfig extends Config {

    @ConfigSection(
            name = "Main Settings",
            description = "Core settings for the woodcutter.",
            position = 0
    )
    String mainSettings = "mainSettings";

    @ConfigItem(
            keyName = "treeType",
            name = "Tree to Chop",
            description = "Select the type of tree you want to chop.",
            position = 1,
            section = mainSettings
    )
    default TreeType treeType() {
        return TreeType.NORMAL;
    }

    @ConfigItem(
            keyName = "logHandling",
            name = "Log Handling",
            description = "What to do with logs when the inventory is full.",
            position = 2,
            section = mainSettings
    )
    default LogHandling logHandling() {
        return LogHandling.DROP;
    }

    @ConfigSection(
            name = "Human Behavior",
            description = "Settings to control the human-like behavior emulation.",
            position = 10
    )
    String humanBehaviorSettings = "humanBehaviorSettings";

    @ConfigItem(
            keyName = "actionMode",
            name = "Action Mode",
            description = "Controls the general speed and reaction times.",
            position = 11,
            section = humanBehaviorSettings
    )
    default ActionMode actionMode() {
        return ActionMode.HUMAN_LIKE;
    }

    @ConfigItem(
            keyName = "enableOvershoots",
            name = "Enable Click Overshoots",
            description = "Occasionally move past the target and then correct, mimicking a common human error.",
            position = 12,
            section = humanBehaviorSettings
    )
    default boolean enableOvershoots() {
        return true;
    }

    @ConfigItem(
            keyName = "enableCameraRotation",
            name = "Enable Camera Rotation",
            description = "Periodically rotate the camera to a new random angle.",
            position = 13,
            section = humanBehaviorSettings
    )
    default boolean enableCameraRotation() {
        return true;
    }

    @ConfigItem(
            keyName = "enableFatigue",
            name = "Enable Fatigue Simulation",
            description = "Subtly slows down actions over a long period of time.",
            position = 14,
            section = humanBehaviorSettings
    )
    default boolean enableFatigue() {
        return true;
    }


    @ConfigSection(
            name = "Diagnostics",
            description = "Tools for visualizing and debugging the plugin's behavior.",
            position = 20
    )
    String diagnosticsSettings = "diagnosticsSettings";

    @ConfigItem(
            keyName = "drawHeatmap",
            name = "Draw Click Heatmap",
            description = "Shows an overlay of all the points the plugin has clicked.",
            position = 21,
            section = diagnosticsSettings
    )
    default boolean drawHeatmap() {
        return false;
    }


    // --- Enums ---

    enum TreeType {
        NORMAL("Tree", 1278),
        OAK("Oak", 10820);

        private final String name;
        private final int treeId;

        TreeType(String name, int treeId) { this.name = name; this.treeId = treeId; }
        public int getTreeId() { return treeId; }
        @Override public String toString() { return name; }
    }

    enum LogHandling {
        DROP, BURN
    }

    enum ActionMode {
        // Defines different timing profiles. Mean delay, reaction time, and standard deviations.
        TICK_PERFECT(10, 5, 20, 10),
        FAST(80, 20, 100, 30),
        HUMAN_LIKE(150, 40, 180, 50),
        RELAXED(250, 60, 300, 80);

        private final int defaultWaitMean;
        private final int defaultWaitStdDev;
        private final int reactionTimeMean;
        private final int reactionTimeStdDev;

        ActionMode(int waitMean, int waitStd, int reactMean, int reactStd) {
            this.defaultWaitMean = waitMean;
            this.defaultWaitStdDev = waitStd;
            this.reactionTimeMean = reactMean;
            this.reactionTimeStdDev = reactStd;
        }

        public int getDefaultWaitMean() { return defaultWaitMean; }
        public int getDefaultWaitStdDev() { return defaultWaitStdDev; }
        public int getReactionTimeMean() { return reactionTimeMean; }
        public int getReactionTimeStdDev() { return reactionTimeStdDev; }
    }
}