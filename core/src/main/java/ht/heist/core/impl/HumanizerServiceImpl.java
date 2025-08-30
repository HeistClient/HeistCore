// ============================================================================
// HumanizerServiceImpl.java
// -----------------------------------------------------------------------------
// Title
//   Human-like randomness engine used by core services.
//
// What this provides
//   • Uniform/Gaussian/Exponential delays (+ sleep helper)
//   • Truncated Gaussian sampling
//   • AR(1) drift step (sticky bias over time)
//   • 2D elliptical Gaussian sampling inside rectangles
//   • Curved Bezier path generation w/ smooth noise
//
// Notes
//   • Java 11, @Singleton
//   • No external libs; simple fBm-like noise via layered sines
// ============================================================================

package ht.heist.core.impl;

import ht.heist.core.services.HumanizerService;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Singleton
public class HumanizerServiceImpl implements HumanizerService
{
    // === RNG =================================================================
    private final Random rng = new Random();

    @Inject
    public HumanizerServiceImpl() { }

    // === Timing ===============================================================
    @Override
    public int randomDelay(int minMs, int maxMs) {
        int lo = Math.min(minMs, maxMs);
        int hi = Math.max(minMs, maxMs);
        return lo + rng.nextInt(hi - lo + 1);
    }

    @Override
    public int gaussianDelay(int meanMs, int stdMs) {
        double g = Math.abs(rng.nextGaussian() * stdMs + meanMs);
        return (int)Math.round(g);
    }

    @Override
    public int exponentialDelayMs(double meanMs) {
        // U in (0,1]; T = -ln(U) * mean
        double u = Math.max(1e-9, rng.nextDouble());
        double ms = -Math.log(u) * Math.max(1.0, meanMs);
        return (int)Math.round(ms);
    }

    @Override
    public void sleep(int minMs, int maxMs) {
        try {
            Thread.sleep(randomDelay(minMs, maxMs));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // === Gaussian samples =====================================================
    @Override
    public double truncatedGaussian(double mean, double std, double min, double max) {
        double v = rng.nextGaussian() * std + mean;
        if (v < min) v = min;
        if (v > max) v = max;
        return v;
    }

    // === Drift / Bias =========================================================
    @Override
    public double ar1Step(double prev, double alpha, double noiseStd) {
        return alpha * prev + rng.nextGaussian() * noiseStd;
    }

    // === Spatial sampling =====================================================
    @Override
    public Point sampleEllipticalInRect(Rectangle r, double rx, double ry, double biasX, double biasY) {
        if (r == null || r.width < 3 || r.height < 3) return new Point(r.x + r.width/2, r.y + r.height/2);

        // Center of rect
        double cx = r.x + r.width  * 0.5;
        double cy = r.y + r.height * 0.5;

        // Ellipse radii in pixels
        double sx = Math.max(1.0, r.width  * Math.max(0.05, Math.min(0.49, rx)));
        double sy = Math.max(1.0, r.height * Math.max(0.05, Math.min(0.49, ry)));

        // Correlated 2D Gaussian -> just sample independent normals (good enough here)
        double gx = rng.nextGaussian() * sx + cx + biasX;
        double gy = rng.nextGaussian() * sy + cy + biasY;

        int x = clampInt((int)Math.round(gx), r.x + 1, r.x + r.width  - 2);
        int y = clampInt((int)Math.round(gy), r.y + 1, r.y + r.height - 2);
        return new Point(x, y);
    }

    @Override
    public List<Point> curvedPath(Point from, Point to, int steps, double curvature, double noisePx) {
        List<Point> out = new ArrayList<>();
        if (from == null || to == null) {
            if (to != null) out.add(new Point(to));
            return out;
        }
        steps = clampInt(steps, 2, 40);

        // Randomized control points: curve roughly perpendicular to the straight line
        double dx = (double)(to.x - from.x);
        double dy = (double)(to.y - from.y);
        double dist = Math.hypot(dx, dy);
        if (dist < 1.0) {
            out.add(new Point(to));
            return out;
        }

        double nx = -dy / dist; // unit normal
        double ny =  dx / dist;

        // Curvature magnitude proportional to distance (capped)
        double curve = Math.max(0.0, Math.min(1.0, curvature));
        double bend = Math.min(60.0, 0.20 * dist) * (0.5 + 0.5 * curve); // px

        // Randomly choose bend direction, add light randomness
        double dir = (rng.nextBoolean() ? 1.0 : -1.0);
        double jitter = (rng.nextDouble() - 0.5) * 0.30; // ±30%
        bend = bend * (1.0 + jitter) * dir;

        // Control points at 35% and 65% along the line, offset by bend*normal
        double p1x = from.x + 0.35 * dx + nx * bend;
        double p1y = from.y + 0.35 * dy + ny * bend;
        double p2x = from.x + 0.65 * dx + nx * (-bend * 0.6);
        double p2y = from.y + 0.65 * dy + ny * (-bend * 0.6);

        // Noise parameters for fBm-like jitter along the path
        double amp = Math.max(0.0, noisePx);
        double phase1 = rng.nextDouble() * Math.PI * 2.0;
        double phase2 = rng.nextDouble() * Math.PI * 2.0;

        for (int i = 1; i <= steps; i++) {
            double t = (double)i / (double)steps;

            // Cubic Bezier
            double bx = bezier(from.x, p1x, p2x, to.x, t);
            double by = bezier(from.y, p1y, p2y, to.y, t);

            // Smooth “noise” perpendicular to the path direction (approx via sines)
            double n = 0.6 * Math.sin(2 * Math.PI * t + phase1) + 0.4 * Math.sin(4 * Math.PI * t + phase2);
            double ox = nx * (n * amp);
            double oy = ny * (n * amp);

            out.add(new Point((int)Math.round(bx + ox), (int)Math.round(by + oy)));
        }

        return out;
    }

    private static double bezier(double p0, double p1, double p2, double p3, double t) {
        double u = 1.0 - t;
        double b = u*u*u * p0
                + 3*u*u*t * p1
                + 3*u*t*t * p2
                + t*t*t   * p3;
        return b;
    }

    // === Helpers ==============================================================
    @Override
    public int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
