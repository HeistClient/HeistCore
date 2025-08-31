// ============================================================================
// FILE: HeatPoint.java
// PACKAGE: ht.heist.hud.service
// -----------------------------------------------------------------------------
// TITLE
//   Small immutable value object for a single heatmap tap.
//   Keep fields private + provide getters (overlay uses getters).
// ============================================================================
package ht.heist.hud.service;

public final class HeatPoint
{
    private final int x;
    private final int y;
    private final long ts;

    public HeatPoint(int x, int y, long ts)
    {
        this.x = x;
        this.y = y;
        this.ts = ts;
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public long getTs() { return ts; }
}
