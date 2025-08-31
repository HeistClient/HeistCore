// ============================================================================
// FILE: HeistHUDConfig.java
// PACKAGE: ht.heist.hud
// -----------------------------------------------------------------------------
// TITLE
//   HUD Configuration — single source of truth for all toggles/paths.
//
// WHAT LIVES HERE
//   • Heatmap visibility & appearance (infrared, layers, radius)
//   • Live layer decay window (ms)
//   • Ingest toggles (human/synthetic)
//   • "Record human clicks" (write JSONL) + file paths
//   • Export buttons + export folder
//   • Cursor tracer toggles (ring + trail)
//
// IMPORTANT NAMING CONTRACT
//   - These method names are referenced from overlays/services/plugins.
//     Keep them EXACT to avoid "cannot find symbol" errors when compiling.
//   - Toggle semantics:
//       * ingestHuman() / ingestSynthetic()  => INPUT GATES (what we accept)
//       * recordHumanClicks()                => RECORDING ONLY (not rendering)
// ============================================================================

package ht.heist.hud;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("heisthud")
public interface HeistHUDConfig extends Config
{
    // ===== Section: Heatmap ==================================================
    @ConfigSection(
            name = "Heatmap",
            description = "Infrared heatmap controls",
            position = 0
    )
    String heatmap = "heatmap";

    @ConfigItem(
            keyName = "showLiveLayer",
            name = "Show live layer",
            description = "Show the 'live' layer (recent clicks only; ages out by the window below).",
            position = 1,
            section = heatmap
    )
    default boolean showLiveLayer() { return true; }

    @ConfigItem(
            keyName = "showPersistentLayer",
            name = "Show persistent layer",
            description = "Show the persistent (infinite memory) layer. Use 'Clear' to wipe.",
            position = 2,
            section = heatmap
    )
    default boolean showPersistentLayer() { return true; }

    @ConfigItem(
            keyName = "liveDecayMs",
            name = "Live decay window (ms)",
            description = "How long a click remains in the 'live' layer (infrared age ramp).",
            position = 3,
            section = heatmap
    )
    default int liveDecayMs() { return 2000; } // ~0–2s “hot zone” for the live ramp

    @ConfigItem(
            keyName = "heatmapDotRadiusPx",
            name = "Dot radius (px)",
            description = "Radius used to draw dots for both layers.",
            position = 4,
            section = heatmap
    )
    default int heatmapDotRadiusPx() { return 1; } // your preferred default

    // ===== Section: Ingest & Record =========================================
    @ConfigSection(
            name = "Ingest & Record",
            description = "Choose which events become dots, and whether to write human clicks to JSONL.",
            position = 10
    )
    String ingest = "ingest";

    @ConfigItem(
            keyName = "ingestHuman",
            name = "Accept HUMAN clicks",
            description = "If ON, HUMAN clicks are added into the heatmap model. (Rendering is separate.)",
            position = 11,
            section = ingest
    )
    default boolean ingestHuman() { return true; }

    @ConfigItem(
            keyName = "ingestSynthetic",
            name = "Accept SYNTHETIC taps",
            description = "If ON, taps from CoreLocator sink are added into the heatmap. (Rendering is separate.)",
            position = 12,
            section = ingest
    )
    default boolean ingestSynthetic() { return true; }

    @ConfigItem(
            keyName = "recordHumanClicks",
            name = "Record HUMAN clicks to JSONL",
            description = "If ON, HUMAN clicks are also written to JSONL at the path below.",
            position = 13,
            section = ingest
    )
    default boolean recordHumanClicks() { return false; }

    @ConfigItem(
            keyName = "humanClicksJsonlPath",
            name = "Human clicks JSONL path",
            description = "Where to store HUMAN click events (one JSON per line).",
            position = 14,
            section = ingest
    )
    default String humanClicksJsonlPath()
    {
        // Use %USERPROFILE%/home for portability (works on all machines)
        return System.getProperty("user.home") + "/.runelite/heist-input/recorder-human.jsonl";
    }

    @ConfigItem(
            keyName = "syntheticClicksJsonlPath",
            name = "Synthetic taps JSONL path",
            description = "Optional: where synthetic taps are written (if any) — used by a tailer.",
            position = 15,
            section = ingest
    )
    default String syntheticClicksJsonlPath()
    {
        return System.getProperty("user.home") + "/.runelite/heist-input/clicks.jsonl";
    }

    // ===== Section: Export / Clear ==========================================
    @ConfigSection(
            name = "Export / Clear",
            description = "Export current heatmap or clear all dots.",
            position = 20
    )
    String export = "export";

    @ConfigItem(
            keyName = "exportFolder",
            name = "Export folder",
            description = "Folder to write PNG/JSON exports (relative paths are under %USERPROFILE%/.runelite).",
            position = 21,
            section = export
    )
    default String exportFolder()
    {
        // If relative, service will resolve under %USERPROFILE%/.runelite
        return "heist-exports";
    }

    @ConfigItem(
            keyName = "exportPngNow",
            name = "Export PNG (click to run)",
            description = "Writes an image of the current heatmap (button). Auto-resets to OFF.",
            position = 22,
            section = export
    )
    default boolean exportPngNow() { return false; }

    @ConfigItem(
            keyName = "exportJsonNow",
            name = "Export JSON (click to run)",
            description = "Writes all heatmap points to JSON (button). Auto-resets to OFF.",
            position = 23,
            section = export
    )
    default boolean exportJsonNow() { return false; }

    @ConfigItem(
            keyName = "clearNow",
            name = "Clear dots (click to run)",
            description = "Clears ALL persistent & live dots (button). Auto-resets to OFF.",
            position = 24,
            section = export
    )
    default boolean clearNow() { return false; }

    // ===== Section: Cursor Tracer ===========================================
    @ConfigSection(
            name = "Cursor Tracer",
            description = "Ring + trail overlay settings.",
            position = 30
    )
    String tracer = "tracer";

    @ConfigItem(
            keyName = "showCursorTracer",
            name = "Show cursor tracer",
            description = "Display the ring + trail overlay.",
            position = 31,
            section = tracer
    )
    default boolean showCursorTracer() { return true; }

    @ConfigItem(
            keyName = "tracerTrailMs",
            name = "Trail lifetime (ms)",
            description = "How long each trail segment persists.",
            position = 32,
            section = tracer
    )
    default int tracerTrailMs() { return 900; }

    @ConfigItem(
            keyName = "tracerRingRadiusPx",
            name = "Ring radius (px)",
            description = "Radius of the cursor ring.",
            position = 33,
            section = tracer
    )
    default int tracerRingRadiusPx() { return 6; }
}
