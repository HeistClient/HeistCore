// ============================================================================
// FILE: ClickLogger.java
// MODULE: core-java (logging)
// PACKAGE: ht.heist.corejava.logging
// -----------------------------------------------------------------------------
// TITLE
//   ClickLogger — Append-only JSONL/CSV writer for TapEvent + Phase-A records.
//
// PURPOSE
//   • Listens to TapBus for TapEvent and writes JSONL lines (and optional CSV).
//   • Exposes writeMoveFeatures(...) and writeTapResult(...) for Phase A.
//   • Creates per-session files under a user-chosen output directory.
//
// FILES
//   • JSONL (always):  clicks-<session>-<timestamp>.jsonl
//   • CSV   (opt)  :  clicks-<session>-<timestamp>.csv
//
// THREADING
//   • Methods are synchronized; writes are atomic per line.
//   • Uses a TapBus.Listener registered on start() and removed on stop().
//
// SCHEMA NOTES
//   • JSONL lines include a top-level "kind":
//       - kind:"tap"          (TapEvent)
//       - kind:"move_features"
//       - kind:"tap_result"
//   • Nulls are omitted in JSON.
//
// DEPENDENCIES
//   • Pure Java; TapBus/TapEvent are API-only.
// ============================================================================

package ht.heist.corejava.logging;

import ht.heist.corejava.api.input.TapBus;
import ht.heist.corejava.api.input.TapEvent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class ClickLogger
{
    private final String sessionId;
    private final File   outDir;
    private final boolean alsoCsv;

    private Writer jsonl;
    private Writer csv;
    private TapBus.Listener busListener;

    public ClickLogger(String sessionId, File outDir, boolean alsoCsv)
    {
        this.sessionId = sessionId;
        this.outDir    = outDir;
        this.alsoCsv   = alsoCsv;
    }

    // ---- Lifecycle ----------------------------------------------------------

    public synchronized void start() throws IOException
    {
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("Cannot create output directory: " + outDir.getAbsolutePath());
        }
        final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        final File jsonlFile = new File(outDir, "clicks-" + sessionId + "-" + stamp + ".jsonl");
        jsonl = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(jsonlFile, true), StandardCharsets.UTF_8));

        if (alsoCsv) {
            final File csvFile = new File(outDir, "clicks-" + sessionId + "-" + stamp + ".csv");
            csv = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(csvFile, true), StandardCharsets.UTF_8));
            // CSV header (stable subset)
            csv.write("ts_ms,x_canvas,y_canvas,type,button,shift,ctrl,alt,session_id,event_id\n");
            csv.flush();
        }

        // Subscribe to TapBus so all TapEvents get captured automatically
        busListener = new TapBus.Listener() {
            @Override public void onTap(TapEvent ev) {
                try { writeTap(ev); } catch (IOException ignored) {}
            }
        };
        TapBus.addListener(busListener);
    }

    public synchronized void stop() throws IOException
    {
        if (busListener != null) {
            TapBus.removeListener(busListener);
            busListener = null;
        }
        if (jsonl != null) { jsonl.flush(); jsonl.close(); jsonl = null; }
        if (csv   != null) { csv.flush();   csv.close();   csv   = null; }
    }

    // ---- Writers ------------------------------------------------------------

    /** Write a TapEvent line to JSONL (and CSV subset). */
    public synchronized void writeTap(TapEvent ev) throws IOException
    {
        if (jsonl == null) return;
        final StringBuilder sb = new StringBuilder(256);
        sb.append("{\"kind\":\"tap\"");
        append(sb, "ts_ms", ev.tsMs);
        append(sb, "x_canvas", ev.xCanvas);
        append(sb, "y_canvas", ev.yCanvas);
        append(sb, "type", ev.type != null ? ev.type.name() : null);
        append(sb, "button", ev.button != null ? ev.button.name() : null);
        append(sb, "shift", ev.shift);
        append(sb, "ctrl", ev.ctrl);
        append(sb, "alt", ev.alt);
        append(sb, "session_id", ev.sessionId);
        append(sb, "event_id", ev.eventId);
        // Optional context (only include if non-null)
        append(sb, "world_x", ev.worldX);
        append(sb, "world_y", ev.worldY);
        append(sb, "plane",   ev.plane);
        append(sb, "scene_x", ev.sceneX);
        append(sb, "scene_y", ev.sceneY);
        append(sb, "widget_id", ev.widgetId);
        append(sb, "menu_option", ev.menuOption);
        append(sb, "menu_target", ev.menuTarget);
        append(sb, "opcode", ev.opcode);
        append(sb, "processed", ev.processed);
        sb.append("}\n");
        jsonl.write(sb.toString());
        jsonl.flush();

        if (csv != null) {
            // Stable CSV subset (no option/target/opcode to keep it simple)
            csv.write(
                    ev.tsMs + "," + ev.xCanvas + "," + ev.yCanvas + "," +
                            (ev.type != null ? ev.type.name() : "") + "," +
                            (ev.button != null ? ev.button.name() : "") + "," +
                            ev.shift + "," + ev.ctrl + "," + ev.alt + "," +
                            safe(ev.sessionId) + "," + safe(ev.eventId) + "\n"
            );
            csv.flush();
        }
    }

    /** Write a MoveFeatures line to JSONL. */
    public synchronized void writeMoveFeatures(MoveFeatures f) throws IOException
    {
        if (jsonl == null || f == null) return;
        final StringBuilder sb = new StringBuilder(256);
        sb.append("{\"kind\":\"move_features\"");
        append(sb, "session_id", f.sessionId);
        append(sb, "event_id",   f.eventId);
        append(sb, "source",     f.source);

        append(sb, "dwell_before_press_ms", f.dwellBeforePressMs);
        append(sb, "reaction_jitter_ms",    f.reactionJitterMs);
        append(sb, "hold_ms",               f.holdMs);

        append(sb, "path_length_px",        f.pathLengthPx);
        append(sb, "duration_ms",           f.durationMs);
        append(sb, "step_count",            f.stepCount);
        append(sb, "curvature_signed",      f.curvatureSigned);
        append(sb, "wobble_amp_px",         f.wobbleAmpPx);

        append(sb, "drift_alpha",           f.driftAlpha);
        append(sb, "drift_noise_std",       f.driftNoiseStd);
        append(sb, "drift_state_x",         f.driftStateX);
        append(sb, "drift_state_y",         f.driftStateY);

        sb.append("}\n");
        jsonl.write(sb.toString());
        jsonl.flush();
    }

    /** Write a TapResult line to JSONL. */
    public synchronized void writeTapResult(TapResult r) throws IOException
    {
        if (jsonl == null || r == null) return;
        final StringBuilder sb = new StringBuilder(192);
        sb.append("{\"kind\":\"tap_result\"");
        append(sb, "session_id", r.sessionId);
        append(sb, "event_id",   r.eventId);
        append(sb, "processed",  r.processed);
        append(sb, "ts_ms",      r.tsMs);
        append(sb, "menu_option",r.menuOption);
        append(sb, "menu_target",r.menuTarget);
        append(sb, "opcode",     r.opcode);
        sb.append("}\n");
        jsonl.write(sb.toString());
        jsonl.flush();
    }

    // ---- Tiny JSON helpers (omit nulls) ------------------------------------

    private static void append(StringBuilder sb, String key, String val) {
        if (val == null) return;
        sb.append(",\"").append(key).append("\":\"").append(escape(val)).append("\"");
    }
    private static void append(StringBuilder sb, String key, Number val) {
        if (val == null) return;
        sb.append(",\"").append(key).append("\":").append(val);
    }
    private static void append(StringBuilder sb, String key, Boolean val) {
        if (val == null) return;
        sb.append(",\"").append(key).append("\":").append(val ? "true" : "false");
    }
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
    private static String safe(String s) {
        return s == null ? "" : s.replace(",", " ");
    }
}
