// ============================================================================
// FILE: HumanizerImpl.java
// PACKAGE: ht.heist.corejava.input
// TITLE: HumanizerImpl — quadratic Bézier path + tiny jitter
//
// HOW THE CURVE IS BUILT
//   • Steps (6–28) scale with pixel distance.
//   • Control point near mid with a small random offset makes a subtle arc.
// ============================================================================
package ht.heist.corejava.input;

import ht.heist.corejava.api.input.Humanizer;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class HumanizerImpl implements Humanizer {

    @Override
    public List<Point> curvedPath(Point from, Point to) {
        List<Point> pts = new ArrayList<>();
        int dx = to.x - from.x, dy = to.y - from.y;
        double dist = Math.hypot(dx, dy);
        int steps = (int)Math.max(6, Math.min(28, Math.round(dist / 18.0)));

        // 1 control point around midpoint with tiny wobble
        double cx = from.x + dx * 0.5 + rand(-12, 12);
        double cy = from.y + dy * 0.5 + rand(-12, 12);

        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            double x = (1 - t)*(1 - t)*from.x + 2*(1 - t)*t*cx + t*t*to.x;
            double y = (1 - t)*(1 - t)*from.y + 2*(1 - t)*t*cy + t*t*to.y;
            pts.add(new Point((int)Math.round(x), (int)Math.round(y)));
        }
        return pts;
    }

    @Override
    public Point jitter(Point p, int maxJitterPx) {
        if (maxJitterPx <= 0) return p;
        return new Point(p.x + rand(-maxJitterPx, maxJitterPx),
                p.y + rand(-maxJitterPx, maxJitterPx));
    }

    private static int rand(int a, int b) {
        return ThreadLocalRandom.current().nextInt(a, b + 1);
    }
}
