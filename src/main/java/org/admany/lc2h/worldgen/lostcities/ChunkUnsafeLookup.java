package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

final class ChunkUnsafeLookup {

    private final IDimensionInfo dimInfo;
    private final ResourceKey<Level> dim;
    private final Map<Long, Boolean> cache = new HashMap<>(8);

    ChunkUnsafeLookup(IDimensionInfo dimInfo, ResourceKey<Level> dim) {
        this.dimInfo = dimInfo;
        this.dim = dim;
        try {
        } catch (Throwable ignored) {
        }
    }

    boolean isUnsafe(int cx, int cz) {
        long key = (((long) cx) << 32) ^ (cz & 0xffffffffL);
        Boolean cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        boolean unsafe = ChunkRoleProbe.isUnsafe(dimInfo, dim, cx, cz);

        cache.put(key, unsafe);
        return unsafe;
    }
}
