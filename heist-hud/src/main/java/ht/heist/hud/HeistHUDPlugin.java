// ============================================================================
// FILE: HeistHUDPlugin.java
// MODULE: heist-hud
// PACKAGE: ht.heist.hud
// -----------------------------------------------------------------------------
// TITLE
//   HeistHUDPlugin — overlays + panel + Phase-A instrumentation.
//
// WHAT THIS PLUGIN DOES
//   • Adds overlays (dots + cursor tracer) and the Heist HUD sidebar panel.
//   • Subscribes to TapBus indirectly via ClickLogger (for TapEvent JSONL).
//   • Captures MANUAL taps from RuneLite AWT events, converts to TapEvent,
//     and now (Phase A) also:
//        - Generates a stable eventId per gesture (DOWN ↔ UP)
//        - Estimates dwell_before_press (manual) without high-rate logging
//        - Computes hold_ms at UP
//        - Detects whether the tap was processed via MenuOptionClicked
//        - Emits 'move_features' + 'tap_result' JSONL lines via ClickLogger
//
// DESIGN NOTES
//   • No behavior change for overlays/export; this is pure instrumentation.
//   • All new logging is append-only JSONL and keyed by 'event_id'.
//   • JDK 11 compatible (no switch expressions).
// ============================================================================

package ht.heist.hud;

import com.google.inject.Provides;
import ht.heist.corejava.api.input.TapBus;
import ht.heist.corejava.api.input.TapEvent;
import ht.heist.corejava.api.input.TapEvent.Button;
import ht.heist.corejava.api.input.TapEvent.Type;
import ht.heist.corejava.logging.ClickLogger;
import ht.heist.corejava.logging.MoveFeatures;
import ht.heist.corejava.logging.TapResult;
import ht.heist.hud.overlay.ClickDotsOverlay;
import ht.heist.hud.overlay.CursorTracerOverlay;
import ht.heist.hud.service.ClickStore;
import ht.heist.hud.ui.HeistHUDPanel;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.input.MouseAdapter;   // RuneLite adapter
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@PluginDescriptor(
        name = "Heist HUD",
        description = "Persistent click dots + IR PNG export + on-disk click logs + Phase-A features."
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
    private TapBus.Listener busListener;      // (kept for completeness; ClickLogger registers its own listener)
    private MouseAdapter rlMouseAdapter;

    // ---- On-disk logging ----------------------------------------------------
    private ClickLogger logger;
    private String sessionId;

    // ---- Phase-A: gesture correlation & dwell tracking ----------------------
    private static final int SIG_MOVE_RADIUS_PX = 2;     // "significant move" radius
    private volatile long lastMoveTs = 0L;
    private volatile int  lastMoveX  = Integer.MIN_VALUE;
    private volatile int  lastMoveY  = Integer.MIN_VALUE;

    private static final long PROCESSED_WINDOW_MS = 200L; // time window to match menu click after DOWN

    /** Pending DOWN info per mouse button, so UP can correlate and finalize. */
    private final Map<Integer, Pending> pendingByButton = new HashMap<>();

    /** Small holder for correlating DOWN↔UP and tap_result. */
    private static final class Pending {
        final String eventId;
        final long   downTs;
        final int    downX, downY;
        final Integer dwellMs;         // estimated at DOWN (manual)
        // Simple menu snapshot (best-effort; helps tap_result matching / audits)
        final String  opt;
        final String  tgt;
        final Integer opcode;
        volatile boolean processed;    // set true when we observe MenuOptionClicked
        Pending(String eventId, long downTs, int downX, int downY,
                Integer dwellMs, String opt, String tgt, Integer opcode)
        {
            this.eventId   = eventId;
            this.downTs    = downTs;
            this.downX     = downX;
            this.downY     = downY;
            this.dwellMs   = dwellMs;
            this.opt       = opt;
            this.tgt       = tgt;
            this.opcode    = opcode;
            this.processed = false;
        }
    }

    @Provides HeistHUDConfig provideConfig(ConfigManager cm) {
        return cm.getConfig(HeistHUDConfig.class);
    }

    @Override
    protected void startUp()
    {
        // 1) Overlays are always added; they check their own config toggles.
        overlayManager.add(dotsOverlay);
        overlayManager.add(cursorTracerOverlay);

        // 2) Sidebar panel (NavigationButton with icon so it always renders)
        final BufferedImage icon = ImageUtil.loadImageResource(HeistHUDPlugin.class, "/heist_hud_icon.png");
        navButton = NavigationButton.builder()
                .tooltip("Heist HUD")
                .icon(icon)
                .priority(5)
                .panel(panel)
                .build();
        clientToolbar.addNavigation(navButton);

        // 3) (Optional) Keep a TapBus ref here for completeness; ClickLogger registers itself.
        busListener = store::add; // still feeds on-screen dots
        TapBus.addListener(busListener);

        // 4) Capture manual clicks via RuneLite mouse manager (PRESS/RELEASE/CLICK + MOVED)
        rlMouseAdapter = new MouseAdapter() {
            @Override public MouseEvent mouseMoved(MouseEvent e)   { onMouseMoved(e);   return e; }
            @Override public MouseEvent mousePressed(MouseEvent e) { onMousePressed(e); return e; }
            @Override public MouseEvent mouseReleased(MouseEvent e){ onMouseReleased(e);return e; }
            @Override public MouseEvent mouseClicked(MouseEvent e) { postFromAwt(e, Type.CLICK, /*eventId*/ null, /*menu snapshot*/ null, null, null); return e; }
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
        // Remove overlays
        overlayManager.remove(dotsOverlay);
        overlayManager.remove(cursorTracerOverlay);

        // Remove sidebar tab
        if (navButton != null) {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }

        // Unsubscribe TapBus listener (dots store)
        if (busListener != null) {
            TapBus.removeListener(busListener);
            busListener = null;
        }

        // Unregister mouse listener
        if (rlMouseAdapter != null) {
            mouseManager.unregisterMouseListener(rlMouseAdapter);
            rlMouseAdapter = null;
        }

        // Stop logger
        if (logger != null) {
            try { logger.stop(); } catch (Exception ignored) {}
            logger = null;
        }

        // Clear state
        pendingByButton.clear();
        store.clear();
    }

    // ------------------------------------------------------------------------
    // Phase A: manual dwell estimation (no high-rate sampling)
    // ------------------------------------------------------------------------
    private void onMouseMoved(MouseEvent e)
    {
        final int x = e.getX(), y = e.getY();
        if (lastMoveX == Integer.MIN_VALUE) {
            lastMoveX = x; lastMoveY = y; lastMoveTs = System.currentTimeMillis();
            return;
        }
        final int dx = Math.abs(x - lastMoveX), dy = Math.abs(y - lastMoveY);
        if (dx >= SIG_MOVE_RADIUS_PX || dy >= SIG_MOVE_RADIUS_PX) {
            lastMoveX = x; lastMoveY = y; lastMoveTs = System.currentTimeMillis();
        }
    }

    // ------------------------------------------------------------------------
    // PRESS → create eventId, estimate dwell, snapshot menu, post TapEvent
    // ------------------------------------------------------------------------
    private void onMousePressed(MouseEvent e)
    {
        final long now = System.currentTimeMillis();
        final String eventId = UUID.randomUUID().toString();

        // Estimate dwell: time since last "significant" move
        Integer dwellMs = null;
        if (lastMoveTs > 0) {
            long d = now - lastMoveTs;
            if (d >= 0 && d <= 30_000) dwellMs = (int) d; // guard against absurd values
        }

        // Snapshot the current default menu entry (best-effort match for processed)
        String opt = null, tgt = null; Integer opcode = null;
        final MenuEntry[] entries = client.getMenuEntries();
        if (entries != null && entries.length > 0) {
            final MenuEntry top = entries[entries.length - 1]; // last is left-click default in many RL builds
            opt = top.getOption();
            tgt = top.getTarget();
            try { opcode = top.getType().getId(); } catch (Throwable ignore) { /* rl version variance */ }
        }

        // Keep pending so UP can finalize hold/features/result
        pendingByButton.put(e.getButton(), new Pending(eventId, now, e.getX(), e.getY(), dwellMs, opt, tgt, opcode));

        // Post TapEvent DOWN with eventId + simple context snapshot
        postFromAwt(e, Type.DOWN, eventId, opt, tgt, opcode);
    }

    // ------------------------------------------------------------------------
    // RELEASE → compute hold, emit move_features, emit tap_result if not set
    // ------------------------------------------------------------------------
    private void onMouseReleased(MouseEvent e)
    {
        final long now = System.currentTimeMillis();
        final Pending p = pendingByButton.remove(e.getButton());
        final String eventId = (p != null ? p.eventId : null);

        // Always post TapEvent UP (correlated by eventId if available)
        postFromAwt(e, Type.UP, eventId, /*opt*/ null, /*tgt*/ null, /*opcode*/ null);

        // If we have a pending DOWN, we can emit features + (possibly) result
        if (p != null && logger != null) {
            final Integer holdMs = (int)Math.max(0, now - p.downTs);

            // Emit move_features for this manual gesture
            final MoveFeatures mf = new MoveFeatures(
                    sessionId, p.eventId, "manual",
                    p.dwellMs, /*reactionJitterMs*/ null, holdMs,
                    /*pathLengthPx*/ null, /*durationMs*/ null, /*stepCount*/ null,
                    /*curvatureSigned*/ null, /*wobbleAmpPx*/ null,
                    /*driftAlpha*/ null, /*driftNoiseStd*/ null, /*driftStateX*/ null, /*driftStateY*/ null
            );
            try { logger.writeMoveFeatures(mf); } catch (Exception ignored) {}

            // If no MenuOptionClicked matched within the window, record processed=false
            if (!p.processed) {
                final TapResult tr = new TapResult(sessionId, p.eventId, /*processed*/ false, now, p.opt, p.tgt, p.opcode);
                try { logger.writeTapResult(tr); } catch (Exception ignored) {}
            }
        }
    }

    // ------------------------------------------------------------------------
    // RL event: MenuOptionClicked — mark the most recent pending as processed
    // ------------------------------------------------------------------------
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked ev)
    {
        // Find a "recent" pending DOWN whose menu snapshot matches best-effort.
        // NOTE: RL params vary by version; we match only option/target/opcode + time window.
        if (pendingByButton.isEmpty()) return;

        final long now = System.currentTimeMillis();
        final String opt = ev.getMenuOption();
        final String tgt = ev.getMenuTarget();
        Integer opcode = null;
        try { opcode = ev.getMenuAction().getId(); } catch (Throwable ignore) {}

        for (Pending p : pendingByButton.values()) {
            if (now - p.downTs > PROCESSED_WINDOW_MS) continue; // too old
            boolean optEq = safeEq(p.opt, opt);
            boolean tgtEq = safeEq(p.tgt, tgt);
            boolean opcEq = (p.opcode == null || opcode == null) ? true : p.opcode.equals(opcode);
            if (optEq && tgtEq && opcEq) {
                p.processed = true;
                // Emit tap_result=true for this gesture
                if (logger != null) {
                    final TapResult tr = new TapResult(sessionId, p.eventId, true, now, p.opt, p.tgt, p.opcode);
                    try { logger.writeTapResult(tr); } catch (Exception ignored) {}
                }
                break;
            }
        }
    }

    private static boolean safeEq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    // ---- Posting TapEvent to TapBus (adds eventId + optional context) -------

    private void postFromAwt(MouseEvent e, Type type, String eventId,
                             String opt, String tgt, Integer opcode)
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
                /*eventId*/ eventId,
                /*worldX*/ null, /*worldY*/ null, /*plane*/ null,
                /*sceneX*/ null, /*sceneY*/ null,
                /*widgetId*/ null,
                /*menuOption*/ opt, /*menuTarget*/ tgt,
                /*opcode*/ opcode,
                /*processed*/ null // we'll emit a separate tap_result later
        );
        TapBus.post(ev);
    }

    /** Expand %USERPROFILE% → user.home so Windows paths work out of the box. */
    private static File expandOutputDir(String raw) {
        String home = System.getProperty("user.home");
        return new File(raw.replace("%USERPROFILE%", home));
    }
}
