// ============================================================================
// FILE: InputRecorderImpl.java
// PACKAGE: ht.heist.core.impl
// -----------------------------------------------------------------------------
// PURPOSE
//   Concrete InputRecorder implementation.
//   • HUMAN: attaches AWT listeners to Canvas to capture real user input.
//   • SYNTHETIC: accepts macro clicks via recordSyntheticClick(Point).
//   • Separate output paths for human vs synthetic.
//   • Auto-creates parent directories if missing.
// DESIGN CHOICES
//   - No Lombok (to avoid your earlier errors). Using SLF4J directly.
//   - No checked exceptions in methods (simple callers).
// ============================================================================
package ht.heist.core.impl;

import ht.heist.core.config.HeistCoreConfig;
import ht.heist.core.services.InputRecorder;
import net.runelite.api.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Canvas;
import java.awt.Point;
import java.awt.event.*;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Singleton
public class InputRecorderImpl implements InputRecorder
{
    // ---- Logging ------------------------------------------------------------
    private static final Logger log = LoggerFactory.getLogger(InputRecorderImpl.class);

    // ---- Injected runtime ---------------------------------------------------
    private final Client client;
    private final HeistCoreConfig cfg;

    // ---- State --------------------------------------------------------------
    private boolean recording = false;

    // Separate buffers: human vs synthetic
    private final List<String> humanEvents = new ArrayList<>();
    private final List<String> syntheticEvents = new ArrayList<>();

    // Macro capture toggle + path
    private boolean recordSynthetic = true;
    private String syntheticOutPath = "C:/Users/barhoo/.runelite/heist-input/analytics-synthetic.json";

    // ---- Canvas listeners for HUMAN input ----------------------------------
    private final MouseAdapter mouseAdapter = new MouseAdapter()
    {
        @Override public void mousePressed(MouseEvent e)  { addHuman("HW_DOWN " + e.getX() + "," + e.getY() + " t=" + ts()); }
        @Override public void mouseReleased(MouseEvent e) { addHuman("HW_UP   " + e.getX() + "," + e.getY() + " t=" + ts()); }
        @Override public void mouseMoved(MouseEvent e)    { addHuman("HW_MOVE " + e.getX() + "," + e.getY() + " t=" + ts()); }
        @Override public void mouseDragged(MouseEvent e)  { addHuman("HW_DRAG " + e.getX() + "," + e.getY() + " t=" + ts()); }
    };
    private final KeyAdapter keyAdapter = new KeyAdapter()
    {
        @Override public void keyPressed(KeyEvent e)  { addHuman("HW_KEY_DOWN " + e.getKeyCode() + " t=" + ts()); }
        @Override public void keyReleased(KeyEvent e) { addHuman("HW_KEY_UP   " + e.getKeyCode() + " t=" + ts()); }
    };

    // ---- DI -----------------------------------------------------------------
    @Inject
    public InputRecorderImpl(Client client, HeistCoreConfig cfg)
    {
        this.client = client;
        this.cfg = cfg;
        // Initialize macro toggles from config defaults
        this.recordSynthetic = cfg.recordSyntheticClicks();
        this.syntheticOutPath = safe(cfg.recorderSyntheticOutputPath(), this.syntheticOutPath);
    }

    // ========================================================================
    // === InputRecorder API ===================================================
    // ========================================================================
    @Override
    public void start(String activityTag)
    {
        if (recording) return;

        humanEvents.clear();
        syntheticEvents.clear();

        addHuman("SESSION_START tag=" + (activityTag == null ? "DEFAULT" : activityTag) + " t=" + ts());
        this.recording = true;

        Canvas c = client.getCanvas();
        if (c != null)
        {
            c.addMouseListener(mouseAdapter);
            c.addMouseMotionListener(mouseAdapter);
            c.addKeyListener(keyAdapter);
        }
        log.info("InputRecorder: started (activity={})", activityTag);
    }

    @Override
    public void stopAndSave()
    {
        if (!recording) return;

        recording = false;

        Canvas c = client.getCanvas();
        if (c != null)
        {
            c.removeMouseListener(mouseAdapter);
            c.removeMouseMotionListener(mouseAdapter);
            c.removeKeyListener(keyAdapter);
        }

        addHuman("SESSION_END t=" + ts());
        writeBothNow();
        log.info("InputRecorder: stopped; human->{} synthetic->{}", resolveHumanPath(), syntheticOutPath);
    }

    @Override
    public void resetData()
    {
        humanEvents.clear();
        syntheticEvents.clear();
        addHuman("RESET t=" + ts());
        log.info("InputRecorder: buffers reset");
    }

    @Override
    public void saveNow()
    {
        writeBothNow();
        log.info("InputRecorder: buffers exported on-demand (human & synthetic)");
    }

    @Override
    public boolean isRecording()
    {
        return recording;
    }

    @Override
    public void recordSyntheticClick(Point p)
    {
        if (!recording || !recordSynthetic || p == null) return;
        syntheticEvents.add("BOT_CLICK " + p.x + "," + p.y + " t=" + ts());
    }

    @Override
    public void setRecordSynthetic(boolean enable)
    {
        this.recordSynthetic = enable;
        log.info("InputRecorder: recordSynthetic={}", enable);
    }

    @Override
    public void setSyntheticOutputPath(String path)
    {
        if (path != null && !path.trim().isEmpty())
        {
            this.syntheticOutPath = path.trim();
            log.info("InputRecorder: synthetic output path set to {}", this.syntheticOutPath);
        }
    }

    // ========================================================================
    // === Helpers =============================================================
    // ========================================================================
    private void addHuman(String s)
    {
        humanEvents.add(s);
    }

    private long ts()
    {
        return System.currentTimeMillis();
    }

    private String resolveHumanPath()
    {
        String path = safe(cfg.recorderOutputPath(),
                safe(cfg.recorderExportPathCompat(),
                        System.getProperty("user.home") + File.separator + ".runelite" + File.separator + "heist-input" + File.separator + "analytics.json"));
        return path;
    }

    private static String safe(String preferred, String fallback)
    {
        if (preferred == null) return fallback;
        String t = preferred.trim();
        return t.isEmpty() ? fallback : t;
    }

    private void ensureParentDir(String outPath)
    {
        try
        {
            File f = new File(outPath).getAbsoluteFile();
            File parent = f.getParentFile();
            if (parent != null && !parent.exists())
            {
                boolean ok = parent.mkdirs();
                if (!ok)
                {
                    log.warn("InputRecorder: could not create {}", parent);
                }
            }
        }
        catch (Exception ex)
        {
            log.warn("InputRecorder: ensureParentDir failed: {}", ex.getMessage());
        }
    }

    private void writeArrayTo(String out, List<String> lines)
    {
        ensureParentDir(out);
        try (FileWriter fw = new FileWriter(out, StandardCharsets.UTF_8, false))
        {
            fw.write("[\n");
            for (int i = 0; i < lines.size(); i++)
            {
                String line = lines.get(i).replace("\"", "\\\"");
                fw.write("  \"" + line + "\"");
                if (i < lines.size() - 1) fw.write(",");
                fw.write("\n");
            }
            fw.write("]\n");
        }
        catch (Exception ex)
        {
            log.warn("InputRecorder: write failed for {}: {}", out, ex.getMessage(), ex);
        }
    }

    private void writeBothNow()
    {
        // HUMAN
        writeArrayTo(resolveHumanPath(), humanEvents);

        // SYNTHETIC
        if (recordSynthetic)
        {
            writeArrayTo(syntheticOutPath, syntheticEvents);
        }
    }
}
