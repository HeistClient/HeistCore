// ============================================================================
// FILE: HeatmapServiceImpl.java
// MODULE: heist-hud
// PACKAGE: ht.heist.hud.service
// -----------------------------------------------------------------------------
// TITLE
//   Heatmap Service — robust JSONL tail + persistent store + clear operations
//
// HIGHLIGHTS
//   • Regex-based JSONL parsing (tolerates negatives and mixed schemas)
//   • Every tap goes into LIVE (fading) and PERSISTENT (long-lived) lists
//   • start()/stop() manage a tail thread that never dies on bad rows
//   • clearLive()/clearPersistent()/clearAll() for UI “buttons”
// ============================================================================
package ht.heist.hud.service;

import ht.heist.hud.HeistHUDConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Singleton
public class HeatmapServiceImpl implements HeatmapService
{
    private static final Logger log = LoggerFactory.getLogger(HeatmapServiceImpl.class);

    private final HeistHUDConfig cfg;

    // Live + persistent stores (thread-safe)
    private final CopyOnWriteArrayList<HeatPoint> live = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<HeatPoint> persistent = new CopyOnWriteArrayList<>();

    // Tailer state
    private Thread tailThread;
    private volatile boolean running = false;

    // Robust field extractors
    private static final Pattern PX  = Pattern.compile("\"x\"\\s*:\\s*(-?\\d+)");
    private static final Pattern PY  = Pattern.compile("\"y\"\\s*:\\s*(-?\\d+)");
    private static final Pattern PTS = Pattern.compile("\"ts\"\\s*:\\s*(\\d+)");

    @Inject
    public HeatmapServiceImpl(HeistHUDConfig cfg)
    {
        this.cfg = cfg;
    }

    @Override
    public void start()
    {
        running = true;

        if (!cfg.enableJsonlIngest()) {
            log.info("[HeatmapService] JSONL ingest disabled.");
            return;
        }

        final Path file = expand(cfg.syntheticInputPath());
        log.info("[HeatmapService] Tail starting: {}", file.toAbsolutePath());

        // Eager backfill: load current file into PERSISTENT (once)
        backfillFile(file);

        tailThread = new Thread(() -> tailLoop(file), "Heist-HeatmapTail");
        tailThread.setDaemon(true);
        tailThread.start();
    }

    @Override
    public void stop()
    {
        running = false;
        if (tailThread != null) tailThread.interrupt();
        tailThread = null;
    }

    @Override
    public void addLiveTap(int x, int y, long ts)
    {
        final HeatPoint hp = new HeatPoint(x, y, ts);
        live.add(hp);
        persistent.add(hp);
    }

    @Override
    public List<HeatPoint> getLive(long sinceMs, long nowMs)
    {
        return Collections.unmodifiableList(live);
    }

    @Override
    public List<HeatPoint> getPersistent()
    {
        return Collections.unmodifiableList(persistent);
    }

    // --- Clear operations -----------------------------------------------------

    @Override
    public void clearLive()
    {
        live.clear();
        log.info("[HeatmapService] Live buffer cleared.");
    }

    @Override
    public void clearPersistent()
    {
        persistent.clear();
        log.info("[HeatmapService] Persistent store cleared.");
    }

    // -------------------------------------------------------------------------
    // Tail helpers
    // -------------------------------------------------------------------------
    private void backfillFile(Path file)
    {
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.createFile(file);
                return;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int added = 0;
            for (String s : lines) {
                if (s == null) continue;
                String line = s.trim();
                if (line.isEmpty()) continue;

                Integer x = groupInt(PX, line);
                Integer y = groupInt(PY, line);
                if (x == null || y == null) continue;
                Long ts = groupLong(PTS, line);
                if (ts == null) ts = Instant.now().toEpochMilli();

                persistent.add(new HeatPoint(x, y, ts));
                added++;
            }
            log.info("[HeatmapService] Backfilled {} persistent taps.", added);
        } catch (Exception e) {
            log.warn("[HeatmapService] Backfill failed: {}", e.toString());
        }
    }

    private void tailLoop(Path file)
    {
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) Files.createFile(file);
        } catch (Exception e) {
            log.warn("[HeatmapService] Cannot create path {}: {}", file, e.toString());
            return;
        }

        long lastLine = 0;
        long lastLog  = 0;

        while (running)
        {
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                final int total = lines.size();
                if (total < lastLine) lastLine = 0; // rotated/truncated

                int ingested = 0;

                for (int i = (int) lastLine; i < total; i++)
                {
                    final String s = lines.get(i);
                    if (s == null) continue;
                    final String line = s.trim();
                    if (line.isEmpty()) continue;

                    try {
                        Integer x = groupInt(PX, line);
                        Integer y = groupInt(PY, line);
                        if (x == null || y == null) continue;
                        Long ts = groupLong(PTS, line);
                        if (ts == null) ts = Instant.now().toEpochMilli();

                        final HeatPoint hp = new HeatPoint(x, y, ts);
                        live.add(hp);
                        persistent.add(hp);
                        ingested++;
                    } catch (Exception perLine) {
                        if (System.currentTimeMillis() - lastLog > 3000) {
                            log.debug("[HeatmapService] bad row ignored: {}", truncate(line, 200));
                            lastLog = System.currentTimeMillis();
                        }
                    }
                }

                if (ingested > 0) {
                    log.info("[HeatmapService] ingested {} taps (live={}, persistent={})",
                            ingested, live.size(), persistent.size());
                }

                lastLine = total;
                Thread.sleep(350);
            }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            catch (Exception loopError) {
                log.warn("[HeatmapService] tail read error: {}", loopError.toString());
                try { Thread.sleep(800); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }

        log.info("[HeatmapService] Tail stopped.");
    }

    // Regex helpers & tiny utils
    private static Integer groupInt(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (!m.find()) return null;
        return Integer.parseInt(m.group(1));
    }

    private static Long groupLong(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (!m.find()) return null;
        return Long.parseLong(m.group(1));
    }

    private static String truncate(String s, int max) {
        return (s.length() <= max) ? s : s.substring(0, max) + "...";
    }

    private static Path expand(String p)
    {
        String user = System.getenv("USERPROFILE");
        if (user == null || user.isBlank()) user = System.getProperty("user.home");
        return Paths.get(p.replace("%USERPROFILE%", user));
    }
}
