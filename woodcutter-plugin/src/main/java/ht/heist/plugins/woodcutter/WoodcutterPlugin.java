// ============================================================================
// FILE: WoodcutterPlugin.java
// PACKAGE: ht.heist.plugins.woodcutter
// -----------------------------------------------------------------------------
// TITLE
//   Thin woodcutter — planning-only core (core-lib) + executor gateway.
//
// WHY THIS VERSION FIXES YELLOW CLICKS
//   - We now explicitly call, in order:
//       1) moveAlong(plan.path(), step delays)
//       2) dwell(plan.dwellMin..Max)   ← HOVER BEFORE PRESS (critical!)
//       3) reactGaussian(mean, std)
//       4) clickAt(target, holdMin..Max)
//   - The dwell gives OSRS time to “red-click” the target.
//
// HOW IT TALKS TO HUD
//   - CoreLocator.getHeatmapTapSink() (may be null until HUD starts).
//   - We push the actual target pixel just before scheduling the click.
// ============================================================================

package ht.heist.plugins.woodcutter;

import com.google.inject.Provides;
import ht.heist.corelib.bridge.CoreLocator;
import ht.heist.corelib.mouse.MousePlanner;
import ht.heist.corelib.mouse.MousePlannerImpl;
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
import java.util.List;
import java.util.*;
import java.util.function.Consumer;

@PluginDescriptor(
        name = "Heist Woodcutter",
        description = "Cuts trees with human-like movement and proper dwell to ensure red clicks.",
        tags = {"heist", "woodcutting"}
)
public class WoodcutterPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(WoodcutterPlugin.class);

    // RuneLite base
    @Inject private Client client;
    @Inject private ConfigManager configManager;
    @Inject private WoodcutterConfig config;

    // Core-lib planner (pure logic)
    private final MousePlanner planner = new MousePlannerImpl();

    // Executor-based AWT dispatcher
    @Inject private MouseGateway mouse;

    // Heatmap sink (set by HUD at runtime)
    private Consumer<java.awt.Point> heatmapTapSink;

    // Simple state machine
    private enum State { STARTING, FINDING_TREE, VERIFYING, CHOPPING, INVENTORY }
    private State state = State.STARTING;
    private long wakeAt = 0L;

    private GameObject targetTree;

    // =========================================================================
    // LIFECYCLE
    // =========================================================================
    @Override
    protected void startUp()
    {
        log.info("Heist Woodcutter: starting");
        bindBridgeHandles();
        state = State.FINDING_TREE;
    }

    @Override
    protected void shutDown()
    {
        log.info("Heist Woodcutter: stopped");
    }

    // =========================================================================
    // TICK LOOP
    // =========================================================================
    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (System.currentTimeMillis() < wakeAt) return;

        // May be null until HUD starts (we re-check every tick)
        bindBridgeHandles();

        final Player me = client.getLocalPlayer();
        if (client.getGameState() != GameState.LOGGED_IN || me == null) return;

        switch (state)
        {
            case STARTING:
            case FINDING_TREE:
            {
                targetTree = findNearestTree(config.treeType().getId());
                if (targetTree == null)
                {
                    log.debug("No tree found nearby; waiting");
                    sleep(600, 900);
                }
                else
                {
                    clickGameObjectHull(targetTree);
                    state = State.VERIFYING;
                    sleep(900, 1200);
                }
                break;
            }

            case VERIFYING:
            {
                if (me.getAnimation() == currentAxeAnim()) {
                    state = State.CHOPPING;
                } else if (!isMoving(me)) {
                    state = State.FINDING_TREE; // try again
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
                if (config.logHandling() == WoodcutterConfig.LogHandling.DROP)
                    dropOneLogIfPresent();
                else
                    burnOneLogIfPossible();

                state = State.FINDING_TREE;
                break;
            }
        }
    }

    // =========================================================================
    // BRIDGE READER (HUD publisher)
    // =========================================================================
    private void bindBridgeHandles()
    {
        heatmapTapSink = CoreLocator.getHeatmapTapSink(); // may be null
    }

    // =========================================================================
    // CLICK EXECUTION — plan, then execute via MouseGateway
    // =========================================================================
    private void clickGameObjectHull(GameObject obj)
    {
        final Shape hull = (obj != null) ? obj.getConvexHull() : null;
        if (hull == null) return;

        final Rectangle r = hull.getBounds();
        final java.awt.Point target = planner.pickPointInShape(hull, r);
        final net.runelite.api.Point mp = client.getMouseCanvasPosition();
        final java.awt.Point from = (mp != null) ? new java.awt.Point(mp.getX(), mp.getY()) : null;

        final MousePlanner.MousePlan plan = planner.plan(from, target);

        // 1) Move along the planned path
        mouse.moveAlong(toAwtList(plan.path()), plan.stepDelayMinMs(), plan.stepDelayMaxMs());

        // 2) Hover dwell BEFORE press (prevents yellow/no-action clicks)
        mouse.dwell(plan.dwellMinMs(), plan.dwellMaxMs());

        // 3) Immediately fan-out the actual click pixel to the heatmap (HUD may be null)
        if (heatmapTapSink != null) heatmapTapSink.accept(target);

        // 4) Human reaction jitter JUST BEFORE the press
        mouse.reactGaussian(plan.reactMeanMs(), plan.reactStdMs());

        // 5) Click (no shift for object interactions)
        mouse.clickAt(target, plan.holdMinMs(), plan.holdMaxMs(), false);
    }

    private static List<java.awt.Point> toAwtList(List<java.awt.Point> in) { return in; }

    // =========================================================================
    // INVENTORY HELPERS (minimal; can be replaced later)
    // =========================================================================
    private boolean isInventoryFull()
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        return inv != null && inv.count() >= 28;
    }

    private void dropOneLogIfPresent()
    {
        Widget inv = client.getWidget(WidgetInfo.INVENTORY);
        if (inv == null || inv.isHidden()) return;

        for (Widget w : inv.getDynamicChildren())
        {
            int id = w.getItemId();
            if (id == ItemID.LOGS || id == ItemID.OAK_LOGS || id == ItemID.WILLOW_LOGS)
            {
                // For items we can still use rect picker
                Rectangle rect = w.getBounds();
                if (rect != null)
                {
                    final java.awt.Point target = planner.pickPointInRect(rect);
                    final net.runelite.api.Point mp = client.getMouseCanvasPosition();
                    final java.awt.Point from = (mp != null) ? new java.awt.Point(mp.getX(), mp.getY()) : null;

                    final MousePlanner.MousePlan plan = planner.plan(from, target);
                    mouse.moveAlong(plan.path(), plan.stepDelayMinMs(), plan.stepDelayMaxMs());
                    mouse.dwell(plan.dwellMinMs(), plan.dwellMaxMs());
                    if (heatmapTapSink != null) heatmapTapSink.accept(target);
                    mouse.reactGaussian(plan.reactMeanMs(), plan.reactStdMs());
                    mouse.clickAt(target, plan.holdMinMs(), plan.holdMaxMs(), true /*shift*/);
                }
                sleep(140, 220);
                return;
            }
        }
    }

    private void burnOneLogIfPossible()
    {
        Widget inv = client.getWidget(WidgetInfo.INVENTORY);
        if (inv == null || inv.isHidden()) return;

        Widget tinder = findInvItem(inv, ItemID.TINDERBOX);
        Widget log    = findFirstOf(inv, List.of(ItemID.LOGS, ItemID.OAK_LOGS, ItemID.WILLOW_LOGS));

        if (tinder != null && log != null)
        {
            // Use rect clicks for inventory widgets
            if (tinder.getBounds() != null) {
                java.awt.Point pt = planner.pickPointInRect(tinder.getBounds());
                MousePlanner.MousePlan plan = planner.plan(currentMousePoint(), pt);
                mouse.moveAlong(plan.path(), plan.stepDelayMinMs(), plan.stepDelayMaxMs());
                mouse.dwell(plan.dwellMinMs(), plan.dwellMaxMs());
                if (heatmapTapSink != null) heatmapTapSink.accept(pt);
                mouse.reactGaussian(plan.reactMeanMs(), plan.reactStdMs());
                mouse.clickAt(pt, plan.holdMinMs(), plan.holdMaxMs(), false);
            }
            sleep(120, 200);
            if (log.getBounds() != null) {
                java.awt.Point pt = planner.pickPointInRect(log.getBounds());
                MousePlanner.MousePlan plan = planner.plan(currentMousePoint(), pt);
                mouse.moveAlong(plan.path(), plan.stepDelayMinMs(), plan.stepDelayMaxMs());
                mouse.dwell(plan.dwellMinMs(), plan.dwellMaxMs());
                if (heatmapTapSink != null) heatmapTapSink.accept(pt);
                mouse.reactGaussian(plan.reactMeanMs(), plan.reactStdMs());
                mouse.clickAt(pt, plan.holdMinMs(), plan.holdMaxMs(), false);
            }
            sleep(800, 1200);
        }
    }

    private java.awt.Point currentMousePoint()
    {
        net.runelite.api.Point mp = client.getMouseCanvasPosition();
        return (mp != null) ? new java.awt.Point(mp.getX(), mp.getY()) : null;
    }

    private Widget findInvItem(Widget inv, int itemId)
    {
        for (Widget w : inv.getDynamicChildren())
            if (w.getItemId() == itemId) return w;
        return null;
    }

    private Widget findFirstOf(Widget inv, List<Integer> ids)
    {
        for (Widget w : inv.getDynamicChildren())
            if (ids.contains(w.getItemId())) return w;
        return null;
    }

    // =========================================================================
    // WORLD HELPERS
    // =========================================================================
    private GameObject findNearestTree(int objectId)
    {
        Player me = client.getLocalPlayer();
        if (me == null) return null;

        Tile[][][] tiles = client.getScene().getTiles();
        GameObject best = null;
        int bestDist = Integer.MAX_VALUE;

        for (int z = 0; z < tiles.length; z++)
        {
            for (int x = 0; x < tiles[z].length; x++)
            {
                for (int y = 0; y < tiles[z][x].length; y++)
                {
                    Tile t = tiles[z][x][y];
                    if (t == null) continue;
                    for (GameObject go : t.getGameObjects())
                    {
                        if (go == null || go.getId() != objectId || go.getConvexHull() == null) continue;
                        int d = go.getWorldLocation().distanceTo(me.getWorldLocation());
                        if (d < bestDist) { best = go; bestDist = d; }
                    }
                }
            }
        }
        return best;
    }

    private boolean isMoving(Player me)
    {
        return me.getPoseAnimation() != me.getIdlePoseAnimation();
    }

    private int currentAxeAnim()
    {
        ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
        if (eq == null) return -1;
        Item weapon = eq.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
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

    private void sleep(int min, int max) { wakeAt = System.currentTimeMillis() + randBetween(min, max); }
    private static int randBetween(int lo, int hi) {
        if (lo > hi) { int t = lo; lo = hi; hi = t; }
        return lo + (int)(Math.random() * Math.max(1, (hi - lo + 1)));
    }

    // =========================================================================
    // GUICE PROVIDER (only our config)
    // =========================================================================
    @Provides
    WoodcutterConfig provideConfig(ConfigManager cm) { return cm.getConfig(WoodcutterConfig.class); }
}
