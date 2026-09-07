package org.admany.lc2h.worldgen.lostcities;

public final class LostCityGenerationHotPath {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private LostCityGenerationHotPath() {
    }

    public static boolean isActive() {
        return DEPTH.get() > 0;
    }

    public static void run(Runnable action) {
        if (action == null) {
            return;
        }
        DEPTH.set(DEPTH.get() + 1);
        try {
            action.run();
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
