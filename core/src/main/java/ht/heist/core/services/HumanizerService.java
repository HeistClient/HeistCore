// ============================================================================
// HumanizerService.java
// -----------------------------------------------------------------------------
// Purpose
//   Centralized “human-like” randomness utilities used by MouseService,
//   CameraService, and any other core behaviors.
//   - Timing noise (uniform / Gaussian / exponential)
//   - AR(1) drift (sticky directional bias)
//   - 2D elliptical Gaussian point sampling inside rectangles
//   - Smooth curved mouse paths (cubic Bezier + light fBm noise)
//   - Small helpers for sleeps and clamps
//
// Binding
//   Implemented by HumanizerServiceImpl. Java 11 compatible.
// ============================================================================

package ht.heist.core.services;

import com.google.inject.ImplementedBy;
import ht.heist.core.impl.HumanizerServiceImpl;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

@ImplementedBy(HumanizerServiceImpl.class)
public interface HumanizerService
{
    // === Timing ===============================================================
    int randomDelay(int minMs, int maxMs);
    int gaussianDelay(int meanMs, int stdMs);
    int exponentialDelayMs(double meanMs);
    void sleep(int minMs, int maxMs);

    // === Gaussian samples =====================================================
    double truncatedGaussian(double mean, double std, double min, double max);

    // === Drift / Bias =========================================================
    /**
     * One AR(1) step: next = alpha * prev + N(0, noiseStd).
     * Returns the new drift value.
     */
    double ar1Step(double prev, double alpha, double noiseStd);

    // === Spatial sampling =====================================================
    /**
     * Sample a point inside a rectangle using an elliptical Gaussian footprint.
     * rx, ry are relative ellipse radii in [0..1] (e.g., 0.35 means 35% of width).
     * biasX, biasY shift the mean within the rect by a few px (e.g., small AR drift).
     */
    Point sampleEllipticalInRect(Rectangle r, double rx, double ry, double biasX, double biasY);

    /**
     * Generate a smooth, curved path from 'from' to 'to' using a cubic Bezier
     * and light fractal noise. Returns at least 2 points (includes 'to').
     * - steps: number of segments (6..30 typical)
     * - curvature: 0.0 (straight) .. 1.0 (pronounced curve)
     * - noisePx: amplitude of small perpendicular jitter along the path
     */
    List<Point> curvedPath(Point from, Point to, int steps, double curvature, double noisePx);

    // === Small helpers ========================================================
    int clampInt(int v, int lo, int hi);
}
