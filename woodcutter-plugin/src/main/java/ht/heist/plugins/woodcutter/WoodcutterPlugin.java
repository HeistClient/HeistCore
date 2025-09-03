// ============================================================================
// FILE: WoodcutterPlugin.java
// PATH: woodcutter-plugin/src/main/java/ht/heist/plugins/woodcutter/WoodcutterPlugin.java
// PACKAGE: ht.heist.plugins.woodcutter
// -----------------------------------------------------------------------------
// TITLE
//   Heist Woodcutter (Thin, Unified Feel)
// -----------------------------------------------------------------------------
// WHAT THIS FILE DOES (IN PLAIN ENGLISH)
//   • Keeps the plugin "thin": it asks for clicks; the core decides *how*.
//   • Delegates all humanization (curvature, drift, speed, holds) to core-java.
//   • Adds small human-ish idles using IdleController (no direct fatigue knob).
//   • Performs a simple visibility (LoS) gate via CameraService before clicking.
//   • Logs important milestones with structured diagnostics for easy triage.
// -----------------------------------------------------------------------------
// DESIGN NOTES
//   • NO direct calls to humanizer.* — HumanMouse encapsulates feel internally.
//   • If you later want explicit APIs (e.g., startSession(), setFatigue(...)),
//     expose tiny methods on HumanMouse that forward to core-java safely.
//   • Misclicks/overshoot/ease-in should live inside the planner used by
//     HumanMouse. The plugin remains declarative: "click this hull/rect".
// ============================================================================

package ht.heist.plugins.woodcutter;

import com.google.inject.Provides;

import ht.heist.corejava.logging.HeistLog;
import ht.heist.corerl.input.CameraService;
import ht.heist.corerl.input.HumanMouse;
import ht.heist.corerl.input.IdleController;
import ht.heist.corerl.object.TreeDetector;
import ht.heist.corerl.object.TreeType;
import ht.heist.plugins.woodcutter.io.SyntheticTapLogger;

import net.runelite.api.*;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import org.slf4j.Logger;

import javax.inject.Inject;
import java.awt.Shape;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

@PluginDescriptor(
        name = "Heist Woodcutter",
        description = "Cuts trees using unified HumanMouse feel; logs taps to JSONL for the HUD.",
        tags = {"heist","woodcutting"}
)
public class WoodcutterPlugin extends Plugin
{
    // -------------------------------------------------------------------------
    // LOGGING
    // -------------------------------------------------------------------------
    // HeistLog.get(...) returns an org.slf4j.Logger wired to your logging backend.
    private static final Logger log = HeistLog.get(WoodcutterPlugin.class);

    // -------------------------------------------------------------------------
    // INJECTED SERVICES
    // -------------------------------------------------------------------------
    @Inject private Client client;                       // RuneLite client handle (game state, widgets, player)
    @Inject private WoodcutterConfig config;             // Plugin configuration (tree type etc.)
    @Inject private HumanMouse humanMouse;               // Unified mouse executor (curves, timing, overshoot)
    @Inject private SyntheticTapLogger syntheticTapLogger; // Writes JSONL taps (HUD tails this)
    @Inject private IdleController idleController;       // Adds small idle moments to feel human
    @Inject private CameraService cameraService;         // Basic line-of-sight / visibility checks

    // -------------------------------------------------------------------------
    // PLUGIN STATE
    // -------------------------------------------------------------------------
    private enum State { STARTING, FINDING_TREE, VERIFYING, CHOPPING, INVENTORY }
    private State state = State.STARTING;

    /** A simple time gate (epoch millis) used to back off between actions. */
    private long wakeAt = 0L;

    /** The current target tree (if any). */
    private GameObject targetTree = null;

    /** Local RNG for small randomized backoffs. */
    private final Random rng = new Random();

    // -------------------------------------------------------------------------
    // CONFIG PROVIDER (RuneLite standard)
    // -------------------------------------------------------------------------
    @Provides
    WoodcutterConfig provideConfig(ConfigManager cm)
    {
        return cm.getConfig(WoodcutterConfig.class);
    }

    // -------------------------------------------------------------------------
    // LIFECYCLE: startUp / shutDown
    // -------------------------------------------------------------------------
    @Override
    protected void startUp()
    {
        // (1) Wire the "tap sink" so each synthetic click is mirrored to JSONL.
        //     The HUD plugin can tail this file and render your clicks.
        //     NOTE: humanMouse handles null-sinks gracefully; we supply one here.
        humanMouse.setTapSink(p -> {
            try {
                // p.x/p.y are canvas-space target coords for the click.
                syntheticTapLogger.log(p.x, p.y);
            } catch (Throwable ignored) {
                // We keep this sink fire-and-forget; logging failures shouldn't
                // break gameplay. JSONL is nice-to-have telemetry.
            }
        });

        // (2) We do NOT call humanMouse.getHumanizer() or onSessionStart().
        //     The "unified feel" inside core-java initializes its own session/
        //     drift state. If you later expose HumanMouse.startSession(), we
        //     could call it here. For now: zero extra wiring needed.

        state = State.FINDING_TREE;
        HeistLog.diag(log, "woodcutter_start");
        log.info("Heist Woodcutter started");
    }

    @Override
    protected void shutDown()
    {
        // Reset local state. HumanMouse / core-java doesn't require explicit
        // cleanup for feel; it’s stateless across plugin shutdowns by design.
        state = State.STARTING;
        targetTree = null;
        wakeAt = 0L;

        HeistLog.diag(log, "woodcutter_stop");
        log.info("Heist Woodcutter stopped");
    }

    // -------------------------------------------------------------------------
    // MAIN LOOP: runs once per GameTick (~600ms)
    // -------------------------------------------------------------------------
    @Subscribe
    public void onGameTick(GameTick tick)
    {
        // (A) Backoff gate: if we recently acted, skip this tick.
        if (System.currentTimeMillis() < wakeAt)
            return;

        final Player me = client.getLocalPlayer();
        if (client.getGameState() != GameState.LOGGED_IN || me == null)
            return;

        // (B) Optional micro-idle: IdleController decides if we should pause
        //     slightly to look less robotic. This *does not* modify humanizer
        //     internals — the unified feel is self-contained in core-java.
        try
        {
            long idleMs = idleController.maybeIdle(); // small stochastic pause
            if (idleMs > 0)
                Thread.sleep(idleMs);
        }
        catch (InterruptedException ignored) {}

        try
        {
            switch (state)
            {
                case STARTING:
                case FINDING_TREE:
                {
                    // (C) Acquire a target of the desired type.
                    final TreeType want = config.treeType();
                    targetTree = TreeDetector.findNearest(client, want);

                    if (targetTree == null)
                    {
                        // Nothing found in view; short randomized backoff then retry.
                        wakeAt = System.currentTimeMillis() + 350 + rng.nextInt(450);
                        HeistLog.diag(log, "no_tree_found_retry");
                        break;
                    }

                    // (D) Simple LoS gate. If not visible, avoid blind clicks and
                    //     let CameraService rotate/adjust in future iterations.
                    if (!cameraService.isVisible(targetTree))
                    {
                        HeistLog.diag(log, "tree_not_visible_retrying");
                        // TODO (future): cameraService.face(targetTree)
                        wakeAt = System.currentTimeMillis() + 280 + rng.nextInt(220);
                        break;
                    }

                    // (E) Delegate the *entire* click execution to HumanMouse.
                    //     Inside, it will: generate a curved path, apply drift,
                    //     overshoot (if enabled), apply deceleration near target,
                    //     sample reaction/hold times, and dispatch events.
                    clickGameObjectHull(targetTree);

                    // (F) Move to VERIFYING, give the worker time to start.
                    state = State.VERIFYING;
                    wakeAt = System.currentTimeMillis() + 900 + rng.nextInt(300);
                    break;
                }

                case VERIFYING:
                {
                    // (G) If our player is playing the axe animation, we’re chopping.
                    if (me.getAnimation() == currentAxeAnim())
                    {
                        state = State.CHOPPING;
                        HeistLog.diag(log, "chop_started");
                    }
                    else
                    {
                        // If idle (pose anim == idle pose) we likely missed; try again.
                        if (me.getPoseAnimation() == me.getIdlePoseAnimation())
                            state = State.FINDING_TREE;
                    }
                    break;
                }

                case CHOPPING:
                {
                    // (H) If the chop animation stops, the tree despawned or chop ended.
                    if (me.getAnimation() != currentAxeAnim())
                    {
                        state = State.FINDING_TREE;
                    }

                    // (I) Inventory management: if full, go drop logs.
                    if (isInventoryFull())
                    {
                        state = State.INVENTORY;
                        HeistLog.diag(log, "inventory_full");
                    }
                    break;
                }

                case INVENTORY:
                {
                    // (J) Shift-drop one log per pass. HumanMouse manages shift timing.
                    if (!dropOneLogIfPresent())
                    {
                        // No logs left; resume chopping.
                        state = State.FINDING_TREE;
                        HeistLog.diag(log, "inventory_cleared");
                    }

                    // Human-like short pause between drops.
                    wakeAt = System.currentTimeMillis() + 180 + rng.nextInt(120);
                    break;
                }
            }
        }
        catch (Throwable t)
        {
            // (K) Guardrails: one-line diagnostic + full stack (warn) for triage.
            HeistLog.diag(log, "tick_error", "reason", t.getClass().getSimpleName());
            log.warn("Woodcutter tick error (continuing)", t);

            // Small backoff avoids tight error loops.
            wakeAt = System.currentTimeMillis() + 250;
        }
    }

    // -------------------------------------------------------------------------
    // CLICK HELPERS (thin delegations to HumanMouse)
    // -------------------------------------------------------------------------

    /**
     * Clicks anywhere inside a GameObject's convex hull.
     * HumanMouse will compute a believable approach path and timings.
     */
    private void clickGameObjectHull(GameObject obj)
    {
        if (obj == null)
            return;

        final Shape hull = obj.getConvexHull();
        if (hull == null)
            return;

        // shift=false for regular interactions.
        humanMouse.clickHull(hull, /* shift = */ false);
    }

    // -------------------------------------------------------------------------
    // INVENTORY HELPERS (Shift-drop logs)
    // -------------------------------------------------------------------------

    /**
     * Attempts to shift-drop a single log stack in the inventory.
     * @return true if a log was found and clicked; false otherwise.
     */
    private boolean dropOneLogIfPresent()
    {
        final Widget inv = client.getWidget(WidgetInfo.INVENTORY);
        if (inv == null || inv.isHidden())
            return false;

        final List<Integer> logIds = Arrays.asList(
                ItemID.LOGS, ItemID.OAK_LOGS, ItemID.WILLOW_LOGS, ItemID.MAPLE_LOGS,
                ItemID.TEAK_LOGS, ItemID.MAHOGANY_LOGS, ItemID.YEW_LOGS, ItemID.MAGIC_LOGS
        );

        // Iterate visible inventory children; click the first log stack found.
        for (Widget w : inv.getDynamicChildren())
        {
            if (w == null)
                continue;

            if (logIds.contains(w.getItemId()))
            {
                // Shift-click a rectangle (widget bounds). HumanMouse will:
                //  • choose an interior point (or a small jitter),
                //  • approach with a human-ish path,
                //  • press with shift modifier using realistic hold timing.
                humanMouse.clickRect(w.getBounds(), /* shift = */ true);
                HeistLog.diag(log, "drop_log_click", "itemId", w.getItemId());
                return true;
            }
        }

        return false;
    }

    /** @return true if the inventory is full (>= 28 items). */
    private boolean isInventoryFull()
    {
        final ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        return inv != null && inv.count() >= 28;
    }

    // -------------------------------------------------------------------------
    // ANIMATION LOOKUP
    // -------------------------------------------------------------------------

    /**
     * Returns the expected woodcutting animation id for the currently equipped axe.
     * If the weapon is unknown or absent, returns -1.
     */
    private int currentAxeAnim()
    {
        final ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
        if (eq == null)
            return -1;

        final Item weapon = eq.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
        if (weapon == null)
            return -1;

        switch (weapon.getId())
        {
            case ItemID.BRONZE_AXE:   return AnimationID.WOODCUTTING_BRONZE;
            case ItemID.IRON_AXE:     return AnimationID.WOODCUTTING_IRON;
            case ItemID.STEEL_AXE:    return AnimationID.WOODCUTTING_STEEL;
            case ItemID.MITHRIL_AXE:  return AnimationID.WOODCUTTING_MITHRIL;
            case ItemID.ADAMANT_AXE:  return AnimationID.WOODCUTTING_ADAMANT;
            case ItemID.RUNE_AXE:     return AnimationID.WOODCUTTING_RUNE;
            // Extend for dragon/infernal/3A if desired:
            // case ItemID.DRAGON_AXE:  return AnimationID.WOODCUTTING_DRAGON;
            default: return -1;
        }
    }
}
