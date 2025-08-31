// ============================================================================
// FILE: HeistCoreConfig.java
// PACKAGE: ht.heist.core.config
// -----------------------------------------------------------------------------
// PURPOSE
//   Central configuration for Heist Core (overlays, recorder, exports, timing).
//
//   Organized sections:
//     • Overlays — Heatmap
//     • Overlays — Cursor Tracer
//     • Humanizer — Mouse Timing      <-- added for canvasMoveSleep min/max
//     • Recorder — Human
//     • Recorder — Macro (Synthetic)
//     • Exports
//
// BUTTON-STYLE TOGGLES
//   - "Export heatmap now": flip ON -> export -> auto-reset to OFF
//   - "Export recorder now": flip ON -> save -> auto-reset to OFF
//
// PATHS
//   - recorderOutputPath:       HUMAN JSON output
//   - recorderExportPathCompat: legacy fallback (used only if Output Path blank)
//   - recorderSyntheticOutputPath: separate JSON for MACRO clicks
//
// NEW (build fix):
//   - heatmapInfinite(): allows HeatmapServiceImpl to keep points forever
//   - canvasMoveSleepMinMs()/canvasMoveSleepMaxMs(): used by MouseServiceImpl
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
    // === SECTION: Overlays — Heatmap ========================================
    // ========================================================================
    @ConfigSection(
            name = "Overlays — Heatmap",
            description = "Click heatmap drawing and export.",
            position = 0
    )
    String secHeatmap = "secHeatmap";

    @ConfigItem(
            keyName = "showHeatmap",
            name = "Show click heatmap",
            description = "Toggle the heatmap overlay on/off.",
            position = 1,
            section = secHeatmap
    )
    default boolean showHeatmap() { return true; }

    @ConfigItem(
            keyName = "heatmapDotRadiusPx",
            name = "Dot radius (px)",
            description = "0 = single pixel; 1 = true 1px dot; etc.",
            position = 2, section = secHeatmap
    )
    default int heatmapDotRadiusPx() { return 1; }

    @ConfigItem(
            keyName = "heatmapFadeMs",
            name = "Dot fade (ms)",
            description = "Lifetime in ms; 0 = infinite (no fade).",
            position = 3, section = secHeatmap
    )
    default int heatmapFadeMs() { return 0; }

    // *** NEW: explicit infinite flag used by HeatmapServiceImpl ***
    @ConfigItem(
            keyName = "heatmapInfinite",
            name = "Infinite heatmap (no fade)",
            description = "If ON, dots never fade regardless of 'Dot fade (ms)'.",
            position = 4, section = secHeatmap
    )
    default boolean heatmapInfinite() { return true; }

    @ConfigItem(
            keyName = "heatmapMaxPoints",
            name = "Max dots in memory",
            description = "Ring buffer cap to avoid unlimited growth.",
            position = 5, section = secHeatmap
    )
    default int heatmapMaxPoints() { return 4000; }

    enum HeatmapColorMode { MONO, HOT_COLD, INFRARED }

    @ConfigItem(
            keyName = "heatmapColorMode",
            name = "Color mode",
            description = "MONO, HOT_COLD, or INFRARED.",
            position = 6, section = secHeatmap
    )
    default HeatmapColorMode heatmapColorMode() { return HeatmapColorMode.INFRARED; }

    @ConfigItem(
            keyName = "heatmapExportFolder",
            name = "Heatmap export folder",
            description = "Where PNGs are written on export.",
            position = 7, section = secHeatmap
    )
    default String heatmapExportFolder()
    {
        return "C:/Users/barhoo/.runelite/heist-heatmaps";
    }

    @ConfigItem(
            keyName = "heatmapExportNow",
            name = "Export heatmap now",
            description = "Flip ON to export a PNG immediately. Auto-resets.",
            position = 8, section = secHeatmap
    )
    default boolean heatmapExportNow() { return false; }

    // ========================================================================
    // === SECTION: Overlays — Cursor Tracer ==================================
    // ========================================================================
    @ConfigSection(
            name = "Overlays — Cursor Tracer",
            description = "Fake cursor tracer overlay settings.",
            position = 10
    )
    String secTracer = "secTracer";

    @ConfigItem(
            keyName = "showCursorTracer",
            name = "Show cursor tracer",
            description = "Toggle the cursor tracer overlay on/off.",
            position = 11, section = secTracer
    )
    default boolean showCursorTracer() { return true; }

    @ConfigItem(
            keyName = "tracerTrailMs",
            name = "Trail lifetime (ms)",
            description = "How long tracer segments persist.",
            position = 12, section = secTracer
    )
    default int tracerTrailMs() { return 900; }

    @ConfigItem(
            keyName = "tracerRingRadiusPx",
            name = "Ring radius (px)",
            description = "Radius of the ring drawn at cursor position.",
            position = 13, section = secTracer
    )
    default int tracerRingRadiusPx() { return 6; }

    // ========================================================================
    // === SECTION: Humanizer — Mouse Timing (used by MouseServiceImpl) =======
    // ========================================================================
    @ConfigSection(
            name = "Humanizer — Mouse Timing",
            description = "Canvas move stepping micro-sleeps.",
            position = 15
    )
    String secTiming = "secTiming";

    // *** NEW: used by MouseServiceImpl.canvasMoveSleepMinMs/MaxMs ***
    @ConfigItem(
            keyName = "canvasMoveSleepMinMs",
            name = "Canvas move sleep MIN (ms)",
            description = "Lower bound of per-step sleep while moving the cursor.",
            position = 16, section = secTiming
    )
    default int canvasMoveSleepMinMs() { return 2; }

    @ConfigItem(
            keyName = "canvasMoveSleepMaxMs",
            name = "Canvas move sleep MAX (ms)",
            description = "Upper bound of per-step sleep while moving the cursor.",
            position = 17, section = secTiming
    )
    default int canvasMoveSleepMaxMs() { return 6; }

    // ========================================================================
    // === SECTION: Recorder — Human ==========================================
    // ========================================================================
    @ConfigSection(
            name = "Recorder — Human",
            description = "Hardware input recorder.",
            position = 20
    )
    String secRecorderHuman = "secRecorderHuman";

    @ConfigItem(
            keyName = "recorderEnabled",
            name = "Enable human recorder",
            description = "Start tracking hardware input.",
            position = 21, section = secRecorderHuman
    )
    default boolean recorderEnabled() { return true; }

    @ConfigItem(
            keyName = "recorderActivityTag",
            name = "Activity tag",
            description = "Label sessions (e.g., DEFAULT, WOODCUTTING).",
            position = 22, section = secRecorderHuman
    )
    default String recorderActivityTag() { return "DEFAULT"; }

    @ConfigItem(
            keyName = "recorderOutputPath",
            name = "Recorder Output Path (human)",
            description = "Primary JSON path for HUMAN input analytics.",
            position = 23, section = secRecorderHuman
    )
    default String recorderOutputPath()
    {
        return "C:/Users/barhoo/.runelite/heist-input/analytics.json";
    }

    @ConfigItem(
            keyName = "recorderExportPathCompat",
            name = "Recorder Export Path (compat)",
            description = "Legacy alias; used only if Output Path is blank.",
            position = 24, section = secRecorderHuman
    )
    default String recorderExportPathCompat()
    {
        return "C:/Users/barhoo/.runelite/heist-input/analytics.json";
    }

    @ConfigItem(
            keyName = "recorderExportNow",
            name = "Export recorder now",
            description = "Flip ON to write the human/synthetic buffers now. Auto-resets.",
            position = 25, section = secRecorderHuman
    )
    default boolean recorderExportNow() { return false; }

    // ========================================================================
    // === SECTION: Recorder — Macro (Synthetic) ===============================
    // ========================================================================
    @ConfigSection(
            name = "Recorder — Macro",
            description = "Capture macro (synthetic) clicks separately.",
            position = 30
    )
    String secRecorderMacro = "secRecorderMacro";

    @ConfigItem(
            keyName = "recordSyntheticClicks",
            name = "Record macro clicks",
            description = "If ON, MouseService can send synthetic clicks to the recorder.",
            position = 31, section = secRecorderMacro
    )
    default boolean recordSyntheticClicks() { return true; }

    @ConfigItem(
            keyName = "recorderSyntheticOutputPath",
            name = "Macro Output Path (synthetic)",
            description = "JSON path for MACRO click analytics.",
            position = 32, section = secRecorderMacro
    )
    default String recorderSyntheticOutputPath()
    {
        return "C:/Users/barhoo/.runelite/heist-input/analytics-synthetic.json";
    }

    // ========================================================================
    // === SECTION: Exports (Other) ============================================
    // ========================================================================
    @ConfigSection(
            name = "Exports",
            description = "Misc one-shot export controls.",
            position = 40
    )
    String secExports = "secExports";
}
