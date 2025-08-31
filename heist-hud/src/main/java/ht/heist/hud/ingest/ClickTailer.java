// ============================================================================
// FILE: ClickTailer.java
// PACKAGE: ht.heist.hud.ingest
// -----------------------------------------------------------------------------
// TITLE
//   JSONL Click Tailer — ingests synthetic taps written by other plugins.
//
// WHAT IT DOES
//   • Opens (or waits for) the synthetic JSONL file
//     (default: ${user.home}/.runelite/heist-input/clicks.jsonl)
//   • Tails NEW lines (starts at end of file to avoid historical spam)
//   • For each JSON line, parses {ts,x,y,source} and calls:
//       heatmap.addSyntheticTap(x, y, ts)
//
// RELATIONSHIP TO CONFIG
//   • Obeys ONLY cfg.ingestSynthetic(): when OFF, events are skipped but we
//     keep the tailer running so toggling ON starts showing instantly.
//   • Path comes from cfg.syntheticClicksJsonlPath().
//
// ROBUSTNESS
//   • Handles file creation later, rotation (size shrink), and errors quietly.
//   • Parsing is lightweight (no JSON deps). We scan for keys manually.
//
// THREADING
//   • Background daemon thread. start()/stop() are idempotent.
// ============================================================================
package ht.heist.hud.ingest;

import ht.heist.hud.HeistHUDConfig;
import ht.heist.hud.service.HeatmapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

@Singleton
public class ClickTailer
{
    private static final Logger log = LoggerFactory.getLogger(ClickTailer.class);

    private final HeistHUDConfig cfg;
    private final HeatmapService heatmap;

    private volatile Thread worker;
    private volatile boolean running;

    @Inject
    public ClickTailer(HeistHUDConfig cfg, HeatmapService heatmap)
    {
        this.cfg = cfg;
        this.heatmap = heatmap;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------
    public synchronized void start()
    {
        if (running) return;
        running = true;
        worker = new Thread(this::runLoop, "HeistHUD-ClickTailer");
        worker.setDaemon(true);
        worker.start();
        log.info("ClickTailer started (path={})", cfg.syntheticClicksJsonlPath());
    }

    public synchronized void stop()
    {
        running = false;
        if (worker != null)
        {
            try { worker.join(500); } catch (InterruptedException ignored) {}
            worker = null;
        }
        log.info("ClickTailer stopped");
    }

    // -------------------------------------------------------------------------
    // Tail loop
    // -------------------------------------------------------------------------
    private void runLoop()
    {
        String path = cfg.syntheticClicksJsonlPath();

        RandomAccessFile raf = null;
        long fp = 0L; // file pointer

        while (running)
        {
            try
            {
                // Wait for file to exist
                File f = new File(path);
                if (!f.exists())
                {
                    sleep(300);
                    continue;
                }

                // Open if needed
                if (raf == null)
                {
                    raf = new RandomAccessFile(f, "r");
                    // Start at END to only process new taps from now on
                    fp = f.length();
                    raf.seek(fp);
                }

                // Detect rotation/shrink
                long len = f.length();
                if (len < fp)
                {
                    // file truncated — reopen and seek to end
                    try { raf.close(); } catch (Exception ignored) {}
                    raf = new RandomAccessFile(f, "r");
                    fp = f.length();
                    raf.seek(fp);
                }

                // Read new lines if any
                String line;
                while (running && (line = raf.readLine()) != null)
                {
                    fp = raf.getFilePointer();
                    handleLine(line);
                }

                // idle a bit
                sleep(60);
            }
            catch (Throwable t)
            {
                // Never crash; try again shortly.
                sleep(300);
            }
            finally
            {
                // Note: we keep the file open while running to reduce overhead.
                // We only close on rotation or on stop().
            }
        }

        if (raf != null) {
            try { raf.close(); } catch (Exception ignored) {}
        }
    }

    private void handleLine(String raw)
    {
        // Respect ONLY the synthetic ingest toggle.
        if (!cfg.ingestSynthetic()) return;
        if (raw == null || raw.isEmpty()) return;

        // RandomAccessFile.readLine() returns ISO-8859-1 encoded strings.
        // Convert bytes → UTF-8 safely (clicks JSON is ASCII anyway).
        byte[] bytes = raw.getBytes(StandardCharsets.ISO_8859_1);
        String line = new String(bytes, StandardCharsets.UTF_8).trim();

        // Very small JSONL parser: we just pull numbers for ts, x, y.
        // Expected:
        // {"ts": 1725074797651, "x": 532, "y": 248, "source": "synthetic"}
        try {
            long ts = extractLong(line, "\"ts\"");
            int  x  = (int)extractLong(line, "\"x\"");
            int  y  = (int)extractLong(line, "\"y\"");
            // Source can be ignored for the heatmap.
            heatmap.addSyntheticTap(x, y, ts);
        } catch (Exception ignored) {
            // Ignore malformed lines silently.
        }
    }

    // Simple extractor: finds "<key>": <number>
    private static long extractLong(String s, String key)
    {
        int i = s.indexOf(key);
        if (i < 0) throw new IllegalArgumentException("key not found");
        i = s.indexOf(':', i);
        if (i < 0) throw new IllegalArgumentException("colon not found");
        i++;
        // skip spaces
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        // read sign+digits
        int j = i;
        while (j < s.length())
        {
            char c = s.charAt(j);
            if (!(c == '-' || (c >= '0' && c <= '9'))) break;
            j++;
        }
        if (j == i) throw new IllegalArgumentException("no number");
        return Long.parseLong(s.substring(i, j));
    }

    private static void sleep(long ms)
    {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
