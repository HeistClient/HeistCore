// ============================================================================
// FILE: HeatmapServiceImpl.java
// PACKAGE: ht.heist.hud.service
// -----------------------------------------------------------------------------
// TITLE
//   Simple in-memory heatmap store. Thread-safe enough for overlay usage.
// ============================================================================
package ht.heist.hud.service;

import ht.heist.hud.HeistHUDConfig;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Singleton
public class HeatmapServiceImpl implements HeatmapService
{
    private final HeistHUDConfig cfg;

    // Storage
    private final List<HeatPoint> persistent = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean acceptHuman = true;
    private volatile boolean acceptSynthetic = true;

    @Inject
    public HeatmapServiceImpl(HeistHUDConfig cfg)
    {
        this.cfg = cfg;
    }

    // ---- Config passthrough -------------------------------------------------
    @Override
    public int liveDecayMs() { return cfg.liveDecayMs(); }

    // ---- Acceptors ----------------------------------------------------------
    @Override
    public void addHumanTap(int x, int y, long ts)
    {
        if (!acceptHuman) return;
        persistent.add(new HeatPoint(x, y, ts));
    }

    @Override
    public void addSyntheticTap(int x, int y, long ts)
    {
        if (!acceptSynthetic) return;
        persistent.add(new HeatPoint(x, y, ts));
    }

    // ---- Readers ------------------------------------------------------------
    @Override
    public List<HeatPoint> getPersistent()
    {
        synchronized (persistent)
        {
            return new ArrayList<>(persistent);
        }
    }

    @Override
    public List<HeatPoint> getLive(long nowMs, long decayMs)
    {
        final long cutoff = nowMs - Math.max(0, decayMs);
        final ArrayList<HeatPoint> out = new ArrayList<>();
        synchronized (persistent)
        {
            for (HeatPoint p : persistent)
                if (p.getTs() >= cutoff) out.add(p);
        }
        return out;
    }

    // ---- Admin --------------------------------------------------------------
    @Override
    public void clearAll()
    {
        persistent.clear();
    }

    @Override
    public void setAcceptHuman(boolean on) { this.acceptHuman = on; }

    @Override
    public void setAcceptSynthetic(boolean on) { this.acceptSynthetic = on; }
}
