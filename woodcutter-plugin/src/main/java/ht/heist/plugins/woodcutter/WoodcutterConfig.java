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
}
