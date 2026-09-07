package org.admany.lc2h.worldgen.lostcities;

import java.util.concurrent.atomic.AtomicLong;

/** Runtime counters for the Lost Cities post-todo safety bridge. */
public final class LostCityPostTodoSafety {
    private static final AtomicLong SKIPPED_OUTSIDE_REGION = new AtomicLong();
    private static final AtomicLong SKIPPED_ASYNC_SERVER_LEVEL = new AtomicLong();

    private LostCityPostTodoSafety() {
    }

    public static void skippedOutsideRegion() {
        SKIPPED_OUTSIDE_REGION.incrementAndGet();
    }

    public static void skippedAsyncServerLevel() {
        SKIPPED_ASYNC_SERVER_LEVEL.incrementAndGet();
    }

    public static String diagnostics() {
        return "outsideRegionSkips=" + SKIPPED_OUTSIDE_REGION.get()
            + ", asyncServerLevelSkips=" + SKIPPED_ASYNC_SERVER_LEVEL.get();
    }
}
