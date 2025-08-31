// ============================================================================
// FILE: HeistHUDPlugin.java
// PACKAGE: ht.heist.hud
// -----------------------------------------------------------------------------
// TITLE
//   Heist HUD — overlays + ingest wiring + CoreLocator sink + exports.
//
// WHY THIS VERSION FIXES YOUR TOGGLE ISSUE
//   • Producers NEVER check toggles; they always push events:
//       - CoreLocator sink → heatmap.addSyntheticTap(...)
//       - ClickTailer      → heatmap.addSyntheticTap(...)
//       - RecorderHuman    → heatmap.addHumanTap(...)
//   • Only the SERVICE checks toggles (setAcceptHuman / setAcceptSynthetic).
//   • Overlay only renders what the service stored; it never checks ingest.
//
// WHAT WE DO HERE
//   • Add overlays
//   • Publish sink for synthetic taps from other plugins
//   • Start/stop recorder + tailer
//   • Keep HeatmapService ingest toggles in sync with config
//   • Handle Export/ Clear buttons
// ============================================================================

package ht.heist.hud;

import com.google.inject.Provides;
import ht.heist.corelib.bridge.CoreLocator;
import ht.heist.hud.ingest.ClickTailer;
import ht.heist.hud.ingest.RecorderHuman;
import ht.heist.hud.overlay.CursorTracerOverlay;
import ht.heist.hud.overlay.HeatmapOverlay;
import ht.heist.hud.service.HeatmapService;
import ht.heist.hud.service.HeatmapServiceImpl;
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
import javax.inject.Singleton;

@PluginDescriptor(
        name = "Heist HUD",
        description = "Infrared heatmap + cursor tracer + ingest/record/export",
        tags = { "heist", "hud", "heatmap", "cursor" }
)
public class HeistHUDPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(HeistHUDPlugin.class);

    // ----- RL base + overlays ------------------------------------------------
    @Inject private Client client;
    @Inject private OverlayManager overlayManager;
    @Inject private HeatmapOverlay heatmapOverlay;
    @Inject private CursorTracerOverlay cursorOverlay;

    // ----- Config + service --------------------------------------------------
    @Inject private HeistHUDConfig cfg;
    @Inject private HeatmapService heatmap;

    // ----- Ingest helpers ----------------------------------------------------
    @Inject private RecorderHuman recorderHuman;
    @Inject private ClickTailer tailer;

    // ----- Config manager (for resetting button toggles) ---------------------
    @Inject private ConfigManager cm;

    // ========================================================================
    // STARTUP / SHUTDOWN
    // ========================================================================
    @Override
    protected void startUp()
    {
        overlayManager.add(heatmapOverlay);
        overlayManager.add(cursorOverlay);

        // Keep service ingest toggles synced with config (the service enforces them)
        heatmap.setAcceptHuman(cfg.ingestHuman());
        heatmap.setAcceptSynthetic(cfg.ingestSynthetic());

        // CoreLocator sink: PRODUCER pushes always; service decides whether to accept.
        CoreLocator.setHeatmapTapSink(pt -> {
            if (pt != null) {
                heatmap.addSyntheticTap(pt.x, pt.y, System.currentTimeMillis());
            }
        });

        // File tailer: also a PRODUCER that always pushes
        tailer.configurePath(cfg.syntheticClicksJsonlPath());
        tailer.setOnTap((x, y, ts) -> heatmap.addSyntheticTap(x, y, ts));
        tailer.start();

        // Human recorder: produces both file output (if you use it) AND in-memory taps
        if (cfg.recordHumanClicks()) recorderHuman.start(); else recorderHuman.stop();

        log.info("Heist HUD started");
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(heatmapOverlay);
        overlayManager.remove(cursorOverlay);

        CoreLocator.setHeatmapTapSink(null);

        try { tailer.stop(); }        catch (Exception ignored) {}
        try { recorderHuman.stop(); } catch (Exception ignored) {}

        log.info("Heist HUD stopped");
    }

    // ========================================================================
    // CONFIG CHANGES — keep service flags synced + handle buttons
    // ========================================================================
    @Subscribe
    public void onConfigChanged(ConfigChanged e)
    {
        if (!"heisthud".equals(e.getGroup())) return;

        switch (e.getKey())
        {
            case "ingestHuman":
                heatmap.setAcceptHuman(cfg.ingestHuman());
                break;

            case "ingestSynthetic":
                heatmap.setAcceptSynthetic(cfg.ingestSynthetic());
                break;

            case "recordHumanClicks":
                if (cfg.recordHumanClicks()) recorderHuman.start();
                else                          recorderHuman.stop();
                break;

            case "syntheticClicksJsonlPath":
                tailer.configurePath(cfg.syntheticClicksJsonlPath());
                break;

            case "clearNow":
                if (cfg.clearNow()) {
                    heatmap.clear();
                    cm.setConfiguration("heisthud", "clearNow", "false");
                }
                break;

            case "exportPngNow":
                if (cfg.exportPngNow()) {
                    try {
                        String out = heatmap.exportPng(cfg.exportFolder());
                        log.info("Heatmap PNG exported: {}", out);
                    } catch (Exception ex) {
                        log.warn("PNG export failed", ex);
                    } finally {
                        cm.setConfiguration("heisthud","exportPngNow","false");
                    }
                }
                break;

            case "exportJsonNow":
                if (cfg.exportJsonNow()) {
                    try {
                        String out = heatmap.exportJson(cfg.exportFolder());
                        log.info("Heatmap JSON exported: {}", out);
                    } catch (Exception ex) {
                        log.warn("JSON export failed", ex);
                    } finally {
                        cm.setConfiguration("heisthud","exportJsonNow","false");
                    }
                }
                break;
        }
    }

    // ========================================================================
    // BINDINGS (Guice @Provides)
    // ========================================================================
    @Provides
    HeistHUDConfig provideConfig(ConfigManager cm) {
        return cm.getConfig(HeistHUDConfig.class);
    }

    @Provides
    @Singleton
    HeatmapService provideHeatmapService(Client client, HeistHUDConfig cfg) {
        return new HeatmapServiceImpl(client, cfg);
    }
}
