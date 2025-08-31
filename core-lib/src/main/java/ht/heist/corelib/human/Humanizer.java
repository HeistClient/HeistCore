// ============================================================================
// FILE: Humanizer.java
// PACKAGE: ht.heist.corelib.human
// -----------------------------------------------------------------------------
// TITLE
//   Humanizer (API) — small surface area of human-like timing and sampling.
//
// EXPOSED PRIMITIVES
//   • randomDelay(min,max): inclusive ms range
//   • gaussianDelay(mean,std): ms sampled ~ N(mean, std)
//   • ar1Step(prev, alpha, noiseStd): 1st-order autoregressive drift step
//   • sampleEllipticalInRect(...): point inside a rectangle with elliptical bias
// ============================================================================

package ht.heist.corelib.human;

import java.awt.*;

public interface Humanizer
{
    int randomDelay(int minMs, int maxMs);

    int gaussianDelay(int meanMs, int stdMs);

    double ar1Step(double prev, double alpha, double noiseStd);

    Point sampleEllipticalInRect(Rectangle r,
                                 double rxFrac, double ryFrac,
                                 double biasX, double biasY);
}
