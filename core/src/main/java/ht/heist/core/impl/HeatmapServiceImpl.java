// ============================================================================
// FILE: HeatmapServiceImpl.java
// PACKAGE: ht.heist.core.impl
// -----------------------------------------------------------------------------
// PURPOSE
//   Concrete implementation of HeatmapService.
//   - Stores recent click points (timestamped).
//   - Respects config: max points, infinite vs fade.
//   - Exposes a thread-safe snapshot for overlays.
//   - Exports a PNG to a predictable folder, auto-creating directories.
//
// RENDERING NOTES
//   - Overlay draws points (and density colors) in real time.
//   - Export writes a simple PNG of current points over canvas size.
//
// DEPENDENCIES
//   - HeistCoreConfig (for toggles, limits, folder)
//   - RuneLite Client (for canvas size)
// ============================================================================
package ht.heist.core.impl;

import ht.heist.core.config.HeistCoreConfig;
import ht.heist.core.services.HeatmapService;
import net.runelite.api.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import static java.awt.Color.WHITE;
import static javax.imageio.ImageIO.write;

@Singleton
public class HeatmapServiceImpl implements HeatmapService
{
    private static final Logger log = LoggerFactory.getLogger(HeatmapServiceImpl.class);

    private final Client client;
    private final HeistCoreConfig config;

    // Ring buffer of click samples
    private final Deque<Sample> points = new ArrayDeque<>();

    // Visibility flag (overlay also checks config.showHeatmap())
    private volatile boolean enabled = true;

    @Inject
    public HeatmapServiceImpl(Client client, HeistCoreConfig config)
    {
        this.client = client;
        this.config = config;
    }

    // -------------------------------------------------------------------------
    // HeatmapService API
    // -------------------------------------------------------------------------
    @Override public void enable()  { enabled = true; }
    @Override public void disable() { enabled = false; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public void enableIfConfigured() { enabled = config.showHeatmap(); }

    @Override
    public void addClick(int x, int y)
    {
        // Add new point
        synchronized (points)
        {
            points.addLast(new Sample(x, y, System.currentTimeMillis()));
            // Enforce cap
            trimToMax(points, config.heatmapMaxPoints());
        }
    }

    @Override
    public List<Sample> snapshot()
    {
        final long now = System.currentTimeMillis();
        final int fadeMs = config.heatmapFadeMs();
        final boolean infinite = config.heatmapInfinite();

        final List<Sample> copy = new ArrayList<>();
        synchronized (points)
        {
            // Optionally fade out old points
            if (!infinite && fadeMs > 0)
            {
                for (Iterator<Sample> it = points.iterator(); it.hasNext();)
                {
                    Sample s = it.next();
                    if ((now - s.tsMillis) > fadeMs)
                        it.remove();
                }
            }
            // Copy remaining
            copy.addAll(points);
        }
        return copy;
    }

    @Override
    public String exportSnapshot() throws IOException
    {
        // Resolve target folder (relative under %USERPROFILE%/.runelite)
        Path folder = resolveExportFolder(config.heatmapExportFolder());
        Files.createDirectories(folder); // auto-create

        // Copy a safe snapshot of the points
        List<Sample> snap = snapshot();

        // Determine current canvas size; fallback if unavailable
        int w = 765, h = 503; // reasonable defaults
        try {
            if (client != null && client.getCanvas() != null) {
                w = Math.max(1, client.getCanvas().getWidth());
                h = Math.max(1, client.getCanvas().getHeight());
            }
        } catch (Throwable ignored) {}

        // Render a simple export: black background + white dots
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setBackground(new java.awt.Color(0, 0, 0, 0));
        g.clearRect(0, 0, w, h);
        g.setColor(WHITE);

        int r = Math.max(0, config.heatmapDotRadiusPx());
        for (Sample s : snap)
        {
            if (r <= 0) {
                img.setRGB(clamp(s.x, 0, w - 1), clamp(s.y, 0, h - 1), 0xFFFFFFFF);
            } else {
                g.fillOval(s.x - r, s.y - r, r * 2 + 1, r * 2 + 1);
            }
        }
        g.dispose();

        String filename = "heatmap-" + System.currentTimeMillis() + ".png";
        Path out = folder.resolve(filename);
        write(img, "PNG", out.toFile());

        log.info("Heatmap exported: {}", out.toAbsolutePath());
        return out.toAbsolutePath().toString();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private static void trimToMax(Deque<Sample> dq, int max)
    {
        if (max <= 0) return;
        while (dq.size() > max) dq.removeFirst();
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Resolve export folder: absolute → used as-is; relative → under %USERPROFILE%/.runelite/. */
    private Path resolveExportFolder(String configuredFolder)
    {
        Path p = Paths.get(configuredFolder == null ? "" : configuredFolder);
        if (p.isAbsolute()) return p;
        Path rl = Paths.get(System.getProperty("user.home"), ".runelite");
        return rl.resolve(p);
    }
}
