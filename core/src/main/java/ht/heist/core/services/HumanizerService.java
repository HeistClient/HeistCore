package ht.heist.core.services;

import com.google.inject.ImplementedBy;
import ht.heist.core.impl.HumanizerServiceImpl;

@ImplementedBy(HumanizerServiceImpl.class)
public interface HumanizerService {
    /** Uniform delay between [minMs, maxMs] inclusive. */
    int randomDelay(int minMs, int maxMs);

    /** Gaussian-ish offset around a base value with a variance multiplier. */
    double randomOffset(double base, double variance);

    /** Sleep wrapper that swallows InterruptedException. */
    void sleep(int minMs, int maxMs);
}
