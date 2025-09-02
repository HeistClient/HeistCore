// ============================================================================
// FILE: ClickStore.java
// MODULE: heist-hud
// PACKAGE: ht.heist.hud.service
// -----------------------------------------------------------------------------
// TITLE
//   ClickStore — in-memory reservoir of TapEvents used by overlays/exporter
//
// PURPOSE
//   - Keep a persistent (non-fading) list of TapEvents for the current session.
//   - The dot overlay renders directly from this store.
//   - The PNG exporter uses this same list to compute density.
//
// THREADING
//   - Methods are synchronized; we copy out when giving to renderers/exporters.
// ============================================================================

package ht.heist.hud.service;

import ht.heist.corejava.api.input.TapEvent;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Singleton
public final class ClickStore
{
    private final ArrayList<TapEvent> events = new ArrayList<>();

    /** Append an event (called by TapBus listener). */
    public synchronized void add(TapEvent e) {
        if (e != null) events.add(e);
    }

    /** Snapshot copy for exporters, not live-backed. */
    public synchronized List<TapEvent> getAll() {
        return new ArrayList<>(events);
    }

    /** Clear the in-memory set (overlay + PNG will reflect this). */
    public synchronized void clear() { events.clear(); }

    /** Read-only snapshot view (still a copy under the hood). */
    public synchronized List<TapEvent> unmodifiableView() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }
}
