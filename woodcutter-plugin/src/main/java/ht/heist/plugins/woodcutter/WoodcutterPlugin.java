package ht.heist;

import com.google.inject.Provides;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@Slf4j
@PluginDescriptor(
        name = "Heist Woodcutter",
        description = "A sophisticated woodcutting plugin using AWT Event Dispatching.",
        tags = {"woodcutting", "automation", "educational", "heist"}
)
public class WoodcutterPlugin extends Plugin {

    @Inject private Client client;
    @Inject private WoodcutterConfig config;
    @Inject private OverlayManager overlayManager;
    @Inject private HeatmapOverlay heatmapOverlay;
    private HumanBehavior human;
    private ExecutorService executor;

    private State currentState;
    private long stateTimeout = 0;
    private long nextActionTime = 0;
    private WorldPoint startLocation;

    private GameObject currentTarget;
    private final Map<WorldPoint, Long> blacklist = new HashMap<>();
    private static final long BLACKLIST_DURATION_MS = 10000;

    // Use explicit class names to resolve ambiguity
    private static final Map<Integer, Integer> AXE_ANIMATION_MAP = new HashMap<>();
    static {
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

    private enum State {
        STARTING, IDLE, FINDING_TREE, WALKING_TO_TREE, VERIFYING_CHOP_START, CHOPPING,
        HANDLING_INVENTORY, DROPPING_LOGS, BURNING_LOGS, VERIFYING_FIREMAKING_START,
        WAITING_FOR_FIRE_TO_BURN, MOVING_TO_SAFE_TILE, RECOVERING
    }

    @Override
    protected void startUp() {
        executor = Executors.newSingleThreadExecutor();
        human = new HumanBehavior(client, config, executor, heatmapOverlay);
        overlayManager.add(heatmapOverlay);
        heatmapOverlay.clearPoints();
        if (client.getGameState() == GameState.LOGGED_IN) {
            initializePluginState();
        }
    }

    private void initializePluginState() {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer != null) {
            startLocation = localPlayer.getWorldLocation();
            log.info("Heist Woodcutter started. Start location saved: {}", startLocation);
            setState(State.FINDING_TREE);
        } else {
            setState(State.STARTING);
        }
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(heatmapOverlay);
        executor.shutdownNow();
        log.info("Heist Woodcutter stopped.");
    }

    @Provides
    WoodcutterConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(WoodcutterConfig.class);
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        Player localPlayer = client.getLocalPlayer();
        if (client.getGameState() != GameState.LOGGED_IN || localPlayer == null || isWaiting()) return;
        if (currentState == State.STARTING) {
            initializePluginState();
            return;
        }
        human.updateFatigue();
        if (getEquippedAxeId() == -1) {
            log.error("No axe equipped. Stopping.");
            setState(State.IDLE);
            return;
        }
        if (isInventoryFull() && isNotHandlingInventory()) {
            log.info("Inventory is full. Switching to HANDLE_INVENTORY state.");
            setState(State.HANDLING_INVENTORY);
        }
        runStateMachine();
    }

    private void runStateMachine() {
        switch (currentState) {
            case FINDING_TREE:          handleFindingTreeState(); break;
            case WALKING_TO_TREE:       handleWalkingState(); break;
            case VERIFYING_CHOP_START:  handleVerifyChopStartState(); break;
            case CHOPPING:              handleChoppingState(); break;
            case HANDLING_INVENTORY:    handleInventoryState(); break;
            case DROPPING_LOGS:         handleDroppingState(); break;
            case BURNING_LOGS:          handleBurningState(); break;
            case VERIFYING_FIREMAKING_START: handleVerifyFiremakingState(); break;
            case WAITING_FOR_FIRE_TO_BURN: handleWaitingForFireState(); break;
            case MOVING_TO_SAFE_TILE:   handleMovingToSafeTileState(); break;
            case RECOVERING:            handleRecoveryState(); break;
        }
    }

    private void handleFindingTreeState() {
        if (isPlayerBusy()) return;
        currentTarget = findNearestTreeByIteration();
        if (currentTarget != null) {
            log.info("Found tree at {}. Interacting.", currentTarget.getWorldLocation());
            human.dispatchHumanLikeClick(currentTarget.getConvexHull(), false);
            setState(State.VERIFYING_CHOP_START, 5000);
        } else {
            log.info("No visible trees found. Recovering.");
            setState(State.RECOVERING);
        }
    }

    private void handleWalkingState() {
        if (client.getLocalPlayer().getAnimation() == getEquippedAxeAnimation()) {
            log.info("Started chopping while walking.");
            setState(State.CHOPPING);
            return;
        }
        if (!isPlayerMoving() || isInteractionTimeout()) {
            log.warn("Failed to reach or interact with tree. Re-evaluating.");
            blacklist(currentTarget.getWorldLocation());
            setState(State.FINDING_TREE);
        }
    }

    private void handleVerifyChopStartState() {
        if (client.getLocalPlayer().getAnimation() == getEquippedAxeAnimation()) {
            log.info("Successfully started chopping.");
            setState(State.CHOPPING);
            return;
        }
        if(isPlayerMoving()) {
            setState(State.WALKING_TO_TREE, 8000);
            return;
        }
        if (isInteractionTimeout()) {
            log.warn("Interaction timed out. Blacklisting target and finding a new tree.");
            blacklist(currentTarget.getWorldLocation());
            setState(State.FINDING_TREE);
        }
    }

    private void handleChoppingState() {
        if (client.getLocalPlayer().getAnimation() != getEquippedAxeAnimation()) {
            log.info("Chopping animation stopped. Assuming tree is gone.");
            if (currentTarget != null) blacklist(currentTarget.getWorldLocation());
            setState(State.FINDING_TREE);
        }
    }

    private void handleInventoryState() {
        if (!isInventoryOpen()) {
            log.info("Opening inventory tab.");
            human.dispatchKeyEvent(KeyEvent.VK_ESCAPE);
            setWait(600, 1200);
            return;
        }
        if (config.logHandling() == WoodcutterConfig.LogHandling.DROP) setState(State.DROPPING_LOGS);
        else setState(State.BURNING_LOGS);
    }

    private void handleDroppingState() {
        Widget logToDrop = getFirstLogFromInventory();
        if (logToDrop != null) {
            log.info("Dropping log: {}", getLogName());
            human.dispatchHumanLikeClick(logToDrop.getBounds(), true);
            setWait(config.actionMode().getDefaultWaitMean(), config.actionMode().getDefaultWaitMean() + 100);
        } else {
            log.info("Finished dropping all logs.");
            setState(State.FINDING_TREE);
        }
    }

    private void handleBurningState() {
        if (isStandingOnFireByIteration(client.getLocalPlayer().getWorldLocation())) {
            log.info("Standing on fire. Moving to a safe tile.");
            setState(State.MOVING_TO_SAFE_TILE);
            return;
        }
        Widget tinderbox = getInventoryItem(TINDERBOX_ID);
        Widget logToBurn = getFirstLogFromInventory();
        if (tinderbox != null && logToBurn != null) {
            log.info("Attempting to burn a {}", getLogName());
            human.dispatchHumanLikeClick(tinderbox.getBounds(), false);
            setWait(config.actionMode().getReactionTimeMean(), config.actionMode().getReactionTimeMean() + 100);
            human.dispatchHumanLikeClick(logToBurn.getBounds(), false);
            setState(State.VERIFYING_FIREMAKING_START, 3000);
        } else {
            log.info("No more logs to burn.");
            setState(State.FINDING_TREE);
        }
    }

    private void handleMovingToSafeTileState() {
        if (!isPlayerMoving()) {
            WorldPoint safeTile = findSafeNearbyTile(startLocation);
            if (safeTile != null) {
                log.info("Moving to safe tile: {}", safeTile);
                clickOnMinimap(safeTile);
                setWait(2000, 4000);
            } else {
                log.error("No safe tile found! Stopping.");
                setState(State.IDLE);
            }
        } else if (!isStandingOnFireByIteration(client.getLocalPlayer().getWorldLocation())) {
            setState(State.BURNING_LOGS);
        }
    }

    private void handleVerifyFiremakingState() {
        if (client.getLocalPlayer().getAnimation() == FIREMAKING_ANIMATION_ID) {
            log.info("Successfully started firemaking.");
            setState(State.WAITING_FOR_FIRE_TO_BURN);
        } else if (isInteractionTimeout()) {
            log.warn("Firemaking did not start. Retrying.");
            setState(State.BURNING_LOGS);
        }
    }

    private void handleWaitingForFireState() {
        if (client.getLocalPlayer().getAnimation() != FIREMAKING_ANIMATION_ID) {
            log.info("Firemaking animation finished.");
            setWait(config.actionMode().getDefaultWaitMean(), config.actionMode().getDefaultWaitMean() * 2);
            setState(State.BURNING_LOGS);
        }
    }

    private void handleRecoveryState() {
        if (startLocation != null && client.getLocalPlayer().getWorldLocation().distanceTo(startLocation) > 5) {
            log.info("Far from start location. Walking back.");
            clickOnMinimap(startLocation);
            setWait(3000, 5000);
        }
        setState(State.FINDING_TREE);
    }

    private GameObject findNearestTreeByIteration() {
        cleanBlacklist();
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) return null;

        List<GameObject> validTrees = new ArrayList<>();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();

        for (int z = 0; z < Constants.MAX_Z; z++) {
            for (int x = 0; x < Constants.SCENE_SIZE; x++) {
                for (int y = 0; y < Constants.SCENE_SIZE; y++) {
                    Tile tile = tiles[z][x][y];
                    if (tile == null) continue;
                    for (GameObject gameObject : tile.getGameObjects()) {
                        if (gameObject != null && gameObject.getId() == config.treeType().getTreeId()
                                && gameObject.getConvexHull() != null && !blacklist.containsKey(gameObject.getWorldLocation())) {
                            validTrees.add(gameObject);
                        }
                    }
                }
            }
        }
        return validTrees.stream().min(Comparator.comparingInt(tree -> tree.getWorldLocation().distanceTo(localPlayer.getWorldLocation()))).orElse(null);
    }

    private boolean isStandingOnFireByIteration(WorldPoint location) {
        LocalPoint localPoint = LocalPoint.fromWorld(client, location);
        if (localPoint == null) return false;
        Tile tile = client.getScene().getTiles()[location.getPlane()][localPoint.getSceneX()][localPoint.getSceneY()];
        if (tile == null) return false;
        return Arrays.stream(tile.getGameObjects()).anyMatch(obj -> obj != null && obj.getId() == FIRE_OBJECT_ID);
    }

    private WorldPoint findSafeNearbyTile(WorldPoint center) {
        Player local = client.getLocalPlayer();
        if (center == null || local == null) return null;
        CollisionData[] collisionMaps = client.getCollisionMaps();
        if (collisionMaps == null || collisionMaps.length <= client.getPlane()) return null;

        for (int radius = 1; radius <= 5; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    WorldPoint potentialTile = center.dx(dx).dy(dy);
                    LocalPoint localTile = LocalPoint.fromWorld(client, potentialTile);
                    if (localTile != null &&
                            (collisionMaps[client.getPlane()].getFlags()[localTile.getSceneX()][localTile.getSceneY()] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0 &&
                            !isStandingOnFireByIteration(potentialTile)) {
                        return potentialTile;
                    }
                }
            }
        }
        return null;
    }

    private boolean isNotHandlingInventory() {
        return currentState != State.HANDLING_INVENTORY && currentState != State.DROPPING_LOGS &&
                currentState != State.BURNING_LOGS && currentState != State.VERIFYING_FIREMAKING_START &&
                currentState != State.WAITING_FOR_FIRE_TO_BURN && currentState != State.MOVING_TO_SAFE_TILE;
    }
    private void clickOnMinimap(WorldPoint target) {
        LocalPoint localTarget = LocalPoint.fromWorld(client, target);
        if (localTarget == null) return;
        Point minimapPoint = Perspective.localToMinimap(client, localTarget);
        if (minimapPoint != null) {
            human.dispatchHumanLikeClick(new Rectangle(minimapPoint.getX(), minimapPoint.getY(), 1, 1), false);
        }
    }
    private boolean isInventoryFull() {
        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        return inventory != null && inventory.count() >= 28;
    }
    private boolean isInventoryOpen() {
        return client.getWidget(WidgetInfo.INVENTORY) != null;
    }
    private boolean isPlayerBusy() {
        Player localPlayer = client.getLocalPlayer();
        return localPlayer.getAnimation() != -1 || isPlayerMoving() || localPlayer.getInteracting() != null;
    }
    private boolean isPlayerMoving() {
        return client.getLocalPlayer().getPoseAnimation() != client.getLocalPlayer().getIdlePoseAnimation();
    }
    private int getEquippedAxeAnimation() { return AXE_ANIMATION_MAP.getOrDefault(getEquippedAxeId(), -1); }
    private int getEquippedAxeId() {
        ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
        if (equipment == null) return -1;
        Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
        return (weapon != null) ? weapon.getId() : -1;
    }
    private List<Integer> getLogIds() {
        // Use explicit ItemID class to resolve ambiguity
        return Arrays.asList(ItemID.LOGS, ItemID.OAK_LOGS, ItemID.WILLOW_LOGS, ItemID.MAPLE_LOGS);
    }
    private String getLogName() { return config.treeType() == WoodcutterConfig.TreeType.OAK ? "Oak log" : "log"; }
    private Widget getFirstLogFromInventory() { return getInventoryItem(getLogIds()); }
    private Widget getInventoryItem(List<Integer> ids) {
        Widget inventoryWidget = client.getWidget(WidgetInfo.INVENTORY);
        if (inventoryWidget == null) return null;
        return Arrays.stream(inventoryWidget.getDynamicChildren()).filter(item -> ids.contains(item.getItemId())).findFirst().orElse(null);
    }
    private Widget getInventoryItem(int id) { return getInventoryItem(Collections.singletonList(id)); }
    private void setState(State newState, long timeout) {
        if (currentState != newState) {
            log.info("State: {} -> {}", currentState, newState);
            currentState = newState;
            stateTimeout = System.currentTimeMillis() + timeout;
        }
    }
    private void setState(State newState) { setState(newState, 0); }
    private boolean isInteractionTimeout() { return stateTimeout != 0 && System.currentTimeMillis() > stateTimeout; }
    private void setWait(int min, int max) { nextActionTime = System.currentTimeMillis() + human.getRandomDelay(min, max); }
    private boolean isWaiting() { return System.currentTimeMillis() < nextActionTime; }
    private void blacklist(WorldPoint point) {
        if (point == null) return;
        log.warn("Blacklisting tile: {}", point);
        blacklist.put(point, System.currentTimeMillis() + BLACKLIST_DURATION_MS);
    }
    private void cleanBlacklist() { blacklist.entrySet().removeIf(entry -> System.currentTimeMillis() > entry.getValue()); }

    private static class HumanBehavior {
        private final Client client;
        private final WoodcutterConfig config;
        private final ExecutorService executor;
        private final HeatmapOverlay heatmap;
        private final Random random = new Random();
        private long startTime = System.currentTimeMillis();
        private double fatigueMultiplier = 1.0;

        public HumanBehavior(Client client, WoodcutterConfig config, ExecutorService executor, HeatmapOverlay heatmap) {
            this.client = client;
            this.config = config;
            this.executor = executor;
            this.heatmap = heatmap;
        }

        public void updateFatigue() {
            if (!config.enableFatigue()) {
                fatigueMultiplier = 1.0;
                return;
            }
            long elapsedMinutes = (System.currentTimeMillis() - startTime) / 60000;
            fatigueMultiplier = 1.0 + (elapsedMinutes / 30.0) * 0.025;
        }

        public int getRandomDelay(int min, int max) {
            return (int) (min + (random.nextDouble() * (max - min)));
        }

        public long getGaussianDelay(int mean, int stdDev) {
            return (long) (Math.abs(mean + random.nextGaussian() * stdDev) * fatigueMultiplier);
        }

        private Point generateClickPoint(Shape shape) {
            Rectangle bounds = shape.getBounds();
            double meanX = bounds.getCenterX();
            double meanY = bounds.getCenterY();
            double stdDevX = bounds.getWidth() / 4.0;
            double stdDevY = bounds.getHeight() / 4.0;

            Point p;
            int attempts = 0;
            do {
                double x = random.nextGaussian() * stdDevX + meanX;
                double y = random.nextGaussian() * stdDevY + meanY;
                p = new Point((int) x, (int) y);
                attempts++;
            } while (!shape.contains(p.getX(), p.getY()) && attempts < 10);

            if (attempts >= 10) {
                p = new Point((int) meanX, (int) meanY);
            }
            return p;
        }

        public void dispatchHumanLikeClick(Shape targetShape, boolean shift) {
            if (client.getCanvas() == null || targetShape == null) return;
            executor.submit(() -> {
                try {
                    Point clickPoint = generateClickPoint(targetShape);
                    if (config.drawHeatmap()) {
                        heatmap.addPoint(clickPoint);
                    }
                    if (config.enableOvershoots() && random.nextDouble() < 0.1) {
                        Rectangle bounds = targetShape.getBounds();
                        int dx = clickPoint.getX() > bounds.getCenterX() ? 1 : -1;
                        int dy = clickPoint.getY() > bounds.getCenterY() ? 1 : -1;
                        Point overshootPoint = new Point(
                                clickPoint.getX() + (int)(dx * bounds.getWidth() * 0.3),
                                clickPoint.getY() + (int)(dy * bounds.getHeight() * 0.3)
                        );
                        moveMouse(overshootPoint);
                        Thread.sleep(getGaussianDelay(config.actionMode().getReactionTimeMean() / 2, config.actionMode().getReactionTimeStdDev() / 2));
                    }
                    moveMouse(clickPoint);
                    Thread.sleep(getGaussianDelay(config.actionMode().getReactionTimeMean(), config.actionMode().getReactionTimeStdDev()));
                    performClick(clickPoint, shift);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        private void moveMouse(Point target) throws InterruptedException {
            final Canvas canvas = client.getCanvas();
            MouseEvent move = new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, target.getX(), target.getY(), 0, false);
            canvas.dispatchEvent(move);
            Thread.sleep(getGaussianDelay(config.actionMode().getDefaultWaitMean(), config.actionMode().getDefaultWaitStdDev()));
        }

        private void performClick(Point p, boolean shift) throws InterruptedException {
            final Canvas canvas = client.getCanvas();
            final int x = p.getX();
            final int y = p.getY();
            final int modifiers = shift ? java.awt.event.InputEvent.SHIFT_DOWN_MASK : 0;
            if (shift) {
                KeyEvent pressShift = new KeyEvent(canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED);
                canvas.dispatchEvent(pressShift);
                Thread.sleep(getGaussianDelay(20, 10));
            }
            MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), modifiers, x, y, 1, false, MouseEvent.BUTTON1);
            canvas.dispatchEvent(press);
            Thread.sleep(getGaussianDelay(config.actionMode().getDefaultWaitMean(), config.actionMode().getDefaultWaitStdDev()));
            MouseEvent release = new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), modifiers, x, y, 1, false, MouseEvent.BUTTON1);
            canvas.dispatchEvent(release);
            if (shift) {
                Thread.sleep(getGaussianDelay(20, 10));
                KeyEvent releaseShift = new KeyEvent(canvas, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED);
                canvas.dispatchEvent(releaseShift);
            }
        }

        public void dispatchKeyEvent(int keyCode) {
            executor.submit(() -> {
                try {
                    final Canvas canvas = client.getCanvas();
                    KeyEvent press = new KeyEvent(canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, keyCode, KeyEvent.CHAR_UNDEFINED);
                    canvas.dispatchEvent(press);
                    Thread.sleep(getGaussianDelay(config.actionMode().getDefaultWaitMean(), config.actionMode().getDefaultWaitStdDev()));
                    KeyEvent release = new KeyEvent(canvas, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, keyCode, KeyEvent.CHAR_UNDEFINED);
                    canvas.dispatchEvent(release);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }
}