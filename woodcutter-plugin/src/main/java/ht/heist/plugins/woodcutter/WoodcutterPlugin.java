// ============================================================================
// FILE: WoodcutterPlugin.java
// MODULE: woodcutter-plugin
// PACKAGE: ht.heist.plugins.woodcutter
// -----------------------------------------------------------------------------
// TITLE
//   Heist Woodcutter (Thin Controller)
//
// WHAT THIS DOES
//   • Finds a tree (via core-rl TreeDetector + TreeType from your config)
//   • Delegates the full humanized click to core-rl HumanMouse:
//       curved path → reaction pause BEFORE press → short hold → (optional Shift)
//   • Wires HumanMouse tap sink to SyntheticTapLogger (JSONL) for the HUD
//   • Shift-drops logs by calling humanMouse.clickRect(..., true)
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
        description = "Cuts trees with core-rl human mouse and shift-drops logs.",
        tags = {"heist","woodcutting"}
)
public class WoodcutterPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(WoodcutterPlugin.class);

    // RL + core-rl
    @Inject private Client client;
    @Inject private WoodcutterConfig config;
    @Inject private HumanMouse humanMouse;

    // JSONL writer for HUD
    @Inject private SyntheticTapLogger syntheticTapLogger;

    // Simple state
    private enum State { STARTING, FINDING_TREE, VERIFYING, CHOPPING, INVENTORY }
    private State state = State.STARTING;
    private long  wakeAt = 0L;

    private GameObject targetTree = null;
    private final Random rng = new Random();

    @Provides
    WoodcutterConfig provideConfig(ConfigManager cm) { return cm.getConfig(WoodcutterConfig.class); }

    @Override
    protected void startUp()
    {
        // Wire tap sink → JSONL (HUD tails it). Also emit a quick log per tap.
        humanMouse.setTapSink(p -> {
            try {
                log.info("[Woodcutter] tap at {},{}", p.x, p.y);
                syntheticTapLogger.log(p.x, p.y);
            } catch (Throwable ignored) {}
        });

        // Optional: if shift feels tight, bump small waits (if your HumanMouse exposes them)
        // humanMouse.setShiftWaitsMs(30, 30);

        state = State.FINDING_TREE;
        log.info("Heist Woodcutter started.");
    }

    @Override
    protected void shutDown()
    {
        state = State.STARTING;
        log.info("Heist Woodcutter stopped.");
    }

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
                    wakeAt = System.currentTimeMillis() + 400 + rng.nextInt(400);
                    break;
                }

                clickHull(targetTree);
                state = State.VERIFYING;
                wakeAt = System.currentTimeMillis() + 900 + rng.nextInt(300);
                break;
            }

            case VERIFYING:
            {
                if (me.getAnimation() == currentAxeAnim()) {
                    state = State.CHOPPING;
                } else if (me.getPoseAnimation() == me.getIdlePoseAnimation()) {
                    state = State.FINDING_TREE;
                }
                break;
            }

            case CHOPPING:
            {
                if (me.getAnimation() != currentAxeAnim()) {
                    state = State.FINDING_TREE;
                }
                if (isInventoryFull()) {
                    state = State.INVENTORY;
                }
                break;
            }

            case INVENTORY:
            {
                if (!dropOneLogIfPresent()) {
                    state = State.FINDING_TREE; // done dropping → resume
                }
                wakeAt = System.currentTimeMillis() + 180 + rng.nextInt(120);
                break;
            }
        }
    }

    // ---- thin click helper --------------------------------------------------
    private void clickHull(GameObject obj)
    {
        if (obj == null) return;
        final Shape hull = obj.getConvexHull();
        if (hull == null) return;

        // HumanMouse worker will: curve move → reaction wait → press/hold/release
        humanMouse.clickHull(hull, /*shift=*/false);
    }

    // ---- inventory helpers --------------------------------------------------
    private boolean dropOneLogIfPresent()
    {
        final Widget inv = client.getWidget(WidgetInfo.INVENTORY);
        if (inv == null || inv.isHidden()) return false;

        final List<Integer> logIds = Arrays.asList(
                ItemID.LOGS, ItemID.OAK_LOGS, ItemID.WILLOW_LOGS, ItemID.MAPLE_LOGS
        );

        for (Widget w : inv.getDynamicChildren())
        {
            if (w == null) continue;
            if (logIds.contains(w.getItemId()))
            {
                // Shift-drop via HumanMouse rectangle helper
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

    // ---- axe anim helper ----------------------------------------------------
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
