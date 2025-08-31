// ============================================================================
// FILE: HeatmapService.java
// PACKAGE: ht.heist.core.services
// -----------------------------------------------------------------------------
// PURPOSE
//   Interface for click heatmap backend.
//   - Collects click points
//   - Provides snapshots for overlays
//   - Can be enabled/disabled
//   - Can export a PNG snapshot
// ============================================================================
package ht.heist.core.services;

import java.io.IOException;
import java.util.List;

public interface HeatmapService
{
    // Simple immutable DTO for a recorded click
    final class Sample {
        public final int x;
        public final int y;
        public final long tsMillis;
        public Sample(int x, int y, long tsMillis) {
            this.x = x; this.y = y; this.tsMillis = tsMillis;
        }
    }

    // --- Lifecycle / toggles -------------------------------------------------
    void enable();
    void disable();
    boolean isEnabled();
    void enableIfConfigured(); // convenience: reads HeistCoreConfig.showHeatmap()

    // --- Data ----------------------------------------------------------------
    void addClick(int x, int y);           // record a click at canvas coords
    List<Sample> snapshot();               // returns a thread-safe copy

    // --- Export --------------------------------------------------------------
    String exportSnapshot() throws IOException; // writes PNG, returns absolute path
}
