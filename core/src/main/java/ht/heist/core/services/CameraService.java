// ============================================================================
// CameraService.java
// -----------------------------------------------------------------------------
// Purpose
//   Core abstraction for human-like camera control using AWT key events,
//   with both direct controls and high-level “context hooks” that the plugin
//   can call (idle ticks, fail-to-find, before/after clicks, recovery, etc.).
//
// Binding
//   Uses @ImplementedBy to allow Guice to bind without a module.
//   The implementation is CameraServiceImpl.
// ============================================================================

package ht.heist.core.services;

import com.google.inject.ImplementedBy;
import ht.heist.core.impl.CameraServiceImpl;

@ImplementedBy(CameraServiceImpl.class)
public interface CameraService
{
    // === Direct controls =====================================================

    /**
     * Nudge yaw by an approximate number of degrees.
     * Positive = rotate right; negative = rotate left.
     */
    void nudgeYaw(int degrees);

    /**
     * Set camera pitch to a desired percentage [0.0 .. 1.0].
     * 0.0 = lowest, 1.0 = highest. Uses a tap-count heuristic.
     */
    void setPitch(double percent);

    // === Randomized “human” behavior ========================================

    /**
     * Call every game tick. Runs the internal interval-based scheduler that
     * occasionally rotates the camera with natural timing (Poisson/exponential).
     */
    void onTick();

    /** Call when you failed to find a tree this cycle. */
    void onFailFindTree();

    /** Call periodically while in recovery state. */
    void onRecoveryPulse();

    /** Call right before clicking a world object (e.g., a tree). */
    void onPreObjectClick();

    /** Call right after clicking a world object. */
    void onPostObjectClick();

    /** Call when your state machine is idle (no actions this tick). */
    void onIdleTick();
}
