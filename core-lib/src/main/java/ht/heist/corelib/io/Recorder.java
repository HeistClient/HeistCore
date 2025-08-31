// ============================================================================
// FILE: Recorder.java
// PACKAGE: ht.heist.corelib.io
// -----------------------------------------------------------------------------
// TITLE
//   Recorder (API) — ultra-light interface to write activity logs to disk.
//
// STORAGE MODEL
//   • start(activityTag): sets current activity (e.g., "DEFAULT", "WOODCUT").
//   • recordHumanClick / recordSyntheticClick: appends events to separate files.
//   • stopAndSave(): closes current files.
//
// PATHS
//   • Set explicitly at construction time; HUD controls where files go.
// ============================================================================

package ht.heist.corelib.io;

import java.awt.*;

public interface Recorder
{
    void configurePaths(String humanPath, String syntheticPath);

    void start(String activityTag);

    void recordHumanClick(Point p);

    void recordSyntheticClick(Point p);

    boolean isRecording();

    void stopAndSave() throws Exception;
}
