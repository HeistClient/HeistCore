package ht.heist.hud.ingest;

import ht.heist.hud.service.HeatPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Tiny, polling-based tailer for a JSONL file with lines like:
 * {"x":493,"y":271,"ts":1725072345123}
 *
 * It keeps the last read position and reads any newly-appended lines
 * every 250ms. Lines that fail to parse are skipped quietly.
 */
@Singleton
public class JsonlTailService
{
    private static final Logger log = LoggerFactory.getLogger(JsonlTailService.class);

    private final ScheduledExecutorService exec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Heist-JsonlTail");
                t.setDaemon(true);
                return t;
            });

    private volatile ScheduledFuture<?> task;
    private volatile String path;
    private volatile long position;

    public void setPath(String path)
    {
        this.path = path;
        this.position = 0L; // reset when path changes
    }

    public synchronized void start(TapConsumer consumer)
    {
        stop();
        if (path == null || path.isEmpty()) return;

        task = exec.scheduleWithFixedDelay(() -> {
            try {
                File f = new File(path);
                if (!f.exists()) return;

                try (RandomAccessFile raf = new RandomAccessFile(f, "r"))
                {
                    if (position > raf.length())
                        position = 0; // file truncated/rotated, start from top

                    raf.seek(position);

                    String line;
                    List<HeatPoint> batch = new ArrayList<>(64);
                    while ((line = raf.readLine()) != null)
                    {
                        position = raf.getFilePointer();
                        HeatPoint hp = parse(line);
                        if (hp != null) batch.add(hp);
                        if (batch.size() >= 128) break; // small cap per tick
                    }

                    if (!batch.isEmpty()) consumer.accept(batch);
                }
            } catch (Throwable t) {
                // Never crash the scheduler; log & continue
                log.debug("JSONL tail error: {}", t.getMessage());
            }
        }, 0, 250, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop()
    {
        if (task != null) {
            task.cancel(true);
            task = null;
        }
    }

    /** Minimal JSONL parser (no dep). */
    private HeatPoint parse(String line)
    {
        // Super lenient: scan for x:, y:, ts:
        try {
            String s = new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8).trim();
            int xi = s.indexOf("\"x\":");
            int yi = s.indexOf("\"y\":");
            int ti = s.indexOf("\"ts\":");
            if (xi < 0 || yi < 0 || ti < 0) return null;

            int x = Integer.parseInt(numAfter(s, xi + 4));
            int y = Integer.parseInt(numAfter(s, yi + 4));
            long ts = Long.parseLong(numAfter(s, ti + 5));
            return new HeatPoint(x, y, ts);
        } catch (Exception ignored) { return null; }
    }

    private String numAfter(String s, int from)
    {
        int i = from;
        while (i < s.length() && (s.charAt(i) == ' ')) i++;
        int j = i;
        while (j < s.length() && "-0123456789".indexOf(s.charAt(j)) >= 0) j++;
        return s.substring(i, j);
    }

    @FunctionalInterface
    public interface TapConsumer {
        void accept(List<HeatPoint> newPoints);
    }
}
