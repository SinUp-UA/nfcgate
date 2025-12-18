package de.tu_darmstadt.seemoo.nfcgate.util;

import java.util.concurrent.atomic.AtomicInteger;

/** Lightweight counters for diagnostics export (no PII, no payloads). */
public final class DiagnosticsStats {
    private static final AtomicInteger DROPPED_SEND_MESSAGES = new AtomicInteger(0);
    private static final AtomicInteger DROPPED_LOG_ENTRIES = new AtomicInteger(0);
    private static final AtomicInteger WATCHDOG_RECONNECTS = new AtomicInteger(0);

    private DiagnosticsStats() {}

    public static void incDroppedSendMessages() {
        DROPPED_SEND_MESSAGES.incrementAndGet();
    }

    public static void incDroppedLogEntries() {
        DROPPED_LOG_ENTRIES.incrementAndGet();
    }

    public static int getDroppedSendMessages() {
        return DROPPED_SEND_MESSAGES.get();
    }

    public static int getDroppedLogEntries() {
        return DROPPED_LOG_ENTRIES.get();
    }

    public static void incWatchdogReconnects() {
        WATCHDOG_RECONNECTS.incrementAndGet();
    }

    public static int getWatchdogReconnects() {
        return WATCHDOG_RECONNECTS.get();
    }
}
