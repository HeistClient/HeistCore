// ============================================================================
// FILE: WoodcutterPlugin.java
// PACKAGE: ht.heist.plugins.woodcutter
// -----------------------------------------------------------------------------
// TITLE
//   Heist Woodcutter — clean, self-contained plugin panel and logic
//
// WHY THIS VERSION
//   • FIX: Removed any call to Widget.info() (does not exist in RuneLite API).
//   • FIX: Stopped referencing WidgetInfo.TAB_INVENTORY (not present in your build).
//           The inventory is opened with the default F4 hotkey instead.
//   • Avoids WidgetInfo entirely; uses WidgetID.INVENTORY_GROUP_ID for max compat.
//   • Keeps the heatmap bridge via CoreLocator (if Core publishes the sink).
//
// PANEL CLEANLINESS
//   • This plugin provides ONLY its own WoodcutterConfig, so its panel shows
//     “Tree Type” and “Log Handling” — not Heist Core settings.
// ============================================================================

package ht.heist.plugins.woodcutter;

import com.google.inject.Provides;
import ht.heist.core.CoreLocator;
import ht.heist.core.config.HeistCoreConfig;
import ht.heist.core.impl.HumanizerServiceImpl;
import ht.heist.core.impl.MouseServiceImpl;
import ht.heist.core.services.HumanizerService;
import ht.heist.core.services.MouseService;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.awt.Canvas;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.function.Consumer;

@PluginDescriptor(
        name = "Heist Woodcutter",
        description = "Cuts trees and handles logs with human-like input.",
        tags = {"heist", "woodcutting"}
)
public class WoodcutterPlugin extends Plugin
{
    // ===== Logging ===========================================================
    private static final Logger log = LoggerFactory.getLogger(WoodcutterPlugin.class);

    // ===== Injected: RL base + config manager ================================
    @Inject private Client client;
    @Inject private ConfigManager configManager;
    @Inject private WoodcutterConfig config;

    // ===== Injected: local services (we bind them below) =====================
    @Inject private HumanizerService humanizer;
    @Inject private MouseService mouse;

    // ===== State machine =====================================================
    private enum State
    {
        STARTING,
        IDLE,
        FINDING_TREE,
        WALKING_TO_TREE,
        VERIFYING_CHOP_START,
        CHOPPING,
        HANDLING_INVENTORY,
        DROPPING_LOGS,
        BURNING_LOGS,
        VERIFYING_FIREMAKING_START,
        WAITING_FOR_FIRE_TO_BURN,
        MOVING_TO_SAFE_TILE,
        RECOVERING
    }

    private State currentState = State.STARTING;
    private long stateTimeout = 0L;
    private long nextActionTime = 0L;

    private WorldPoint startLocation;
    private GameObject currentTarget;

    // ===== Simple blacklist for failed targets ===============================
    private static final long BLACKLIST_MS = 10_000;
    private final Map<WorldPoint, Long> blacklist = new HashMap<>();

    // ===== Axe animation lookup =============================================
    private static final Map<Integer, Integer> AXE_ANIM = new HashMap<>();
    static {
        AXE_ANIM.put(ItemID.BRONZE_AXE,  AnimationID.WOODCUTTING_BRONZE);
        AXE_ANIM.put(ItemID.IRON_AXE,    AnimationID.WOODCUTTING_IRON);
        AXE_ANIM.put(ItemID.STEEL_AXE,   AnimationID.WOODCUTTING_STEEL);
        AXE_ANIM.put(ItemID.MITHRIL_AXE, AnimationID.WOODCUTTING_MITHRIL);
        AXE_ANIM.put(ItemID.ADAMANT_AXE, AnimationID.WOODCUTTING_ADAMANT);
        AXE_ANIM.put(ItemID.RUNE_AXE,    AnimationID.WOODCUTTING_RUNE);
    }

    // ===== Firemaking bits ===================================================
    private static final int TINDERBOX_ID = ItemID.TINDERBOX;
    private static final int FIRE_OBJECT_ID = 26185; // ObjectID.FIRE_26185

    // ===== Tiny timing helpers ==============================================
    private static final int REACT_MEAN = 120;
    private static final int REACT_INV_OPEN = 600;
    private static final int REACT_DROP = 120;

    // =========================================================================
    // LIFECYCLE
    // =========================================================================
    @Override
    protected void startUp()
    {
        log.info("Woodcutter starting");

        if (client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null)
            startLocation = client.getLocalPlayer().getWorldLocation();

        // Route actual click points to Core heatmap + Core recorder (if present)
        if (mouse instanceof MouseServiceImpl)
        {
            ((MouseServiceImpl) mouse).setClickTapListener((java.awt.Point pt) -> {
                final Consumer<java.awt.Point> sink = CoreLocator.getHeatmapTapSink();
                if (sink != null) sink.accept(pt);                         // → Core Heatmap
                if (CoreLocator.getRecorder() != null)
                    CoreLocator.getRecorder().recordSyntheticClick(pt);     // → Core Recorder
            });
        }

        setState(State.FINDING_TREE);
    }

    @Override
    protected void shutDown()
    {
        log.info("Woodcutter: stopped");
    }

    // =========================================================================
    // TICK LOOP
    // =========================================================================
    @Subscribe
    public void onGameTick(GameTick tick)
    {
        final Player local = client.getLocalPlayer();
        if (client.getGameState() != GameState.LOGGED_IN || local == null || isWaiting())
            return;

        if (currentState == State.STARTING)
        {
            startLocation = local.getWorldLocation();
            setState(State.FINDING_TREE);
            return;
        }

        if (getEquippedAxeId() == -1)
        {
            log.warn("No axe equipped → IDLE");
            setState(State.IDLE);
            return;
        }

        if (isInventoryFull() && isNotHandlingInventory())
        {
            log.info("Inventory full → HANDLING_INVENTORY");
            setState(State.HANDLING_INVENTORY);
        }

        runState();
    }

    // =========================================================================
    // STATE DRIVER
    // =========================================================================
    private void runState()
    {
        switch (currentState)
        {
            case FINDING_TREE:               findingTree(); break;
            case WALKING_TO_TREE:            walking(); break;
            case VERIFYING_CHOP_START:       verifyingChopStart(); break;
            case CHOPPING:                   chopping(); break;
            case HANDLING_INVENTORY:         handlingInventory(); break;
            case DROPPING_LOGS:              droppingLogs(); break;
            case BURNING_LOGS:               burningLogs(); break;
            case VERIFYING_FIREMAKING_START: verifyingFiremaking(); break;
            case WAITING_FOR_FIRE_TO_BURN:   waitingForFire(); break;
            case MOVING_TO_SAFE_TILE:        movingToSafe(); break;
            case RECOVERING:                 recovering(); break;
            case IDLE:
            case STARTING:
            default: break;
        }
    }

    // =========================================================================
    // STATE HANDLERS (LABELED)
    // =========================================================================

    // [FINDING_TREE] — pick nearest allowed tree and click its hull
    private void findingTree()
    {
        if (isPlayerBusy()) return;

        cleanBlacklist();
        currentTarget = findNearestTreeByIteration();
        if (currentTarget == null)
        {
            log.info("No tree found → RECOVERING");
            setState(State.RECOVERING);
            return;
        }

        log.info("Found tree at {}. Interacting.", currentTarget.getWorldLocation());
        clickShapeHuman(currentTarget.getConvexHull(), false);
        setState(State.VERIFYING_CHOP_START, 5000);
    }

    // [WALKING_TO_TREE]
    private void walking()
    {
        if (client.getLocalPlayer().getAnimation() == getEquippedAxeAnimation())
        {
            setState(State.CHOPPING);
            return;
        }

        if (!isPlayerMoving() || isInteractionTimeout())
        {
            log.warn("Failed to reach tree → re-evaluate");
            blacklist(currentTarget != null ? currentTarget.getWorldLocation() : null);
            setState(State.FINDING_TREE);
        }
    }

    // [VERIFYING_CHOP_START]
    private void verifyingChopStart()
    {
        if (client.getLocalPlayer().getAnimation() == getEquippedAxeAnimation())
        {
            setState(State.CHOPPING);
            return;
        }

        if (isPlayerMoving())
        {
            setState(State.WALKING_TO_TREE, 8000);
            return;
        }

        if (isInteractionTimeout())
        {
            log.warn("Chop didn’t start → blacklist & retry");
            blacklist(currentTarget != null ? currentTarget.getWorldLocation() : null);
            setState(State.FINDING_TREE);
        }
    }

    // [CHOPPING]
    private void chopping()
    {
        if (client.getLocalPlayer().getAnimation() != getEquippedAxeAnimation())
        {
            if (currentTarget != null) blacklist(currentTarget.getWorldLocation());
            setState(State.FINDING_TREE);
        }
    }

    // [HANDLING_INVENTORY] — open inventory and branch
    private void handlingInventory()
    {
        if (!isInventoryOpen())
        {
            openInventoryTab();
            setWait(REACT_MEAN, REACT_MEAN + REACT_INV_OPEN);
            return;
        }

        if (config.logHandling() == WoodcutterConfig.LogHandling.DROP)
            setState(State.DROPPING_LOGS);
        else
            setState(State.BURNING_LOGS);
    }

    // [DROPPING_LOGS]
    private void droppingLogs()
    {
        if (dropOne(getLogIds()))
        {
            setWait(REACT_MEAN, REACT_MEAN + REACT_DROP);
            return;
        }

        log.info("Logs dropped → back to trees");
        setState(State.FINDING_TREE);
    }

    // [BURNING_LOGS]
    private void burningLogs()
    {
        if (isStandingOnFireByIteration(client.getLocalPlayer().getWorldLocation()))
        {
            setState(State.MOVING_TO_SAFE_TILE);
            return;
        }

        Widget tinder = getInventoryItem(TINDERBOX_ID);
        Widget logW = getFirstLogFromInventory();

        if (tinder != null && logW != null)
        {
            clickRectHuman(tinder.getBounds(), false);
            setWait(REACT_MEAN, REACT_MEAN + 100);
            clickRectHuman(logW.getBounds(), false);
            setState(State.VERIFYING_FIREMAKING_START, 3000);
        }
        else
        {
            log.info("No logs to burn → back to trees");
            setState(State.FINDING_TREE);
        }
    }

    // [VERIFYING_FIREMAKING_START]
    private void verifyingFiremaking()
    {
        if (client.getLocalPlayer().getAnimation() == AnimationID.FIREMAKING)
        {
            setState(State.WAITING_FOR_FIRE_TO_BURN);
        }
        else if (isInteractionTimeout())
        {
            setState(State.BURNING_LOGS);
        }
    }

    // [WAITING_FOR_FIRE_TO_BURN]
    private void waitingForFire()
    {
        if (client.getLocalPlayer().getAnimation() != AnimationID.FIREMAKING)
        {
            setWait(120, 240);
            setState(State.BURNING_LOGS);
        }
    }

    // [MOVING_TO_SAFE_TILE]
    private void movingToSafe()
    {
        if (!isPlayerMoving())
        {
            WorldPoint safe = findSafeNearbyTile(startLocation);
            if (safe != null)
            {
                clickOnMinimap(safe);
                setWait(2000, 4000);
            }
            else
            {
                log.warn("No safe tile found → IDLE");
                setState(State.IDLE);
            }
        }
        else if (!isStandingOnFireByIteration(client.getLocalPlayer().getWorldLocation()))
        {
            setState(State.BURNING_LOGS);
        }
    }

    // [RECOVERING]
    private void recovering()
    {
        final Player local = client.getLocalPlayer();
        if (startLocation != null && local != null
                && local.getWorldLocation().distanceTo(startLocation) > 5)
        {
            clickOnMinimap(startLocation);
            setWait(3000, 5000);
        }
        setState(State.FINDING_TREE);
    }

    // =========================================================================
    // INVENTORY HELPERS
    // =========================================================================

    // Root of the inventory interface (robust across RL versions)
    private Widget getInventoryRoot()
    {
        // Most builds expose the root at group INVENTORY_GROUP_ID child 0
        return client.getWidget(WidgetID.INVENTORY_GROUP_ID, 0);
    }

    // Is the inventory panel visible?
    private boolean isInventoryOpen()
    {
        Widget inv = getInventoryRoot();
        return inv != null && !inv.isHidden();
    }

    // Open the inventory tab — use F4 (default OSRS hotkey)
    private void openInventoryTab()
    {
        dispatchKey(KeyEvent.VK_F4);
    }

    // Is backpack full?
    private boolean isInventoryFull()
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv == null) return false;

        int filled = 0;
        final Item[] items = inv.getItems();
        if (items != null)
        {
            for (Item it : items)
            {
                if (it != null && it.getId() != -1) filled++;
            }
        }
        return filled >= 28;
    }

    // Find an inventory widget by any of the item IDs
    private Widget getInventoryItem(List<Integer> ids)
    {
        Widget inv = getInventoryRoot();
        if (inv == null || inv.isHidden()) return null;

        for (Widget w : inv.getDynamicChildren())
        {
            if (w.getItemId() != -1 && ids.contains(w.getItemId()))
                return w;
        }
        return null;
    }

    private Widget getInventoryItem(int id) { return getInventoryItem(Collections.singletonList(id)); }
    private Widget getFirstLogFromInventory() { return getInventoryItem(getLogIds()); }

    private List<Integer> getLogIds()
    {
        return Arrays.asList(ItemID.LOGS, ItemID.OAK_LOGS, ItemID.WILLOW_LOGS, ItemID.MAPLE_LOGS);
    }

    // Shift-click drop a single log; returns true if one was dropped
    private boolean dropOne(List<Integer> ids)
    {
        if (!isInventoryOpen())
            openInventoryTab();

        Widget inv = getInventoryRoot();
        if (inv == null || inv.isHidden()) return false;

        for (Widget w : inv.getDynamicChildren())
        {
            if (w.getItemId() != -1 && ids.contains(w.getItemId()))
            {
                clickRectHuman(w.getBounds(), true); // shift-click
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // SCENE HELPERS
    // =========================================================================

    private GameObject findNearestTreeByIteration()
    {
        final Player local = client.getLocalPlayer();
        if (local == null) return null;

        final int desiredId = config.treeType().getTreeId();
        final Scene scene = client.getScene();
        final Tile[][][] tiles = scene.getTiles();

        GameObject best = null;
        int bestDist = Integer.MAX_VALUE;

        for (int z = 0; z < Constants.MAX_Z; z++)
        {
            for (int x = 0; x < Constants.SCENE_SIZE; x++)
            {
                for (int y = 0; y < Constants.SCENE_SIZE; y++)
                {
                    Tile t = tiles[z][x][y];
                    if (t == null) continue;

                    for (GameObject obj : t.getGameObjects())
                    {
                        if (obj == null || obj.getId() != desiredId || obj.getConvexHull() == null)
                            continue;
                        WorldPoint wp = obj.getWorldLocation();
                        if (blacklist.containsKey(wp)) continue;

                        int d = wp.distanceTo(local.getWorldLocation());
                        if (d < bestDist) { best = obj; bestDist = d; }
                    }
                }
            }
        }
        return best;
    }

    private boolean isStandingOnFireByIteration(WorldPoint loc)
    {
        LocalPoint lp = LocalPoint.fromWorld(client, loc);
        if (lp == null) return false;
        Tile tile = client.getScene().getTiles()[loc.getPlane()][lp.getSceneX()][lp.getSceneY()];
        if (tile == null) return false;

        for (GameObject o : tile.getGameObjects())
            if (o != null && o.getId() == FIRE_OBJECT_ID) return true;
        return false;
    }

    private WorldPoint findSafeNearbyTile(WorldPoint center)
    {
        if (center == null) return null;
        final CollisionData[] maps = client.getCollisionMaps();
        if (maps == null || maps.length <= client.getPlane()) return null;

        for (int r = 1; r <= 5; r++)
        {
            for (int dx = -r; dx <= r; dx++)
            {
                for (int dy = -r; dy <= r; dy++)
                {
                    if (dx == 0 && dy == 0) continue;
                    WorldPoint wp = center.dx(dx).dy(dy);
                    LocalPoint lp = LocalPoint.fromWorld(client, wp);
                    if (lp == null) continue;

                    int flags = maps[client.getPlane()].getFlags()[lp.getSceneX()][lp.getSceneY()];
                    boolean blocked = (flags & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0;
                    if (!blocked && !isStandingOnFireByIteration(wp)) return wp;
                }
            }
        }
        return null;
    }

    // =========================================================================
    // INPUT HELPERS
    // =========================================================================

    private void clickOnMinimap(WorldPoint target)
    {
        LocalPoint lp = LocalPoint.fromWorld(client, target);
        if (lp == null) return;
        net.runelite.api.Point p = Perspective.localToMinimap(client, lp);
        if (p != null) mouse.humanClick(new Rectangle(p.getX(), p.getY(), 1, 1), false);
    }

    private void clickShapeHuman(Shape shape, boolean shift) { if (shape != null) mouse.humanClick(shape, shift); }
    private void clickRectHuman(Rectangle r, boolean shift) { if (r != null)    mouse.humanClick(r, shift);     }

    private void dispatchKey(int keyCode)
    {
        Canvas canvas = client.getCanvas();
        if (canvas == null) return;

        try {
            canvas.dispatchEvent(new java.awt.event.KeyEvent(canvas, java.awt.event.KeyEvent.KEY_PRESSED,  System.currentTimeMillis(), 0, keyCode, java.awt.event.KeyEvent.CHAR_UNDEFINED));
            humanizer.sleep(45, 95);
            canvas.dispatchEvent(new java.awt.event.KeyEvent(canvas, java.awt.event.KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, keyCode, java.awt.event.KeyEvent.CHAR_UNDEFINED));
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // SMALL UTILS & STATE HELPERS
    // =========================================================================

    private void setState(State s) { setState(s, 0); }
    private void setState(State s, long timeoutMs)
    {
        if (currentState != s) log.info("State: {} → {}", currentState, s);
        currentState = s;
        stateTimeout = timeoutMs > 0 ? (System.currentTimeMillis() + timeoutMs) : 0;
    }
    private boolean isInteractionTimeout() { return stateTimeout != 0 && System.currentTimeMillis() > stateTimeout; }
    private void setWait(int minMs, int maxMs) { nextActionTime = System.currentTimeMillis() + humanizer.randomDelay(minMs, maxMs); }
    private boolean isWaiting() { return System.currentTimeMillis() < nextActionTime; }

    private int getEquippedAxeId()
    {
        ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
        if (eq == null) return -1;
        Item weapon = eq.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
        return weapon != null ? weapon.getId() : -1;
    }
    private int getEquippedAxeAnimation() { return AXE_ANIM.getOrDefault(getEquippedAxeId(), -1); }
    private boolean isPlayerMoving()
    {
        Player local = client.getLocalPlayer();
        return local != null && local.getPoseAnimation() != local.getIdlePoseAnimation();
    }
    private boolean isPlayerBusy()
    {
        Player local = client.getLocalPlayer();
        return local == null || local.getAnimation() != -1 || isPlayerMoving() || local.getInteracting() != null;
    }
    private void blacklist(WorldPoint wp) { if (wp != null) blacklist.put(wp, System.currentTimeMillis() + BLACKLIST_MS); }
    private void cleanBlacklist() { long now = System.currentTimeMillis(); blacklist.entrySet().removeIf(e -> now > e.getValue()); }
    private boolean isNotHandlingInventory()
    {
        return currentState != State.HANDLING_INVENTORY
                && currentState != State.DROPPING_LOGS
                && currentState != State.BURNING_LOGS
                && currentState != State.VERIFYING_FIREMAKING_START
                && currentState != State.WAITING_FOR_FIRE_TO_BURN
                && currentState != State.MOVING_TO_SAFE_TILE;
    }

    // =========================================================================
    // GUICE BINDINGS — EXACTLY ONE CONFIG HERE
    //   Note: We also expose HeistCoreConfig so MouseServiceImpl can be built
    //   if it needs it. This doesn't surface Core settings in this panel.
// =========================================================================
    @Provides
    WoodcutterConfig provideWoodcutterConfig(ConfigManager cm)
    {
        return cm.getConfig(WoodcutterConfig.class);
    }

    @Provides
    HumanizerService provideHumanizer(HumanizerServiceImpl impl) { return impl; }

    @Provides
    MouseService provideMouse(MouseServiceImpl impl) { return impl; }

    @Provides
    HeistCoreConfig provideHeistCoreConfig(ConfigManager cm) { return cm.getConfig(HeistCoreConfig.class); }
}
