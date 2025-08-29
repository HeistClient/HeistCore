package ht.heist.core.services;

public interface HeatmapService {
    void recordClick(int x, int y);
    void recordEvent(String event);
}
