package org.admany.lc2h.util;

/** Hash-safe key for two exact signed coordinates. */
public final class PackedCoordinateKey {

    private PackedCoordinateKey() {
    }

    public static long of(int x, int z) {
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        key = (key ^ (key >>> 30)) * 0xbf58476d1ce4e5b9L;
        key = (key ^ (key >>> 27)) * 0x94d049bb133111ebL;
        return key ^ (key >>> 31);
    }
}
