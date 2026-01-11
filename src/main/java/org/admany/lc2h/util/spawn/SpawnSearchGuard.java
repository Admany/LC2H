package org.admany.lc2h.util.spawn;

public final class SpawnSearchGuard {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private SpawnSearchGuard() {
    }

    public static void enter() {
        DEPTH.set(DEPTH.get() + 1);
    }

    public static void exit() {
        int value = DEPTH.get() - 1;
        if (value <= 0) {
            DEPTH.remove();
        } else {
            DEPTH.set(value);
        }
    }

    public static boolean isActive() {
        return DEPTH.get() > 0;
    }
}
