// ============================================================================
// FILE: HeistHUDConfig.java
// MODULE: heist-hud
// PACKAGE: ht.heist.hud
// -----------------------------------------------------------------------------
// TITLE
//   HeistHUDConfig — overlay + export + tracer configuration (complete)
//
// PURPOSE
//   Centralize all small HUD knobs used by:
//     • ClickDotsOverlay (persistent dots)
//     • CursorTracerOverlay (ring + short trail)
//     • HeatmapExporter (IR / Red density PNG)
//     • Output folder and CSV toggle for logs
//
// DESIGN
//   Keep options minimal and self-explanatory. Tracer has its own tiny section.
//   Dots/tracer overlays are ALWAYS registered by the plugin; rendering is
//   self-gated here so toggling is instant without reloading the plugin.
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
    // Palettes used by exporter (and optional preview)
    enum Palette { RED, INFRARED }

    // ---- Sections (purely for UI grouping) ---------------------------------
    @ConfigSection(
            name = "Dots Overlay",
            description = "Persistent click dots drawn on the canvas",
            position = 0
    )
    String sectionDots = "dots";

    @ConfigSection(
            name = "Cursor Tracer",
            description = "Cursor ring + short fading trail",
            position = 10
    )
    String sectionTracer = "tracer";

    @ConfigSection(
            name = "Export & Logging",
            description = "PNG palette, output folder, CSV toggle",
            position = 20
    )
    String sectionExport = "export";

    // ---- Dots overlay (persistent) -----------------------------------------
    @ConfigItem(
            keyName = "showDots",
            name = "Show click dots",
            description = "Draw a solid dot on the canvas for every click event.",
            position = 0, section = sectionDots
    )
    default boolean showDots() { return true; }

    @Range(min = 1, max = 20)
    @ConfigItem(
            keyName = "dotRadiusPx",
            name = "Dot radius (px)",
            description = "Radius of each dot in the overlay.",
            position = 1, section = sectionDots
    )
    default int dotRadiusPx() { return 4; }

    // ---- Cursor tracer (ring + short trail) --------------------------------
    @ConfigItem(
            keyName = "showCursorTracer",
            name = "Show cursor tracer",
            description = "Draw a fading trail and a ring around the cursor.",
            position = 0, section = sectionTracer
    )
    default boolean showCursorTracer() { return false; }

    @Range(min = 100, max = 3000)
    @ConfigItem(
            keyName = "tracerTrailMs",
            name = "Trail length (ms)",
            description = "How long the tracer trail lingers before fading out.",
            position = 1, section = sectionTracer
    )
    default int tracerTrailMs() { return 900; }

    @Range(min = 2, max = 40)
    @ConfigItem(
            keyName = "tracerRingRadiusPx",
            name = "Ring radius (px)",
            description = "Radius of the cursor ring.",
            position = 2, section = sectionTracer
    )
    default int tracerRingRadiusPx() { return 6; }

    // ---- Export & logging ---------------------------------------------------
    @ConfigItem(
            keyName = "infrared",
            name = "Infrared palette (PNG)",
            description = "Use IR-style color ramp for PNG exports (hot/cold).",
            position = 0, section = sectionExport
    )
    default boolean infrared() { return true; }

    @ConfigItem(
            keyName = "palette",
            name = "Palette",
            description = "Export palette for density PNG.",
            position = 1, section = sectionExport
    )
    default Palette palette() { return infrared() ? Palette.INFRARED : Palette.RED; }

    @ConfigItem(
            keyName = "outputDir",
            name = "Output directory",
            description = "Folder for exports and logs. %USERPROFILE% expands to your home.",
            position = 2, section = sectionExport
    )
    default String outputDir() { return "%USERPROFILE%/.runelite/heist-output"; }

    @ConfigItem(
            keyName = "writeCsv",
            name = "Also write CSV logs",
            description = "Alongside JSONL, write CSV rows to disk for spreadsheets.",
            position = 3, section = sectionExport
    )
    default boolean writeCsv() { return true; }
}
