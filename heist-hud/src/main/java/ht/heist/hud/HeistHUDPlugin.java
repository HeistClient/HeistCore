// ============================================================================
// FILE: HeistHUDPlugin.java
// MODULE: heist-hud
// PACKAGE: ht.heist.hud
// -----------------------------------------------------------------------------
// TITLE
//   HeistHUDPlugin — wires overlays/panel, subscribes to TapBus, captures
//   manual clicks via RuneLite MouseAdapter, and runs on-disk logging.
//
// CRITICAL FIX
//   - The TapBus listener is created/registered in startUp() (after Guice
//     injection). We *do not* bind method refs at field init time.
//   - This avoids the constructor-time NPE you saw.
//
// SIDEBAR ICON
//   - We load a PNG from the plugin’s resources and assign it to the
//     NavigationButton via .icon(...). Without an icon, some RL versions
//     don’t render the tab at all.
//   - Your icon file path (in repo): heist-hud/src/main/resources/heist_hud_icon.png
//   - Its classpath resource path (used below): "/heist_hud_icon.png"
//
// DEPENDENCIES
//   - core-java: ht.heist.corejava.api.input.* , ht.heist.corejava.logging.*
//   - RuneLite: mouse manager, overlays, client toolbar.
// ============================================================================

package ht.heist.hud;

import com.google.inject.Provides;
import ht.heist.corejava.api.input.TapBus;
import ht.heist.corejava.api.input.TapEvent;
import ht.heist.corejava.api.input.TapEvent.Button;
import ht.heist.corejava.api.input.TapEvent.Type;
import ht.heist.corejava.logging.ClickLogger;
import ht.heist.hud.overlay.ClickDotsOverlay;
import ht.heist.hud.overlay.CursorTracerOverlay;
import ht.heist.hud.service.ClickStore;
import ht.heist.hud.ui.HeistHUDPanel;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.input.MouseAdapter;   // RuneLite adapter
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;

// ---- NEW: for sidebar icon loading -----------------------------------------
import net.runelite.client.util.ImageUtil;
import java.awt.image.BufferedImage;
// -----------------------------------------------------------------------------

import javax.inject.Inject;
import java.awt.event.MouseEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

@PluginDescriptor(
        name = "Heist HUD",
        description = "Persistent click dots + IR PNG export + on-disk click logs."
)
public final class HeistHUDPlugin extends Plugin
{
    // ---- Injected services --------------------------------------------------
    @Inject private OverlayManager overlayManager;
    @Inject private ClientToolbar clientToolbar;
    @Inject private MouseManager mouseManager;
    @Inject private Client client;

    @Inject private HeistHUDConfig cfg;
    @Inject private ClickStore store;
    @Inject private ClickDotsOverlay dotsOverlay;
    @Inject private CursorTracerOverlay cursorTracerOverlay;
    @Inject private HeistHUDPanel panel;

    // ---- UI handle ----------------------------------------------------------
    private NavigationButton navButton;

    // ---- Listeners (created in startUp, not at field init) ------------------
    private TapBus.Listener busListener;
    private MouseAdapter rlMouseAdapter;

    // ---- On-disk logging ----------------------------------------------------
    private ClickLogger logger;
    private String sessionId;

    @Provides HeistHUDConfig provideConfig(ConfigManager cm) {
        return cm.getConfig(HeistHUDConfig.class);
    }

    @Override
    protected void startUp()
    {
        // 1) Overlays are always added; they check their own config toggles.
        overlayManager.add(dotsOverlay);
        overlayManager.add(cursorTracerOverlay);

        // 2) Sidebar panel (NavigationButton) --------------------------------
        //    - Load icon from resources (classpath root) so the tab actually renders.
        //    - If the path is wrong, icon will be null; the tab may be invisible on some RL builds.
        final BufferedImage icon = ImageUtil.loadImageResource(
                HeistHUDPlugin.class, "/heist_hud_icon.png");

        navButton = NavigationButton.builder()
                .tooltip("Heist HUD")
                .icon(icon)          // <<--- IMPORTANT: set the icon so the tab shows up
                .priority(5)
                .panel(panel)        // the UI panel with buttons (Export/Open/Clear)
                .build();

        clientToolbar.addNavigation(navButton);

        // 3) Create TapBus listener *after* injection (no constructor NPE)
        busListener = store::add;
        TapBus.addListener(busListener);

        // 4) Capture manual clicks via RuneLite mouse manager
        rlMouseAdapter = new MouseAdapter() {
            @Override public MouseEvent mousePressed(MouseEvent e)  { postFromAwt(e, Type.DOWN);  return e; }
            @Override public MouseEvent mouseReleased(MouseEvent e) { postFromAwt(e, Type.UP);    return e; }
            @Override public MouseEvent mouseClicked(MouseEvent e)  { postFromAwt(e, Type.CLICK); return e; }
        };
        mouseManager.registerMouseListener(rlMouseAdapter);

        // 5) Start JSONL/CSV logging in the output folder
        sessionId = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File outDir = expandOutputDir(cfg.outputDir());
        logger = new ClickLogger(sessionId, outDir, cfg.writeCsv());
        try { logger.start(); } catch (Exception ignored) {}
    }

    @Override
    protected void shutDown()
    {
        // Remove overlays (idempotent)
        overlayManager.remove(dotsOverlay);
        overlayManager.remove(cursorTracerOverlay);

        // Remove sidebar tab
        if (navButton != null) {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }

        // Unsubscribe listeners
        if (busListener != null) {
            TapBus.removeListener(busListener);
            busListener = null;
        }
        if (rlMouseAdapter != null) {
            mouseManager.unregisterMouseListener(rlMouseAdapter);
            rlMouseAdapter = null;
        }

        // Stop logger
        if (logger != null) {
            try { logger.stop(); } catch (Exception ignored) {}
            logger = null;
        }

        // Reset in-memory store so a new session starts fresh
        store.clear();
    }

    // ---- Helpers ------------------------------------------------------------

    /**
     * Convert RuneLite MouseEvent → TapEvent and post to TapBus (JDK 11–safe).
     * We map AWT buttons to our enum and include modifier keys + sessionId.
     */
    private void postFromAwt(MouseEvent e, Type type)
    {
        Button b;
        switch (e.getButton()) {
            case MouseEvent.BUTTON1: b = Button.LEFT;   break;
            case MouseEvent.BUTTON2: b = Button.MIDDLE; break;
            case MouseEvent.BUTTON3: b = Button.RIGHT;  break;
            default:                  b = Button.UNKNOWN;
        }

        boolean shift = (e.getModifiersEx() & MouseEvent.SHIFT_DOWN_MASK) != 0;
        boolean ctrl  = (e.getModifiersEx() & MouseEvent.CTRL_DOWN_MASK)  != 0;
        boolean alt   = (e.getModifiersEx() & MouseEvent.ALT_DOWN_MASK)   != 0;

        TapEvent ev = new TapEvent(
                System.currentTimeMillis(),
                e.getX(), e.getY(),
                type, b, shift, ctrl, alt,
                sessionId,
                null,null,null, null,null,
                null, null,null,null, null
        );
        TapBus.post(ev);
    }

    /** Expand %USERPROFILE% → user.home so Windows paths work out of the box. */
    private static File expandOutputDir(String raw) {
        String home = System.getProperty("user.home");
        return new File(raw.replace("%USERPROFILE%", home));
    }
}
