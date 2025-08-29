package ht.heist.plugins.woodcutter;

import com.google.inject.Provides;
import ht.heist.core.services.HumanizerService;
import ht.heist.core.services.MouseService;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
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

import javax.inject.Inject;
import java.awt.Canvas;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.*;

@Slf4j
@PluginDescriptor(
        name = "Heist Woodcutter",
        description = "A sophisticated woodcutting plugin using AWT Event Dispatching.",
        tags = {"woodcutting", "automation", "educational", "heist"}
)
public class WoodcutterPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private WoodcutterConfig config;
    @Inject private OverlayManager overlayManager;
    @Inject private HeatmapOverlay heatmapOverlay;

    // NEW: injected core services
    @Inject private HumanizerService humanizer;
    @Inject private MouseService mouse;

    private State currentState;
    private long stateTimeout = 0;
    private long nextActionTime = 0;
    private WorldPoint startLocation;

    private GameObject currentTarget;
    private final Map<WorldPoint, Long> blacklist = new HashMap<>();
    private static final long BLACKLIST_DURATION_MS = 10_000;

    private static final Map<Integer, Integer> AXE_ANIMATION_MAP = new HashMap<>();
    static
    {
        AXE_ANIMATION_MAP.put(ItemID.BRONZE_AXE, AnimationID.WOODCUTTING_BRONZE);
        AXE_ANIMATION_MAP.put(ItemID.IRON_AXE, AnimationID.WOODCUTTING_IRON);
        AXE_ANIMATION_MAP.put(ItemID.STEEL_AXE, AnimationID.WOODCUTTING_STEEL);
        AXE_ANIMATION_MAP.put(ItemID.MITHRIL_AXE, AnimationID.WOODCUTTING_MITHRIL);
        AXE_ANIMATION_MAP.put(ItemID.ADAMANT_AXE, AnimationID.WOODCUTTING_ADAMANT);
        AXE_ANIMATION_MAP.put(ItemID.RUNE_AXE, AnimationID.WOODCUTTING_RUNE);
    }

    private static final int TINDERBOX_ID = ItemID.TINDERBOX;
    private static final int FIRE_OBJECT_ID = ObjectID.FIRE_26185;
    private static final int FIREMAKING_ANIMATION_ID = AnimationID.FIREMAKING;

    private enum State
    {
        STARTING, IDLE, FINDING_TREE, WALKING_TO_TREE, VERIFYING_CHOP_START, CHOPPING,
        HANDLING_INVENTORY, DROPPING_LOGS, BURNING_LOGS, VERIFYING_FIREMAKING_START,
        WAITING_FOR_FIRE_TO_BURN, MOVING_TO_SAFE_TILE, RECOVERING
    }

    // ---------------- Lifecycle ----------------

    @Override
    protected void startUp()
    {
        overlayManager.add(heatmapOverlay);
        heatmapOverlay.clearPoints();

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

    @Provides
    WoodcutterConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(WoodcutterConfig.class);
    }

    // ---------------- Tick Loop ----------------

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

        // fatigue handled inside your HumanizerService (via sleep calls where needed)
        if (getEquippedAxeId() == -1)
        {
            log.error("No axe equipped. Stopping.");
            setState(State.IDLE);
            return;
        }

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
            case FINDING_TREE:                 handleFindingTreeState(); break;
            case WALKING_TO_TREE:              handleWalkingState(); break;
            case VERIFYING_CHOP_START:         handleVerifyChopStartState(); break;
            case CHOPPING:                     handleChoppingState(); break;
            case HANDLING_INVENTORY:           handleInventoryState(); break;
            case DROPPING_LOGS:                handleDroppingState(); break;
            case BURNING_LOGS:                 handleBurningState(); break;
            case VERIFYING_FIREMAKING_START:   handleVerifyFiremakingState(); break;
            case WAITING_FOR_FIRE_TO_BURN:     handleWaitingForFireState(); break;
            case MOVING_TO_SAFE_TILE:          handleMovingToSafeTileState(); break;
            case RECOVERING:                   handleRecoveryState(); break;
            case IDLE:
            case STARTING:
            default: break;
        }
    }

    // ---------------- States ----------------

    private void handleFindingTreeState()
    {
        if (isPlayerBusy())
        {
            return;
        }

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

    private void handleChoppingState()
    {
        if (client.getLocalPlayer().getAnimation() != getEquippedAxeAnimation())
        {
            log.info("Chopping animation stopped. Assuming tree is gone.");
            if (currentTarget != null)
            {
                blacklist(currentTarget.getWorldLocation());
            }
            setState(State.FINDING_TREE);
        }
    }

    private void handleInventoryState()
    {
        if (!isInventoryOpen())
        {
            log.info("Opening inventory tab (ESC).");
            dispatchKey(KeyEvent.VK_ESCAPE);
            setWait(config.actionMode().getDefaultWaitMean(), config.actionMode().getDefaultWaitMean() + 600);
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

    private void handleDroppingState()
    {
        Widget logToDrop = getFirstLogFromInventory();
        if (logToDrop != null)
        {
            log.info("Dropping log: {}", getLogName());
            clickRectHuman(logToDrop.getBounds(), true);
            setWait(config.actionMode().getDefaultWaitMean(),
                    config.actionMode().getDefaultWaitMean() + 100);
        }
        else
        {
            log.info("Finished dropping all logs.");
            setState(State.FINDING_TREE);
        }
    }

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
            setWait(config.actionMode().getReactionTimeMean(),
                    config.actionMode().getReactionTimeMean() + 100);
            clickRectHuman(logToBurn.getBounds(), false);
            setState(State.VERIFYING_FIREMAKING_START, 3000);
        }
        else
        {
            log.info("No more logs to burn.");
            setState(State.FINDING_TREE);
        }
    }

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

    private void handleWaitingForFireState()
    {
        if (client.getLocalPlayer().getAnimation() != FIREMAKING_ANIMATION_ID)
        {
            log.info("Firemaking animation finished.");
            setWait(config.actionMode().getDefaultWaitMean(),
                    config.actionMode().getDefaultWaitMean() * 2);
            setState(State.BURNING_LOGS);
        }
    }

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

    // ---------------- Helpers ----------------

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

    private boolean isStandingOnFireByIteration(WorldPoint loc)
    {
        LocalPoint lp = LocalPoint.fromWorld(client, loc);
        if (lp == null) return false;

        Tile tile = client.getScene().getTiles()[loc.getPlane()][lp.getSceneX()][lp.getSceneY()];
        if (tile == null) return false;

        return Arrays.stream(tile.getGameObjects())
                .anyMatch(o -> o != null && o.getId() == FIRE_OBJECT_ID);
    }

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
                        if (!blocked && !isStandingOnFireByIteration(wp))
                        {
                            return wp;
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean isNotHandlingInventory()
    {
        return currentState != State.HANDLING_INVENTORY
                && currentState != State.DROPPING_LOGS
                && currentState != State.BURNING_LOGS
                && currentState != State.VERIFYING_FIREMAKING_START
                && currentState != State.WAITING_FOR_FIRE_TO_BURN
                && currentState != State.MOVING_TO_SAFE_TILE;
    }

    private void clickOnMinimap(WorldPoint target)
    {
        LocalPoint lp = LocalPoint.fromWorld(client, target);
        if (lp == null) return;

        net.runelite.api.Point p = Perspective.localToMinimap(client, lp);
        if (p != null)
        {
            // draw heatmap
            if (config.drawHeatmap())
            {
                heatmapOverlay.addPoint(new net.runelite.api.Point(p.getX(), p.getY()));
            }
            // human click
            mouse.humanClick(new Rectangle(p.getX(), p.getY(), 1, 1), false);
        }
    }

    private boolean isInventoryFull()
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        return inv != null && inv.count() >= 28;
    }

    private boolean isInventoryOpen()
    {
        return client.getWidget(WidgetInfo.INVENTORY) != null;
    }

    private boolean isPlayerBusy()
    {
        Player local = client.getLocalPlayer();
        return local.getAnimation() != -1 || isPlayerMoving() || local.getInteracting() != null;
    }

    private boolean isPlayerMoving()
    {
        Player local = client.getLocalPlayer();
        return local.getPoseAnimation() != local.getIdlePoseAnimation();
    }

    private int getEquippedAxeAnimation()
    {
        return AXE_ANIMATION_MAP.getOrDefault(getEquippedAxeId(), -1);
    }

    private int getEquippedAxeId()
    {
        ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
        if (eq == null) return -1;
        Item weapon = eq.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
        return weapon != null ? weapon.getId() : -1;
    }

    private List<Integer> getLogIds()
    {
        return Arrays.asList(ItemID.LOGS, ItemID.OAK_LOGS, ItemID.WILLOW_LOGS, ItemID.MAPLE_LOGS);
    }

    private String getLogName()
    {
        return config.treeType() == WoodcutterConfig.TreeType.OAK ? "Oak log" : "log";
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

    private void setState(State newState, long timeoutMs)
    {
        if (currentState != newState)
        {
            log.info("State: {} -> {}", currentState, newState);
            currentState = newState;
            stateTimeout = timeoutMs > 0 ? (System.currentTimeMillis() + timeoutMs) : 0;
        }
    }

    private void setState(State newState)
    {
        setState(newState, 0);
    }

    private boolean isInteractionTimeout()
    {
        return stateTimeout != 0 && System.currentTimeMillis() > stateTimeout;
    }

    private void setWait(int minMs, int maxMs)
    {
        nextActionTime = System.currentTimeMillis() + humanizer.randomDelay(minMs, maxMs);
    }

    private boolean isWaiting()
    {
        return System.currentTimeMillis() < nextActionTime;
    }

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

    // centralize “human click + heatmap”
    private void clickShapeHuman(Shape shape, boolean shift)
    {
        if (shape == null) return;

        if (config.drawHeatmap())
        {
            Rectangle b = shape.getBounds();
            int cx = (int) Math.round(b.getCenterX());
            int cy = (int) Math.round(b.getCenterY());
            heatmapOverlay.addPoint(new net.runelite.api.Point(cx, cy));
        }
        mouse.humanClick(shape, shift);
    }

    private void clickRectHuman(Rectangle rect, boolean shift)
    {
        if (rect == null) return;

        if (config.drawHeatmap())
        {
            int cx = (int) Math.round(rect.getCenterX());
            int cy = (int) Math.round(rect.getCenterY());
            heatmapOverlay.addPoint(new net.runelite.api.Point(cx, cy));
        }
        mouse.humanClick(rect, shift);
    }

    // simple key dispatch (ESC for inventory)
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
        catch (Exception ignored)
        {
            Thread.currentThread().interrupt();
        }
    }
}
