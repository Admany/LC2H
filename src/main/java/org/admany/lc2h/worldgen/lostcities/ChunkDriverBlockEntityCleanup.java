package org.admany.lc2h.worldgen.lostcities;

import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide counters for the bounded pending block-entity cleanup performed
 * after Lost Cities flushes its ChunkDriver section cache.
 */
public final class ChunkDriverBlockEntityCleanup {

    private static final LongAdder POSITIONS_CHECKED = new LongAdder();
    private static final LongAdder STALE_ENTRIES_REMOVED = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();

    private ChunkDriverBlockEntityCleanup() {
    }

    public static void checked() {
        POSITIONS_CHECKED.increment();
    }

    public static void removed() {
        STALE_ENTRIES_REMOVED.increment();
    }

    public static void failed() {
        FAILURES.increment();
    }

    public static String diagnostics() {
        return "checked=" + POSITIONS_CHECKED.sum()
            + ", removed=" + STALE_ENTRIES_REMOVED.sum()
            + ", failures=" + FAILURES.sum();
    }
}
