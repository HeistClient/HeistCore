package ht.heist.core.impl;

import ht.heist.core.config.HeistCoreConfig;
import ht.heist.core.services.HeatmapService;
import net.runelite.api.Client;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

@Singleton
public class HeatmapServiceImpl implements HeatmapService
{
    private final Client client;
    private final HeistCoreConfig cfg;

    private volatile boolean enabled = true;

    // Unbounded deque trimmed by config (max points)
    private final ConcurrentLinkedDeque<PointSample> points = new ConcurrentLinkedDeque<>();

    @Inject
    public HeatmapServiceImpl(Client client, HeistCoreConfig cfg)
    {
        this.client = client;
        this.cfg = cfg;
    }

    @Override
    public void enableIfConfigured()
    {
        if (cfg.showHeatmap()) enable(); else disable();
    }

    @Override public void enable()  { enabled = true; }
    @Override public void disable() { enabled = false; }
    @Override public boolean isEnabled() { return enabled; }

    @Override
    public void addClick(int x, int y)
    {
        if (!enabled || !cfg.showHeatmap()) return;

        final long now = System.currentTimeMillis();
        points.addLast(new PointSample(x, y, now));

        // Trim to max
        int max = Math.max(100, cfg.heatmapMaxPoints());
        while (points.size() > max)
        {
            points.pollFirst();
        }

        // Age-out only if fading is enabled (fadeMs > 0)
        final int fadeMs = cfg.heatmapFadeMs();
        if (fadeMs > 0)
        {
            long cutoff = now - (fadeMs * 3L); // keep ~3× fade horizon
            while (true)
            {
                PointSample head = points.peekFirst();
                if (head == null) break;
                if (head.ts < cutoff) points.pollFirst();
                else break;
            }
        }
    }

    @Override
    public List<PointSample> snapshot()
    {
        if (!enabled || !cfg.showHeatmap()) return Collections.emptyList();
        return new ArrayList<>(points);
    }
}
