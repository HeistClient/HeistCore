// ============================================================================
// FILE: HeistHUDPlugin.java
// PACKAGE: ht.heist.hud
// -----------------------------------------------------------------------------
// TITLE
//   Heist HUD — overlays + ingest + export + CoreLocator bridge.
//
// KEY FIX IN THIS VERSION
//   • RecorderHuman (AWT listener) is started ALWAYS at startup so
//     “Accept human clicks” controls drawing independently from the
//     “Record human clicks to JSONL” toggle (which now ONLY controls writing).
//
// WHAT THIS PLUGIN DOES
//   • Adds overlays (HeatmapOverlay, CursorTracerOverlay)
//   • Publishes CoreLocator heatmap sink (synthetic taps from other plugins)
//   • Starts human recorder unconditionally; tailer for synthetic JSONL
//   • Handles config buttons: Clear / Export PNG / Export JSON
//
// BINDINGS
//   - @Provides HeistHUDConfig
//   - @Provides @Singleton HeatmapService → HeatmapServiceImpl
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
        tags = {"heist", "hud", "heatmap", "cursor"}
)
public class HeistHUDPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(HeistHUDPlugin.class);

    // ----- RuneLite/Guice injected deps -------------------------------------
    @Inject private Client client;
    @Inject private OverlayManager overlayManager;

    @Inject private HeistHUDConfig cfg;
    @Inject private HeatmapService heatmap;

    @Inject private HeatmapOverlay heatmapOverlay;
    @Inject private CursorTracerOverlay cursorOverlay;

    @Inject private RecorderHuman recorderHuman; // <— now runs ALWAYS, see startUp()
    @Inject private ClickTailer tailer;

    // For resetting “button” toggles after we act on them
    @Inject private ConfigManager cm;
    private ConfigManager getConfigManager() { return cm; }

    // =========================================================================
    // LIFECYCLE
    // =========================================================================
    @Override
    protected void startUp()
    {
        overlayManager.add(heatmapOverlay);
        overlayManager.add(cursorOverlay);

        // Publish a shared sink so thin plugins can report synthetic taps.
        CoreLocator.setHeatmapTapSink(pt -> {
            if (pt != null && cfg.ingestSynthetic()) {
                heatmap.addSyntheticTap(pt.x, pt.y, System.currentTimeMillis());
            }
        });

        // IMPORTANT CHANGE:
        // Always start AWT human listener. Internally it will:
        //  • draw → only if cfg.ingestHuman()
        //  • write → only if cfg.recordHumanClicks()
        recorderHuman.start();

        // Tail synthetic JSONL (always safe to run)
        tailer.start();

        log.info("Heist HUD started");
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(heatmapOverlay);
        overlayManager.remove(cursorOverlay);

        CoreLocator.setHeatmapTapSink(null);

        try { recorderHuman.stop(); } catch (Exception ignored) {}
        try { tailer.stop(); }        catch (Exception ignored) {}

        log.info("Heist HUD stopped");
    }

    // =========================================================================
    // CONFIG REACTIONS (buttons + toggles)
    // =========================================================================
    @Subscribe
    public void onConfigChanged(ConfigChanged e)
    {
        if (!"heisthud".equals(e.getGroup())) return;

        switch (e.getKey())
        {
            case "clearNow":
                if (cfg.clearNow()) {
                    heatmap.clear();
                    getConfigManager().setConfiguration("heisthud", "clearNow", "false");
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
                        getConfigManager().setConfiguration("heisthud", "exportPngNow", "false");
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
                        getConfigManager().setConfiguration("heisthud", "exportJsonNow", "false");
                    }
                }
                break;

            // NOTE: We do NOT start/stop recorderHuman here anymore.
            //       It always runs; it checks cfg.ingestHuman() and
            //       cfg.recordHumanClicks() for draw/write decisions.
        }
    }

    // =========================================================================
    // BINDINGS (Guice @Provides)
    // =========================================================================
    @Provides
    HeistHUDConfig provideConfig(ConfigManager cm)
    {
        return cm.getConfig(HeistHUDConfig.class);
    }

    @Provides
    @Singleton
    HeatmapService provideHeatmapService(Client client, HeistHUDConfig cfg)
    {
        return new HeatmapServiceImpl(client, cfg);
    }
}
