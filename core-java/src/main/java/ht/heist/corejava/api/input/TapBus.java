// ============================================================================
// FILE: TapBus.java
// MODULE: core-rl
// PACKAGE: ht.heist.corerl.input
// -----------------------------------------------------------------------------
// TITLE
//   TapBus — ultra-tiny, thread-safe event bus for TapEvent
//
// PURPOSE
//   - Decouple producers (HumanMouse, manual input listeners) from consumers
//     (HUD overlays, loggers, analytics) without creating module cycles.
//   - Any module can listen; posting is non-blocking and exception-safe.
//
// USAGE
//   - Producers: TapBus.post(TapEvent) whenever a tap/click occurs.
//   - Consumers: TapBus.addListener(listener) to receive events.
//
// THREADING
//   - Listeners stored in CopyOnWriteArrayList so add/remove are safe and
//     iteration is consistent without external locks.
//   - post(...) delivers on the calling thread. Consumers that need to run
//     on the RL client thread should switch threads themselves.
//
// IMPORTANT
//   - This class has NO dependencies outside Java stdlib + TapEvent.
//   - It lives in core-rl so both gameplay plugins and HUD can share it.
// ============================================================================

package ht.heist.corejava.api.input;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TapBus
{
    // ---- Listener contract --------------------------------------------------
    @FunctionalInterface
    public interface Listener { void onTap(TapEvent event); }

    // ---- Storage for current listeners -------------------------------------
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private TapBus() {}

    // Add a listener; no-op if already present
    public static void addListener(Listener l) {
        if (l != null) listeners.addIfAbsent(l);
    }

    // Remove a listener; safe if not present
    public static void removeListener(Listener l) {
        if (l != null) listeners.remove(l);
    }

    // Accessor (mostly for testing)
    public static List<Listener> currentListeners() {
        return List.copyOf(listeners);
    }

    // Post an event to all listeners; exceptions are contained per-listener
    public static void post(TapEvent e) {
        if (e == null) return;
        for (Listener l : listeners) {
            try { l.onTap(e); } catch (Throwable t) {
                // Do not let one misbehaving listener break the bus
                // (Optional) print or route to your logger
                // t.printStackTrace();
            }
        }
    }
}
