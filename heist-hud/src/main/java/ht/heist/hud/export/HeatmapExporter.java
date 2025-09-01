// ============================================================================
// FILE: HeatmapExporter.java
// MODULE: heist-hud
// PACKAGE: ht.heist.hud.export
// -----------------------------------------------------------------------------
// TITLE
//   Heatmap PNG Exporter — bakes persistent/live points into an image
//
// HOW IT WORKS
//   • Builds a scalar intensity grid via a simple circular kernel (radius R)
//   • Finds the max intensity and maps to the selected palette (IR or RED)
//   • Optionally layers LIVE points as well
//
// PERFORMANCE
//   • O(width*height + points*kernel_area). With defaults this is quick.
// ============================================================================
package ht.heist.hud.export;

import ht.heist.hud.HeistHUDConfig;
import ht.heist.hud.service.HeatmapService.HeatPoint;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class HeatmapExporter
{
    private HeatmapExporter() {}

    public static void exportPng(List<HeatPoint> persistent,
                                 List<HeatPoint> live,
                                 int width,
                                 int height,
                                 HeistHUDConfig.Palette pal,
                                 int kernelRadius,
                                 boolean includeLive,
                                 Path outPath) throws Exception
    {
        // 1) Intensity grid
        float[][] grid = new float[height][width];

        // Kernel: simple circular mask with 1/r dropoff
        final int R = Math.max(1, kernelRadius);
        final float[] weight = radialWeights(R);

        // Accumulate persistent first
        for (HeatPoint p : persistent) stamp(grid, p.x, p.y, width, height, R, weight);

        // Optional live layer
        if (includeLive && live != null) {
            for (HeatPoint p : live) stamp(grid, p.x, p.y, width, height, R, weight);
        }

        // 2) Find max intensity
        float max = 0f;
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                if (grid[y][x] > max) max = grid[y][x];

        // 3) Colorize
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        if (max <= 0f) {
            // nothing to draw; still save an empty image
            ImageIO.write(img, "png", outPath.toFile());
            return;
        }

        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                float t = grid[y][x] / max; // 0..1
                if (t <= 0f) continue;

                Color c = (pal == HeistHUDConfig.Palette.INFRARED) ? irRamp(t) : redRamp(t);
                img.setRGB(x, y, c.getRGB());
            }
        }

        // 4) Ensure directory and save
        Files.createDirectories(outPath.getParent());
        ImageIO.write(img, "png", outPath.toFile());
    }

    // --- helpers -------------------------------------------------------------

    private static void stamp(float[][] grid, int cx, int cy, int w, int h, int R, float[] weight)
    {
        if (cx < -R || cy < -R || cx >= w+R || cy >= h+R) return;

        for (int dy = -R; dy <= R; dy++)
        {
            int yy = cy + dy;
            if (yy < 0 || yy >= h) continue;

            for (int dx = -R; dx <= R; dx++)
            {
                int xx = cx + dx;
                if (xx < 0 || xx >= w) continue;

                int rr = dx*dx + dy*dy;
                if (rr > R*R) continue;

                int ri = (int)Math.round(Math.sqrt(rr));
                grid[yy][xx] += weight[ri];
            }
        }
    }

    private static float[] radialWeights(int R)
    {
        float[] w = new float[R+1];
        for (int r = 0; r <= R; r++)
        {
            // 1/(1+r) gives strong center with soft falloff
            w[r] = 1.0f / (1.0f + r);
        }
        return w;
    }

    // Color ramps for export (opaque; no alpha blending)
    private static Color redRamp(float x)
    {
        x = clamp01(x);
        int r = 255;
        int g = (int)(50 + 205*x);
        int b = (int)(50 + 205*x);
        return new Color(r, 255 - g, 255 - b); // red→white as intensity grows
    }

    private static Color irRamp(float x)
    {
        x = clamp01(x);
        if (x < 0.20f) { float t = x / 0.20f;       return new Color(0, (int)(64 + 191*t), 255); }
        if (x < 0.40f) { float t = (x-0.20f)/0.20f; return new Color(0, 255, (int)(255 - 255*t)); }
        if (x < 0.60f) { float t = (x-0.40f)/0.20f; return new Color((int)(255*t), 255, 0); }
        if (x < 0.80f) { float t = (x-0.60f)/0.20f; return new Color(255, (int)(255 - 155*t), 0); }
        float t = (x-0.80f)/0.20f;                  return new Color(255, (int)(100*t), (int)(100*t));
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
}
