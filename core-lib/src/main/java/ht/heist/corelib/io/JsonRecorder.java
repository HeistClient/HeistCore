// ============================================================================
// FILE: JsonRecorder.java
// PACKAGE: ht.heist.corelib.io
// -----------------------------------------------------------------------------
// TITLE
//   JsonRecorder — dead-simple newline JSON logger for human/synthetic clicks.
//
// WHAT THIS CLASS DOES
//   • Writes each click as one compact JSON line to a UTF-8 text file.
//   • Maintains two files:
//       - humanPath:     all "human" inputs you log (optional)
//       - syntheticPath: all "macro" (synthetic) inputs you log (optional)
//   • You choose the paths via configurePaths(...).
//   • You choose the "activity tag" via start(activityTag).
//   • stopAndSave() flushes & closes both writers.
//
// FORMAT OF EACH LINE (example)
//   {"ts":1725072345123,"act":"DEFAULT","type":"synthetic","x":493,"y":271}
//
// WHY NIO (Files.newBufferedWriter) INSTEAD OF FileWriter
//   - Avoids the confusing FileWriter(Charset...) constructor overload order.
//   - Lets us specify CREATE + TRUNCATE_EXISTING cleanly.
//   - Avoids platform-default charset bugs.
// ============================================================================

package ht.heist.corelib.io;

import java.awt.Point;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public class JsonRecorder implements Recorder
{
    // ---------- Output paths (may be null if that stream is disabled) --------
    private String humanPath = null;
    private String syntheticPath = null;

    // ---------- Runtime state -------------------------------------------------
    private String activity = "DEFAULT";    // Tag written with each event
    private BufferedWriter humanOut = null; // Writer for human clicks
    private BufferedWriter synthOut = null; // Writer for synthetic clicks

    // ========================================================================
    // CONFIGURE OUTPUT DESTINATIONS
    // ========================================================================
    @Override
    public void configurePaths(String humanPath, String syntheticPath)
    {
        // Accept nulls: recorder will simply skip that stream if path is null
        this.humanPath = humanPath;
        this.syntheticPath = syntheticPath;
    }

    // ========================================================================
    // START RECORDING (opens/creates both files and truncates them)
    // ========================================================================
    @Override
    public void start(String activityTag)
    {
        // Use "DEFAULT" when not provided - avoids empty labels in logs
        this.activity = (activityTag == null || activityTag.isEmpty()) ? "DEFAULT" : activityTag;

        // Close any prior resources just in case (idempotent)
        closeQuietly();

        try
        {
            // Open/prepare human stream if a path was configured
            if (humanPath != null)
            {
                Path hp = Path.of(humanPath);
                ensureParentDirs(hp);
                // CREATE file if missing, TRUNCATE_EXISTING to start fresh
                humanOut = Files.newBufferedWriter(
                        hp,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );
            }

            // Open/prepare synthetic stream if a path was configured
            if (syntheticPath != null)
            {
                Path sp = Path.of(syntheticPath);
                ensureParentDirs(sp);
                synthOut = Files.newBufferedWriter(
                        sp,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );
            }
        }
        catch (Exception e)
        {
            // If anything goes wrong, release resources and surface as runtime
            closeQuietly();
            throw new RuntimeException("Recorder start failed: " + e.getMessage(), e);
        }
    }

    // ========================================================================
    // WRITE EVENTS (no-ops when that stream is not configured)
    // ========================================================================
    @Override
    public void recordHumanClick(Point p)
    {
        write(humanOut, "human", p);
    }

    @Override
    public void recordSyntheticClick(Point p)
    {
        write(synthOut, "synthetic", p);
    }

    // ========================================================================
    // LIFECYCLE QUERIES / STOP
    // ========================================================================
    @Override
    public boolean isRecording()
    {
        return humanOut != null || synthOut != null;
    }

    @Override
    public void stopAndSave() throws Exception
    {
        // Just flush + close; we treat each "start()" as a fresh file session
        closeQuietly();
    }

    // ========================================================================
    // INTERNAL: ONE-LINE JSON WRITE (fires only when writer != null)
    // ========================================================================
    private void write(BufferedWriter out, String type, Point p)
    {
        if (out == null || p == null) return;
        try
        {
            long ts = Instant.now().toEpochMilli();

            // Build compact JSON manually to avoid extra dependencies
            // NOTE: activity/tag is not escaped here — if you allow free text
            //       inputs, you might want to escape quotes.
            String json = "{\"ts\":" + ts
                    + ",\"act\":\"" + activity + "\""
                    + ",\"type\":\"" + type + "\""
                    + ",\"x\":" + p.x
                    + ",\"y\":" + p.y
                    + "}\n";

            out.write(json);
            out.flush(); // flush so data is visible on disk even if client crashes
        }
        catch (Exception ignored) { /* do not crash the game loop on I/O issues */ }
    }

    // ========================================================================
    // INTERNAL: UTILITIES
    // ========================================================================
    private static void ensureParentDirs(Path path) throws Exception
    {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
    }

    private void closeQuietly()
    {
        try { if (humanOut != null) humanOut.close(); } catch (Exception ignored) {}
        try { if (synthOut != null) synthOut.close(); } catch (Exception ignored) {}
        humanOut = null;
        synthOut = null;
    }
}
