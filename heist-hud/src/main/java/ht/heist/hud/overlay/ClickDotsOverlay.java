// ============================================================================
// FILE: ClickDotsOverlay.java
// PACKAGE: ht.heist.hud.overlay
// TITLE: ClickDotsOverlay — draw small dots for tailed synthetic taps
//
// PURPOSE
// • Lightweight verification that the JSONL bridge works and paths are correct.
// • We keep a bounded circular buffer of recent points and draw filled ovals.
//
// DRAWING
// • Uses canvas coordinates (already in the JSONL), so no transforms needed.
// ============================================================================
package ht.heist.hud.overlay;

import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;

import java.awt.*;
import java.util.ArrayDeque;
import java.util.Deque;

public final class ClickDotsOverlay extends Overlay {

    private final Deque<Point> recent = new ArrayDeque<>(4096);
    private int radiusPx = 2;

    public ClickDotsOverlay() {
        setPosition(OverlayPosition.DYNAMIC);
        setPriority(PRIORITY_MED);
        setMovable(false);
    }

    /** Called from the tailer callback on client thread. */
    public void addPoint(int x, int y, int keep, int radiusPx) {
        this.radiusPx = Math.max(1, radiusPx);
        if (recent.size() >= keep) recent.pollFirst();
        recent.addLast(new Point(x, y));
    }

    @Override
    public Dimension render(Graphics2D g) {
        final int r = radiusPx;
        final int d = r * 2;
        final Color old = g.getColor();
        g.setColor(new Color(255, 200, 0, 180));
        for (Point p : recent) {
            g.fillOval(p.x - r, p.y - r, d, d);
        }
        g.setColor(old);
        return null;
    }
}
