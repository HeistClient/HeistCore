/*
 * =============================================================================
 *  FILE: CoreServices.java
 *  PACKAGE: ht.heist.core
 *
 *  PURPOSE
 *  -----------------------------------------------------------------------------
 *  Simple static registry that exposes the single CoreManager instance to other
 *  plugins. RuneLite creates a separate Guice injector per plugin JAR, so DI
 *  instances are NOT shared across plugins. This registry is the safe bridge:
 *
 *     HeistCorePlugin.startUp()  → CoreServices.register(coreManager)
 *     WoodcutterPlugin.startUp() → CoreServices.get() to obtain services
 *
 *  SAFETY
 *  -----------------------------------------------------------------------------
 *  - Thread-safe enough for our purposes (volatile reference).
 *  - If Heist Core isn’t started yet, get() returns null; callers must handle.
 * =============================================================================
 */

package ht.heist.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CoreServices
{
    private static final Logger log = LoggerFactory.getLogger(CoreServices.class);

    /** The single CoreManager instance owned by Heist Core plugin. */
    private static volatile CoreManager CORE;

    private CoreServices() {}

    /** Register or clear (null) the CoreManager reference. */
    public static void register(CoreManager manager)
    {
        CORE = manager;
        log.info("CoreServices: {}", manager != null ? "registered" : "cleared");
    }

    /** Get the registered CoreManager, or null if Heist Core hasn’t started yet. */
    public static CoreManager get()
    {
        return CORE;
    }

    /** Explicit clear helper for shutdown. */
    public static void clear()
    {
        CORE = null;
        log.info("CoreServices: cleared");
    }
}
