// ============================================================================
// CursorTracerServiceImpl.java
// -----------------------------------------------------------------------------
// Purpose
//   Concrete manager for the core cursor tracer overlay.
//   - Adds/removes overlay via OverlayManager
//   - Reads HeistCoreConfig for default visibility
// ============================================================================

package ht.heist.core.impl;

import ht.heist.core.config.HeistCoreConfig;
import ht.heist.core.services.CursorTracerService;
import ht.heist.core.ui.CursorTracerOverlay;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class CursorTracerServiceImpl implements CursorTracerService
{
    private final OverlayManager overlayManager;
    private final HeistCoreConfig cfg;
    private final CursorTracerOverlay overlay;

    private volatile boolean added = false;

    @Inject
    public CursorTracerServiceImpl(OverlayManager overlayManager,
                                   HeistCoreConfig cfg,
                                   CursorTracerOverlay overlay)
    {
        this.overlayManager = overlayManager;
        this.cfg = cfg;
        this.overlay = overlay;
    }

    @Override
    public void enableIfConfigured()
    {
        if (cfg.showCursorTracer()) enable();
        else disable();
    }

    @Override
    public synchronized void enable()
    {
        if (added) return;
        overlayManager.add(overlay);
        added = true;
    }

    @Override
    public synchronized void disable()
    {
        if (!added) return;
        overlayManager.remove(overlay);
        added = false;
        overlay.clear();
    }

    @Override
    public boolean isEnabled()
    {
        return added;
    }

    @Override
    public void clear()
    {
        overlay.clear();
    }
}
