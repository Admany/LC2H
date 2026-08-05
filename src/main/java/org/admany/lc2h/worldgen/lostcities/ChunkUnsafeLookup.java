package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

final class ChunkUnsafeLookup {

    private final IDimensionInfo dimInfo;
    private final ResourceKey<Level> dim;
    private long[] keys = new long[32];
    private byte[] values = new byte[32];
    private int size;

    ChunkUnsafeLookup(IDimensionInfo dimInfo, ResourceKey<Level> dim) {
        this.dimInfo = dimInfo;
        this.dim = dim;
    }

    boolean isUnsafe(int cx, int cz) {
        long key = (((long) cx) << 32) ^ (cz & 0xffffffffL);
        int mask = keys.length - 1;
        int slot = mix(key) & mask;
        while (values[slot] != 0) {
            if (keys[slot] == key) {
                return values[slot] == 2;
            }
            slot = (slot + 1) & mask;
        }

        boolean unsafe = ChunkRoleProbe.isUnsafe(dimInfo, dim, cx, cz);
        if ((size + 1) * 3 >= keys.length * 2) {
            grow();
            mask = keys.length - 1;
            slot = mix(key) & mask;
            while (values[slot] != 0) {
                slot = (slot + 1) & mask;
            }
        }
        keys[slot] = key;
        values[slot] = unsafe ? (byte) 2 : (byte) 1;
        size++;
        return unsafe;
    }

    private void grow() {
        long[] oldKeys = keys;
        byte[] oldValues = values;
        keys = new long[oldKeys.length << 1];
        values = new byte[oldValues.length << 1];
        int mask = keys.length - 1;
        for (int i = 0; i < oldValues.length; i++) {
            byte value = oldValues[i];
            if (value == 0) {
                continue;
            }
            long key = oldKeys[i];
            int slot = mix(key) & mask;
            while (values[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            keys[slot] = key;
            values[slot] = value;
        }
    }

    private static int mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53l;
        value ^= value >>> 33;
        return (int) value;
    }
}
