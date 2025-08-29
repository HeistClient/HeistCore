package ht.heist.core.services;

public interface HumanizerService {
    int randomDelay(int minMs, int maxMs);
    double randomOffset(double base,double variance);
}
