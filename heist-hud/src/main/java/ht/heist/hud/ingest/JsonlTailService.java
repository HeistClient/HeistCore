// ============================================================================
// FILE: JsonlTailService.java
// PACKAGE: ht.heist.hud.ingest
// -----------------------------------------------------------------------------
// TITLE
//   JSONL Tailer — reads synthetic tap lines from disk and feeds HeatmapService.
//
// FORMAT (one line):
//   {"ts":1725072345123,"act":"WOODCUT","type":"synthetic","x":493,"y":271}
//
// DESIGN
//   • Separate daemon thread; safe to start/stop with the plugin
//   • No JSON lib — tiny parser to extract x,y,ts (robust enough for our lines)
// ============================================================================
package ht.heist.hud.ingest;

import ht.heist.hud.HeistHUDConfig;
import ht.heist.hud.service.HeatmapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Singleton
public final class JsonlTailService
{
    private static final Logger log = LoggerFactory.getLogger(JsonlTailService.class);

    private final HeistHUDConfig cfg;
    private final HeatmapService heatmap;

    private volatile boolean running = false;
    private Thread worker = null;

    @Inject
    public JsonlTailService(HeistHUDConfig cfg, HeatmapService heatmap)
    {
        this.cfg = cfg;
        this.heatmap = heatmap;
    }

    public void start()
    {
        if (!cfg.enableJsonlIngest()) {
            log.info("[HUD] JSONL ingest disabled in config.");
            return;
        }
        final String pathStr = cfg.syntheticInputPath();
        if (pathStr == null || pathStr.isEmpty()) {
            log.warn("[HUD] syntheticInputPath is empty; heatmap ingest will be off.");
            return;
        }

        running = true;
        worker = new Thread(() -> tailLoop(Paths.get(pathStr)), "Heist-JsonlTailer");
        worker.setDaemon(true);
        worker.start();
        log.info("[HUD] JSONL ingest started: {}", pathStr);
    }

    public void stop()
    {
        running = false;
        if (worker != null) {
            try { worker.join(500); } catch (InterruptedException ignored) {}
        }
        worker = null;
        log.info("[HUD] JSONL ingest stopped.");
    }

    // ---- Internals ----------------------------------------------------------

    private void tailLoop(Path file)
    {
        try
        {
            // Ensure parent folder exists so users see it created
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (!Files.exists(file)) {
                // Create empty file so tailer has something to open
                Files.createFile(file);
            }

            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r"))
            {
                // Start at end — show new clicks only
                long pos = raf.length();
                raf.seek(pos);

                while (running)
                {
                    String line = raf.readLine();
                    if (line == null) {
                        sleep(150);
                        continue;
                    }

                    // Attempt lightweight parse: find x, y, ts
                    // Accepts both "human" and "synthetic" types
                    try {
                        int x = extractInt(line, "\"x\":");
                        int y = extractInt(line, "\"y\":");
                        long ts = extractLong(line, "\"ts\":");
                        heatmap.addSyntheticTap(x, y, ts); // we treat both as taps into same store
                    } catch (Exception parseEx) {
                        // Skip bad lines but don’t spam the log
                    }
                }
            }
        }
        catch (Exception e)
        {
            log.warn("[HUD] JSONL tailer stopped with error: {}", e.getMessage());
        }
    }

    // Extract integer after a token; tolerant of commas/spaces
    private static int extractInt(String s, String token)
    {
        int i = s.indexOf(token);
        if (i < 0) throw new IllegalArgumentException("token not found");
        i += token.length();
        int j = i;
        while (j < s.length() && (s.charAt(j) == ' ')) j++;
        int k = j;
        while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
        return Integer.parseInt(s.substring(j, k));
    }

    private static long extractLong(String s, String token)
    {
        int i = s.indexOf(token);
        if (i < 0) throw new IllegalArgumentException("token not found");
        i += token.length();
        int j = i;
        while (j < s.length() && (s.charAt(j) == ' ')) j++;
        int k = j;
        while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
        return Long.parseLong(s.substring(j, k));
    }

    private static void sleep(long ms)
    {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
