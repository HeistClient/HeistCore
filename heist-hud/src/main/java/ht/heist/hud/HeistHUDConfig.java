// (REPLACE the previous file with this version)
package ht.heist.hud;

import net.runelite.client.config.*;
import java.nio.file.Paths;

@ConfigGroup("heist-hud")
public interface HeistHUDConfig extends Config
{
    // ----- Master toggles ----------------------------------------------------
    @ConfigItem(
            keyName = "showHeatmap",
            name = "Show heatmap (master)",
            description = "Toggle the heatmap overlay on/off.",
            position = 0
    )
    default boolean showHeatmap() { return true; }

    @ConfigItem(
            keyName = "showCursorTracer",
            name = "Show cursor tracer",
            description = "Draw a small ring at the mouse and a short trail.",
            position = 1
    )
    default boolean showCursorTracer() { return true; }

    // ----- Heatmap style -----------------------------------------------------
    @ConfigItem(
            keyName = "heatmapInfraredPalette",
            name = "Infrared palette",
            description = "Use blue→green→yellow→red instead of plain red.",
            position = 2
    )
    default boolean heatmapInfraredPalette() { return true; }

    // ----- Heatmap behavior --------------------------------------------------
    @Range(min = 250, max = 6000)
    @ConfigItem(
            keyName = "liveDecayMs",
            name = "Live decay (ms)",
            description = "How long a tap remains in the 'live' layer.",
            position = 3
    )
    default int liveDecayMs() { return 1500; }

    @Range(min = 1, max = 12)
    @ConfigItem(
            keyName = "heatmapDotRadiusPx",
            name = "Dot radius (px)",
            description = "Radius for heatmap dots.",
            position = 4
    )
    default int heatmapDotRadiusPx() { return 3; }

    @ConfigItem(
            keyName = "showPersistentLayer",
            name = "Show persistent layer",
            description = "Shows all taps since plugin start (can get heavy).",
            position = 5
    )
    default boolean showPersistentLayer() { return false; }

    // ----- Cursor tracer controls -------------------------------------------
    @Range(min = 100, max = 5000)
    @ConfigItem(
            keyName = "tracerTrailMs",
            name = "Tracer trail (ms)",
            description = "How long to keep tail points for the cursor tracer.",
            position = 10
    )
    default int tracerTrailMs() { return 900; }

    @Range(min = 2, max = 20)
    @ConfigItem(
            keyName = "tracerRingRadiusPx",
            name = "Cursor ring radius (px)",
            description = "Small ring size drawn at the cursor.",
            position = 11
    )
    default int tracerRingRadiusPx() { return 6; }

    // ----- Ingest (JSONL) ----------------------------------------------------
    @ConfigItem(
            keyName = "enableJsonlIngest",
            name = "Read synthetic taps from file",
            description = "Tail %USERPROFILE%/.runelite/heist-input/clicks.jsonl and render taps.",
            position = 20
    )
    default boolean enableJsonlIngest() { return true; }

    @ConfigItem(
            keyName = "syntheticInputPath",
            name = "Synthetic JSONL path",
            description = "Full path to clicks.jsonl you want the HUD to read.",
            position = 21
    )
    default String syntheticInputPath()
    {
        String home = System.getProperty("user.home"); // portable USERPROFILE
        return Paths.get(home, ".runelite", "heist-input", "clicks.jsonl").toString();
    }
}
