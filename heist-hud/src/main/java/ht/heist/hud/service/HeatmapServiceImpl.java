// ============================================================================
// FILE: HeatmapServiceImpl.java
// PACKAGE: ht.heist.hud.service
// -----------------------------------------------------------------------------
// TITLE
//   Heatmap Service (Implementation) — infinite memory + independent ingest.
//
// DESIGN NOTES
//   • Stores ALL taps (human/synthetic) in one persistent list (thread-safe).
//     "Live" is computed by filtering by age at snapshot time. This gives you
//     infinite memory for persistent AND a live decayed view without losing data.
//   • Ingest toggles are enforced HERE. Producers (recorder, tailer, CoreLocator
//     sink) should NOT check toggles; they just call addHumanTap/addSyntheticTap.
//   • Overlay only consumes snapshots. It NEVER checks ingest toggles.
//
// THREADING
//   • All mutation on a single lock; snapshots copy to a new list to avoid
//     concurrent modification.
//
// EXPORTS
//   • PNG uses your infrared palette + cfg.heatmapDotRadiusPx().
//   • JSON writes each Sample as an object (with ts/x/y/source) on one line.
// ============================================================================

package ht.heist.hud.service;

import ht.heist.hud.HeistHUDConfig;
import net.runelite.api.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static javax.imageio.ImageIO.write;

@Singleton
public class HeatmapServiceImpl implements HeatmapService
{
    private static final Logger log = LoggerFactory.getLogger(HeatmapServiceImpl.class);

    // ----- RL/Config (for canvas size + radius + decay window used in export) -
    private final Client client;
    private final HeistHUDConfig cfg;

    // ----- Storage -----------------------------------------------------------
    private final List<Sample> persistent = new ArrayList<>(); // infinite until clear()

    // ----- Ingest flags (independent) ----------------------------------------
    private volatile boolean acceptHuman     = true;
    private volatile boolean acceptSynthetic = true;

    @Inject
    public HeatmapServiceImpl(Client client, HeistHUDConfig cfg) {
        this.client = client;
        this.cfg = cfg;
    }

    // ========================================================================
    // Ingest toggles
    // ========================================================================
    @Override public void setAcceptHuman(boolean accept)     { this.acceptHuman = accept; }
    @Override public void setAcceptSynthetic(boolean accept) { this.acceptSynthetic = accept; }
    @Override public boolean isAcceptHuman()                 { return acceptHuman; }
    @Override public boolean isAcceptSynthetic()             { return acceptSynthetic; }

    // ========================================================================
    // Mutations
    // ========================================================================
    @Override
    public void addHumanTap(int x, int y, long ts)
    {
        if (!acceptHuman) return; // <- ENFORCED HERE
        synchronized (persistent) {
            persistent.add(new Sample(x, y, ts, "human"));
        }
    }

    @Override
    public void addSyntheticTap(int x, int y, long ts)
    {
        if (!acceptSynthetic) return; // <- ENFORCED HERE
        synchronized (persistent) {
            persistent.add(new Sample(x, y, ts, "synthetic"));
        }
    }

    @Override
    public void clear()
    {
        synchronized (persistent) {
            persistent.clear();
        }
    }

    // ========================================================================
    // Queries
    // ========================================================================
    @Override
    public List<Sample> snapshotPersistent()
    {
        synchronized (persistent) {
            return new ArrayList<>(persistent); // copy for thread-safety
        }
    }

    @Override
    public List<Sample> snapshotLive(long nowMillis, int liveWindowMs)
    {
        if (liveWindowMs <= 0) liveWindowMs = 1;
        final long cutoff = nowMillis - liveWindowMs;

        final List<Sample> out = new ArrayList<>();
        synchronized (persistent) {
            for (int i = persistent.size() - 1; i >= 0; i--) {
                Sample s = persistent.get(i);
                if (s.ts >= cutoff) out.add(s);
                else break; // taps are mostly appended in time order; early exit helps
            }
        }
        return out;
    }

    // ========================================================================
    // Exports
    // ========================================================================
    @Override
    public String exportPng(String exportFolder) throws IOException
    {
        final Path folder = resolveFolder(exportFolder);
        Files.createDirectories(folder);

        final List<Sample> snap = snapshotPersistent();

        // canvas size fallback
        int w = 765, h = 503;
        try {
            if (client != null && client.getCanvas() != null) {
                w = Math.max(1, client.getCanvas().getWidth());
                h = Math.max(1, client.getCanvas().getHeight());
            }
        } catch (Throwable ignored) {}

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setBackground(new Color(0, 0, 0, 0));
        g.clearRect(0, 0, w, h);

        final int r = Math.max(0, cfg.heatmapDotRadiusPx());
        final long now = System.currentTimeMillis();
        final int liveWin = Math.max(1, cfg.liveDecayMs());

        for (Sample s : snap) {
            g.setColor(infraredColor(now - s.ts, liveWin));
            if (r <= 0) {
                img.setRGB(clamp(s.x, 0, w - 1), clamp(s.y, 0, h - 1), 0xFFFFFFFF);
            } else {
                g.fillOval(s.x - r, s.y - r, r * 2 + 1, r * 2 + 1);
            }
        }
        g.dispose();

        final String filename = "heatmap-" + System.currentTimeMillis() + ".png";
        final Path out = folder.resolve(filename);
        write(img, "PNG", out.toFile());
        return out.toAbsolutePath().toString();
    }

    @Override
    public String exportJson(String exportFolder) throws IOException
    {
        final Path folder = resolveFolder(exportFolder);
        Files.createDirectories(folder);

        final Path out = folder.resolve("heatmap-" + System.currentTimeMillis() + ".json");
        try (BufferedWriter w = Files.newBufferedWriter(out,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
        {
            w.write("{\"points\":[\n");
            final List<Sample> snap = snapshotPersistent();
            for (int i = 0; i < snap.size(); i++) {
                Sample s = snap.get(i);
                w.write("  {\"ts\":" + s.ts + ",\"x\":" + s.x + ",\"y\":" + s.y + ",\"source\":\"" + s.source + "\"}");
                if (i + 1 < snap.size()) w.write(",\n");
            }
            w.write("\n]}\n");
        }
        return out.toAbsolutePath().toString();
    }

    // ========================================================================
    // Helpers
    // ========================================================================
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Age (ms) → infrared color. 0ms is “white-hot”, decayMs=fully cool. */
    private static Color infraredColor(long ageMs, int decayMs)
    {
        if (decayMs <= 0) decayMs = 1;
        float t = (float)Math.max(0, Math.min(1, (double)ageMs / (double)decayMs));
        float r = 1.0f;
        float g = 1.0f - 0.8f * t;
        float b = 0.2f * (1.0f - t);
        return new Color(r, g, b);
    }

    /** export folder: absolute → as-is, relative → under %USERPROFILE%/.runelite/. */
    private static Path resolveFolder(String configuredFolder)
    {
        Path p = Paths.get(configuredFolder == null ? "" : configuredFolder);
        if (p.isAbsolute()) return p;
        Path rl = Paths.get(System.getProperty("user.home"), ".runelite");
        return rl.resolve(p);
    }
}
