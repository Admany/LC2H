package org.admany.lc2h.worldgen.lostcities;

import java.util.function.Supplier;

public final class PlannerHotPath {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Boolean> EXTERNAL_WORLDGEN = new ThreadLocal<>();

    private PlannerHotPath() {
    }

    public static boolean isActive() {
        return DEPTH.get() > 0;
    }

    public static boolean shouldAvoidBlocking() {
        if (isActive() || LostCityGenerationHotPath.isActive()) {
            return true;
        }
        Boolean cached = EXTERNAL_WORLDGEN.get();
        if (Boolean.TRUE.equals(cached)) {
            return true;
        }
        Thread thread = Thread.currentThread();
        String name = thread.getName().toLowerCase(java.util.Locale.ROOT);
        boolean external = name.contains("c2me")
            || name.contains("fastchunkgen")
            || name.contains("chunk gen")
            || name.contains("chunk-generation")
            || name.contains("generation worker");
        if (!external) {
            for (StackTraceElement frame : thread.getStackTrace()) {
                String owner = frame.getClassName().toLowerCase(java.util.Locale.ROOT);
                if (owner.contains("c2me") || owner.contains("fastchunkgen")) {
                    external = true;
                    break;
                }
            }
        }
        if (external) {
            EXTERNAL_WORLDGEN.set(Boolean.TRUE);
        } else {
            EXTERNAL_WORLDGEN.remove();
        }
        return external;
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
