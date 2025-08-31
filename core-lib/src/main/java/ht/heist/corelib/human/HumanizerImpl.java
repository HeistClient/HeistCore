// ============================================================================
// FILE: HumanizerImpl.java
// PACKAGE: ht.heist.corelib.human
// -----------------------------------------------------------------------------
// TITLE
//   Humanizer (Impl) — concrete implementation of timing + sampling helpers.
//
// DESIGN NOTES
//   • No dependencies on RuneLite. Only java.util.Random and java.awt.Point.
//   • AR(1) drift keeps click distribution “sticky” without teleport jumps.
//   • Elliptical sampling biases points toward the center with slight drift.
// ============================================================================

package ht.heist.corelib.human;

import java.awt.*;
import java.util.Random;

public class HumanizerImpl implements Humanizer
{
    private final Random rng = new Random();

    @Override
    public int randomDelay(int minMs, int maxMs)
    {
        if (maxMs <= minMs) return Math.max(0, minMs);
        return minMs + rng.nextInt(maxMs - minMs + 1);
    }

    @Override
    public int gaussianDelay(int meanMs, int stdMs)
    {
        // Clamp to non-negative
        return Math.max(0, (int) Math.round(meanMs + rng.nextGaussian() * stdMs));
    }

    @Override
    public double ar1Step(double prev, double alpha, double noiseStd)
    {
        // AR(1): x_t = alpha * x_{t-1} + eps_t, eps ~ N(0, noiseStd)
        return alpha * prev + rng.nextGaussian() * noiseStd;
    }

    @Override
    public Point sampleEllipticalInRect(Rectangle r,
                                        double rxFrac, double ryFrac,
                                        double biasX, double biasY)
    {
        if (r == null) return new Point(0, 0);

        // Ellipse radii as fractions of rect width/height
        final double rx = Math.max(1.0, r.width * rxFrac);
        final double ry = Math.max(1.0, r.height * ryFrac);

        // Rejection sample inside unit circle, then scale, add bias, clamp
        for (int attempts = 0; attempts < 50; attempts++)
        {
            double x = rng.nextGaussian();
            double y = rng.nextGaussian();
            double norm = Math.hypot(x, y);
            if (norm == 0) continue;
            x /= norm;
            y /= norm;

            // Center of rect
            double cx = r.x + r.width / 2.0;
            double cy = r.y + r.height / 2.0;

            // Scale to ellipse + bias
            int px = (int) Math.round(cx + x * rx + biasX);
            int py = (int) Math.round(cy + y * ry + biasY);

            // Clamp to rect bounds
            px = Math.max(r.x, Math.min(r.x + r.width - 1, px));
            py = Math.max(r.y, Math.min(r.y + r.height - 1, py));
            return new Point(px, py);
        }

        // Fallback = rect center
        return new Point(r.x + r.width / 2, r.y + r.height / 2);
    }
}
