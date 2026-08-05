package org.admany.lc2h.dev.debug;

import mcjty.lostcities.varia.ChunkCoord;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class WorldParityObservedChunkTracker {
    private static final Object LOCK = new Object();
    private static final Map<ResourceKey<Level>, ActiveWindow> ACTIVE_WINDOWS = new HashMap<>();
    private static final Map<ResourceKey<Level>, LinkedHashSet<Long>> OBSERVED = new HashMap<>();

    private record ActiveWindow(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        private boolean contains(int chunkX, int chunkZ) {
            return chunkX >= minChunkX && chunkX <= maxChunkX && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
        }
    }

    private WorldParityObservedChunkTracker() {
    }

    public static void activate(ResourceKey<Level> dimension, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        if (dimension == null) {
            return;
        }
        synchronized (LOCK) {
            ACTIVE_WINDOWS.put(dimension, new ActiveWindow(minChunkX, maxChunkX, minChunkZ, maxChunkZ));
            OBSERVED.computeIfAbsent(dimension, ignored -> new LinkedHashSet<>()).clear();
        }
    }

    public static void deactivate(ResourceKey<Level> dimension) {
        if (dimension == null) {
            return;
        }
        synchronized (LOCK) {
            ACTIVE_WINDOWS.remove(dimension);
            OBSERVED.remove(dimension);
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            ACTIVE_WINDOWS.clear();
            OBSERVED.clear();
        }
    }

    public static void record(ChunkCoord coord) {
        if (coord == null || coord.dimension() == null) {
            return;
        }
        synchronized (LOCK) {
            ActiveWindow window = ACTIVE_WINDOWS.get(coord.dimension());
            if (window == null || !window.contains(coord.chunkX(), coord.chunkZ())) {
                return;
            }
            OBSERVED.computeIfAbsent(coord.dimension(), ignored -> new LinkedHashSet<>()).add(ChunkPos.asLong(coord.chunkX(), coord.chunkZ()));
        }
    }

    public static List<ChunkPos> drain(ResourceKey<Level> dimension, int limit, LinkedHashSet<Long> knownChunks) {
        if (dimension == null || limit <= 0) {
            return List.of();
        }
        synchronized (LOCK) {
            LinkedHashSet<Long> observed = OBSERVED.get(dimension);
            if (observed == null || observed.isEmpty()) {
                return List.of();
            }
            ArrayList<ChunkPos> drained = new ArrayList<>(Math.min(limit, observed.size()));
            var iterator = observed.iterator();
            while (iterator.hasNext() && drained.size() < limit) {
                long chunkKey = iterator.next();
                iterator.remove();
                if (knownChunks != null && knownChunks.contains(chunkKey)) {
                    continue;
                }
                drained.add(new ChunkPos(chunkKey));
            }
            return drained;
        }
    }
}
