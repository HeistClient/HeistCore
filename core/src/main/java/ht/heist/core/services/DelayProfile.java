package ht.heist.core.services;

public class DelayProfile {
    public final int defaultWaitMean;
    public final int defaultWaitStd;
    public final int reactionMean;
    public final int reactionStd;

    public DelayProfile(int defaultWaitMean, int defaultWaitStd, int reactionMean, int reactionStd) {
        this.defaultWaitMean = defaultWaitMean;
        this.defaultWaitStd = defaultWaitStd;
        this.reactionMean = reactionMean;
        this.reactionStd = reactionStd;
    }
}
