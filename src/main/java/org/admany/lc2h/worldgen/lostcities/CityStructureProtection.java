package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.admany.lc2h.dev.diagnostics.Lc2hTimingRegistry;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/** Keeps structure placement out of Lost Cities chunks. */
public final class CityStructureProtection {
    private static final LongAdder QUERIES = new LongAdder();
    private static final LongAdder CHUNKS_CHECKED = new LongAdder();
    private static final LongAdder CITY_HITS = new LongAdder();
    private static final LongAdder MULTI_HITS = new LongAdder();
    private static final LongAdder ROLE_CACHE_HITS = new LongAdder();
    private static final LongAdder COLD_FAST_PASSES = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final LongAdder START_CHECKS = new LongAdder();
    private static final LongAdder START_REJECTS = new LongAdder();
    private static final LongAdder START_DEFERRED = new LongAdder();

    private CityStructureProtection() {
    }

    public static boolean intersectsChunk(IDimensionInfo provider,
                                          ResourceKey<Level> dimension,
                                          int chunkX,
                                          int chunkZ,
                                          int buffer) {
        int radius = Math.max(0, Math.min(4, buffer));
        return intersectsChunks(provider, dimension,
            chunkX - radius, chunkX + radius,
            chunkZ - radius, chunkZ + radius);
    }

    public static boolean intersectsBox(IDimensionInfo provider,
                                        ResourceKey<Level> dimension,
                                        BoundingBox box,
                                        int buffer) {
        if (box == null) {
            return false;
        }
        int radius = Math.max(0, Math.min(4, buffer));
        return intersectsChunks(provider, dimension,
            (box.minX() >> 4) - radius,
            (box.maxX() >> 4) + radius,
            (box.minZ() >> 4) - radius,
            (box.maxZ() >> 4) + radius);
    }

    /** Checks a complete start without entering the synchronous LC noise path. */
    public static StructureDecision inspectStructureBox(IDimensionInfo provider,
                                                        ResourceKey<Level> dimension,
                                                        BoundingBox box,
                                                        int buffer) {
        START_CHECKS.increment();
        if (provider == null || dimension == null || box == null) {
            return StructureDecision.allow();
        }
        int radius = Math.max(0, Math.min(4, buffer));
        int minChunkX = (box.minX() >> 4) - radius;
        int maxChunkX = (box.maxX() >> 4) + radius;
        int minChunkZ = (box.minZ() >> 4) - radius;
        int maxChunkZ = (box.maxZ() >> 4) + radius;
        boolean unknown = false;
        try {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    ChunkCoord coord = new ChunkCoord(dimension, chunkX, chunkZ);
                    if (MultiBuildingFootprintRegistry.owns(provider, coord)) {
                        START_REJECTS.increment();
                        return StructureDecision.reject("multi-building footprint", true);
                    }

                    ChunkRoleProbe.Probe stable =
                        ChunkRoleProbe.peekStableTerrainProbe(provider, dimension, chunkX, chunkZ);
                    if (stable != null) {
                        ROLE_CACHE_HITS.increment();
                        if (stable.isCity()) {
                            START_REJECTS.increment();
                            return StructureDecision.reject("city role", true);
                        }
                        continue;
                    }

                    mcjty.lostcities.api.LostChunkCharacteristics snapshot =
                        ChunkRoleProbe.peekCharacteristics(coord);
                    if (snapshot != null) {
                        if (snapshot.isCity) {
                            START_REJECTS.increment();
                            return StructureDecision.reject("published city characteristics", true);
                        }
                        continue;
                    }

                    unknown = true;
                    COLD_FAST_PASSES.increment();
                    ChunkRoleProbe.requestStableTerrainProbe(provider, dimension, chunkX, chunkZ);
                }
            }
        } catch (Throwable ignored) {
            FAILURES.increment();
            return StructureDecision.allow();
        }
        if (unknown) {
            START_DEFERRED.increment();
            return StructureDecision.unknown();
        }
        return StructureDecision.allow();
    }

    private static boolean intersectsChunks(IDimensionInfo provider,
                                             ResourceKey<Level> dimension,
                                             int minChunkX,
                                             int maxChunkX,
                                             int minChunkZ,
                                             int maxChunkZ) {
        if (provider == null || dimension == null) {
            return false;
        }
        QUERIES.increment();
        long started = System.nanoTime();
        try {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    CHUNKS_CHECKED.increment();
                    ChunkCoord coord = new ChunkCoord(dimension, chunkX, chunkZ);
                    if (MultiBuildingFootprintRegistry.owns(provider, coord)) {
                        MULTI_HITS.increment();
                        return true;
                    }
                    /* Cold role facts are queued for bounded prewarming. */
                    ChunkRoleProbe.Probe stable =
                        ChunkRoleProbe.peekStableTerrainProbe(provider, dimension, chunkX, chunkZ);
                    if (stable != null) {
                        ROLE_CACHE_HITS.increment();
                    } else {
                        COLD_FAST_PASSES.increment();
                        ChunkRoleProbe.requestStableTerrainProbe(provider, dimension, chunkX, chunkZ);
                    }
                    if (stable != null && stable.isCity()) {
                        CITY_HITS.increment();
                        return true;
                    }
                    if (stable == null) {
                        mcjty.lostcities.api.LostChunkCharacteristics snapshot =
                            ChunkRoleProbe.peekCharacteristics(coord);
                        if (snapshot != null && snapshot.isCity) {
                            CITY_HITS.increment();
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            FAILURES.increment();
        } finally {
            Lc2hTimingRegistry.record("structure.city_protection", System.nanoTime() - started);
        }
        return false;
    }

    public static String diagnostics() {
        return String.format(Locale.ROOT,
            "queries=%d chunks=%d cityHits=%d multiHits=%d roleCacheHits=%d coldFastPasses=%d failures=%d " +
                "startChecks=%d startRejects=%d startDeferred=%d",
            QUERIES.sum(), CHUNKS_CHECKED.sum(), CITY_HITS.sum(), MULTI_HITS.sum(),
            ROLE_CACHE_HITS.sum(), COLD_FAST_PASSES.sum(), FAILURES.sum(),
            START_CHECKS.sum(), START_REJECTS.sum(), START_DEFERRED.sum());
    }

    public record StructureDecision(boolean reject, boolean complete, String reason) {
        public static StructureDecision allow() {
            return new StructureDecision(false, true, "-");
        }

        public static StructureDecision unknown() {
            return new StructureDecision(false, false, "role pending");
        }

        public static StructureDecision reject(String reason, boolean complete) {
            return new StructureDecision(true, complete, reason);
        }
    }
}
