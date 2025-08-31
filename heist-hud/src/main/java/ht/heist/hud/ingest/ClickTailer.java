// ============================================================================
// FILE: ClickTailer.java
// PACKAGE: ht.heist.hud.ingest
// -----------------------------------------------------------------------------
// TITLE
//   JSONL Tailer for Synthetic Taps (FILE BRIDGE across separate JARs)
//
// OVERVIEW
//   • Tails a newline-delimited JSON file (JSONL) where feature plugins write
//     synthetic taps, e.g.  {"ts":<long>,"x":<int>,"y":<int>,"source":"synthetic"}
//   • Emits parsed taps through a tiny callback interface (TapHandler).
//   • Robust against the file not existing yet (keeps retrying).
//
// WHY A FILE?
//   RuneLite loads each plugin in its own classloader; statics across JARs
//   won’t be shared. A file is the simplest, reliable bridge.
//
// PUBLIC API
//   • configurePath(String)   -> set/replace the JSONL path to tail
//   • setOnTap(TapHandler)    -> set a consumer for parsed taps (x,y,ts)
//   • start()                 -> spawn a daemon thread that tails the file
//   • stop()                  -> stop the thread cleanly
//
// THREADING
//   • One background daemon thread (“Heist-ClickTailer”) that:
//       - polls the file every ~200–300ms
//       - remembers last byte position with RandomAccessFile.seek()
//       - reads any new lines, parses them, invokes TapHandler
//
// FAILURE/RECOVERY
//   • If the file disappears/rotates, the next loop iteration re-opens it
//     and resumes from start if needed.
// ============================================================================

package ht.heist.hud.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Singleton
public class ClickTailer
{
    // ----- Logging -----------------------------------------------------------
    private static final Logger log = LoggerFactory.getLogger(ClickTailer.class);

    // ----- Configurable path (volatile so updates are visible to worker) -----
    private volatile String path = System.getProperty("user.home") + "/.runelite/heist-input/clicks.jsonl";

    // ----- Callback: minimal functional interface (no external deps) ---------
    @FunctionalInterface
    public interface TapHandler {
        /** Called for each parsed tap in the tailed file. */
        void onTap(int x, int y, long ts);
    }

    // Default no-op handler so we never NPE
    private volatile TapHandler onTap = (x, y, ts) -> {};

    // ----- Worker thread bookkeeping ----------------------------------------
    private volatile Thread worker;
    private volatile boolean running = false;

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /** Set/replace the file path to tail (safe to call before/after start). */
    public void configurePath(String p)
    {
        if (p != null && !p.isEmpty()) {
            this.path = p;
            log.info("ClickTailer path set to {}", this.path);
        }
    }

    /** Register a tap consumer; null resets to a no-op. */
    public void setOnTap(TapHandler h)
    {
        this.onTap = (h != null ? h : (x, y, ts) -> {});
    }

    /** Start (or restart) the tailing thread. */
    public synchronized void start()
    {
        stop(); // idempotent restart behavior
        running = true;
        worker = new Thread(this::runLoop, "Heist-ClickTailer");
        worker.setDaemon(true);
        worker.start();
        log.info("ClickTailer started (path={})", path);
    }

    /** Stop the tailing thread. */
    public synchronized void stop()
    {
        running = false;
        if (worker != null) {
            try { worker.interrupt(); } catch (Exception ignored) {}
            worker = null;
        }
        log.info("ClickTailer stopped");
    }

    // =========================================================================
    // WORKER LOOP
    // =========================================================================

    /** Background tailer loop — re-opens the file each cycle and reads new lines. */
    private void runLoop()
    {
        long lastPos = 0L; // byte offset into the file we’ve processed

        while (running)
        {
            try
            {
                final File file = new File(path);

                // Ensure parent dir exists so feature plugins can create the file later
                Path parent = Paths.get(path).getParent();
                if (parent != null) {
                    try { Files.createDirectories(parent); } catch (Exception ignored) {}
                }

                if (!file.exists()) {
                    // File not created yet — just wait and try again
                    Thread.sleep(300);
                    continue;
                }

                // If the file shrank/rotated, reset our offset
                if (lastPos > file.length()) lastPos = 0L;

                // Open and seek to our last processed offset
                try (RandomAccessFile raf = new RandomAccessFile(file, "r"))
                {
                    raf.seek(lastPos);
                    String line;
                    while ((line = raf.readLine()) != null)
                    {
                        lastPos = raf.getFilePointer(); // remember how far we got
                        parseAndEmit(line);             // parse JSON and call handler
                    }
                }

                // Polling cadence — light on IO, responsive enough for UI
                Thread.sleep(200);
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                break; // graceful shutdown
            }
            catch (Exception ex)
            {
                // Any transient parse/IO issue: keep the loop alive.
                log.debug("ClickTailer loop error: {}", ex.getMessage());
                try { Thread.sleep(400); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
    }

    // =========================================================================
    // PARSER (minimal, allocation-light)
    // =========================================================================

    /**
     * Parse a line like:
     *   {"ts":1725070000000,"x":421,"y":312,"source":"synthetic"}
     * We only care about ts/x/y for drawing; source is ignored by the tailer.
     */
    private void parseAndEmit(String raw)
    {
        if (raw == null) return;
        final String s = raw.trim();
        if (s.isEmpty() || s.charAt(0) != '{') return;

        try {
            long ts = extractLong(s, "\"ts\":");
            int  x  = (int)extractLong(s, "\"x\":");
            int  y  = (int)extractLong(s, "\"y\":");

            // Fire the callback — HUD wires this to heatmap.addSyntheticTap(...)
            onTap.onTap(x, y, ts);
        } catch (Exception ignored) {
            // swallow malformed lines to keep the tailer resilient
        }
    }

    /**
     * Find a numeric value after a JSON key prefix, very minimal parsing:
     *   s = '{"ts":123,"x":45}', key="\"ts\":"  -> returns 123
     */
    private static long extractLong(String s, String key)
    {
        int i = s.indexOf(key);
        if (i < 0) return 0L;
        i += key.length();

        // skip spaces
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;

        // parse digits
        int j = i;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c >= '0' && c <= '9') j++;
            else break;
        }
        if (j == i) return 0L;
        return Long.parseLong(s.substring(i, j));
    }
}
