// ============================================================================
// FILE: HeatmapService.java
// PACKAGE: ht.heist.hud.service
// -----------------------------------------------------------------------------
// TITLE
//   Heatmap Service (Interface) — single source of truth for points + ingest.
//
// WHAT THIS INTERFACE GUARANTEES
//   • Two adders: addHumanTap(...) and addSyntheticTap(...)
//   • Independent ingest toggles (acceptHuman / acceptSynthetic) that the
//     IMPLEMENTATION enforces internally. Producers always call the adders;
//     the service drops events if their type is disabled.
//   • Two snapshots:
//        - snapshotPersistent(): all points ever added (until clear())
//        - snapshotLive(now, windowMs): points whose (now - ts) <= windowMs
//   • Export helpers (PNG / JSON) and a clear().
//
// IMPORTANT: Overlays must NOT use ingest toggles; they just render snapshots.
//            The HUD plugin is in charge of keeping acceptHuman/acceptSynthetic
//            in sync with the config.
// ============================================================================

package ht.heist.hud.service;

import java.io.IOException;
import java.util.List;

public interface HeatmapService
{
    // ----- Point DTO ---------------------------------------------------------
    final class Sample {
        public final int x;
        public final int y;
        public final long ts;         // epoch millis
        public final String source;   // "human" or "synthetic"

        public Sample(int x, int y, long ts, String source) {
            this.x = x; this.y = y; this.ts = ts; this.source = source;
        }
    }

    // ----- Ingest toggles (enforced INSIDE the service) ----------------------
    void setAcceptHuman(boolean accept);
    void setAcceptSynthetic(boolean accept);
    boolean isAcceptHuman();
    boolean isAcceptSynthetic();

    // ----- Mutations ---------------------------------------------------------
    void addHumanTap(int x, int y, long ts);
    void addSyntheticTap(int x, int y, long ts);
    void clear();

    // ----- Queries -----------------------------------------------------------
    List<Sample> snapshotPersistent();
    List<Sample> snapshotLive(long nowMillis, int liveWindowMs);

    // ----- Exports -----------------------------------------------------------
    String exportPng(String exportFolderRelativeOrAbsolute) throws IOException;
    String exportJson(String exportFolderRelativeOrAbsolute) throws IOException;
}
