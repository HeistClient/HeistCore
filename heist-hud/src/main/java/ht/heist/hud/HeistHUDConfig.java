// ============================================================================
// FILE: HeistHUDConfig.java
// MODULE: heist-hud
// PACKAGE: ht.heist.hud
// -----------------------------------------------------------------------------
// TITLE
//   Heist HUD Config — overlays, palettes, JSONL ingest, PNG export, actions
//
// WHAT THIS PROVIDES
//   • Overlays: show/hide heatmap & cursor tracer
//   • Heatmap: palette (RED or INFRARED), dot radius, live decay window, layers
//   • Ingest: JSONL tail on/off + file path (with %USERPROFILE% expansion)
//   • Export: one-click PNG export (dimensions, kernel radius, output folder)
//   • Actions: one-click “Clear dots now” that erases ALL heatmap points
// ============================================================================
package ht.heist.hud;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("heist-hud")
public interface HeistHUDConfig extends Config
{
    // Palettes used by overlay/export
    enum Palette {
        RED,        // mono red fade
        INFRARED    // cool→hot ramp (blue/green/yellow/orange/red/white)
    }

    // Where to paint points from
    enum HeatmapSource {
        LIVE_ONLY,
        JSONL_ONLY,
        LIVE_PLUS_JSONL
    }

    // Sections
    @ConfigSection(name = "Overlays", description = "High-level HUD toggles", position = 0)
    String sectionOverlays = "overlays";

    @ConfigSection(name = "Cursor Tracer", description = "Fading trail and ring", position = 10)
    String sectionTracer = "cursorTracer";

    @ConfigSection(name = "Heatmap", description = "Rendering & behavior", position = 20)
    String sectionHeatmap = "heatmap";

    @ConfigSection(name = "Ingest", description = "External inputs (JSONL tail)", position = 30)
    String sectionIngest = "ingest";

    @ConfigSection(name = "Export", description = "PNG export settings", position = 40)
    String sectionExport = "export";

    @ConfigSection(name = "Actions", description = "One-click commands", position = 50)
    String sectionActions = "actions";

    // Overlays
    @ConfigItem(
            keyName = "showCursorTracer",
            name = "Show cursor tracer",
            description = "Draw a fading trail and ring around the cursor.",
            position = 0, section = sectionOverlays
    )
    default boolean showCursorTracer() { return true; }

    @ConfigItem(
            keyName = "showHeatmap",
            name = "Show heatmap",
            description = "Render a click heatmap on the game canvas.",
            position = 1, section = sectionOverlays
    )
    default boolean showHeatmap() { return true; }

    // Cursor Tracer
    @Range(min = 100, max = 3000)
    @ConfigItem(
            keyName = "tracerTrailMs",
            name = "Trail length (ms)",
            description = "How long the trail lingers before fading.",
            position = 0, section = sectionTracer
    )
    default int tracerTrailMs() { return 900; }

    @Range(min = 2, max = 40)
    @ConfigItem(
            keyName = "tracerRingRadiusPx",
            name = "Ring radius (px)",
            description = "Radius of the cursor ring.",
            position = 1, section = sectionTracer
    )
    default int tracerRingRadiusPx() { return 6; }

    // Heatmap
    @ConfigItem(
            keyName = "infraredPalette",
            name = "Infrared palette (toggle)",
            description = "If true, palette() resolves to INFRARED; else RED.",
            position = 0, section = sectionHeatmap
    )
    default boolean infraredPalette() { return true; }

    @ConfigItem(
            keyName = "palette",
            name = "Palette (resolved)",
            description = "Color palette used by renderer/exporter.",
            position = 1, section = sectionHeatmap
    )
    default Palette palette() { return infraredPalette() ? Palette.INFRARED : Palette.RED; }

    @Range(min = 1, max = 20)
    @ConfigItem(
            keyName = "heatmapDotRadiusPx",
            name = "Dot radius (px)",
            description = "Radius of each heatmap dot.",
            position = 2, section = sectionHeatmap
    )
    default int heatmapDotRadiusPx() { return 4; }

    @ConfigItem(
            keyName = "showPersistentLayer",
            name = "Show persistent layer",
            description = "Draw long-lived dots that never decay.",
            position = 3, section = sectionHeatmap
    )
    default boolean showPersistentLayer() { return true; }

    @ConfigItem(
            keyName = "showLiveLayer",
            name = "Show live layer",
            description = "Draw points captured during this session (with fade).",
            position = 4, section = sectionHeatmap
    )
    default boolean showLiveLayer() { return true; }

    @Range(min = 1_000, max = 3_600_000)
    @ConfigItem(
            keyName = "liveDecayMs",
            name = "Live decay window (ms)",
            description = "How long a live dot remains before fully fading.",
            position = 5, section = sectionHeatmap
    )
    default int liveDecayMs() { return 8_000; }

    // Ingest
    @ConfigItem(
            keyName = "enableJsonlIngest",
            name = "Read JSONL taps",
            description = "Tail a JSONL file and feed taps into the heatmap.",
            position = 0, section = sectionIngest
    )
    default boolean enableJsonlIngest() { return true; }

    @ConfigItem(
            keyName = "syntheticInputPath",
            name = "JSONL path",
            description = "Path to synthetic taps file. %USERPROFILE% expands to your home dir.",
            position = 1, section = sectionIngest
    )
    default String syntheticInputPath() { return "%USERPROFILE%/.runelite/heist-input/clicks.jsonl"; }

    @ConfigItem(
            keyName = "heatmapSource",
            name = "Heatmap source",
            description = "Which source(s) to render (legacy compatibility).",
            position = 2, section = sectionIngest
    )
    default HeatmapSource heatmapSource() {
        return enableJsonlIngest() ? HeatmapSource.LIVE_PLUS_JSONL : HeatmapSource.LIVE_ONLY;
    }

    @ConfigItem(
            keyName = "heatmapJsonlPath",
            name = "JSONL path (legacy key)",
            description = "Legacy key; mirrors JSONL path above.",
            position = 3, section = sectionIngest
    )
    default String heatmapJsonlPath() { return syntheticInputPath(); }

    // Export
    @ConfigItem(
            keyName = "exportNow",
            name = "Export heatmap now",
            description = "Turn ON to export a PNG; it will turn itself OFF after saving.",
            position = 0, section = sectionExport
    )
    default boolean exportNow() { return false; }

    @ConfigItem(
            keyName = "exportIncludeLive",
            name = "Include LIVE layer",
            description = "If ON, the PNG includes the current live buffer as well.",
            position = 1, section = sectionExport
    )
    default boolean exportIncludeLive() { return true; }

    @Range(min = 64, max = 4000)
    @ConfigItem(
            keyName = "exportWidth",
            name = "PNG width",
            description = "Output image width.",
            position = 2, section = sectionExport
    )
    default int exportWidth() { return 765; }

    @Range(min = 64, max = 4000)
    @ConfigItem(
            keyName = "exportHeight",
            name = "PNG height",
            description = "Output image height.",
            position = 3, section = sectionExport
    )
    default int exportHeight() { return 503; }

    @Range(min = 1, max = 20)
    @ConfigItem(
            keyName = "exportKernelRadius",
            name = "Kernel radius (px)",
            description = "Spread per point for density bake.",
            position = 4, section = sectionExport
    )
    default int exportKernelRadius() { return 4; }

    @ConfigItem(
            keyName = "exportDir",
            name = "Export folder",
            description = "Folder where PNGs are written.",
            position = 5, section = sectionExport
    )
    default String exportDir() { return "%USERPROFILE%/.runelite/heist-output"; }

    // Actions
    @ConfigItem(
            keyName = "clearAllNow",
            name = "Clear dots now",
            description = "Turn ON to erase both LIVE and PERSISTENT points. Resets to OFF.",
            position = 0, section = sectionActions
    )
    default boolean clearAllNow() { return false; }
}
