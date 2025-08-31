// ============================================================================
// FILE: MousePositionProvider.java
// PACKAGE: ht.heist.corejava.api.input
// -----------------------------------------------------------------------------
// TITLE:
//   MousePositionProvider — supplies the CURRENT canvas mouse (AWT Point).
//
// WHY:
//   • MouseServiceImpl must KNOW where the mouse is now to plan a REAL path
//     (series of MOUSE_MOVED events) from "current → target", so the client
//     sees hover BEFORE press and doesn't "teleport".
//   • The plugin (RuneLite side) implements this by returning
//       client.getMouseCanvasPosition() converted to java.awt.Point.
// ============================================================================
package ht.heist.corejava.api.input;

import java.awt.Point;

@FunctionalInterface
public interface MousePositionProvider {
    /** @return current canvas-local mouse position (AWT), or null if unknown. */
    Point currentCanvasMouse();
}
