// ============================================================================
// FILE: HeistCorePlugin.java
// PACKAGE: ht.heist.core
// -----------------------------------------------------------------------------
// TITLE
//   Heist Core plugin — binds core services and manages core overlays.
//   - Binds: MouseService, HeatmapService, CursorTracerService, InventoryService,
//            HumanizerService, InputRecorder (all interface -> impl).
//   - Adds/removes overlays (Heatmap, Cursor Tracer).
//   - Listens to Heist Core config changes (export heatmap now; recorder toggles).
//   - Publishes a heatmap “tap sink” + recorder via CoreLocator so feature
//     plugins can send real click points to Core without depending on a
//     particular HeatmapService method name.
// ============================================================================

package ht.heist.core;

import ht.heist.core.config.HeistCoreConfig;
import ht.heist.core.impl.*;
import ht.heist.core.services.*;
import ht.heist.core.ui.CoreHeatmapOverlay;
import ht.heist.core.ui.CursorTracerOverlay;

import com.google.inject.Provides;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

@PluginDescriptor(
        name = "Heist Core",
        description = "Core services, overlays, recorder, and humanizer for Heist plugins.",
        tags = {"heist", "core", "overlay"}
)
public class HeistCorePlugin extends Plugin
{
    // ===== Logging ===========================================================
    private static final Logger log = LoggerFactory.getLogger(HeistCorePlugin.class);

    // ===== Injected: RL base + config =======================================
    @Inject private Client client;
    @Inject private ConfigManager configManager;
    @Inject private HeistCoreConfig config;

    // ===== Injected: Services (interfaces) ==================================
    @Inject private MouseService mouse;
    @Inject private HeatmapService heatmap;
    @Inject private CursorTracerService tracer;
    @Inject private InventoryService inventory;
    @Inject private HumanizerService humanizer;
    @Inject private InputRecorder recorder;

    // ===== Injected: Overlays + manager =====================================
    @Inject private OverlayManager overlayManager;
    @Inject private CoreHeatmapOverlay heatmapOverlay;
    @Inject private CursorTracerOverlay tracerOverlay;

    // ========================================================================
    // STARTUP / SHUTDOWN
    // ========================================================================
    @Override
    protected void startUp()
    {
        // Add overlays once; overlays read config to decide whether to draw.
        overlayManager.add(heatmapOverlay);
        overlayManager.add(tracerOverlay);

        // Recorder initial state
        if (config.recorderEnabled())
        {
            recorder.start(config.recorderActivityTag());
            log.info("InputRecorder started (activity={})", config.recorderActivityTag());
        }

        // --- Publish “tap sink” + recorder to other plugins via CoreLocator ---
        //     Woodcutter will call CoreLocator.getHeatmapTapSink().accept(pt)
        //     and CoreLocator.getRecorder().recordSyntheticClick(pt).
        CoreLocator.setHeatmapTapSink(pt -> {
            try {
                // Try several common point-logging method names
                java.lang.reflect.Method m = null;
                final Class<?> c = heatmap.getClass();
                for (String name : new String[]{"recordTap", "recordClick", "tap", "push", "addPoint", "record"})
                {
                    try { m = c.getMethod(name, java.awt.Point.class); break; }
                    catch (NoSuchMethodException ignore) { /* try next */ }
                }
                if (m != null) {
                    m.invoke(heatmap, pt);
                } else {
                    // Nothing matched — harmless; just skip
                    log.debug("HeatmapService has no (recordTap/recordClick/tap/push/addPoint/record)(Point). Tap ignored.");
                }
            } catch (Throwable t) {
                log.debug("Heatmap tap invoke failed: {}", t.toString());
            }
        });
        CoreLocator.setRecorder(recorder);

        log.info("Heist Core started");
    }

    @Override
    protected void shutDown()
    {
        // Stop recorder if running
        if (recorder.isRecording())
        {
            try {
                recorder.stopAndSave(); // writes to config.recorderOutputPath()
                log.info("InputRecorder stopped and saved");
            } catch (Exception e) {
                log.warn("Recorder save failed: {}", e.getMessage(), e);
            }
        }

        // Clear shared pointers so other plugins don't hold stale refs
        CoreLocator.setHeatmapTapSink(null);
        CoreLocator.setRecorder(null);

        // Remove overlays
        overlayManager.remove(heatmapOverlay);
        overlayManager.remove(tracerOverlay);

        log.info("Heist Core stopped");
    }

    // ========================================================================
    // CONFIG CHANGES
    // ========================================================================
    @Subscribe
    public void onConfigChanged(ConfigChanged e)
    {
        if (!"heistcore".equals(e.getGroup()))
        {
            return;
        }

        switch (e.getKey())
        {
            // ---- Export heatmap NOW (boolean button) ------------------------
            case "heatmapExportNow":
            {
                boolean trigger = Boolean.parseBoolean(
                        configManager.getConfiguration("heistcore", "heatmapExportNow")
                );
                if (trigger)
                {
                    try {
                        String path = heatmap.exportSnapshot();
                        log.info("Heatmap exported to {}", path);
                    } catch (Exception ex) {
                        log.warn("Heatmap export failed: {}", ex.getMessage(), ex);
                    }
                    finally {
                        // auto-reset to false so the "button" re-arms
                        configManager.setConfiguration("heistcore", "heatmapExportNow", "false");
                    }
                }
                break;
            }

            // ---- Recorder on/off --------------------------------------------
            case "recorderEnabled":
            {
                if (config.recorderEnabled() && !recorder.isRecording())
                {
                    recorder.start(config.recorderActivityTag());
                    log.info("Recorder started (activity={})", config.recorderActivityTag());
                }
                else if (!config.recorderEnabled() && recorder.isRecording())
                {
                    try {
                        recorder.stopAndSave();
                        log.info("Recorder stopped and saved");
                    } catch (Exception ex) {
                        log.warn("Recorder save failed: {}", ex.getMessage(), ex);
                    }
                }
                break;
            }

            // ---- Recorder activity tag live change --------------------------
            case "recorderActivityTag":
            {
                if (recorder.isRecording())
                {
                    try {
                        recorder.stopAndSave();
                        log.info("Recorder auto-saved on activity change");
                    } catch (Exception ex) {
                        log.warn("Recorder auto-save failed: {}", ex.getMessage(), ex);
                    }
                    recorder.start(config.recorderActivityTag());
                    log.info("Recorder restarted (activity={})", config.recorderActivityTag());
                }
                break;
            }

            // NOTE: showHeatmap / showCursorTracer are read in overlays directly.
        }
    }

    // ========================================================================
    // GUICE BINDINGS (interface -> impl) — centralized for Core
    // ========================================================================
    @Provides HeistCoreConfig provideHeistCoreConfig(ConfigManager cm)
    {
        return cm.getConfig(HeistCoreConfig.class);
    }

    @Provides MouseService provideMouse(MouseServiceImpl impl) { return impl; }

    @Provides HeatmapService provideHeatmap(HeatmapServiceImpl impl) { return impl; }

    @Provides CursorTracerService provideTracer(CursorTracerServiceImpl impl) { return impl; }

    @Provides InventoryService provideInventory(InventoryServiceImpl impl) { return impl; }

    @Provides HumanizerService provideHumanizer(HumanizerServiceImpl impl) { return impl; }

    @Provides InputRecorder provideRecorder(InputRecorderImpl impl) { return impl; }
}
