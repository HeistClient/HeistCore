// ============================================================================
// FILE: CoreLocator.java
// PACKAGE: ht.heist.core
// -----------------------------------------------------------------------------
// TITLE
//   CoreLocator — tiny static bridge for cross-plugin sharing.
//
// WHAT IT EXPOSES
//   • heatmapTapSink:  Consumer<Point> that accepts the ACTUAL click point.
//   • recorder:        InputRecorder, for synthetic click logging.
//
// WHY
//   RuneLite creates a separate Guice injector per plugin. This static helper
//   lets the Core plugin publish its services (or just a sink function) so
//   other plugins (like Woodcutter) can send events to the same overlay/recorder.
// ============================================================================

package ht.heist.core;

import ht.heist.core.services.InputRecorder;

import java.awt.*;
import java.util.function.Consumer;

public final class CoreLocator
{
    private CoreLocator() {}

    // ===== Global “tap” sink for heatmap clicks ===============================
    private static volatile Consumer<Point> HEATMAP_TAP_SINK;

    // ===== Global recorder pointer (optional) =================================
    private static volatile InputRecorder RECORDER;

    // -- setter/getter: heatmap tap sink --------------------------------------
    public static void setHeatmapTapSink(Consumer<Point> sink) { HEATMAP_TAP_SINK = sink; }
    public static Consumer<Point> getHeatmapTapSink() { return HEATMAP_TAP_SINK; }

    // -- setter/getter: recorder ----------------------------------------------
    public static void setRecorder(InputRecorder r) { RECORDER = r; }
    public static InputRecorder getRecorder() { return RECORDER; }
}
