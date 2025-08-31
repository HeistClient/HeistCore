// ============================================================================
// FILE: HeatmapService.java
// PACKAGE: ht.heist.hud.service
// -----------------------------------------------------------------------------
// TITLE
//   Service interface used by overlays + plugins to push and read tap points.
// ============================================================================
package ht.heist.hud.service;

import java.util.List;

public interface HeatmapService
{
    // Configuration hints (overlays can read current knobs from config)
    int liveDecayMs();

    // Acceptors (plugins call these)
    void addHumanTap(int x, int y, long ts);
    void addSyntheticTap(int x, int y, long ts);

    // Readers (overlays call these)
    List<HeatPoint> getPersistent();
    List<HeatPoint> getLive(long nowMs, long decayMs);

    // Admin
    void clearAll();
    void setAcceptHuman(boolean on);
    void setAcceptSynthetic(boolean on);
}
