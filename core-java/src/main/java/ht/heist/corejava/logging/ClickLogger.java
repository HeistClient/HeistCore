// ============================================================================
// FILE: ClickLogger.java
// MODULE: core-rl
// PACKAGE: ht.heist.corerl.logging
// -----------------------------------------------------------------------------
// TITLE
//   ClickLogger — append TapEvent stream to JSONL and/or CSV on disk
//
// PURPOSE
//   - Persist raw click data (timestamp, canvas coords, button, modifiers,
//     and optional context) for offline analysis or replay.
//   - Works by registering itself as a TapBus listener.
//
// OUTPUT
//   - One JSONL file per session:  %USERPROFILE%/.runelite/heist-output/clicks-<session>.jsonl
//   - Optional CSV alongside it:   clicks-<session>.csv
//
// THREADING
//   - File appends are synchronized on "this" to avoid interleaving.
//   - Call start()/stop() from your plugin startup/shutdown.
//
// DEPENDENCIES
//   - Pure Java (no RuneLite types). Safe to be used by any module.
// ============================================================================

package ht.heist.corejava.logging;

import ht.heist.corejava.api.input.TapBus;
import ht.heist.corejava.api.input.TapEvent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class ClickLogger implements TapBus.Listener, Closeable
{
    private final String sessionId;
    private final File jsonlFile;
    private final File csvFile;
    private final boolean writeCsv;

    private volatile boolean running = false;
    private OutputStream jsonlOut;
    private OutputStream csvOut;

    // Create a logger bound to a sessionId and an output directory.
    // If writeCsv=false, only JSONL is produced.
    public ClickLogger(String sessionId, File outputDir, boolean writeCsv) {
        this.sessionId = sessionId;
        this.writeCsv = writeCsv;

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        String base = "clicks-" + (sessionId != null ? sessionId + "-" : "") + stamp;
        this.jsonlFile = new File(outputDir, base + ".jsonl");
        this.csvFile   = new File(outputDir, base + ".csv");
    }

    // Begin logging: open files and subscribe to the bus
    public synchronized void start() throws IOException {
        if (running) return;
        ensureParent(jsonlFile);
        jsonlOut = new BufferedOutputStream(new FileOutputStream(jsonlFile, true));
        if (writeCsv) {
            ensureParent(csvFile);
            boolean exists = csvFile.exists();
            csvOut = new BufferedOutputStream(new FileOutputStream(csvFile, true));
            if (!exists) {
                // CSV header on first creation
                writeCsvLine(csvOut,
                        "ts_ms,x_canvas,y_canvas,type,button,shift,ctrl,alt,session_id," +
                                "world_x,world_y,plane,scene_x,scene_y,widget_id,menu_option,menu_target,opcode,processed");
            }
        }
        TapBus.addListener(this);
        running = true;
    }

    // Stop logging: unsubscribe and close streams
    public synchronized void stop() throws IOException {
        if (!running) return;
        TapBus.removeListener(this);
        if (jsonlOut != null) jsonlOut.close();
        if (csvOut != null) csvOut.close();
        running = false;
    }

    // Listener callback — append a single event to on-disk logs
    @Override
    public synchronized void onTap(TapEvent e) {
        if (!running || e == null) return;
        try {
            // JSONL (newline-delimited JSON)
            String json = toJson(e) + "\n";
            jsonlOut.write(json.getBytes(StandardCharsets.UTF_8));

            // CSV (optional)
            if (writeCsv) {
                writeCsvLine(csvOut, toCsvRow(e));
            }
        } catch (IOException io) {
            // Best-effort; if disk fails, we don't crash the client
            // io.printStackTrace();
        }
    }

    // Required by Closeable (for try-with-resources or explicit close)
    @Override
    public void close() throws IOException { stop(); }

    // ---- Utilities ----------------------------------------------------------

    private static void ensureParent(File f) throws IOException {
        File p = f.getParentFile();
        if (p != null && !p.exists() && !p.mkdirs()) {
            throw new IOException("Unable to create dir: " + p);
        }
    }

    private static void writeCsvLine(OutputStream out, String line) throws IOException {
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.write('\n');
    }

    // Serialize one event to CSV (escaping commas and quotes as needed)
    private static String toCsvRow(TapEvent e) {
        return String.join(",",
                s(e.tsMs), s(e.xCanvas), s(e.yCanvas), q(e.type.name()), q(e.button.name()),
                s(e.shift), s(e.ctrl), s(e.alt), q(nullToEmpty(e.sessionId)),
                n(e.worldX), n(e.worldY), n(e.plane),
                n(e.sceneX), n(e.sceneY),
                n(e.widgetId),
                q(nullToEmpty(e.menuOption)), q(nullToEmpty(e.menuTarget)), n(e.opcode),
                n(e.processed));
    }

    // Serialize one event to a compact JSON object (no external libs)
    private static String toJson(TapEvent e) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{')
                .append("\"ts_ms\":").append(e.tsMs).append(',')
                .append("\"x_canvas\":").append(e.xCanvas).append(',')
                .append("\"y_canvas\":").append(e.yCanvas).append(',')
                .append("\"type\":\"").append(e.type.name()).append("\",")
                .append("\"button\":\"").append(e.button.name()).append("\",")
                .append("\"shift\":").append(e.shift).append(',')
                .append("\"ctrl\":").append(e.ctrl).append(',')
                .append("\"alt\":").append(e.alt);
        if (e.sessionId != null) sb.append(",\"session_id\":\"").append(escape(e.sessionId)).append('"');
        if (e.worldX != null) sb.append(",\"world_x\":").append(e.worldX);
        if (e.worldY != null) sb.append(",\"world_y\":").append(e.worldY);
        if (e.plane  != null) sb.append(",\"plane\":").append(e.plane);
        if (e.sceneX != null) sb.append(",\"scene_x\":").append(e.sceneX);
        if (e.sceneY != null) sb.append(",\"scene_y\":").append(e.sceneY);
        if (e.widgetId != null) sb.append(",\"widget_id\":").append(e.widgetId);
        if (e.menuOption != null) sb.append(",\"menu_option\":\"").append(escape(e.menuOption)).append('"');
        if (e.menuTarget != null) sb.append(",\"menu_target\":\"").append(escape(e.menuTarget)).append('"');
        if (e.opcode != null) sb.append(",\"opcode\":").append(e.opcode);
        if (e.processed != null) sb.append(",\"processed\":").append(e.processed);
        sb.append('}');
        return sb.toString();
    }

    // Basic CSV helpers
    private static String s(Object v) { return String.valueOf(v); }          // scalar to string
    private static String n(Object v) { return v == null ? "" : String.valueOf(v); } // nullable
    private static String q(String s) {
        // Quote and escape inner quotes for CSV safety
        String t = s.replace("\"","\"\"");
        return "\"" + t + "\"";
    }
    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    // Basic JSON escape for quotes/backslashes
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // Accessors for test/diagnostics
    public File getJsonlFile() { return jsonlFile; }
    public File getCsvFile()   { return csvFile; }
    public boolean isRunning() { return running; }
}
