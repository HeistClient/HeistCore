// ============================================================================
// FILE: CoreLocator.java
// PACKAGE: ht.heist.corelib.bridge
// -----------------------------------------------------------------------------
// TITLE
//   CoreLocator — tiny static "bridge board" shared by ALL plugins.
//   Because RuneLite loads each plugin in its own classloader, statics in a
//   shaded library would be duplicated and NOT shared. To guarantee sharing,
//   we merge both plugins + this class into ONE bundle jar so the statics are
//   truly singletons.
//
// WHAT IT PUBLISHES / CONSUMES
//   • Heatmap Tap Sink: Consumer<Point> that receives ACTUAL click points.
//   • Recorder: a lightweight interface for writing human/synthetic events.
//
// LIFE CYCLE
//   • Heist HUD publishes (setHeatmapTapSink, setRecorder) on startup.
//   • Woodcutter reads (getHeatmapTapSink, getRecorder) whenever it clicks.
//
// THREAD-SAFETY
//   • Simple volatile fields. Plugins set once at startup; reads are frequent.
// ============================================================================

package ht.heist.corelib.bridge;

import ht.heist.corelib.io.Recorder;

import java.awt.Point;
import java.util.function.Consumer;

public final class CoreLocator
{
    // Prevent instantiation
    private CoreLocator() {}

    // --- Shared heatmap sink (tap point consumer) ----------------------------
    private static volatile Consumer<Point> HEATMAP_TAP_SINK = null;

    // --- Shared recorder instance (human + synthetic) ------------------------
    private static volatile Recorder RECORDER = null;

    // ====== PUBLISHERS (called by Heist HUD) =================================

    /** Publish a function that receives ACTUAL click points for heatmap. */
    public static void setHeatmapTapSink(Consumer<Point> sink)
    {
        HEATMAP_TAP_SINK = sink;
    }

    /** Publish a shared recorder instance (human + synthetic). */
    public static void setRecorder(Recorder recorder)
    {
        RECORDER = recorder;
    }

    // ====== CONSUMERS (called by Woodcutter or others) =======================

    /** Retrieve the heatmap tap sink; may be null if HUD not started yet. */
    public static Consumer<Point> getHeatmapTapSink()
    {
        return HEATMAP_TAP_SINK;
    }

    /** Retrieve the shared recorder; may be null if HUD not started yet. */
    public static Recorder getRecorder()
    {
        return RECORDER;
    }
}
