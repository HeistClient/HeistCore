// ============================================================================
// FILE: CursorTracerService.java
// PACKAGE: ht.heist.core.services
// -----------------------------------------------------------------------------
// PURPOSE
//   Minimal service that controls the Cursor Tracer feature's enabled state.
//   • Overlay queries this to decide whether to render.
//   • No dependency back to UI to avoid circular injection with Guice.
// ============================================================================
package ht.heist.core.services;

public interface CursorTracerService
{
    // --- Lifecycle toggles ---------------------------------------------------
    void enable();
    void disable();
    boolean isEnabled();

    // Convenience: read the current config and enable/disable accordingly.
    void enableIfConfigured();
}
