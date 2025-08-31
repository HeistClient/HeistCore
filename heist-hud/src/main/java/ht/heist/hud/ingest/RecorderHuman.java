// ============================================================================
// FILE: RecorderHuman.java
// PACKAGE: ht.heist.hud.ingest
// -----------------------------------------------------------------------------
// TITLE
//   Human click recorder + heatmap feeder.
//
// WHAT IT DOES
//   • Listens to AWT mouse RELEASE events on the RuneLite canvas.
//   • Adds the click to HeatmapService as "human" (ALWAYS).
//   • If cfg.recordHumanClicks() == true, also writes one JSONL line to
//     cfg.humanClicksJsonlPath().
//
// IMPORTANT
//   • This class NEVER affects synthetic ingestion. It’s independent.
//   • Start/stop is driven by a config toggle in the HUD plugin.
// ============================================================================

package ht.heist.hud.ingest;

import ht.heist.hud.HeistHUDConfig;
import ht.heist.hud.service.HeatmapService;
import net.runelite.api.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Canvas;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

@Singleton
public class RecorderHuman
{
    private static final Logger log = LoggerFactory.getLogger(RecorderHuman.class);

    private final Client client;
    private final HeistHUDConfig cfg;
    private final HeatmapService heatmap;

    private volatile boolean running = false;
    private final MouseAdapter listener = new MouseAdapter() {
        @Override public void mouseReleased(MouseEvent e) {
            handle(e);
        }
    };

    @Inject
    public RecorderHuman(Client client, HeistHUDConfig cfg, HeatmapService heatmap)
    {
        this.client  = client;
        this.cfg     = cfg;
        this.heatmap = heatmap;
    }

    // -- lifecycle ------------------------------------------------------------
    public synchronized void start()
    {
        if (running) return;
        Canvas c = client.getCanvas();
        if (c == null) return;
        c.addMouseListener(listener);
        running = true;
        log.info("RecorderHuman started (path={})", cfg.humanClicksJsonlPath());
    }

    public synchronized void stop()
    {
        if (!running) return;
        Canvas c = client.getCanvas();
        if (c != null) {
            try { c.removeMouseListener(listener); } catch (Exception ignored) {}
        }
        running = false;
        log.info("RecorderHuman stopped");
    }

    // -- event handling -------------------------------------------------------
    private void handle(MouseEvent e)
    {
        // Canvas coords
        final int x = e.getX();
        final int y = e.getY();
        final long ts = System.currentTimeMillis();

        // ALWAYS add to heatmap as "human" (visibility is handled at render time)
        heatmap.addHumanTap(x, y, ts);

        // Optionally record to JSONL if toggle is on
        if (cfg.recordHumanClicks()) {
            appendJsonl(cfg.humanClicksJsonlPath(), ts, x, y, "human");
        }
    }

    private static void appendJsonl(String path, long ts, int x, int y, String source)
    {
        try {
            Path p = Paths.get(path);
            Path parent = p.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
            try (BufferedWriter w = Files.newBufferedWriter(
                    p, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND))
            {
                w.write("{\"ts\":" + ts + ",\"x\":" + x + ",\"y\":" + y + ",\"source\":\"" + source + "\"}\n");
            }
        } catch (Exception ignored) {}
    }
}
