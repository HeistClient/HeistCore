// ============================================================================
// HeistCoreConfig.java
// -----------------------------------------------------------------------------
// Purpose
//   Keep the minimal timing knobs that match the old behavior:
//   - Short per-move settle
//   - Press-hold window
//   - “Reaction” mean/std used between MOVE and PRESS
// ============================================================================

package ht.heist.core.config;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("heistcore")
public interface HeistCoreConfig extends Config
{
    // --- Section: Mouse Timing ----------------------------------------------
    @ConfigSection(
            name = "Mouse Timing",
            description = "Simple timings to mimic legacy behavior.",
            position = 0
    )
    String mouseTiming = "mouseTiming";

    // tiny settle after move (used by move-only)
    @ConfigItem(
            keyName = "canvasMoveSleepMinMs",
            name = "Move pause min (ms)",
            description = "Minimum pause right after MOUSE_MOVED.",
            position = 1,
            section = mouseTiming
    )
    default int canvasMoveSleepMinMs() { return 15; }

    @ConfigItem(
            keyName = "canvasMoveSleepMaxMs",
            name = "Move pause max (ms)",
            description = "Maximum pause right after MOUSE_MOVED.",
            position = 2,
            section = mouseTiming
    )
    default int canvasMoveSleepMaxMs() { return 30; }

    // press-hold (helps stability)
    @ConfigItem(
            keyName = "pressHoldMinMs",
            name = "Press hold min (ms)",
            description = "Minimum duration to hold the mouse button down.",
            position = 3,
            section = mouseTiming
    )
    default int pressHoldMinMs() { return 30; }

    @ConfigItem(
            keyName = "pressHoldMaxMs",
            name = "Press hold max (ms)",
            description = "Maximum duration to hold the mouse button down.",
            position = 4,
            section = mouseTiming
    )
    default int pressHoldMaxMs() { return 55; }

    // “reaction” between MOVE and PRESS (old HumanBehavior used gaussian wait)
    @ConfigItem(
            keyName = "reactionMeanMs",
            name = "Reaction mean (ms)",
            description = "Average delay between MOVE and PRESS (legacy behavior).",
            position = 5,
            section = mouseTiming
    )
    default int reactionMeanMs() { return 120; } // tune as needed

    @ConfigItem(
            keyName = "reactionStdMs",
            name = "Reaction std dev (ms)",
            description = "Std deviation for delay between MOVE and PRESS.",
            position = 6,
            section = mouseTiming
    )
    default int reactionStdMs() { return 40; }
}
