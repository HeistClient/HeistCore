// ============================================================================
// HeistCoreConfig.java
// -----------------------------------------------------------------------------
// Purpose
//   Global core config used by MouseServiceImpl, CameraServiceImpl, and
//   core UI overlays (e.g., CursorTracerOverlay).
//   - Mouse timing: tiny settle after MOVE, press/hold, reaction delay
//   - Human touches: overshoot / fatigue
//   - Cursor Tracer (global): fake cursor ring + fading trail
//
// Notes
//   • Config group id stays "heistcore" (plugins @Provides should match).
//   • Positions are spaced so future options can slot in easily.
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

    // -- Tiny settle after MOVE (used by MouseServiceImpl) --------------------
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

    // -- Press/hold window ----------------------------------------------------
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

    // -- Reaction (MOVE → PRESS) ---------------------------------------------
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

    // -- Overshoot ------------------------------------------------------------
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

    // -- Fatigue --------------------------------------------------------------
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

    // -- Master toggle --------------------------------------------------------
    @ConfigItem(
            keyName = "showCursorTracer",
            name = "Show cursor tracer",
            description = "Draw a fake cursor ring with a fading trail.",
            position = 91, section = cursorTracer
    )
    default boolean showCursorTracer() { return true; }

    // -- Trail lifetime -------------------------------------------------------
    @ConfigItem(
            keyName = "tracerTrailMs",
            name = "Trail lifetime (ms)",
            description = "How long each tail segment persists before fading out.",
            position = 92, section = cursorTracer
    )
    default int tracerTrailMs() { return 900; }

    // -- Ring radius ----------------------------------------------------------
    @ConfigItem(
            keyName = "tracerRingRadiusPx",
            name = "Ring radius (px)",
            description = "Radius of the fake cursor ring drawn at the mouse.",
            position = 93, section = cursorTracer
    )
    default int tracerRingRadiusPx() { return 6; }
}
