// ============================================================================
// FILE: HeistLog.java
// PATH: core-java/src/main/java/ht/heist/corejava/logging/HeistLog.java
// PACKAGE: ht.heist.corejava.logging
// -----------------------------------------------------------------------------
// TITLE
//   HeistLog — Tiny SLF4J helper for consistent structured logs
//
// WHAT THIS PROVIDES
//   • get(Class<?>): returns org.slf4j.Logger via LoggerFactory
//   • diag(log, "msg"): convenience overload (no kvs)
//   • diag(log, "msg", key, val, ...): prints "msg key=val key2=val2"
//     (odd kvs are safely ignored so callers never crash)
// -----------------------------------------------------------------------------
package ht.heist.corejava.logging;

import org.slf4j.Logger;          // <-- SLF4J API (add slf4j-api to pom)
import org.slf4j.LoggerFactory;   // <-- SLF4J factory

public final class HeistLog
{
    private HeistLog() {}

    /** Get a SLF4J logger bound to the given class. */
    public static Logger get(Class<?> cls) {
        return LoggerFactory.getLogger(cls);
    }

    /** Convenience: log just a message at INFO. */
    public static void diag(Logger log, String msg) {
        if (log != null && log.isInfoEnabled()) {
            log.info(msg);
        }
    }

    /**
     * Diagnostic "structured-ish" log with key=value pairs.
     * Accepts an arbitrary number of kv entries; odd trailing item is ignored.
     *
     * Example:
     *   HeistLog.diag(log, "picked_target", "name","oak", "dist",7);
     */
    public static void diag(Logger log, String msg, Object... kv) {
        if (log == null || !log.isInfoEnabled()) return;
        StringBuilder sb = new StringBuilder(msg);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(' ').append(kv[i]).append('=').append(kv[i + 1]);
        }
        log.info(sb.toString());
    }
}
