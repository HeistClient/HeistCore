// ============================================================================
// FILE: Humanizer.java
// PACKAGE: ht.heist.corejava.api.input
// TITLE: Humanizer — curved movement + tiny jitter contract
//
// WHAT IT DOES
//   • Generates a sequence of intermediate points along a smooth curve from
//     current mouse position to the target.
//   • Adds tiny 1–2 px jitter for clicks so we’re not machine-perfect.
//
// NOTE: PURE JAVA.
// ============================================================================
package ht.heist.corejava.api.input;

import java.awt.Point;
import java.util.List;

public interface Humanizer {
    /** Return a series of screen points to move the mouse along (curved path). */
    List<Point> curvedPath(Point fromScreen, Point toScreen);

    /** Add ±maxJitterPx random offset to the click point. */
    Point jitter(Point p, int maxJitterPx);
}
