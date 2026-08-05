package org.admany.lc2h.worldgen.lostcities;

import java.util.function.Supplier;

public final class PlannerHotPath {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private PlannerHotPath() {
    }

    public static boolean isActive() {
        return DEPTH.get() > 0;
    }

    public static <T> T run(Supplier<T> supplier) {
        DEPTH.set(DEPTH.get() + 1);
        try {
            return supplier.get();
        } finally {
            int next = DEPTH.get() - 1;
            if (next <= 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(next);
            }
        }
    }
}
