// ============================================================================
// FILE: CursorTracerServiceImpl.java
// PACKAGE: ht.heist.core.impl
// -----------------------------------------------------------------------------
// PURPOSE
//   Concrete implementation of CursorTracerService:
//     • Tracks whether the tracer is enabled.
//     • Reads HeistCoreConfig in enableIfConfigured().
// DESIGN
//   • NO references to CursorTracerOverlay here (critical to avoid
//     UI↔service circular dependency).
// ============================================================================
package ht.heist.core.impl;

import ht.heist.core.config.HeistCoreConfig;
import ht.heist.core.services.CursorTracerService;
import net.runelite.api.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class CursorTracerServiceImpl implements CursorTracerService
{
    private static final Logger log = LoggerFactory.getLogger(CursorTracerServiceImpl.class);

    private final Client client;
    private final HeistCoreConfig config;

    private volatile boolean enabled = true;

    @Inject
    public CursorTracerServiceImpl(Client client, HeistCoreConfig config)
    {
        this.client  = client;
        this.config  = config;
    }

    // -------------------------------------------------------------------------
    // CursorTracerService API
    // -------------------------------------------------------------------------
    @Override
    public void enable()
    {
        enabled = true;
        log.debug("CursorTracerService: enabled");
    }

    @Override
    public void disable()
    {
        enabled = false;
        log.debug("CursorTracerService: disabled");
    }

    @Override
    public boolean isEnabled()
    {
        return enabled;
    }

    @Override
    public void enableIfConfigured()
    {
        if (config.showCursorTracer()) enable();
        else                           disable();
    }
}
