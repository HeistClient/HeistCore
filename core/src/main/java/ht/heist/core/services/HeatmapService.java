package ht.heist.core.services;

import java.util.List;

public interface HeatmapService
{
    // Turn overlay logic on/off according to config (convenience)
    void enableIfConfigured();
    void enable();
    void disable();
    boolean isEnabled();

    // Record an actual click location (canvas pixels)
    void addClick(int x, int y);

    // Snapshot for rendering (immutable / safe to iterate)
    List<PointSample> snapshot();

    // Struct for the overlay to draw
    final class PointSample {
        public final int x;
        public final int y;
        public final long ts;  // epoch millis

        public PointSample(int x, int y, long ts) {
            this.x = x; this.y = y; this.ts = ts;
        }
    }
}
