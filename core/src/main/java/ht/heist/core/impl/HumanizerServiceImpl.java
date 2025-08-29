package ht.heist.core.impl;

import ht.heist.core.services.HumanizerService;

import javax.inject.Singleton;
import java.util.concurrent.ThreadLocalRandom;

@Singleton
public class HumanizerServiceImpl implements HumanizerService {

    @Override
    public int randomDelay(int minMs, int maxMs) {
        int lo = Math.min(minMs, maxMs);
        int hi = Math.max(minMs, maxMs);
        return ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }

    @Override
    public double randomOffset(double base, double variance) {
        // Simple gaussian-ish wiggle around base
        double gaussian = ThreadLocalRandom.current().nextGaussian(); // mean 0, stddev 1
        return base + gaussian * variance;
    }

    @Override
    public void sleep(int minMs, int maxMs) {
        try {
            Thread.sleep(randomDelay(minMs, maxMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
