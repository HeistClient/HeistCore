// ============================================================================
// FILE: TapSink.java
// MODULE: core-rl
// PACKAGE: ht.heist.corerl.input
// -----------------------------------------------------------------------------
// TITLE
//   TapSink — minimal adapter for components that want to "send taps"
//
// PURPOSE
//   - Synthetic input generators (e.g., HumanMouse) can be parameterized
//     with a TapSink. Implementations typically forward to TapBus.post(...).
//
// NOTE
//   - Optional helper; many callers can just call TapBus.post directly.
//   - Exists to keep your HumanMouse class free of static TapBus references
//     if you prefer dependency injection there.
// ============================================================================

package ht.heist.corejava.api.input;

@FunctionalInterface
public interface TapSink {
    void onTap(TapEvent event);
}
