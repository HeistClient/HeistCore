// ============================================================================
// FILE: WoodcutterPlugin.java
// PATH: woodcutter-plugin/src/main/java/ht/heist/plugins/woodcutter/WoodcutterPlugin.java
// PACKAGE: ht.heist.plugins.woodcutter
// -----------------------------------------------------------------------------
// TITLE
//   Heist Woodcutter (Thin, Unified Feel, With Session Reset + Idle/Fatigue)
//
// WHAT THIS FILE ADDS
//   • Calls humanizer.onSessionStart() at plugin start (resets drift each run).
//   • Uses IdleController to occasionally pause + gradually increase fatigue.
//   • Adds a simple "LoS" check via CameraService before clicking trees.
//   • Wraps risky sections with structured logs (HeistLog.diag) + guardrails.
// -----------------------------------------------------------------------------
package ht.heist.plugins.woodcutter;

import com.google.inject.Provides;
import ht.heist.corerl.input.CameraService;
import ht.heist.corerl.input.HumanMouse;
import ht.heist.corerl.input.IdleController;
import ht.heist.corerl.object.TreeDetector;
import ht.heist.corerl.object.TreeType;
import ht.heist.plugins.woodcutter.io.SyntheticTapLogger;
import ht.heist.corejava.logging.HeistLog;
import org.slf4j.Logger;

import net.runelite.api.*;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

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
    // ---- Logger (SLF4J) -----------------------------------------------------
    private static final Logger log = HeistLog.get(WoodcutterPlugin.class);

    // ---- Injected deps -------------------------------------------------------
    @Inject private Client client;
    @Inject private WoodcutterConfig config;
    @Inject private HumanMouse humanMouse;                    // unified feel
    @Inject private SyntheticTapLogger syntheticTapLogger;    // JSONL writer (HUD tails this)
    @Inject private IdleController idleController;            // small idle + fatigue drift
    @Inject private CameraService cameraService;              // simple LoS check (expand later)

    // ---- State ---------------------------------------------------------------
    private enum State { STARTING, FINDING_TREE, VERIFYING, CHOPPING, INVENTORY }
    private State state = State.STARTING;
    private long  wakeAt = 0L;

    private GameObject targetTree = null;
    private final Random rng = new Random();

    // ---- Config provider (RuneLite standard) --------------------------------
    @Provides
    WoodcutterConfig provideConfig(ConfigManager cm) {
        return cm.getConfig(WoodcutterConfig.class);
    }

    // ------------------------------------------------------------------------
    // LIFECYCLE
    // ------------------------------------------------------------------------
    @Override
    protected void startUp()
    {
        // (A) Wire the synthetic tap sink → JSONL (HUD will tail that file)
        humanMouse.setTapSink(p -> {
            try { syntheticTapLogger.log(p.x, p.y); } catch (Throwable ignored) {}
        });

        // (B) RESET SESSION FEEL:
        //     This zeros the tiny AR(1) drift and resets fatigue to 0 so that
        //     each plugin run starts fresh. THIS is the "session reset" wiring.
        humanMouse.getHumanizer().onSessionStart();

        // (C) Optional: you can set an initial fatigue if desired (0..1)
        // humanMouse.getHumanizer().setFatigueLevel(0.0);

        state = State.FINDING_TREE;
        HeistLog.diag(log, "woodcutter_start");
    }

    @Override
    protected void shutDown()
    {
        state = State.STARTING;
        HeistLog.diag(log, "woodcutter_stop");
    }

    // ------------------------------------------------------------------------
    // TICK LOOP
    // ------------------------------------------------------------------------
    @Subscribe
    public void onGameTick(GameTick tick)
    {
        // (D) Respect any "wakeAt" gate to avoid burning CPU or spamming actions
        if (System.currentTimeMillis() < wakeAt) return;

        final Player me = client.getLocalPlayer();
        if (client.getGameState() != GameState.LOGGED_IN || me == null) return;

        // (E) OCCASIONAL HUMAN-LIKE IDLE + FATIGUE DRIFT
        //     This is *optional* but adds realism; tweak IdleController to taste.
        try {
            long idleMs = idleController.maybeIdle();
            if (idleMs > 0) Thread.sleep(idleMs);
            // Gradually increase fatigue (affects reaction mean slightly)
            double f = idleController.nextFatigue(humanMouse.getHumanizer().fatigueLevel());
            humanMouse.getHumanizer().setFatigueLevel(f);
        } catch (InterruptedException ignored) {}

        try {
            switch (state)
            {
                case STARTING:
                case FINDING_TREE:
                {
                    final TreeType want = config.treeType();
                    targetTree = TreeDetector.findNearest(client, want);

                    if (targetTree == null)
                    {
                        // nothing in view; idle lightly and retry
                        wakeAt = System.currentTimeMillis() + 400 + rng.nextInt(400);
                        break;
                    }

                    // (F) Simple visibility gate: if not visible, nudge the camera (future work)
                    if (!cameraService.isVisible(targetTree)) {
                        // TODO: cameraService.face(targetTree) with small key/drag steps
                        // For now, just back off a bit and retry; avoids blind clicking.
                        HeistLog.diag(log, "tree_not_visible_retrying");
                        wakeAt = System.currentTimeMillis() + 280 + rng.nextInt(220);
                        break;
                    }

                    // (G) Delegate the entire click to HumanMouse (curved → reaction → press)
                    clickGameObjectHull(targetTree);

                    // Allow the worker to run a bit, then verify via animation
                    state = State.VERIFYING;
                    wakeAt = System.currentTimeMillis() + 900 + rng.nextInt(300);
                    break;
                }

                case VERIFYING:
                {
                    if (me.getAnimation() == currentAxeAnim())
                    {
                        state = State.CHOPPING;
                    }
                    else
                    {
                        // not animating and idle → try again
                        if (me.getPoseAnimation() == me.getIdlePoseAnimation())
                            state = State.FINDING_TREE;
                    }
                    break;
                }

                case CHOPPING:
                {
                    if (me.getAnimation() != currentAxeAnim())
                    {
                        // chop ended/despawned
                        state = State.FINDING_TREE;
                    }
                    // inventory full? go drop
                    if (isInventoryFull())
                    {
                        state = State.INVENTORY;
                    }
                    break;
                }

                case INVENTORY:
                {
                    // Shift-drop one log per loop; HumanMouse handles Shift timing internally
                    if (!dropOneLogIfPresent())
                    {
                        // nothing left to drop; resume cutting
                        state = State.FINDING_TREE;
                    }
                    // brief human pause between drops
                    wakeAt = System.currentTimeMillis() + 180 + rng.nextInt(120);
                    break;
                }
            }
        } catch (Throwable t) {
            // (H) GUARDRails + structured log line for fast triage
            HeistLog.diag(log, "tick_error", "reason", t.getClass().getSimpleName());
            log.warn("Woodcutter tick error (continuing)", t);
            // small backoff to avoid tight error loops
            wakeAt = System.currentTimeMillis() + 250;
        }
    }

    // ------------------------------------------------------------------------
    // Thin click helper (delegates to HumanMouse)
    // ------------------------------------------------------------------------
    private void clickGameObjectHull(GameObject obj)
    {
        if (obj == null) return;
        final Shape hull = obj.getConvexHull();
        if (hull == null) return;

        humanMouse.clickHull(hull, /*shift=*/false);
    }

    // ------------------------------------------------------------------------
    // Inventory helpers (Shift-drop)
    // ------------------------------------------------------------------------
    private boolean dropOneLogIfPresent()
    {
        final Widget inv = client.getWidget(WidgetInfo.INVENTORY);
        if (inv == null || inv.isHidden()) return false;

        final List<Integer> logIds = Arrays.asList(
                ItemID.LOGS, ItemID.OAK_LOGS, ItemID.WILLOW_LOGS, ItemID.MAPLE_LOGS,
                ItemID.TEAK_LOGS, ItemID.MAHOGANY_LOGS, ItemID.YEW_LOGS, ItemID.MAGIC_LOGS
        );

        for (Widget w : inv.getDynamicChildren())
        {
            if (w == null) continue;
            if (logIds.contains(w.getItemId()))
            {
                // Shift-drop this slot via HumanMouse (curved → react → press with Shift)
                humanMouse.clickRect(w.getBounds(), /*shift=*/true);
                return true;
            }
        }
        return false;
    }

    private boolean isInventoryFull()
    {
        final ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        return inv != null && inv.count() >= 28;
    }

    // ------------------------------------------------------------------------
    // Axe animation lookup (unchanged)
    // ------------------------------------------------------------------------
    private int currentAxeAnim()
    {
        final ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
        if (eq == null) return -1;
        final Item weapon = eq.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
        if (weapon == null) return -1;

        switch (weapon.getId())
        {
            case ItemID.BRONZE_AXE:  return AnimationID.WOODCUTTING_BRONZE;
            case ItemID.IRON_AXE:    return AnimationID.WOODCUTTING_IRON;
            case ItemID.STEEL_AXE:   return AnimationID.WOODCUTTING_STEEL;
            case ItemID.MITHRIL_AXE: return AnimationID.WOODCUTTING_MITHRIL;
            case ItemID.ADAMANT_AXE: return AnimationID.WOODCUTTING_ADAMANT;
            case ItemID.RUNE_AXE:    return AnimationID.WOODCUTTING_RUNE;
            default: return -1;
        }
    }
}
