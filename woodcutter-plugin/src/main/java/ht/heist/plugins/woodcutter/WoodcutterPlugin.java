// ============================================================================
// FILE: WoodcutterPlugin.java
// MODULE: woodcutter-plugin
// PACKAGE: ht.heist.plugins.woodcutter
// -----------------------------------------------------------------------------
// TITLE
//   Heist Woodcutter (Thin, HUD-Decoupled)
// -----------------------------------------------------------------------------
// WHAT THIS DOES
//   • Finds the nearest tree (core-rl TreeDetector + your config TreeType)
//   • Delegates the *entire* click pipeline to core-rl HumanMouse
//       (curved move → reaction BEFORE press → short hold → optional Shift)
//   • On startup, wires HumanMouse's tap-sink to SyntheticTapLogger so every
//     tap is written to JSONL; Heist HUD tails that file to draw the heatmap.
//   • If inventory fills, shift-drops one log per loop using HumanMouse.clickRect.
// -----------------------------------------------------------------------------
// WHY THIS VERSION
//   • Removes direct references to heist-hud (HeatmapService), eliminating the
//     compile errors you saw: "Cannot resolve symbol HeatmapService" / addLiveTap.
//   • HUD integration now happens *only* through the JSONL file (clean boundary).
// -----------------------------------------------------------------------------
// REQUIREMENTS (already in your repo):
//   • ht.heist.corerl.input.HumanMouse
//   • ht.heist.corerl.object.TreeDetector / TreeType
//   • ht.heist.plugins.woodcutter.io.SyntheticTapLogger (your JSONL writer)
// -----------------------------------------------------------------------------
// HUD SETUP REMINDER
//   In the Heist HUD config:
//     - Read JSONL taps: ON
//     - JSONL path: must match your SyntheticTapLogger path
//     - Show heatmap (and layers): ON
//     - Palette: as you prefer (Infrared or Red)
// ============================================================================

package ht.heist.plugins.woodcutter;

import com.google.inject.Provides;
import ht.heist.corerl.input.HumanMouse;
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
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.awt.Shape;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

@PluginDescriptor(
        name = "Heist Woodcutter",
        description = "Cuts trees using core-rl HumanMouse; logs taps to JSONL for the HUD.",
        tags = {"heist","woodcutting"}
)
public class WoodcutterPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(WoodcutterPlugin.class);

    // ---- Injected deps -------------------------------------------------------
    @Inject private Client client;
    @Inject private WoodcutterConfig config;
    @Inject private HumanMouse humanMouse;                    // core-rl: move→react→press on worker
    @Inject private SyntheticTapLogger syntheticTapLogger;    // JSONL writer (HUD tails this)

    // ---- State ---------------------------------------------------------------
    private enum State { STARTING, FINDING_TREE, VERIFYING, CHOPPING, INVENTORY }
    private State state = State.STARTING;
    private long  wakeAt = 0L;                                // simple tick gate

    private GameObject targetTree = null;
    private final Random rng = new Random();

    // ---- Config provider (RuneLite standard) --------------------------------
    @Provides
    WoodcutterConfig provideConfig(ConfigManager cm) {
        return cm.getConfig(WoodcutterConfig.class);
    }

    // ---- Lifecycle -----------------------------------------------------------
    @Override
    protected void startUp()
    {
        // 1) Feed synthetic taps → JSONL (HUD tail will show them on the heatmap)
        humanMouse.setTapSink(p -> {
            try { syntheticTapLogger.log(p.x, p.y); } catch (Throwable ignored) {}
        });

        // 2) (Optional) feel tuning — safe defaults are already in HumanMouse
        // humanMouse.setStepRange(6, 12);     // path step pacing (ms)
        // humanMouse.setReaction(120, 40);    // pre-press reaction (gaussian)
        // humanMouse.setHoldRange(30, 55);    // press/hold (ms)
        // humanMouse.setShiftWaitsMs(30, 30); // if you need generous Shift windows

        state = State.FINDING_TREE;
        log.info("Heist Woodcutter started (taps → JSONL; HUD tails file).");
    }

    @Override
    protected void shutDown()
    {
        state = State.STARTING;
        log.info("Heist Woodcutter stopped.");
    }

    // ---- Tick loop (thin controller) ----------------------------------------
    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (System.currentTimeMillis() < wakeAt) return;

        final Player me = client.getLocalPlayer();
        if (client.getGameState() != GameState.LOGGED_IN || me == null) return;

        switch (state)
        {
            case STARTING:
            case FINDING_TREE:
            {
                final TreeType want = config.treeType();
                targetTree = TreeDetector.findNearest(client, want);

                if (targetTree == null)
                {
                    // nothing in view; idle lightly
                    wakeAt = System.currentTimeMillis() + 400 + rng.nextInt(400);
                    break;
                }

                // Delegate the entire click to HumanMouse (worker thread):
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
                    // not moving and no chop? try again
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
    }

    // ---- Thin click helper (delegates to core-rl) ---------------------------
    private void clickGameObjectHull(GameObject obj)
    {
        if (obj == null) return;
        final Shape hull = obj.getConvexHull();
        if (hull == null) return;

        humanMouse.clickHull(hull, /*shift=*/false);
    }

    // ---- Inventory helpers (Shift-drop) -------------------------------------
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

    // ---- Axe animation lookup (unchanged) -----------------------------------
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
