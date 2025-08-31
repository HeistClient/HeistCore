// ============================================================================
// FILE: InputRecorder.java
// PACKAGE: ht.heist.core.services
// -----------------------------------------------------------------------------
// PURPOSE
//   Service interface for recording input analytics.
//   • Records HUMAN input (hardware events via Canvas listeners).
//   • Can optionally record MACRO (synthetic) clicks if the macro calls us.
//   • Supports separate output files for human vs. synthetic data.
//   • Provides "save now" and "reset data" utilities.
// API CONTRACT
//   - No checked exceptions (simplifies callers and @Subscribe handlers).
//   - 'recordSyntheticClick' is a no-op unless 'setRecordSynthetic(true)'.
//   - 'setSyntheticOutputPath' lets you change the macro file path at runtime.
// ============================================================================
package ht.heist.core.services;

import java.awt.Point;

public interface InputRecorder
{
    // ---- Session lifecycle --------------------------------------------------

    /** Begin recording; activityTag is descriptive (e.g., "WOODCUTTING"). */
    void start(String activityTag);

    /** Stop recording and write current buffers to disk. */
    void stopAndSave();

    /** Drop in-memory buffers (does NOT delete files). */
    void resetData();

    /** Persist buffers immediately without stopping the session. */
    void saveNow();

    /** True while an active recording session is running. */
    boolean isRecording();

    // ---- Macro (synthetic) capture -----------------------------------------

    /**
     * Optionally record a macro-generated click. Call this from your macro after you
     * compute the final click pixel. No-op if recordSynthetic=false or not recording.
     */
    void recordSyntheticClick(Point p);

    /** Enable/disable capturing of macro clicks. */
    void setRecordSynthetic(boolean enable);

    /** Set a separate output path for macro (synthetic) events. */
    void setSyntheticOutputPath(String path);
}
