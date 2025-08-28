package ht.heist;

import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class HeatmapOverlay extends Overlay {

    private final Client client;
    private final WoodcutterConfig config;
    private final List<Point> clickPoints = new CopyOnWriteArrayList<>();

    @Inject
    public HeatmapOverlay(Client client, WoodcutterConfig config) {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
    }

    public void addPoint(Point point) {
        clickPoints.add(point);
    }

    public void clearPoints() {
        clickPoints.clear();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.drawHeatmap()) {
            return null;
        }

        for (Point point : clickPoints) {
            graphics.setColor(new Color(255, 0, 0, 150)); // Semi-transparent red
            graphics.fillOval(point.getX() - 2, point.getY() - 2, 4, 4);
            graphics.setColor(Color.RED);
            graphics.drawOval(point.getX() - 2, point.getY() - 2, 4, 4);
        }
        return null;
    }
}