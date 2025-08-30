// ============================================================================
// WoodcutterConfig.java
// -----------------------------------------------------------------------------
// Purpose
//   Plugin-specific knobs for the Heist Woodcutter.
//   • Behavior: tree type, log handling
//   • Overlay: heatmap toggle
//   • Cursor Tracer (Bridge): mirrors core tracer settings so this plugin panel
//     can control the core overlay via runtime sync.
// Group ID must stay "heistwoodcutter" (plugin @Provides relies on it).
// ============================================================================
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

    // ===== Section: Overlay ==================================================
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

    // ===== Section: Cursor Tracer (Bridge to core) ===========================
    @ConfigSection(
            name = "Cursor Tracer (Core Bridge)",
            description = "Controls the core cursor tracer overlay from this plugin.",
            position = 20
    )
    String tracer = "tracer";

    @ConfigItem(
            keyName = "wcShowCursorTracer",
            name = "Show cursor tracer",
            description = "Enable core cursor tracer overlay (mirrors heistcore).",
            position = 21,
            section = tracer
    )
    default boolean wcShowCursorTracer() { return true; }

    @ConfigItem(
            keyName = "wcTracerTrailMs",
            name = "Trail lifetime (ms)",
            description = "How long each trail segment persists (mirrors heistcore).",
            position = 22,
            section = tracer
    )
    default int wcTracerTrailMs() { return 900; }

    @ConfigItem(
            keyName = "wcTracerRingRadiusPx",
            name = "Ring radius (px)",
            description = "Radius of the fake cursor ring (mirrors heistcore).",
            position = 23,
            section = tracer
    )
    default int wcTracerRingRadiusPx() { return 6; }

    // ===== Section: Heatmap Bridge (to core) =====
    @ConfigSection(
            name = "Heatmap",
            description = "Mirrors to core heatmap settings.",
            position = 20
    )
    String heatmap = "heatmap";

    @ConfigItem(
            keyName = "wcShowHeatmap",
            name = "Show heatmap",
            description = "Mirror to core showHeatmap.",
            position = 21, section = heatmap
    )
    default boolean wcShowHeatmap() { return true; }

    @ConfigItem(
            keyName = "wcHeatmapFadeMs",
            name = "Dot fade (ms)",
            description = "0 = never fade. Mirrors to core heatmapFadeMs.",
            position = 22, section = heatmap
    )
    default int wcHeatmapFadeMs() { return 0; } // <- no fade by default

    @ConfigItem(
            keyName = "wcHeatmapDotRadiusPx",
            name = "Dot radius (px)",
            description = "0 = single pixel. Mirrors to core heatmapDotRadiusPx.",
            position = 23, section = heatmap
    )
    default int wcHeatmapDotRadiusPx() { return 0; } // <- 1px

    @ConfigItem(
            keyName = "wcHeatmapMaxPoints",
            name = "Max dots",
            description = "Mirror to core heatmapMaxPoints.",
            position = 24, section = heatmap
    )
    default int wcHeatmapMaxPoints() { return 2000; }

    enum WcHeatmapColorMode { MONO, HOT_COLD }

    @ConfigItem(
            keyName = "wcHeatmapColorMode",
            name = "Color mode",
            description = "MONO or HOT_COLD. Mirrors to core heatmapColorMode.",
            position = 25, section = heatmap
    )
    default WcHeatmapColorMode wcHeatmapColorMode() { return WcHeatmapColorMode.HOT_COLD; }

    @ConfigItem(
            keyName = "wcHeatmapExportEnabled",
            name = "Enable periodic export",
            description = "Mirror to core heatmapExportEnabled.",
            position = 26, section = heatmap
    )
    default boolean wcHeatmapExportEnabled() { return false; }

    @ConfigItem(
            keyName = "wcHeatmapExportEveryMs",
            name = "Export interval (ms)",
            description = "Mirror to core heatmapExportEveryMs.",
            position = 27, section = heatmap
    )
    default int wcHeatmapExportEveryMs() { return 15000; }

    @ConfigItem(
            keyName = "wcHeatmapExportFolder",
            name = "Export folder",
            description = "Mirror to core heatmapExportFolder.",
            position = 28, section = heatmap
    )
    default String wcHeatmapExportFolder() { return "heist-heatmaps"; }
}
