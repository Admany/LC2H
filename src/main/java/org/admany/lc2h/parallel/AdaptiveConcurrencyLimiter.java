package org.admany.lc2h.parallel;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class AdaptiveConcurrencyLimiter {

    public interface Token extends AutoCloseable {
        @Override
        void close();
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();

    private final int min;
    private final int max;

    private final AtomicInteger limit;
    private int active;

    public AdaptiveConcurrencyLimiter(int initialLimit, int min, int max) {
        this.min = Math.max(1, min);
        this.max = Math.max(this.min, max);
        int clamped = clamp(initialLimit);
        this.limit = new AtomicInteger(clamped);
        this.active = 0;
    }

    public int getLimit() {
        return limit.get();
    }

    public void setLimit(int newLimit) {
        int clamped = clamp(newLimit);
        int prev = limit.getAndSet(clamped);
        if (prev != clamped) {
            signalAll();
        }
    }

    public Token enter() {
        lock.lock();
        try {
            while (active >= limit.get()) {
                available.awaitUninterruptibly();
            }
            active++;
            return this::exit;
        } finally {
            lock.unlock();
        }
    }

    public Token tryEnter() {
        lock.lock();
        try {
            if (active >= limit.get()) {
                return null;
            }
            active++;
            return this::exit;
        } finally {
            lock.unlock();
        }
    }

    private void exit() {
        lock.lock();
        try {
            if (active > 0) {
                active--;
            }
            available.signal();
        } finally {
            lock.unlock();
        }
    }

    private void signalAll() {
        lock.lock();
        try {
            available.signalAll();
        } finally {
            lock.unlock();
        }
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
}
