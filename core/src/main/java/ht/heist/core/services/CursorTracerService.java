// ============================================================================
// CursorTracerService.java
// -----------------------------------------------------------------------------
// Purpose
//   Core-level manager for the cursor tracer overlay. Lets any plugin turn the
//   tracer on/off globally without knowing overlay plumbing.
//
// Binding
//   Uses @ImplementedBy so Guice auto-binds the interface to the impl.
// ============================================================================

package ht.heist.core.services;

import com.google.inject.ImplementedBy;
import ht.heist.core.impl.CursorTracerServiceImpl;

@ImplementedBy(CursorTracerServiceImpl.class)
public interface CursorTracerService
{
    /** Ensure overlay is added if config says it should be visible. */
    void enableIfConfigured();

    /** Force-enable (adds to OverlayManager regardless of current config). */
    void enable();

    /** Disable and remove overlay from OverlayManager. */
    void disable();

    /** @return true if currently added to OverlayManager. */
    boolean isEnabled();

    /** Clear the trail dots immediately. */
    void clear();
}
