// ============================================================================
// FILE: SyntheticTapLogger.java
// MODULE: woodcutter-plugin
// PACKAGE: ht.heist.plugins.woodcutter.io
// -----------------------------------------------------------------------------
// TITLE
//   Synthetic Tap Logger — write each synthetic click to JSONL
//
// WHY
//   • Decouples woodcutter from HUD: just write taps; HUD tails the file.
//   • Logs the absolute path on first write so you can confirm paths match.
//
// FORMAT (one line per click)
//   {"ts":1725140000000,"x":493,"y":271}
//
// DEFAULT PATH
//   %USERPROFILE%/.runelite/heist-input/clicks.jsonl
//
// Make sure HUD config → syntheticInputPath points to the SAME file.
// ============================================================================
package ht.heist.plugins.woodcutter.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

public class SyntheticTapLogger
{
    private static final Logger log = LoggerFactory.getLogger(SyntheticTapLogger.class);

    private final Path path;
    private final AtomicBoolean loggedPathOnce = new AtomicBoolean(false);

    public SyntheticTapLogger()
    {
        this.path = expand("%USERPROFILE%/.runelite/heist-input/clicks.jsonl");
        ensureParentDirs(path);
    }

    public void log(int x, int y)
    {
        try {
            if (loggedPathOnce.compareAndSet(false, true)) {
                log.info("[SyntheticTapLogger] writing to {}", path.toAbsolutePath());
            }
            String line = "{\"ts\":" + Instant.now().toEpochMilli() + ",\"x\":" + x + ",\"y\":" + y + "}\n";
            try (BufferedWriter out = Files.newBufferedWriter(
                    path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND))
            {
                out.write(line);
            }
        } catch (Exception e) {
            // don't crash woodcutter — heatmap is a bonus
        }
    }

    private static Path expand(String p)
    {
        String user = System.getenv("USERPROFILE");
        if (user == null || user.isBlank()) user = System.getProperty("user.home");
        return Paths.get(p.replace("%USERPROFILE%", user));
    }

    private static void ensureParentDirs(Path file)
    {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception ignored) {}
    }
}
