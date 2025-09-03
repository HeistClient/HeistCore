// ============================================================================
// FILE: CameraService.java
// PATH: core-rl/src/main/java/ht/heist/corerl/input/CameraService.java
// PACKAGE: ht.heist.corerl.input
// -----------------------------------------------------------------------------
// TITLE
//   CameraService — Line-of-sight check + (future) human-like camera nudges
//
// WHAT THIS CLASS DOES (NOW)
//   • isVisible(GameObject): simple LoS proxy using non-null convex hull.
//   • face(GameObject): placeholder to rotate camera gradually (to implement).
//
// HOW TO USE
//   In a plugin before clicking a GameObject:
//     if (!cameraService.isVisible(obj)) {
//         cameraService.face(obj);
//         // wait a bit, then try again
//     }
//
// IMPLEMENTATION NOTES
//   RuneLite generally doesn't expose direct "set camera instantly" for safety.
//   Two common patterns:
//     1) Simulate arrow keys (KeyEvent) to rotate/tilt gradually.
//     2) Simulate right-drag or middle-drag to pan the camera (mouse events).
//   Implement either inside face() with sleeps/jitter to stay human-like.
// ============================================================================

package ht.heist.corerl.input;

import net.runelite.api.Client;
import net.runelite.api.GameObject;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class CameraService
{
    private final Client client;

    @Inject
    public CameraService(Client client) {
        this.client = client;
    }

    /** Simple line-of-sight proxy: object has a non-null convex hull on screen. */
    public boolean isVisible(GameObject go) {
        return go != null && go.getConvexHull() != null;
    }

    /**
     * Nudge camera toward an object in a human-like way.
     * TODO: compute intended yaw/tilt and simulate arrow-key presses or a
     * right/middle drag with small sleeps between steps.
     */
    public void face(GameObject go) throws InterruptedException {
        if (go == null) return;
        // Placeholder: you can implement stepped key presses here.
        // e.g., press RIGHT arrow for 80-120ms a few times with small sleeps.
    }
}
