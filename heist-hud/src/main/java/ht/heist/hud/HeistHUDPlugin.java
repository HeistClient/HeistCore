// ============================================================================
// FILE: HeistHUDPlugin.java
// MODULE: heist-hud
// PACKAGE: ht.heist.hud
// -----------------------------------------------------------------------------
// TITLE
//   HeistHUDPlugin — overlays + panel + Phase-A instrumentation
//   (with robust "processed" detection: match-window + post-UP grace).
//
// WHAT THIS VERSION ADDS
//   • "Match window": we accept a MenuOptionClicked up to 450 ms after DOWN.
//   • "Grace timer": instead of writing processed:false immediately at UP,
//     we schedule it ~180 ms later; if a real MenuOptionClicked arrives in
//     the meantime, we cancel the false write and emit processed:true.
//
// WHY THIS MATTERS
//   This eliminates rare "false negatives" when the client reports acceptance
//   slightly after your UP due to normal latency.
//
// COMPATIBILITY
//   • JDK 11 safe (no switch expressions).
//   • No behavior change to overlays/export; this only affects logging.
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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

    // ---- Listeners (created in startUp) ------------------------------------
    private TapBus.Listener busListener;
    private MouseAdapter rlMouseAdapter;

    // ---- On-disk logging ----------------------------------------------------
    private ClickLogger logger;
    private String sessionId;

    // ---- Phase-A: gesture correlation & dwell tracking ----------------------
    private static final int  SIG_MOVE_RADIUS_PX   = 2;    // "significant move" radius
    private static final long MATCH_WINDOW_MS      = 450;  // accept MenuOptionClicked this long after DOWN
    private static final long FALSE_GRACE_MS       = 180;  // delay before writing processed:false
    private volatile long lastMoveTs = 0L;
    private volatile int  lastMoveX  = Integer.MIN_VALUE;
    private volatile int  lastMoveY  = Integer.MIN_VALUE;

    /** Pending DOWN info per mouse button, so UP can correlate and finalize. */
    private final Map<Integer, Pending> pendingByButton = new HashMap<>();

    /** Single-thread scheduler for the post-UP grace (daemon). */
    private ScheduledExecutorService scheduler;

    /** Small holder for correlating DOWN↔UP and tap_result (with grace future). */
    private static final class Pending {
        final String eventId;
        final long   downTs;
        final int    downX, downY;
        final Integer dwellMs;         // estimated at DOWN (manual)
        final String  opt;             // snapshot of default menu at DOWN (best-effort)
        final String  tgt;
        final Integer opcode;
        volatile boolean processed;    // true once we saw a matching MenuOptionClicked
        volatile ScheduledFuture<?> graceFuture; // "write false" task scheduled at UP
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
            this.graceFuture = null;
        }
    }

    @Provides HeistHUDConfig provideConfig(ConfigManager cm) {
        return cm.getConfig(HeistHUDConfig.class);
    }

    @Override
    protected void startUp()
    {
        // Overlays always added; they check their own config toggles.
        overlayManager.add(dotsOverlay);
        overlayManager.add(cursorTracerOverlay);

        // Sidebar panel (NavigationButton with icon so it always renders).
        final BufferedImage icon = ImageUtil.loadImageResource(HeistHUDPlugin.class, "/heist_hud_icon.png");
        navButton = NavigationButton.builder()
                .tooltip("Heist HUD")
                .icon(icon)
                .priority(5)
                .panel(panel)
                .build();
        clientToolbar.addNavigation(navButton);

        // Dots store listener (for on-screen points) — logger also subscribes.
        busListener = store::add;
        TapBus.addListener(busListener);

        // Capture manual clicks via RuneLite mouse manager (MOVE/PRESS/RELEASE/CLICK).
        rlMouseAdapter = new MouseAdapter() {
            @Override public MouseEvent mouseMoved(MouseEvent e)   { onMouseMoved(e);   return e; }
            @Override public MouseEvent mousePressed(MouseEvent e) { onMousePressed(e); return e; }
            @Override public MouseEvent mouseReleased(MouseEvent e){ onMouseReleased(e);return e; }
            @Override public MouseEvent mouseClicked(MouseEvent e) { postFromAwt(e, Type.CLICK, /*eventId*/ null, null, null, null); return e; }
        };
        mouseManager.registerMouseListener(rlMouseAdapter);

        // Start JSONL/CSV logging in the output folder.
        sessionId = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File outDir = expandOutputDir(cfg.outputDir());
        logger = new ClickLogger(sessionId, outDir, cfg.writeCsv());
        try { logger.start(); } catch (Exception ignored) {}

        // Start grace scheduler (daemon thread).
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HeistHUD-ResultGrace");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    protected void shutDown()
    {
        // Remove overlays & sidebar tab.
        overlayManager.remove(dotsOverlay);
        overlayManager.remove(cursorTracerOverlay);
        if (navButton != null) {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }

        // Unsubscribe TapBus listener (dots store).
        if (busListener != null) {
            TapBus.removeListener(busListener);
            busListener = null;
        }

        // Unregister mouse listener.
        if (rlMouseAdapter != null) {
            mouseManager.unregisterMouseListener(rlMouseAdapter);
            rlMouseAdapter = null;
        }

        // Cancel any pending grace tasks and stop scheduler.
        for (Pending p : pendingByButton.values()) {
            ScheduledFuture<?> f = p.graceFuture;
            if (f != null) { f.cancel(true); }
        }
        pendingByButton.clear();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        // Stop logger.
        if (logger != null) {
            try { logger.stop(); } catch (Exception ignored) {}
            logger = null;
        }

        // Clear on-screen store.
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

        // Estimate dwell: time since last "significant" move.
        Integer dwellMs = null;
        if (lastMoveTs > 0) {
            long d = now - lastMoveTs;
            if (d >= 0 && d <= 30_000) dwellMs = (int) d;
        }

        // Snapshot the current default menu entry (best-effort for matching).
        String opt = null, tgt = null; Integer opcode = null;
        final MenuEntry[] entries = client.getMenuEntries();
        if (entries != null && entries.length > 0) {
            final MenuEntry top = entries[entries.length - 1]; // many RL builds: last = left-click default
            opt = top.getOption();
            tgt = top.getTarget();
            try { opcode = top.getType().getId(); } catch (Throwable ignore) {}
        }

        // Keep pending so UP can finalize hold/features/result (with grace).
        pendingByButton.put(e.getButton(), new Pending(eventId, now, e.getX(), e.getY(), dwellMs, opt, tgt, opcode));

        // Post TapEvent DOWN with eventId + simple context snapshot.
        postFromAwt(e, Type.DOWN, eventId, opt, tgt, opcode);
    }

    // ------------------------------------------------------------------------
    // RELEASE → compute hold, emit move_features, schedule processed:false
    // ------------------------------------------------------------------------
    private void onMouseReleased(MouseEvent e)
    {
        final long now = System.currentTimeMillis();
        final Pending p = pendingByButton.get(e.getButton()); // keep until grace completes
        final String eventId = (p != null ? p.eventId : null);

        // Always post TapEvent UP (correlated by eventId if available).
        postFromAwt(e, Type.UP, eventId, /*opt*/ null, /*tgt*/ null, /*opcode*/ null);

        // Emit move_features if we have a pending DOWN.
        if (p != null && logger != null) {
            final Integer holdMs = (int)Math.max(0, now - p.downTs);
            final MoveFeatures mf = new MoveFeatures(
                    sessionId, p.eventId, "manual",
                    p.dwellMs, /*reactionJitterMs*/ null, holdMs,
                    /*pathLengthPx*/ null, /*durationMs*/ null, /*stepCount*/ null,
                    /*curvatureSigned*/ null, /*wobbleAmpPx*/ null,
                    /*driftAlpha*/ null, /*driftNoiseStd*/ null, /*driftStateX*/ null, /*driftStateY*/ null
            );
            try { logger.writeMoveFeatures(mf); } catch (Exception ignored) {}

            // Schedule a "processed:false" write after a short grace.
            if (scheduler != null) {
                p.graceFuture = scheduler.schedule(() -> {
                    if (!p.processed && logger != null) {
                        final TapResult tr = new TapResult(sessionId, p.eventId, false, System.currentTimeMillis(), p.opt, p.tgt, p.opcode);
                        try { logger.writeTapResult(tr); } catch (Exception ignored) {}
                    }
                    // Clean up this pending if it still belongs to this button key.
                    pendingByButton.remove(e.getButton(), p);
                }, FALSE_GRACE_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    // ------------------------------------------------------------------------
    // RL event: MenuOptionClicked — mark processed and cancel false write
    // ------------------------------------------------------------------------
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked ev)
    {
        if (pendingByButton.isEmpty()) return;

        final long now = System.currentTimeMillis();
        final String opt = ev.getMenuOption();
        final String tgt = ev.getMenuTarget();
        Integer opcode = null;
        try { opcode = ev.getMenuAction().getId(); } catch (Throwable ignore) {}

        for (Map.Entry<Integer, Pending> entry : pendingByButton.entrySet()) {
            final Pending p = entry.getValue();
            // respect the match window relative to DOWN
            if (now - p.downTs > MATCH_WINDOW_MS) continue;

            boolean optEq = safeEq(p.opt, opt);
            boolean tgtEq = safeEq(p.tgt, tgt);
            boolean opcEq = (p.opcode == null || opcode == null) ? true : p.opcode.equals(opcode);
            if (optEq && tgtEq && opcEq) {
                // Mark processed, cancel pending false, write true result.
                p.processed = true;
                final ScheduledFuture<?> f = p.graceFuture;
                if (f != null) { f.cancel(true); }

                if (logger != null) {
                    final TapResult tr = new TapResult(sessionId, p.eventId, true, now, p.opt, p.tgt, p.opcode);
                    try { logger.writeTapResult(tr); } catch (Exception ignored) {}
                }

                // We keep the Pending until the grace task or UP cleanup removes it,
                // to avoid races if UP hasn't scheduled yet.
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
                /*processed*/ null // result is emitted as a separate tap_result record
        );
        TapBus.post(ev);
    }

    /** Expand %USERPROFILE% → user.home so Windows paths work out of the box. */
    private static File expandOutputDir(String raw) {
        String home = System.getProperty("user.home");
        return new File(raw.replace("%USERPROFILE%", home));
    }
}
