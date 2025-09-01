// ============================================================================
// FILE: HeistHUDPlugin.java
// MODULE: heist-hud
// PACKAGE: ht.heist.hud
// -----------------------------------------------------------------------------
// TITLE
//   Heist HUD Plugin — wires overlays, starts tailer, PNG export, clear button
//
// WHAT IT DOES
//   • Adds Heatmap and CursorTracer overlays based on config toggles
//   • Starts/stops HeatmapService (JSONL tail + persistent store)
//   • Watches “Export heatmap now” → writes PNG → resets toggle
//   • Watches “Clear dots now”     → clears live+persistent → resets toggle
// ============================================================================
package ht.heist.hud;

import com.google.inject.Provides;
import ht.heist.hud.export.HeatmapExporter;
import ht.heist.hud.overlay.CursorTracerOverlay;
import ht.heist.hud.overlay.HeatmapOverlay;
import ht.heist.hud.service.HeatmapService;
import ht.heist.hud.service.HeatmapServiceImpl;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.List;

@PluginDescriptor(
        name = "Heist HUD",
        description = "Cursor tracer + heatmap (live & persistent) with JSONL ingest and PNG export.",
        enabledByDefault = true
)
public class HeistHUDPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(HeistHUDPlugin.class);

    // Injected
    @Inject private Client client;
    @Inject private OverlayManager overlayManager;
    @Inject private HeistHUDConfig cfg;
    @Inject private ConfigManager configManager;

    // Overlays
    @Inject private CursorTracerOverlay cursorTracerOverlay;
    @Inject private HeatmapOverlay heatmapOverlay;

    // Service (bind impl → iface in @Provides below)
    @Inject private HeatmapService heatmapService;

    @Provides HeistHUDConfig provideConfig(ConfigManager cm) { return cm.getConfig(HeistHUDConfig.class); }

    // Bind iface to impl for Guice
    @Provides HeatmapService provideHeatmapService(HeatmapServiceImpl impl) { return impl; }

    @Override
    protected void startUp()
    {
        // Start JSONL tail + persistent store
        heatmapService.start();

        // Add overlays only if enabled
        if (cfg.showHeatmap())      overlayManager.add(heatmapOverlay);
        if (cfg.showCursorTracer()) overlayManager.add(cursorTracerOverlay);

        log.info("[HeistHUD] started: heatmap={}, tracer={}", cfg.showHeatmap(), cfg.showCursorTracer());
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(heatmapOverlay);
        overlayManager.remove(cursorTracerOverlay);
        heatmapService.stop();
        log.info("[HeistHUD] stopped.");
    }

    // Handle export + clear “buttons”
    @Subscribe
    public void onGameTick(GameTick tick)
    {
        // --- Export button ----------------------------------------------------
        if (cfg.exportNow())
        {
            try {
                final int w = cfg.exportWidth();
                final int h = cfg.exportHeight();
                final int kr = cfg.exportKernelRadius();

                final List<ht.heist.hud.service.HeatmapService.HeatPoint> per = heatmapService.getPersistent();
                final List<ht.heist.hud.service.HeatmapService.HeatPoint> live =
                        cfg.exportIncludeLive() ? heatmapService.getLive(0, System.currentTimeMillis()) : List.of();

                final Path out = buildExportPath(cfg.exportDir());
                HeatmapExporter.exportPng(per, live, w, h, cfg.palette(), kr, cfg.exportIncludeLive(), out);
                log.info("[HeistHUD] Exported heatmap to {}", out.toAbsolutePath());
            }
            catch (Exception e)
            {
                log.warn("[HeistHUD] Export failed: {}", e.toString());
            }
            finally
            {
                // Auto-reset toggle so it behaves like a button
                configManager.setConfiguration("heist-hud", "exportNow", false);
            }
        }

        // --- Clear button -----------------------------------------------------
        if (cfg.clearAllNow())
        {
            try {
                heatmapService.clearAll();
                log.info("[HeistHUD] Cleared LIVE + PERSISTENT dots.");
            }
            catch (Exception e)
            {
                log.warn("[HeistHUD] Clear failed: {}", e.toString());
            }
            finally
            {
                // Auto-reset toggle so it behaves like a button
                configManager.setConfiguration("heist-hud", "clearAllNow", false);
            }
        }
    }

    private static Path buildExportPath(String dir)
    {
        String user = System.getenv("USERPROFILE");
        if (user == null || user.isBlank()) user = System.getProperty("user.home");
        String base = dir.replace("%USERPROFILE%", user);
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        return Paths.get(base, "heatmap-" + ts + ".png");
    }
}
