// ============================================================================
// FILE: WoodcutterPlugin.java
// PACKAGE: ht.heist.plugins.woodcutter
// -----------------------------------------------------------------------------
// TITLE
//   Heist Woodcutter (Thin Controller) — tree detect → delegate human click
//
// OVERVIEW
//   This plugin keeps gameplay logic *thin* and pushes all mouse “humanization”
//   into core-rl’s HumanMouse (curved move → reaction-before-press → short hold).
//
//   Responsibilities in THIS class:
//     • Find a nearby tree that matches the selected TreeType.
//     • Ask HumanMouse to click the GameObject’s convex hull (non-teleporting).
//     • Verify chopping by watching for the axe animation.
//     • When inventory is full, drop logs using HumanMouse.clickRect(...) with
//       Shift held (assumes player has OSRS “Shift-drop” enabled).
//     • Publish a synthetic-tap sink so every click is written to clicks.jsonl.
//
//   What it DOES NOT do here (by design):
//     • Build mouse paths, hover, reaction, or press timing (handled by core-rl).
//     • Dispatch AWT events directly (core-rl/MouseGateway does that).
//
// DEPENDENCIES
//   • HumanMouse (ht.heist.corerl.input.HumanMouse) — provides:
//       - clickHull(Shape hull, boolean shift)
//       - clickRect(Rectangle rect, boolean shift)
//       - setTapSink(Consumer<Point> sink)
//   • TreeDetector (ht.heist.corerl.object.TreeDetector) — findNearest(Client, TreeType)
//   • SyntheticTapLogger (ht.heist.plugins.woodcutter.io.SyntheticTapLogger) — writes JSONL
//
// PLACEMENT
//   HeistCore/woodcutter-plugin/src/main/java/ht/heist/plugins/woodcutter/WoodcutterPlugin.java
//
// RUNTIME NOTES
//   • All clicks are queued on HumanMouse’s single-thread executor; we just
//     “set wakeAt” here to give the worker time before verifying state.
//   • Yellow-click protection comes from the reaction-before-press inside
//     HumanMouse (not from sleeps in this plugin).
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
import java.awt.*;
import java.util.Arrays;
import java.util.List;

@PluginDescriptor(
        name = "Heist Woodcutter",
        description = "Thin tree-cutting controller that delegates red-click behavior to core-rl HumanMouse.",
        tags = {"heist","woodcutting"}
)
public class WoodcutterPlugin extends Plugin
{
    // ----- Logging ------------------------------------------------------------
    private static final Logger log = LoggerFactory.getLogger(WoodcutterPlugin.class);

    // ----- Injected deps ------------------------------------------------------
    @Inject private Client client;
    @Inject private WoodcutterConfig config;
    @Inject private HumanMouse humanMouse;

    // ----- Simple state machine ----------------------------------------------
    private enum State { STARTING, FINDING_TREE, VERIFYING_CHOP_START, CHOPPING, INVENTORY_DROP }
    private State state = State.STARTING;

    /** Wake gate so we don’t spam on every tick while HumanMouse works. */
    private long wakeAtMs = 0L;

    /** Remember the last target so we can blacklist if desired (optional). */
    private GameObject targetTree = null;

    // =========================================================================
    // PLUGIN LIFECYCLE
    // =========================================================================
    @Provides
    WoodcutterConfig provideConfig(ConfigManager cm) {
        return cm.getConfig(WoodcutterConfig.class);
    }

    @Override
    protected void startUp()
    {
        // Wire the tap sink exactly once: every synthetic tap from HumanMouse
        // is written to a JSONL file (user-profile-safe path in the logger).
        humanMouse.setTapSink(p -> {
            try { SyntheticTapLogger.log(p.x, p.y); }
            catch (Throwable t) { /* never break gameplay on I/O */ }
        });

        state = State.FINDING_TREE;
        log.info("[Heist-WC] started (thin controller).");
    }

    @Override
    protected void shutDown()
    {
        log.info("[Heist-WC] stopped.");
    }

    // =========================================================================
    // MAIN LOOP — driven by RuneLite ticks, not blocking the client thread
    // =========================================================================
    @Subscribe
    public void onGameTick(GameTick tick)
    {
        // Respect the wake gate set after we schedule a human click.
        if (System.currentTimeMillis() < wakeAtMs) return;

        // Sanity checks
        if (client.getGameState() != GameState.LOGGED_IN) return;
        final Player me = client.getLocalPlayer();
        if (me == null) return;

        // If the inventory is full, pivot into drop mode immediately.
        if (state != State.INVENTORY_DROP && isInventoryFull()) {
            log.debug("[Heist-WC] inventory appears full → dropping logs");
            state = State.INVENTORY_DROP;
        }

        switch (state)
        {
            case STARTING:
            case FINDING_TREE:
            {
                // Try the configured type first…
                targetTree = TreeDetector.findNearest(client, config.treeType());

                // …and if none found (e.g., willow IDs vary regionally), gracefully
                // fall back to ANY so the user still gets behavior instead of a stall.
                if (targetTree == null && config.treeType() != TreeType.ANY) {
                    targetTree = TreeDetector.findNearest(client, TreeType.ANY);
                    if (targetTree != null) {
                        log.debug("[Heist-WC] fallback: using ANY tree (no {} nearby).", config.treeType());
                    }
                }

                if (targetTree == null)
                {
                    // Nothing to do — check again shortly.
                    wakeAtMs = System.currentTimeMillis() + 600;
                    break;
                }

                // Ask core-rl to perform the human-like click on the hull.
                final Shape hull = targetTree.getConvexHull();
                if (hull != null)
                {
                    log.debug("[Heist-WC] queue hull click (type={}, id={}, loc={})",
                            config.treeType(), targetTree.getId(), targetTree.getWorldLocation());

                    humanMouse.clickHull(hull, /*shift=*/false);

                    // Give the worker a moment to move → react → press, then verify.
                    state = State.VERIFYING_CHOP_START;
                    wakeAtMs = System.currentTimeMillis() + 1000;
                }
                else
                {
                    // Hull missing (off-canvas or obstructed) — retry soon.
                    wakeAtMs = System.currentTimeMillis() + 400;
                }
                break;
            }

            case VERIFYING_CHOP_START:
            {
                // If the axe animation starts, we’re successfully chopping.
                if (me.getAnimation() == currentAxeAnimation()) {
                    state = State.CHOPPING;
                    break;
                }

                // If we’re not moving and didn’t start chopping, try again.
                if (!isMoving(me)) {
                    state = State.FINDING_TREE;
                }
                break;
            }

            case CHOPPING:
            {
                // When chopping stops or the tree despawns, go find another.
                if (me.getAnimation() != currentAxeAnimation()) {
                    state = State.FINDING_TREE;
                }
                break;
            }

            case INVENTORY_DROP:
            {
                // Drop one stack of logs per tick until none remain.
                if (!dropOneLogWithShift()) {
                    // All gone — back to finding trees.
                    state = State.FINDING_TREE;
                }
                // Small pacing so the drops don’t happen “all at once”.
                wakeAtMs = System.currentTimeMillis() + 180;
                break;
            }
        }
    }

    // =========================================================================
    // INVENTORY HELPERS — minimal & robust
    // =========================================================================

    /** Returns true if the inventory container exists and has 28 items. */
    private boolean isInventoryFull()
    {
        final ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        return inv != null && inv.count() >= 28;
    }

    /**
     * Finds the first inventory widget that looks like a log and Shift-drops it.
     *
     * Shift must be configured in the OSRS settings to “Drop” for this to be
     * instant-drop. If it isn’t, the click will simply “use” the item; you can
     * add a config later if you want to support right-click menus instead.
     *
     * @return true if a log widget was found and a drop click was issued
     */
    private boolean dropOneLogWithShift()
    {
        final Widget inv = client.getWidget(WidgetInfo.INVENTORY);
        if (inv == null || inv.isHidden()) return false;

        final List<Integer> logIds = Arrays.asList(
                ItemID.LOGS, ItemID.OAK_LOGS, ItemID.WILLOW_LOGS, ItemID.MAPLE_LOGS,
                ItemID.YEW_LOGS, ItemID.MAGIC_LOGS, ItemID.REDWOOD_LOGS, ItemID.TEAK_LOGS, ItemID.MAHOGANY_LOGS
        );

        for (Widget w : inv.getDynamicChildren())
        {
            if (w == null) continue;
            final int id = w.getItemId();
            if (!logIds.contains(id)) continue;

            final Rectangle bounds = w.getBounds();
            if (bounds == null) continue;

            // Use the new rectangle helper in HumanMouse; hold Shift = true.
            humanMouse.clickRect(bounds, /*shift=*/true);

            log.debug("[Heist-WC] drop log (id={}) via Shift-click at {}", id, bounds);
            return true;
        }
        return false;
    }

    // =========================================================================
    // ANIMATION & MOVEMENT HELPERS
    // =========================================================================

    /** True if the player is in a non-idle pose (simple movement heuristic). */
    private static boolean isMoving(Player me)
    {
        return me.getPoseAnimation() != me.getIdlePoseAnimation();
    }

    /** Determine the correct woodcut animation based on equipped axe. */
    private int currentAxeAnimation()
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
            // Extend here for Dragon/Infernal/3rd Age if you use them.
            default: return -1;
        }
    }
}
