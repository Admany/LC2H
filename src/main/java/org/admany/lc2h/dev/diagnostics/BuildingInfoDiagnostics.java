package org.admany.lc2h.dev.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class BuildingInfoDiagnostics {

    private static final LongAdder CHARACTERISTICS_MEMORY_HITS = new LongAdder();
    private static final LongAdder CHARACTERISTICS_SNAPSHOT_HITS = new LongAdder();
    private static final LongAdder CHARACTERISTICS_DISK_HITS = new LongAdder();
    private static final LongAdder CHARACTERISTICS_COMPUTES = new LongAdder();

    private static final LongAdder BUILDING_INFO_MEMORY_HITS = new LongAdder();
    private static final LongAdder BUILDING_INFO_CREATES = new LongAdder();
    private static final LongAdder BUILDING_INFO_FAILURES = new LongAdder();
    private static final LongAdder BUILDING_INFO_LOCK_WAITS = new LongAdder();
    private static final LongAdder BUILDING_INFO_LOCK_WAIT_NS = new LongAdder();
    private static final AtomicLong BUILDING_INFO_LOCK_WAIT_MAX_NS = new AtomicLong();

    private static final LongAdder CITY_LEVEL_MEMORY_HITS = new LongAdder();
    private static final LongAdder CITY_LEVEL_DISK_HITS = new LongAdder();
    private static final LongAdder CITY_LEVEL_COMPUTES = new LongAdder();
    private static final LongAdder CITY_LEVEL_REGION_MEMORY_HITS = new LongAdder();
    private static final LongAdder CITY_LEVEL_REGION_DISK_HITS = new LongAdder();
    private static final LongAdder CITY_LEVEL_REGION_COMPUTES = new LongAdder();

    private static final LongAdder CITY_RAW_MEMORY_HITS = new LongAdder();
    private static final LongAdder CITY_RAW_DISK_HITS = new LongAdder();
    private static final LongAdder CITY_RAW_COMPUTES = new LongAdder();

    private static final LongAdder MULTI_HEIGHT_STATS_HITS = new LongAdder();
    private static final LongAdder MULTI_HEIGHT_STATS_MISSES = new LongAdder();
    private static final LongAdder MULTI_BOUNDARY_HITS = new LongAdder();
    private static final LongAdder MULTI_BOUNDARY_MISSES = new LongAdder();

    private BuildingInfoDiagnostics() {
    }

    public static void recordCharacteristicsMemoryHit() {
        CHARACTERISTICS_MEMORY_HITS.increment();
    }

    public static void recordCharacteristicsSnapshotHit() {
        CHARACTERISTICS_SNAPSHOT_HITS.increment();
    }

    public static void recordCharacteristicsDiskHit() {
        CHARACTERISTICS_DISK_HITS.increment();
    }

    public static void recordCharacteristicsCompute() {
        CHARACTERISTICS_COMPUTES.increment();
    }

    public static void recordBuildingInfoMemoryHit() {
        BUILDING_INFO_MEMORY_HITS.increment();
    }

    public static void recordBuildingInfoCreate() {
        BUILDING_INFO_CREATES.increment();
    }

    public static void recordBuildingInfoFailure() {
        BUILDING_INFO_FAILURES.increment();
    }

    public static void recordBuildingInfoLockWait(long waitNs) {
        if (waitNs <= 0L) {
            return;
        }
        BUILDING_INFO_LOCK_WAITS.increment();
        BUILDING_INFO_LOCK_WAIT_NS.add(waitNs);
        while (true) {
            long current = BUILDING_INFO_LOCK_WAIT_MAX_NS.get();
            if (waitNs <= current) {
                return;
            }
            if (BUILDING_INFO_LOCK_WAIT_MAX_NS.compareAndSet(current, waitNs)) {
                return;
            }
        }
    }

    public static void recordCityLevelMemoryHit() {
        CITY_LEVEL_MEMORY_HITS.increment();
    }

    public static void recordCityLevelDiskHit() {
        CITY_LEVEL_DISK_HITS.increment();
    }

    public static void recordCityLevelCompute() {
        CITY_LEVEL_COMPUTES.increment();
    }

    public static void recordRegionLevelMemoryHit() {
        CITY_LEVEL_REGION_MEMORY_HITS.increment();
    }

    public static void recordRegionLevelDiskHit() {
        CITY_LEVEL_REGION_DISK_HITS.increment();
    }

    public static void recordRegionLevelCompute() {
        CITY_LEVEL_REGION_COMPUTES.increment();
    }

    public static void recordCityRawMemoryHit() {
        CITY_RAW_MEMORY_HITS.increment();
    }

    public static void recordCityRawDiskHit() {
        CITY_RAW_DISK_HITS.increment();
    }

    public static void recordCityRawCompute() {
        CITY_RAW_COMPUTES.increment();
    }

    public static void recordMultiHeightStatsHit() {
        MULTI_HEIGHT_STATS_HITS.increment();
    }

    public static void recordMultiHeightStatsMiss() {
        MULTI_HEIGHT_STATS_MISSES.increment();
    }

    public static void recordMultiBoundaryHit() {
        MULTI_BOUNDARY_HITS.increment();
    }

    public static void recordMultiBoundaryMiss() {
        MULTI_BOUNDARY_MISSES.increment();
    }

    public static List<String> summaryLines() {
        List<String> lines = new ArrayList<>(5);
        lines.add(String.format(Locale.ROOT,
            "BuildingInfo cache: characteristics[mem=%d snapshot=%d disk=%d build=%d] info[mem=%d create=%d fail=%d]",
            CHARACTERISTICS_MEMORY_HITS.sum(),
            CHARACTERISTICS_SNAPSHOT_HITS.sum(),
            CHARACTERISTICS_DISK_HITS.sum(),
            CHARACTERISTICS_COMPUTES.sum(),
            BUILDING_INFO_MEMORY_HITS.sum(),
            BUILDING_INFO_CREATES.sum(),
            BUILDING_INFO_FAILURES.sum()));
        lines.add(String.format(Locale.ROOT,
            "BuildingInfo city: level[mem=%d disk=%d build=%d] region[mem=%d disk=%d build=%d] raw[mem=%d disk=%d build=%d]",
            CITY_LEVEL_MEMORY_HITS.sum(),
            CITY_LEVEL_DISK_HITS.sum(),
            CITY_LEVEL_COMPUTES.sum(),
            CITY_LEVEL_REGION_MEMORY_HITS.sum(),
            CITY_LEVEL_REGION_DISK_HITS.sum(),
            CITY_LEVEL_REGION_COMPUTES.sum(),
            CITY_RAW_MEMORY_HITS.sum(),
            CITY_RAW_DISK_HITS.sum(),
            CITY_RAW_COMPUTES.sum()));
        long lockWaits = BUILDING_INFO_LOCK_WAITS.sum();
        double avgWaitMs = lockWaits <= 0L ? 0.0D : BUILDING_INFO_LOCK_WAIT_NS.sum() / (double) lockWaits / 1_000_000.0D;
        lines.add(String.format(Locale.ROOT,
            "BuildingInfo sync: lockWaits=%d avgWait=%.3fms maxWait=%.3fms",
            lockWaits,
            avgWaitMs,
            BUILDING_INFO_LOCK_WAIT_MAX_NS.get() / 1_000_000.0D));
        lines.add(String.format(Locale.ROOT,
            "BuildingInfo multi: stats[hit=%d miss=%d] boundary[hit=%d miss=%d]",
            MULTI_HEIGHT_STATS_HITS.sum(),
            MULTI_HEIGHT_STATS_MISSES.sum(),
            MULTI_BOUNDARY_HITS.sum(),
            MULTI_BOUNDARY_MISSES.sum()));
        return lines;
    }
}
