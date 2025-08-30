// ============================================================================
// WoodcutterPlugin.java
// -----------------------------------------------------------------------------
// Purpose
//   Thin woodcutting plugin that delegates input/timing to core services.
//   - Red-click legacy behavior via MouseServiceImpl
//   - Inventory actions via InventoryService (dropOne per tick)
//   - Natural drop order: always the first remaining log (top→down, left→right)
//   - Heatmap shows ACTUAL click points via MouseServiceImpl tap hook
//   - Uses HeistCoreConfig for waits (no dead plugin knobs)
//
// Requirements
//   - Java 11 compatible
//   - Depends on: HeistCoreConfig, MouseService, HumanizerService,
//                 InventoryService, WoodcutterConfig
//   - WoodcutterConfig group id: "heistwoodcutter"
// ============================================================================

package ht.heist.plugins.woodcutter;

// ============================================================================
// Imports: Core & RuneLite
// ============================================================================
import com.google.inject.Provides;
import ht.heist.core.config.HeistCoreConfig;
import ht.heist.core.impl.MouseServiceImpl;   // for heatmap tap hook
import ht.heist.core.services.HumanizerService;
import ht.heist.core.services.MouseService;
import ht.heist.core.services.InventoryService;
import lombok.extern.slf4j.Slf4j;

import net.runelite.api.AnimationID;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Constants;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

// ============================================================================
// Imports: JDK
// ============================================================================
import javax.inject.Inject;
import java.awt.Canvas;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.util.*;

// ============================================================================
// @PluginDescriptor
// ============================================================================
@Slf4j
@PluginDescriptor(
        name = "Heist Woodcutter",
        description = "A thin woodcutting plugin that uses core services for human-like input.",
        tags = {"woodcutting", "automation", "heist"}
)
public class WoodcutterPlugin extends Plugin
{
    // =========================================================================
    // === Injected Services & Config =========================================
    // =========================================================================
    @Inject private Client client;
    @Inject private WoodcutterConfig config;            // plugin knobs (tree type, log handling, overlay)
    @Inject private OverlayManager overlayManager;
    @Inject private HeatmapOverlay heatmapOverlay;

    @Inject private HumanizerService humanizer;         // timing helpers
    @Inject private MouseService mouse;                 // core input (red-click)
    @Inject private HeistCoreConfig coreCfg;            // shared core timings
    @Inject private InventoryService inventory;         // NEW: inventory operations

    // =========================================================================
    // === State & Session Fields =============================================
    // =========================================================================
    private State currentState;
    private long stateTimeout = 0L;
    private long nextActionTime = 0L;
    private WorldPoint startLocation;

    private GameObject currentTarget;
    private final Map<WorldPoint, Long> blacklist = new HashMap<>();
    private static final long BLACKLIST_DURATION_MS = 10_000;

    // =========================================================================
    // === Static Lookups / Constants =========================================
    // =========================================================================
    // Axe animation lookup
    private static final Map<Integer, Integer> AXE_ANIMATION_MAP = new HashMap<>();
    static
    {
        AXE_ANIMATION_MAP.put(net.runelite.api.ItemID.BRONZE_AXE,   AnimationID.WOODCUTTING_BRONZE);
        AXE_ANIMATION_MAP.put(net.runelite.api.ItemID.IRON_AXE,     AnimationID.WOODCUTTING_IRON);
        AXE_ANIMATION_MAP.put(net.runelite.api.ItemID.STEEL_AXE,    AnimationID.WOODCUTTING_STEEL);
        AXE_ANIMATION_MAP.put(net.runelite.api.ItemID.MITHRIL_AXE,  AnimationID.WOODCUTTING_MITHRIL);
        AXE_ANIMATION_MAP.put(net.runelite.api.ItemID.ADAMANT_AXE,  AnimationID.WOODCUTTING_ADAMANT);
        AXE_ANIMATION_MAP.put(net.runelite.api.ItemID.RUNE_AXE,     AnimationID.WOODCUTTING_RUNE);
    }

    // Firemaking bits (use raw ids to avoid RL constant mismatches)
    private static final int TINDERBOX_ID = net.runelite.api.ItemID.TINDERBOX;
    private static final int FIRE_OBJECT_ID = 26185; // was ObjectID.FIRE_26185
    private static final int FIREMAKING_ANIMATION_ID = AnimationID.FIREMAKING;

    // =========================================================================
    // === Enum: High-level State Machine =====================================
    // =========================================================================
    private enum State
    {
        STARTING, IDLE, FINDING_TREE, WALKING_TO_TREE, VERIFYING_CHOP_START, CHOPPING,
        HANDLING_INVENTORY, DROPPING_LOGS, BURNING_LOGS, VERIFYING_FIREMAKING_START,
        WAITING_FOR_FIRE_TO_BURN, MOVING_TO_SAFE_TILE, RECOVERING
    }

    // =========================================================================
    // === Lifecycle: startUp / shutDown ======================================
    // =========================================================================
    @Override
    protected void startUp()
    {
        // --- Overlay on & clear ----------------------------------------------
        overlayManager.add(heatmapOverlay);
        heatmapOverlay.clearPoints();

        // --- Wire heatmap to ACTUAL click points from MouseServiceImpl --------
        if (mouse instanceof MouseServiceImpl)
        {
            MouseServiceImpl impl = (MouseServiceImpl) mouse;
            impl.setClickTapListener(p ->
                    heatmapOverlay.addPoint(new net.runelite.api.Point(p.x, p.y))
            );
        }

        // --- Boot state --------------------------------------------------------
        if (client.getGameState() == GameState.LOGGED_IN)
        {
            initializePluginState();
        }
        else
        {
            currentState = State.STARTING;
        }
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(heatmapOverlay);
        log.info("Heist Woodcutter stopped.");
    }

    // =========================================================================
    // === DI Providers (Guice) ================================================
    // =========================================================================
    @Provides
    WoodcutterConfig provideConfig(ConfigManager cm) { return cm.getConfig(WoodcutterConfig.class); }

    @Provides
    HeistCoreConfig provideHeistCoreConfig(ConfigManager cm) { return cm.getConfig(HeistCoreConfig.class); }

    // =========================================================================
    // === Tick Loop & State Runner ===========================================
    // =========================================================================
    @Subscribe
    public void onGameTick(GameTick event)
    {
        final Player local = client.getLocalPlayer();
        if (client.getGameState() != GameState.LOGGED_IN || local == null || isWaiting())
        {
            return;
        }

        if (currentState == State.STARTING)
        {
            initializePluginState();
            return;
        }

        // --- Guard: has axe ----------------------------------------------------
        if (getEquippedAxeId() == -1)
        {
            log.error("No axe equipped. Stopping.");
            setState(State.IDLE);
            return;
        }

        // --- Only enter handling when NOT already handling --------------------
        if (isInventoryFull() && isNotHandlingInventory())
        {
            log.info("Inventory is full. Switching to HANDLE_INVENTORY state.");
            setState(State.HANDLING_INVENTORY);
        }

        runStateMachine();
    }

    private void runStateMachine()
    {
        switch (currentState)
        {
            case FINDING_TREE:               handleFindingTreeState(); break;
            case WALKING_TO_TREE:            handleWalkingState(); break;
            case VERIFYING_CHOP_START:       handleVerifyChopStartState(); break;
            case CHOPPING:                   handleChoppingState(); break;
            case HANDLING_INVENTORY:         handleInventoryState(); break;
            case DROPPING_LOGS:              handleDroppingState(); break;
            case BURNING_LOGS:               handleBurningState(); break;
            case VERIFYING_FIREMAKING_START: handleVerifyFiremakingState(); break;
            case WAITING_FOR_FIRE_TO_BURN:   handleWaitingForFireState(); break;
            case MOVING_TO_SAFE_TILE:        handleMovingToSafeTileState(); break;
            case RECOVERING:                 handleRecoveryState(); break;
            case IDLE:
            case STARTING:
            default: break;
        }
    }

    // =========================================================================
    // === State Handlers ======================================================
    // =========================================================================

    // --- FINDING_TREE --------------------------------------------------------
    private void handleFindingTreeState()
    {
        if (isPlayerBusy()) return;

        currentTarget = findNearestTreeByIteration();
        if (currentTarget != null)
        {
            log.info("Found tree at {}. Interacting.", currentTarget.getWorldLocation());
            clickShapeHuman(currentTarget.getConvexHull(), false);
            setState(State.VERIFYING_CHOP_START, 5000);
        }
        else
        {
            log.info("No visible trees found. Recovering.");
            setState(State.RECOVERING);
        }
    }

    // --- WALKING_TO_TREE -----------------------------------------------------
    private void handleWalkingState()
    {
        if (client.getLocalPlayer().getAnimation() == getEquippedAxeAnimation())
        {
            log.info("Started chopping while walking.");
            setState(State.CHOPPING);
            return;
        }

        if (!isPlayerMoving() || isInteractionTimeout())
        {
            log.warn("Failed to reach or interact with tree. Re-evaluating.");
            blacklist(currentTarget != null ? currentTarget.getWorldLocation() : null);
            setState(State.FINDING_TREE);
        }
    }

    // --- VERIFYING_CHOP_START ------------------------------------------------
    private void handleVerifyChopStartState()
    {
        if (client.getLocalPlayer().getAnimation() == getEquippedAxeAnimation())
        {
            log.info("Successfully started chopping.");
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
            log.warn("Interaction timed out. Blacklisting target and finding a new tree.");
            blacklist(currentTarget != null ? currentTarget.getWorldLocation() : null);
            setState(State.FINDING_TREE);
        }
    }

    // --- CHOPPING ------------------------------------------------------------
    private void handleChoppingState()
    {
        if (client.getLocalPlayer().getAnimation() != getEquippedAxeAnimation())
        {
            log.info("Chopping animation stopped. Assuming tree is gone.");
            if (currentTarget != null) blacklist(currentTarget.getWorldLocation());
            setState(State.FINDING_TREE);
        }
    }

    // --- HANDLING_INVENTORY (uses InventoryService) --------------------------
    private void handleInventoryState()
    {
        if (!inventory.isOpen())
        {
            log.info("Opening inventory (InventoryService.open).");
            inventory.open();
            setWait(coreCfg.reactionMeanMs(), coreCfg.reactionMeanMs() + 600);
            return;
        }

        if (config.logHandling() == WoodcutterConfig.LogHandling.DROP)
        {
            setState(State.DROPPING_LOGS);
        }
        else
        {
            setState(State.BURNING_LOGS);
        }
    }

    // --- DROPPING_LOGS (Natural: drop one per tick via InventoryService) -----
    private void handleDroppingState()
    {
        boolean dropped = inventory.dropOne(getLogIds());
        if (dropped)
        {
            setWait(coreCfg.reactionMeanMs(), coreCfg.reactionMeanMs() + 120);
            return;
        }

        log.info("No logs remain. Done dropping.");
        setState(State.FINDING_TREE);
    }

    // --- BURNING_LOGS --------------------------------------------------------
    private void handleBurningState()
    {
        if (isStandingOnFireByIteration(client.getLocalPlayer().getWorldLocation()))
        {
            log.info("Standing on fire. Moving to a safe tile.");
            setState(State.MOVING_TO_SAFE_TILE);
            return;
        }

        Widget tinderbox = getInventoryItem(TINDERBOX_ID);
        Widget logToBurn = getFirstLogFromInventory();

        if (tinderbox != null && logToBurn != null)
        {
            log.info("Attempting to burn a {}", getLogName());

            clickRectHuman(tinderbox.getBounds(), false);
            setWait(coreCfg.reactionMeanMs(), coreCfg.reactionMeanMs() + 100);

            clickRectHuman(logToBurn.getBounds(), false);
            setState(State.VERIFYING_FIREMAKING_START, 3000);
        }
        else
        {
            log.info("No more logs to burn.");
            setState(State.FINDING_TREE);
        }
    }

    // --- MOVING_TO_SAFE_TILE -------------------------------------------------
    private void handleMovingToSafeTileState()
    {
        if (!isPlayerMoving())
        {
            WorldPoint safeTile = findSafeNearbyTile(startLocation);
            if (safeTile != null)
            {
                log.info("Moving to safe tile: {}", safeTile);
                clickOnMinimap(safeTile);
                setWait(2000, 4000);
            }
            else
            {
                log.error("No safe tile found! Stopping.");
                setState(State.IDLE);
            }
        }
        else if (!isStandingOnFireByIteration(client.getLocalPlayer().getWorldLocation()))
        {
            setState(State.BURNING_LOGS);
        }
    }

    // --- VERIFYING_FIREMAKING_START -----------------------------------------
    private void handleVerifyFiremakingState()
    {
        if (client.getLocalPlayer().getAnimation() == FIREMAKING_ANIMATION_ID)
        {
            log.info("Successfully started firemaking.");
            setState(State.WAITING_FOR_FIRE_TO_BURN);
        }
        else if (isInteractionTimeout())
        {
            log.warn("Firemaking did not start. Retrying.");
            setState(State.BURNING_LOGS);
        }
    }

    // --- WAITING_FOR_FIRE_TO_BURN -------------------------------------------
    private void handleWaitingForFireState()
    {
        if (client.getLocalPlayer().getAnimation() != FIREMAKING_ANIMATION_ID)
        {
            log.info("Firemaking animation finished.");
            setWait(coreCfg.reactionMeanMs(), coreCfg.reactionMeanMs() * 2);
            setState(State.BURNING_LOGS);
        }
    }

    // --- RECOVERING ----------------------------------------------------------
    private void handleRecoveryState()
    {
        if (startLocation != null
                && client.getLocalPlayer().getWorldLocation().distanceTo(startLocation) > 5)
        {
            log.info("Far from start location. Walking back.");
            clickOnMinimap(startLocation);
            setWait(3000, 5000);
        }
        setState(State.FINDING_TREE);
    }

    // =========================================================================
    // === Helpers: Targeting / Scene / Inventory =============================
    // =========================================================================

    // --- Startup init --------------------------------------------------------
    private void initializePluginState()
    {
        final Player local = client.getLocalPlayer();
        if (local != null)
        {
            startLocation = local.getWorldLocation();
            log.info("Heist Woodcutter started. Start location saved: {}", startLocation);
            setState(State.FINDING_TREE);
        }
        else
        {
            setState(State.STARTING);
        }
    }

    // --- Find nearest tree by scene scan ------------------------------------
    private GameObject findNearestTreeByIteration()
    {
        cleanBlacklist();
        final Player local = client.getLocalPlayer();
        if (local == null) return null;

        List<GameObject> valid = new ArrayList<>();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();

        for (int z = 0; z < Constants.MAX_Z; z++)
        {
            for (int x = 0; x < Constants.SCENE_SIZE; x++)
            {
                for (int y = 0; y < Constants.SCENE_SIZE; y++)
                {
                    Tile tile = tiles[z][x][y];
                    if (tile == null) continue;

                    for (GameObject obj : tile.getGameObjects())
                    {
                        if (obj != null
                                && obj.getId() == config.treeType().getTreeId()
                                && obj.getConvexHull() != null
                                && !blacklist.containsKey(obj.getWorldLocation()))
                        {
                            valid.add(obj);
                        }
                    }
                }
            }
        }

        return valid.stream()
                .min(Comparator.comparingInt(o -> o.getWorldLocation().distanceTo(local.getWorldLocation())))
                .orElse(null);
    }

    // --- Fire detection on current tile -------------------------------------
    private boolean isStandingOnFireByIteration(WorldPoint loc)
    {
        LocalPoint lp = LocalPoint.fromWorld(client, loc);
        if (lp == null) return false;

        Tile tile = client.getScene().getTiles()[loc.getPlane()][lp.getSceneX()][lp.getSceneY()];
        if (tile == null) return false;

        return Arrays.stream(tile.getGameObjects())
                .anyMatch(o -> o != null && o.getId() == FIRE_OBJECT_ID);
    }

    // --- Find nearby safe tile ----------------------------------------------
    private WorldPoint findSafeNearbyTile(WorldPoint center)
    {
        Player local = client.getLocalPlayer();
        if (center == null || local == null) return null;

        CollisionData[] cols = client.getCollisionMaps();
        if (cols == null || cols.length <= client.getPlane()) return null;

        for (int r = 1; r <= 5; r++)
        {
            for (int dx = -r; dx <= r; dx++)
            {
                for (int dy = -r; dy <= r; dy++)
                {
                    if (dx == 0 && dy == 0) continue;

                    WorldPoint wp = center.dx(dx).dy(dy);
                    LocalPoint lp = LocalPoint.fromWorld(client, wp);
                    if (lp != null)
                    {
                        int flags = cols[client.getPlane()].getFlags()[lp.getSceneX()][lp.getSceneY()];
                        boolean blocked = (flags & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0;
                        if (!blocked && !isStandingOnFireByIteration(wp)) return wp;
                    }
                }
            }
        }
        return null;
    }

    // --- Legacy direct widget reads (kept for firemaking path) ---------------
    private boolean isInventoryFull()
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        return inv != null && inv.count() >= 28;
    }

    private boolean isInventoryOpen()
    {
        return client.getWidget(WidgetInfo.INVENTORY) != null;
    }

    private Widget getFirstLogFromInventory()
    {
        return getInventoryItem(getLogIds());
    }

    private Widget getInventoryItem(List<Integer> ids)
    {
        Widget inv = client.getWidget(WidgetInfo.INVENTORY);
        if (inv == null) return null;

        return Arrays.stream(inv.getDynamicChildren())
                .filter(it -> ids.contains(it.getItemId()))
                .findFirst()
                .orElse(null);
    }

    private Widget getInventoryItem(int id)
    {
        return getInventoryItem(Collections.singletonList(id));
    }

    private List<Integer> getLogIds()
    {
        return Arrays.asList(
                net.runelite.api.ItemID.LOGS,
                net.runelite.api.ItemID.OAK_LOGS,
                net.runelite.api.ItemID.WILLOW_LOGS,
                net.runelite.api.ItemID.MAPLE_LOGS
        );
    }

    private String getLogName()
    {
        return config.treeType() == WoodcutterConfig.TreeType.OAK ? "Oak log" : "log";
    }

    // =========================================================================
    // === Helpers: State / Wait / Blacklist / Input ==========================
    // =========================================================================

    // --- Inventory state guard (prevents re-entering while handling) ----------
    private boolean isNotHandlingInventory()
    {
        return currentState != State.HANDLING_INVENTORY
                && currentState != State.DROPPING_LOGS
                && currentState != State.BURNING_LOGS
                && currentState != State.VERIFYING_FIREMAKING_START
                && currentState != State.WAITING_FOR_FIRE_TO_BURN
                && currentState != State.MOVING_TO_SAFE_TILE;
    }

    private void setState(State newState, long timeoutMs)
    {
        if (currentState != newState)
        {
            log.info("State: {} -> {}", currentState, newState);
            currentState = newState;
            stateTimeout = timeoutMs > 0 ? (System.currentTimeMillis() + timeoutMs) : 0;
        }
    }

    private void setState(State newState) { setState(newState, 0); }

    private boolean isInteractionTimeout() { return stateTimeout != 0 && System.currentTimeMillis() > stateTimeout; }

    private void setWait(int minMs, int maxMs)
    {
        nextActionTime = System.currentTimeMillis() + humanizer.randomDelay(minMs, maxMs);
    }

    private boolean isWaiting() { return System.currentTimeMillis() < nextActionTime; }

    private void blacklist(WorldPoint point)
    {
        if (point == null) return;
        log.warn("Blacklisting tile: {}", point);
        blacklist.put(point, System.currentTimeMillis() + BLACKLIST_DURATION_MS);
    }

    private void cleanBlacklist()
    {
        long now = System.currentTimeMillis();
        blacklist.entrySet().removeIf(e -> now > e.getValue());
    }

    // --- Minimap click (uses core mouse) ------------------------------------
    private void clickOnMinimap(WorldPoint target)
    {
        LocalPoint lp = LocalPoint.fromWorld(client, target);
        if (lp == null) return;

        net.runelite.api.Point p = Perspective.localToMinimap(client, lp);
        if (p != null) mouse.humanClick(new Rectangle(p.getX(), p.getY(), 1, 1), false);
    }

    // --- Input helpers (clicks & keys) --------------------------------------
    private void clickShapeHuman(Shape shape, boolean shift)
    {
        if (shape == null) return;
        mouse.humanClick(shape, shift);   // heatmap is fed by MouseServiceImpl hook
    }

    private void clickRectHuman(Rectangle rect, boolean shift)
    {
        if (rect == null) return;
        mouse.humanClick(rect, shift);    // heatmap is fed by MouseServiceImpl hook
    }

    private void dispatchKey(int keyCode)
    {
        Canvas canvas = client.getCanvas();
        if (canvas == null) return;

        try
        {
            KeyEvent press = new KeyEvent(canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, keyCode, KeyEvent.CHAR_UNDEFINED);
            canvas.dispatchEvent(press);
            humanizer.sleep(45, 95);
            KeyEvent release = new KeyEvent(canvas, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, keyCode, KeyEvent.CHAR_UNDEFINED);
            canvas.dispatchEvent(release);
        }
        catch (Exception ignored) { /* don't re-interrupt */ }
    }

    // --- Player / Axe helpers ------------------------------------------------
    private int getEquippedAxeId()
    {
        ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
        if (eq == null) return -1;
        Item weapon = eq.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
        return weapon != null ? weapon.getId() : -1;
    }

    private int getEquippedAxeAnimation()
    {
        return AXE_ANIMATION_MAP.getOrDefault(getEquippedAxeId(), -1);
    }

    private boolean isPlayerMoving()
    {
        Player local = client.getLocalPlayer();
        return local != null && local.getPoseAnimation() != local.getIdlePoseAnimation();
    }

    private boolean isPlayerBusy()
    {
        Player local = client.getLocalPlayer();
        if (local == null) return true; // safe default until logged in
        return local.getAnimation() != -1 || isPlayerMoving() || local.getInteracting() != null;
    }
}
