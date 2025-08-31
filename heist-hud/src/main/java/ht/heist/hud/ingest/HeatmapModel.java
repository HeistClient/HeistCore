// ============================================================================
// FILE: HeatmapModel.java
// PACKAGE: ht.heist.hud.ingest
// -----------------------------------------------------------------------------
// TITLE
//   HeatmapModel (simplified helper) — shared helpers for dot radius and
//   infrared color mapping. No "max points", no color modes.
//
// WHY THIS EXISTS
//   • Some parts of HUD referenced HeatmapModel; we keep a small, clean
//     version so those references compile without needing caps/trim logic.
// ============================================================================

package ht.heist.hud.ingest;

import ht.heist.hud.HeistHUDConfig;

import java.awt.Color;

public final class HeatmapModel
{
    private HeatmapModel() {}

    // --- CONFIG PASSTHROUGHS -------------------------------------------------
    public static int dotRadiusPx(HeistHUDConfig cfg)
    {
        return Math.max(0, cfg.heatmapDotRadiusPx());
    }

    // --- INFRARED COLOR MAPPING ----------------------------------------------
    /**
     * Map age (0..windowMs) to an infrared color.
     *  age=0   → bright yellow/white
     *  age=win → deep red
     */
    public static Color infrared(long ageMs, int windowMs)
    {
        if (windowMs <= 0) windowMs = 1;
        float t = clamp01(ageMs / (float) windowMs); // 0=new → 1=old
        float r = 1f;
        float g = 1f - 0.8f * t;
        float b = 0.2f * (1f - t);
        return new Color(clamp01(r), clamp01(g), clamp01(b), 1f);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
