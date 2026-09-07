package org.admany.lc2h.dev.debug;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class WorldParityPrimeGate {

    private static final AtomicInteger ACTIVE_PRIMES = new AtomicInteger();
    private static final AtomicLong GLOBAL_TODO_SUPPRESSIONS = new AtomicLong();

    private WorldParityPrimeGate() {
    }

    public static Scope enter() {
        ACTIVE_PRIMES.incrementAndGet();
        return Scope.HOLD;
    }

    public static Scope hold() {
        ACTIVE_PRIMES.incrementAndGet();
        return Scope.HOLD;
    }

    public static boolean isActive() {
        return ACTIVE_PRIMES.get() > 0;
    }

    public static int activeCount() {
        return ACTIVE_PRIMES.get();
    }

    public static void recordGlobalTodoSuppressed() {
        GLOBAL_TODO_SUPPRESSIONS.incrementAndGet();
    }

    public static String diagnostics() {
        return "active=" + ACTIVE_PRIMES.get()
            + ", globalTodoSuppressions=" + GLOBAL_TODO_SUPPRESSIONS.get();
    }

    public enum Scope implements AutoCloseable {
        HOLD(true),
        NOOP(false);

        private final boolean releasesActivePrime;

        Scope(boolean releasesActivePrime) {
            this.releasesActivePrime = releasesActivePrime;
        }

        public static Scope noop() {
            return NOOP;
        }

        @Override
        public void close() {
            if (!releasesActivePrime) {
                return;
            }
            ACTIVE_PRIMES.updateAndGet(current -> Math.max(0, current - 1));
        }
    }
}
