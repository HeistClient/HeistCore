// ============================================================================
// FILE: SyntheticTapLogger.java
// PACKAGE: ht.heist.corelib.io
// -----------------------------------------------------------------------------
// TITLE
//   Synthetic Tap Logger — cross-plugin bridge via JSONL file.
//
// WHY THIS EXISTS
//   • RuneLite loads each plugin in a separate classloader. Any "shared static"
//     between JARs is actually duplicated, so a static sink won't cross JARs.
//   • We persist synthetic tap events to a shared JSONL file that ALL plugins
//     agree on (path). The HUD tails that file and ingests taps reliably.
//
// DESIGN
//   • Tiny, dependency-free helper — pure Java I/O; **no** RuneLite imports.
//   • Thread-safe: synchronized appends (click frequency is low — fine).
//   • Self-contained: auto-creates parent directories when first used.
//   • Path is configurable (optional), with a sensible default.
//
// JSONL FORMAT (one line per event)
//   {"ts": 1725074797651, "x": 532, "y": 248, "source": "synthetic"}
//
// COMPANION
//   Heist HUD’s ClickTailer continuously tails the same JSONL path and calls
//   heatmap.addSyntheticTap(x, y, ts).
// ============================================================================
package ht.heist.corelib.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class SyntheticTapLogger
{
    // --- Do not instantiate --------------------------------------------------
    private SyntheticTapLogger() {}

    // --- CONFIGURABLE OUTPUT PATH -------------------------------------------
    // Default path is the SAME as HeistHUDConfig.syntheticClicksJsonlPath() default.
    // Keep these in sync to avoid surprises.
    private static volatile Path OUTPUT = Paths.get(
            System.getProperty("user.home"),
            ".runelite", "heist-input", "clicks.jsonl"
    );

    /**
     * OPTIONAL: override the output file path (absolute).
     * If null/empty, call is ignored and default remains.
     */
    public static void setPath(String absolutePath)
    {
        if (absolutePath == null || absolutePath.isEmpty()) return;
        OUTPUT = Paths.get(absolutePath);
    }

    /** The current absolute output path as a String (for debugging/logging). */
    public static String currentPath()
    {
        return OUTPUT.toAbsolutePath().toString();
    }

    // --- PUBLIC API: log tap -------------------------------------------------
    /**
     * Append a synthetic tap to the shared JSONL file.
     * Safe to call frequently; minimal overhead; silent on I/O failure.
     *
     * @param x canvas X coordinate
     * @param y canvas Y coordinate
     */
    public static void log(int x, int y)
    {
        final long ts = System.currentTimeMillis();
        appendJsonLine(ts, x, y);
    }

    // --- Internal: file append ----------------------------------------------
    private static synchronized void appendJsonLine(long ts, int x, int y)
    {
        try {
            ensureParentDir(OUTPUT);
            try (BufferedWriter w = Files.newBufferedWriter(
                    OUTPUT,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE))
            {
                // Tiny JSON; no external libs; keep keys stable.
                w.write("{\"ts\":");
                w.write(Long.toString(ts));
                w.write(",\"x\":");
                w.write(Integer.toString(x));
                w.write(",\"y\":");
                w.write(Integer.toString(y));
                w.write(",\"source\":\"synthetic\"}");
                w.newLine(); // JSONL requires newline
            }
        } catch (IOException ignored) {
            // Intentionally silent: input must never crash the bot logic if disk hiccups.
        }
    }

    private static void ensureParentDir(Path file) throws IOException
    {
        final Path parent = file.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }
}
