package ht.heist.hud.service;

/** Lightweight point record for overlays. */
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

    public int x()  { return x; }
    public int y()  { return y; }
    public long ts(){ return ts; }
}
