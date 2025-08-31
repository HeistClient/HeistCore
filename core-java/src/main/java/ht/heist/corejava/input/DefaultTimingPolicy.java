// ============================================================================
// FILE: DefaultTimingPolicy.java
// PACKAGE: ht.heist.corejava.input
// TITLE: DefaultTimingPolicy — min floors + Gaussian noise (AVOIDS yellow clicks)
//
// WHY THESE NUMBERS
//   • Floors are modest yet non-trivial (e.g., dwell ≥ 80ms, hold ≥ 45ms).
//   • Gaussian noise around human-ish means prevents robotic timing.
// ============================================================================
package ht.heist.corejava.input;

import ht.heist.corejava.api.input.TimingPolicy;
import java.util.concurrent.ThreadLocalRandom;

public final class DefaultTimingPolicy implements TimingPolicy {

    // -------- Tunable minimum floors (ms) --------
    private static final long DWELL_MIN = 80;   // enough hover for highlight
    private static final long HOLD_MIN  = 45;   // button down time
    private static final long REACT_MIN = 90;   // tiny human latency
    private static final long SETTLE_MIN= 30;   // post-release pause
    private static final long KEY_MIN   = 25;   // key lead/lag around SHIFT

    private static long gaussian(long min, long mean, long std) {
        double g = ThreadLocalRandom.current().nextGaussian(); // mean 0, std 1
        long v = Math.round(mean + g * std);
        return Math.max(min, v);
    }

    @Override public long dwellMs()    { return gaussian(DWELL_MIN, 130, 30); }
    @Override public long reactionMs() { return gaussian(REACT_MIN, 120, 25); }
    @Override public long holdMs()     { return gaussian(HOLD_MIN,   55, 10); }
    @Override public long settleMs()   { return gaussian(SETTLE_MIN, 40, 10); }
    @Override public long keyLeadMs()  { return gaussian(KEY_MIN,    30,  8); }
    @Override public long keyLagMs()   { return gaussian(KEY_MIN,    30,  8); }
}
