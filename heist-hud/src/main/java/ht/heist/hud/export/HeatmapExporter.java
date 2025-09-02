// ============================================================================
// FILE: HeatmapExporter.java
// MODULE: heist-hud
// PACKAGE: ht.heist.hud.export
// -----------------------------------------------------------------------------
// TITLE
//   HeatmapExporter — true density PNG (IR / Red) from TapEvents
//
// PURPOSE
//   - Convert the in-memory ClickStore (TapEvent list) into a density image.
//   - IR palette gives "hot/cold" for quick distribution analysis.
//
// INPUTS
//   - Full TapEvent list (typically from ClickStore#getAll()).
//   - Canvas width/height at export time.
//
// OUTPUT
//   - Writes PNG into the chosen output directory.
//
// NOTE
//   - We weight each tap with a Gaussian kernel ~ dotRadiusPx.
// ============================================================================

package ht.heist.hud.export;

import ht.heist.corejava.api.input.TapEvent;
import ht.heist.hud.HeistHUDConfig;

import javax.imageio.ImageIO;
import javax.inject.Singleton;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@Singleton
public final class HeatmapExporter
{
    /** Render to outDir/heatmap-YYYYMMDD-HHmmss.png. Returns absolute path or null on failure. */
    public String exportTo(File outDir, List<TapEvent> events, HeistHUDConfig.Palette palette,
                           int dotRadiusPx, int width, int height)
    {
        if (events == null || events.isEmpty() || width <= 0 || height <= 0) return null;
        try {
            if (outDir != null && !outDir.exists()) Files.createDirectories(outDir.toPath());
            File outFile = new File(outDir, "heatmap-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".png");
            render(events, palette, dotRadiusPx, width, height, outFile);
            return outFile.getAbsolutePath();
        } catch (Throwable t) { return null; }
    }

    private static void render(List<TapEvent> events, HeistHUDConfig.Palette palette, int dotRadiusPx,
                               int width, int height, File outFile) throws IOException
    {
        final int w = width, h = height;
        final float[] density = new float[w * h];

        final int r = Math.max(1, dotRadiusPx);
        final int kernelRadius = Math.max(2, (int)Math.round(r * 2.5));
        final double sigma = Math.max(1.0, r * 0.75);
        final double invTwoSigma2 = 1.0 / (2.0 * sigma * sigma);

        float maxDensity = 0f;

        for (TapEvent e : events) {
            if (e.type == TapEvent.Type.UP) continue; // count DOWN/CLICK, ignore UP
            int px = clamp(e.xCanvas, 0, w - 1);
            int py = clamp(e.yCanvas, 0, h - 1);

            int minX = Math.max(0, px - kernelRadius);
            int maxX = Math.min(w - 1, px + kernelRadius);
            int minY = Math.max(0, py - kernelRadius);
            int maxY = Math.min(h - 1, py + kernelRadius);

            for (int y = minY; y <= maxY; y++) {
                int dy = y - py;
                for (int x = minX; x <= maxX; x++) {
                    int dx = x - px;
                    int d2 = dx*dx + dy*dy;
                    if (d2 > kernelRadius * kernelRadius) continue;

                    float wgt = (float)Math.exp(-d2 * invTwoSigma2);
                    int idx = y * w + x;
                    density[idx] += wgt;
                    if (density[idx] > maxDensity) maxDensity = density[idx];
                }
            }
        }

        final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        final int[] argb = new int[w * h];
        if (maxDensity > 0f) {
            for (int i = 0; i < argb.length; i++) {
                double t = clamp01(density[i] / maxDensity);
                argb[i] = colorFor(t, palette);
            }
        }
        img.setRGB(0, 0, w, h, argb, 0, w);
        ImageIO.write(img, "png", outFile);
    }

    private static int colorFor(double t, HeistHUDConfig.Palette palette)
    {
        t = clamp01(t);
        if (palette == HeistHUDConfig.Palette.RED) {
            int a = (int)Math.round(255 * t);
            return (a << 24) | (255 << 16); // ARGB for solid red with alpha = t
        }
        final Color[] ramp = new Color[] {
                new Color(  0,   0,  64),
                new Color(  0, 128, 255),
                new Color(  0, 200,   0),
                new Color(255, 200,   0),
                new Color(255,  64,   0),
                new Color(255, 255, 255)
        };
        double seg = t * (ramp.length - 1);
        int i = (int)Math.floor(seg);
        if (i >= ramp.length - 1) i = ramp.length - 2;
        double f = seg - i;

        Color c0 = ramp[i], c1 = ramp[i + 1];
        int r = (int)Math.round(c0.getRed()   + f * (c1.getRed()   - c0.getRed()));
        int g = (int)Math.round(c0.getGreen() + f * (c1.getGreen() - c0.getGreen()));
        int b = (int)Math.round(c0.getBlue()  + f * (c1.getBlue()  - c0.getBlue()));
        int a = (int)Math.round(64 + 191 * t); // 64..255
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double clamp01(double x) { return x <= 0 ? 0 : Math.min(1, x); }
}
