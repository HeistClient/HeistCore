// ============================================================================
// FILE: RecorderHuman.java
// PACKAGE: ht.heist.hud.ingest
// -----------------------------------------------------------------------------
// TITLE
//   Human Click Recorder (AWT Listener)
//
// WHAT THIS CLASS DOES
//   • Attaches a MouseListener to the RuneLite Canvas.
//   • On each LEFT-BUTTON click, it:
//       - Draws to the heatmap if cfg.ingestHuman() is true
//       - Appends JSONL if cfg.recordHumanClicks() is true
//
// IMPORTANT BEHAVIOR
//   • This listener is started ALWAYS by the plugin.
//   • The config toggles control DRAWING and WRITING separately—so the
//     “Accept human clicks” works even when recording is OFF.
// ============================================================================

package ht.heist.hud.ingest;

import ht.heist.hud.HeistHUDConfig;
import ht.heist.hud.service.HeatmapService;
import net.runelite.api.Client;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Canvas;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Singleton
public class RecorderHuman
{
    private final Client client;
    private final HeistHUDConfig cfg;
    private final HeatmapService heatmap;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private MouseAdapter listener;

    @Inject
    public RecorderHuman(Client client, HeistHUDConfig cfg, HeatmapService heatmap)
    {
        this.client = client;
        this.cfg    = cfg;
        this.heatmap = heatmap;
    }

    // -------------------------------------------------------------------------
    // LIFECYCLE
    // -------------------------------------------------------------------------
    public void start()
    {
        if (running.getAndSet(true)) return;

        final Canvas canvas = client.getCanvas();
        if (canvas == null) return;

        listener = new MouseAdapter()
        {
            @Override
            public void mouseReleased(MouseEvent e)
            {
                if (e.getButton() != MouseEvent.BUTTON1) return;

                final int x = e.getX();
                final int y = e.getY();
                final long ts = System.currentTimeMillis();

                // 1) Draw to heatmap only if the user wants human clicks drawn
                if (cfg.ingestHuman())
                {
                    heatmap.addHumanTap(x, y, ts);
                }

                // 2) Write JSONL only if the user turned recording ON
                if (cfg.recordHumanClicks())
                {
                    appendHumanJsonl(cfg.humanClicksJsonlPath(), x, y, ts);
                }
            }
        };

        canvas.addMouseListener(listener);
    }

    public void stop()
    {
        if (!running.getAndSet(false)) return;
        final Canvas canvas = client.getCanvas();
        if (canvas != null && listener != null)
        {
            canvas.removeMouseListener(listener);
        }
        listener = null;
    }

    // -------------------------------------------------------------------------
    // JSONL WRITER (guarded by recordHumanClicks())
    // -------------------------------------------------------------------------
    private static void appendHumanJsonl(String pathStr, int x, int y, long ts)
    {
        try
        {
            final Path path = Paths.get(pathStr);
            final Path dir = path.getParent();
            if (dir != null && !Files.exists(dir)) Files.createDirectories(dir);

            try (BufferedWriter w = Files.newBufferedWriter(
                    path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND))
            {
                w.write("{\"ts\":" + ts + ",\"x\":" + x + ",\"y\":" + y + ",\"source\":\"human\"}");
                w.newLine();
            }
        }
        catch (Exception ignored) {}
    }
}
