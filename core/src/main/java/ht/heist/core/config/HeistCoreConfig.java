// ============================================================================
// HeistCoreConfig.java
// -----------------------------------------------------------------------------
// Purpose
//   Minimal, live knobs used by MouseServiceImpl. Old/unused options removed.
//   - Move pause (tiny settle after MOVE)
//   - Press/hold window
//   - Reaction delay (MOVE -> PRESS)
//   - Optional human touches: overshoot + fatigue
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
    // Section: Mouse Timing
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
    // Section: Human Touches
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
}
