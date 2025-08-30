// ============================================================================
// HeistCoreConfig.java
// -----------------------------------------------------------------------------
// Purpose
//   Global core config used by MouseServiceImpl, CameraServiceImpl, and
//   core UI overlays (CursorTracerOverlay, CoreHeatmapOverlay).
//   - Mouse timing: tiny settle after MOVE, press/hold, reaction delay
//   - Human touches: overshoot / fatigue
//   - Cursor Tracer (global): fake cursor ring + fading trail
//   - Heatmap (global): tiny dots, hot/cold color mode, optional periodic export
// ============================================================================

package ht.heist.core.config;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("heistcore")
public interface HeistCoreConfig extends Config
{
    // ========================================================================
    // === Section: Mouse Timing ==============================================
    // ========================================================================
    @ConfigSection(
            name = "Mouse Timing",
            description = "Timings to mimic legacy behavior.",
            position = 0
    )
    String mouseTiming = "mouseTiming";

    @ConfigItem(
            keyName = "canvasMoveSleepMinMs",
            name = "Move pause min (ms)",
            description = "Minimum pause right after MOUSE_MOVED.",
            position = 1, section = mouseTiming
    )
    default int canvasMoveSleepMinMs() { return 15; }

    @ConfigItem(
            keyName = "canvasMoveSleepMaxMs",
            name = "Move pause max (ms)",
            description = "Maximum pause right after MOUSE_MOVED.",
            position = 2, section = mouseTiming
    )
    default int canvasMoveSleepMaxMs() { return 30; }

    @ConfigItem(
            keyName = "pressHoldMinMs",
            name = "Press hold min (ms)",
            description = "Minimum duration to hold LMB down.",
            position = 3, section = mouseTiming
    )
    default int pressHoldMinMs() { return 30; }

    @ConfigItem(
            keyName = "pressHoldMaxMs",
            name = "Press hold max (ms)",
            description = "Maximum duration to hold LMB down.",
            position = 4, section = mouseTiming
    )
    default int pressHoldMaxMs() { return 55; }

    @ConfigItem(
            keyName = "reactionMeanMs",
            name = "Reaction mean (ms)",
            description = "Average delay between MOVE and PRESS.",
            position = 5, section = mouseTiming
    )
    default int reactionMeanMs() { return 120; }

    @ConfigItem(
            keyName = "reactionStdMs",
            name = "Reaction std dev (ms)",
            description = "Std deviation for the MOVE→PRESS delay.",
            position = 6, section = mouseTiming
    )
    default int reactionStdMs() { return 40; }

    // ========================================================================
    // === Section: Human Touches =============================================
    // ========================================================================
    @ConfigSection(
            name = "Human Touches",
            description = "Optional human-like behaviors.",
            position = 10
    )
    String human = "human";

    @ConfigItem(
            keyName = "enableOvershoot",
            name = "Enable overshoot",
            description = "Occasionally overshoot past target then settle back.",
            position = 11, section = human
    )
    default boolean enableOvershoot() { return true; }

    @ConfigItem(
            keyName = "overshootChancePct",
            name = "Overshoot chance (%)",
            description = "Chance per click to overshoot and settle.",
            position = 12, section = human
    )
    default int overshootChancePct() { return 12; } // ~1 in 8

    @ConfigItem(
            keyName = "enableFatigue",
            name = "Enable fatigue",
            description = "Slowly increases delays over session time.",
            position = 13, section = human
    )
    default boolean enableFatigue() { return true; }

    @ConfigItem(
            keyName = "fatigueMaxPct",
            name = "Max fatigue (+%)",
            description = "Max % increase to reaction/hold after long sessions.",
            position = 14, section = human
    )
    default int fatigueMaxPct() { return 20; } // up to +20%

    // ========================================================================
    // === Section: Cursor Tracer (GLOBAL) ====================================
    // ========================================================================
    @ConfigSection(
            name = "Cursor Tracer",
            description = "Global fake cursor ring + fading trail (core overlay).",
            position = 90
    )
    String cursorTracer = "cursorTracer";

    @ConfigItem(
            keyName = "showCursorTracer",
            name = "Show cursor tracer",
            description = "Draw a fake cursor ring with a fading trail.",
            position = 91, section = cursorTracer
    )
    default boolean showCursorTracer() { return true; }

    @ConfigItem(
            keyName = "tracerTrailMs",
            name = "Trail lifetime (ms)",
            description = "How long each tail segment persists before fading out.",
            position = 92, section = cursorTracer
    )
    default int tracerTrailMs() { return 900; }

    @ConfigItem(
            keyName = "tracerRingRadiusPx",
            name = "Ring radius (px)",
            description = "Radius of the fake cursor ring drawn at the mouse.",
            position = 93, section = cursorTracer
    )
    default int tracerRingRadiusPx() { return 6; }

    // ========================================================================
    // === Section: Heatmap (GLOBAL) ==========================================
    // ========================================================================
    @ConfigSection(
            name = "Heatmap",
            description = "Global click heatmap (core overlay).",
            position = 95
    )
    String heatmap = "heatmap";

    // Master toggle
    @ConfigItem(
            keyName = "showHeatmap",
            name = "Show click heatmap",
            description = "Draw a tiny dot for every mouse click.",
            position = 96, section = heatmap
    )
    default boolean showHeatmap() { return true; }

    // --- Visuals (new names used by core overlay) ----------------------------
    @ConfigItem(
            keyName = "heatmapDotSizePx",
            name = "Dot size (px)",
            description = "Width/height of each click dot (1 = single pixel).",
            position = 97, section = heatmap
    )
    default int heatmapDotSizePx() { return 1; }

    @ConfigItem(
            keyName = "heatmapDecayMs",
            name = "Dot fade (ms)",
            description = "How long each dot stays visible before fully fading.",
            position = 98, section = heatmap
    )
    default int heatmapDecayMs() { return 900; }

    @ConfigItem(
            keyName = "heatmapColdColor",
            name = "Cold color (hex)",
            description = "Hex color for low-density areas (e.g. #1e90ff).",
            position = 99, section = heatmap
    )
    default String heatmapColdColor() { return "#1e90ff"; } // DodgerBlue

    @ConfigItem(
            keyName = "heatmapHotColor",
            name = "Hot color (hex)",
            description = "Hex color for high-density areas (e.g. #ff4000).",
            position = 100, section = heatmap
    )
    default String heatmapHotColor() { return "#ff4000"; }  // Orange-Red

    @ConfigItem(
            keyName = "heatmapAlpha",
            name = "Dot alpha (0-255)",
            description = "Transparency for dots; higher = more opaque.",
            position = 101, section = heatmap
    )
    default int heatmapAlpha() { return 200; }

    @ConfigItem(
            keyName = "heatmapMaxPoints",
            name = "Max dots",
            description = "Upper bound on stored dots (memory/CPU guard).",
            position = 102, section = heatmap
    )
    default int heatmapMaxPoints() { return 20000; }

    enum HeatmapColorMode { MONO, HOT_COLD }

    @ConfigItem(
            keyName = "heatmapColorMode",
            name = "Color mode",
            description = "MONO = one color; HOT_COLD = blue→red by local density.",
            position = 103, section = heatmap
    )
    default HeatmapColorMode heatmapColorMode() { return HeatmapColorMode.HOT_COLD; }

    // --- Export options ------------------------------------------------------
    @ConfigItem(
            keyName = "heatmapExportEnabled",
            name = "Enable periodic export",
            description = "If enabled, the core heatmap overlay will write out PNG snapshots.",
            position = 104, section = heatmap
    )
    default boolean heatmapExportEnabled() { return false; }

    @ConfigItem(
            keyName = "heatmapExportEveryMs",
            name = "Export interval (ms)",
            description = "How often to save a heatmap PNG while enabled.",
            position = 105, section = heatmap
    )
    default int heatmapExportEveryMs() { return 15000; } // 15s

    @ConfigItem(
            keyName = "heatmapExportFolder",
            name = "Export folder",
            description = "Absolute or relative folder to write PNGs into.",
            position = 106, section = heatmap
    )
    default String heatmapExportFolder() { return "heist-heatmaps"; }

    // ------------------------------------------------------------------------
    // Backward-compat convenience (aliases to old names you previously used)
    // These keep older code/readers happy while core uses the new names above.
    // ------------------------------------------------------------------------
    @ConfigItem(
            keyName = "heatmapDotRadiusPx",
            name = "Dot radius (px)",
            description = "Deprecated: use Dot size (px).",
            position = 107, section = heatmap
    )
    default int heatmapDotRadiusPx() { return Math.max(1, heatmapDotSizePx() / 2); }

    @ConfigItem(
            keyName = "heatmapFadeMs",
            name = "Dot fade (ms) [compat]",
            description = "Deprecated: use Dot fade (ms) in new section.",
            position = 108, section = heatmap
    )
    default int heatmapFadeMs() { return heatmapDecayMs(); }
}
