// ============================================================================
// FILE: SyntheticTapLogger.java
// PACKAGE: ht.heist.plugins.woodcutter.io
// -----------------------------------------------------------------------------
// TITLE
//   Synthetic Tap Logger — tiny, dependency-free JSONL writer used by the
//   woodcutter plugin to record *synthetic* clicks for the HUD heatmap.
//
// WHAT THIS CLASS DOES
//   • Writes ONE JSON object per line (JSONL) to a portable default file:
//       %USERPROFILE%/.runelite/heist-input/clicks.jsonl   (Windows)
//       $HOME/.runelite/heist-input/clicks.jsonl           (macOS/Linux)
//   • Format example (compact on purpose):
//       {"ts":1725072345123,"act":"WOODCUT","type":"synthetic","x":493,"y":271}
//
// DESIGN GOALS
//   • ZERO external deps. Pure JDK (java.nio) for correctness + portability.
//   • Thread-safe lazy initialization (safe to call from any plugin thread).
//   • Never throws out to the game loop. On any I/O issue it simply no-ops.
//   • Flushes after each line so the HUD tailer can see new taps immediately.
//
// HOW TO USE (no changes to your existing code):
//   SyntheticTapLogger.log(target.x, target.y);
//
// OPTIONAL (if you want):
//   SyntheticTapLogger.setActivity("WOODCUT");            // label in JSON
//   SyntheticTapLogger.setEnabled(true/false);            // quick kill switch
//   SyntheticTapLogger.setPathOverride("D:/tmp/taps.jsonl"); // custom file
//   SyntheticTapLogger.close();                           // optional on shutdown
//
// NOTE ABOUT THE PACKAGE NAME
//   • You *do not* need a separate "corelib" module. Keeping this package name
//     preserves your existing imports and call sites.
//   • This file simply lives in the woodcutter module under the same package.
// ============================================================================

package ht.heist.plugins.woodcutter.io;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.Paths;
import java.time.Instant;

public final class SyntheticTapLogger
{
    // ----------------------------- SINGLETON STATE ---------------------------
    /** Prevent instantiation; this is a static utility. */
    private SyntheticTapLogger() {}

    /** Synchronization guard for opening/closing the writer. */
    private static final Object LOCK = new Object();

    /** Lazily opened writer; null until first successful init. */
    private static volatile BufferedWriter OUT = null;

    /** Destination path. If null, we resolve a portable default at first use. */
    private static volatile Path OUT_PATH = null;

    /** Quick enable/disable switch (so you don’t need to if-guard every call). */
    private static volatile boolean ENABLED = true;

    /** Activity tag written into each line (helps segment sessions). */
    private static volatile String ACTIVITY = "WOODCUT";

    // ----------------------------- PUBLIC CONFIG -----------------------------

    /**
     * Change the activity tag written with each line (defaults to "WOODCUT").
     * @param tag e.g. "WOODCUT", "FLETCH", "FISHING"
     */
    public static void setActivity(String tag)
    {
        if (tag != null && !tag.isEmpty())
        {
            ACTIVITY = tag;
        }
    }

    /**
     * Enable or disable logging. When disabled, log() becomes a no-op.
     */
    public static void setEnabled(boolean on)
    {
        ENABLED = on;
    }

    /**
     * Override the output file. If already open, closes the current writer so
     * the next log() will target the new path.
     *
     * @param pathStr absolute or relative path (your choice)
     */
    public static void setPathOverride(String pathStr)
    {
        if (pathStr == null || pathStr.isEmpty()) return;
        synchronized (LOCK)
        {
            closeQuietly();
            OUT_PATH = Paths.get(pathStr);
        }
    }

    // ------------------------------ PUBLIC I/O --------------------------------

    /**
     * Append one synthetic tap (x,y) to the JSONL file.
     * Safe to call often; no exceptions escape to the caller.
     */
    public static void log(int x, int y)
    {
        if (!ENABLED) return;

        ensureOpen();                    // lazily open the writer
        final BufferedWriter w = OUT;    // snapshot after ensureOpen()
        if (w == null) return;           // open failed → silently skip

        try
        {
            // Milliseconds since epoch for easy merge with other logs
            final long ts = Instant.now().toEpochMilli();

            // Compact JSON line: keep fields consistent with HUD tailer
            //  - ts   : timestamp
            //  - act  : activity label
            //  - type : "synthetic" (HUD can distinguish from "human" if needed)
            //  - x,y  : canvas coordinates
            final String json =
                    "{\"ts\":" + ts +
                            ",\"act\":\"" + ACTIVITY + "\"" +
                            ",\"type\":\"synthetic\"" +
                            ",\"x\":" + x +
                            ",\"y\":" + y +
                            "}\n";

            w.write(json);
            w.flush(); // flush immediately so HUD can visualize live taps
        }
        catch (Exception ignored)
        {
            // Never bubble up I/O to the game loop
        }
    }

    /**
     * Optional cleanup: closes the writer if open.
     * It’s fine to ignore this — the JVM will close on exit.
     */
    public static void close()
    {
        synchronized (LOCK) { closeQuietly(); }
    }

    // ------------------------------ INTERNALS ---------------------------------

    /** Opens the writer if not open. Thread-safe. */
    private static void ensureOpen()
    {
        if (OUT != null) return;

        synchronized (LOCK)
        {
            if (OUT != null) return;

            try
            {
                // Resolve default if not overridden:
                //   <user.home>/.runelite/heist-input/clicks.jsonl
                if (OUT_PATH == null)
                {
                    final String home = System.getProperty("user.home"); // works on Win/macOS/Linux
                    OUT_PATH = Paths.get(home, ".runelite", "heist-input", "clicks.jsonl");
                }

                // Ensure parent directories exist
                final Path parent = OUT_PATH.getParent();
                if (parent != null) Files.createDirectories(parent);

                // Open append-only, UTF-8
                OUT = Files.newBufferedWriter(
                        OUT_PATH,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.WRITE
                );
            }
            catch (Exception e)
            {
                // If anything goes wrong, keep OUT == null so log() becomes a no-op
                OUT = null;
            }
        }
    }

    /** Quietly closes the current writer and nulls the handle. */
    private static void closeQuietly()
    {
        try { if (OUT != null) OUT.close(); } catch (Exception ignored) {}
        OUT = null;
    }
}
