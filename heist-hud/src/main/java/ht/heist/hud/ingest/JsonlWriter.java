// ============================================================================
// FILE: JsonlWriter.java
// PACKAGE: ht.heist.hud.ingest
// -----------------------------------------------------------------------------
// TITLE
//   JSONL Writer — Append click events to ${user.home}/.runelite/heist-input
//
// PURPOSE
//   Small helper that appends a single-line JSON object per click,
//   matching the schema you approved (with graceful fallbacks for synthetic).
//
// FILE LOCATION
//   Default: ${user.home}/.runelite/heist-input/clicks.jsonl
//   (Use resolveUnderRunelite() to keep relative paths portable.)
// ============================================================================
package ht.heist.hud.ingest;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class JsonlWriter
{
    private final Path defaultFile;

    public JsonlWriter()
    {
        this.defaultFile = Paths.get(System.getProperty("user.home"),
                ".runelite", "heist-input", "clicks.jsonl");
    }

    /** Append a standardized JSONL click line (safe, never throws). */
    public void appendClick(long ts, int x, int y, String source,
                            int button, boolean shift, boolean ctrl, boolean alt,
                            String plugin, String targetType, int[] targetBounds)
    {
        try
        {
            ensureParent(defaultFile);
            try (BufferedWriter w = Files.newBufferedWriter(
                    defaultFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND))
            {
                // Build JSON line manually (fast, no extra deps)
                StringBuilder sb = new StringBuilder(256);
                sb.append('{');
                sb.append("\"ts\":").append(ts).append(',');
                sb.append("\"x\":").append(x).append(',');
                sb.append("\"y\":").append(y).append(',');
                sb.append("\"source\":\"").append(escape(source)).append("\",");
                sb.append("\"button\":").append(button).append(',');
                sb.append("\"modifiers\":{")
                        .append("\"shift\":").append(shift).append(',')
                        .append("\"ctrl\":").append(ctrl).append(',')
                        .append("\"alt\":").append(alt).append("},");
                sb.append("\"plugin\":\"").append(escape(plugin)).append("\",");

                // Optional target block
                if (targetType != null)
                {
                    sb.append("\"target\":{");
                    sb.append("\"type\":\"").append(escape(targetType)).append("\"");
                    if (targetBounds != null && targetBounds.length == 4)
                    {
                        sb.append(",\"bounds\":[")
                                .append(targetBounds[0]).append(',')
                                .append(targetBounds[1]).append(',')
                                .append(targetBounds[2]).append(',')
                                .append(targetBounds[3]).append(']');
                    }
                    sb.append("}");
                }
                else
                {
                    sb.append("\"target\":null");
                }

                // Session id omitted for now; can add later
                sb.append('}');

                w.write(sb.toString());
                w.newLine();
            }
        }
        catch (IOException ignored) { /* never break runtime input */ }
    }

    // ----- Helpers -----------------------------------------------------------
    private static void ensureParent(Path file) throws IOException
    {
        Path parent = file.getParent();
        if (parent != null && !Files.exists(parent))
            Files.createDirectories(parent);
    }

    private static String escape(String s)
    {
        if (s == null) return "";
        // very small escape (quotes + backslash); sufficient for our fields
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Resolve relative folders under ${user.home}/.runelite/. */
    public static Path resolveUnderRunelite(String configuredFolder)
    {
        Path p = Paths.get(configuredFolder == null ? "" : configuredFolder);
        if (p.isAbsolute()) return p;
        return Paths.get(System.getProperty("user.home"), ".runelite").resolve(p);
    }
}
