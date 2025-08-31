// (REPLACE with this updated version — only changes are the tailer wiring)
package ht.heist.hud;

import com.google.inject.Provides;
import ht.heist.hud.ingest.JsonlTailService;
import ht.heist.hud.overlay.CursorTracerOverlay;
import ht.heist.hud.overlay.HeatmapOverlay;
import ht.heist.hud.service.HeatmapService;
import ht.heist.hud.service.HeatmapServiceImpl;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

@PluginDescriptor(
        name = "Heist HUD",
        description = "Cursor tracer + click heatmap with file ingest plumbing.",
        tags = {"heist","hud","overlay","heatmap"}
)
public class HeistHUDPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(HeistHUDPlugin.class);

    @Inject private Client client;
    @Inject private OverlayManager overlayManager;

    @Inject private HeistHUDConfig config;
    @Inject private HeatmapService heatmapService;

    @Inject private HeatmapOverlay heatmapOverlay;
    @Inject private CursorTracerOverlay cursorOverlay;

    // NEW: file tailer
    @Inject private JsonlTailService jsonlTailer;

    @Override
    protected void startUp()
    {
        overlayManager.add(heatmapOverlay);
        overlayManager.add(cursorOverlay);

        // Start ingest if enabled
        jsonlTailer.start();

        log.info("[HeistHUD] started. showHeatmap={} showCursorTracer={} infrared={}",
                config.showHeatmap(), config.showCursorTracer(), config.heatmapInfraredPalette());
    }

    @Override
    protected void shutDown()
    {
        jsonlTailer.stop();

        overlayManager.remove(heatmapOverlay);
        overlayManager.remove(cursorOverlay);
        log.info("[HeistHUD] stopped.");
    }

    @Provides
    HeistHUDConfig provideConfig(ConfigManager cm) { return cm.getConfig(HeistHUDConfig.class); }

    @Provides
    HeatmapService provideHeatmapService(HeistHUDConfig cfg) { return new HeatmapServiceImpl(cfg); }
}
