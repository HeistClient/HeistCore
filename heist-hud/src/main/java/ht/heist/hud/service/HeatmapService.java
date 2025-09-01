// ============================================================================
// FILE: HeatmapService.java
// MODULE: heist-hud
// PACKAGE: ht.heist.hud.service
// -----------------------------------------------------------------------------
// TITLE
//   Heatmap Service API — live buffer + persistent store + clear operations
// ============================================================================
package ht.heist.hud.service;

import java.util.List;

public interface HeatmapService
{
    // Simple tap struct; public fields for zero-GC overlay usage
    final class HeatPoint {
        public final int x;
        public final int y;
        public final long ts;
        public HeatPoint(int x, int y, long ts) { this.x = x; this.y = y; this.ts = ts; }
    }

    void start();
    void stop();

    void addLiveTap(int x, int y, long ts);

    List<HeatPoint> getLive(long sinceMs, long nowMs);

    List<HeatPoint> getPersistent();

    // --- New: clear operations -----------------------------------------------
    void clearLive();
    void clearPersistent();

    // Convenience
    default void clearAll() {
        clearLive();
        clearPersistent();
    }
}
