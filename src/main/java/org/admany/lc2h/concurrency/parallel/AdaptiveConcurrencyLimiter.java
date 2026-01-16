package org.admany.lc2h.concurrency.parallel;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public final class AdaptiveConcurrencyLimiter {

    public interface Token extends AutoCloseable {
        @Override
        void close();
    }

    private final int min;
    private final int max;

    private final AtomicInteger active = new AtomicInteger();
    private volatile int limit;

    public AdaptiveConcurrencyLimiter(int initialLimit, int min, int max) {
        this.min = Math.max(1, min);
        this.max = Math.max(this.min, max);
        int clamped = clamp(initialLimit);
        this.limit = clamped;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int newLimit) {
        limit = clamp(newLimit);
    }

    public Token enter() {
        long backoffNanos = 500_000L;
        while (true) {
            if (tryAcquireInternal()) {
                return newToken();
            }
            if (Thread.currentThread() instanceof ForkJoinWorkerThread) {
                try {
                    ForkJoinPool.managedBlock(new LimiterBlocker(this));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            } else {
                LockSupport.parkNanos(backoffNanos);
            }
            if (backoffNanos < 5_000_000L) {
                backoffNanos += 250_000L;
            }
        }
    }

    public Token tryEnter() {
        return tryAcquireInternal() ? newToken() : null;
    }

    public int availableSlots() {
        int currentLimit = limit;
        int currentActive = active.get();
        return Math.max(0, currentLimit - currentActive);
    }

    private boolean tryAcquireInternal() {
        int currentLimit = limit;
        int currentActive = active.get();
        if (currentActive >= currentLimit) {
            return false;
        }
        return active.compareAndSet(currentActive, currentActive + 1);
    }

    private void exit() {
        while (true) {
            int current = active.get();
            if (current <= 0) {
                return;
            }
            if (active.compareAndSet(current, current - 1)) {
                return;
            }
        }
    }

    private Token newToken() {
        AtomicBoolean closed = new AtomicBoolean(false);
        return () -> {
            if (closed.compareAndSet(false, true)) {
                exit();
            }
        };
    }

    private int clamp(int value) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static final class LimiterBlocker implements ForkJoinPool.ManagedBlocker {
        private final AdaptiveConcurrencyLimiter limiter;
        private boolean acquired;

        private LimiterBlocker(AdaptiveConcurrencyLimiter limiter) {
            this.limiter = limiter;
        }

        @Override
        public boolean block() {
            if (!acquired) {
                acquired = limiter.tryAcquireInternal();
                if (!acquired) {
                    LockSupport.parkNanos(500_000L);
                }
            }
            return acquired;
        }

        @Override
        public boolean isReleasable() {
            return acquired || (acquired = limiter.tryAcquireInternal());
        }
    }
}
