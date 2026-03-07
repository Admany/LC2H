package org.admany.lc2h.dev.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class Lc2hTimingRegistry {

    private static final ConcurrentHashMap<String, TimingCounter> COUNTERS = new ConcurrentHashMap<>();

    private Lc2hTimingRegistry() {
    }

    public static void record(String bucket, long elapsedNs) {
        if (bucket == null || bucket.isBlank() || elapsedNs <= 0L) {
            return;
        }
        COUNTERS.computeIfAbsent(bucket, unused -> new TimingCounter()).record(elapsedNs);
    }

    public static Map<String, TimingSnapshot> snapshot() {
        Map<String, TimingSnapshot> out = new LinkedHashMap<>();
        COUNTERS.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> out.put(entry.getKey(), entry.getValue().snapshot()));
        return out;
    }

    public record TimingSnapshot(long count, long totalNs, long maxNs, double avgNs) {
        public TimingSnapshot delta(TimingSnapshot start) {
            if (start == null) {
                return this;
            }
            long deltaCount = Math.max(0L, count - start.count);
            long deltaTotal = Math.max(0L, totalNs - start.totalNs);
            long deltaMax = Math.max(0L, maxNs);
            double deltaAvg = deltaCount <= 0L ? 0.0D : deltaTotal / (double) deltaCount;
            return new TimingSnapshot(deltaCount, deltaTotal, deltaMax, deltaAvg);
        }
    }

    private static final class TimingCounter {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalNs = new LongAdder();
        private final AtomicLong maxNs = new AtomicLong();

        private void record(long elapsedNs) {
            count.increment();
            totalNs.add(elapsedNs);
            while (true) {
                long current = maxNs.get();
                if (elapsedNs <= current) {
                    return;
                }
                if (maxNs.compareAndSet(current, elapsedNs)) {
                    return;
                }
            }
        }

        private TimingSnapshot snapshot() {
            long c = count.sum();
            long t = totalNs.sum();
            long m = maxNs.get();
            double avg = c <= 0L ? 0.0D : t / (double) c;
            return new TimingSnapshot(c, t, m, avg);
        }
    }
}
