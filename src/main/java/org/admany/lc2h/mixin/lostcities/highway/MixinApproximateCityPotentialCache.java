package org.admany.lc2h.mixin.lostcities.highway;

import mcjty.lostcities.worldgen.highway.ApproximateCityPotential;
import mcjty.lostcities.worldgen.lost.CityRarityMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Keeps highway potential lookups exact without allocating two Random objects
 * for every possible city centre checked. A cold highway region can ask for
 * millions of these first draws, so the allocation was far more expensive
 * than the actual deterministic calculation :L
 */
@Mixin(value = ApproximateCityPotential.class, remap = false)
public abstract class MixinApproximateCityPotentialCache {
    @Unique private static final long LC2H_RANDOM_MULTIPLIER = 0x5DEECE66DL;
    @Unique private static final long LC2H_RANDOM_ADDEND = 0xBL;
    @Unique private static final long LC2H_RANDOM_MASK = (1L << 48) - 1L;
    @Unique private static final double LC2H_DOUBLE_UNIT = 0x1.0p-53;
    @Unique private static final int LC2H_MAX_POTENTIALS = Math.max(16_384,
        Integer.getInteger("lc2h.highway.potentialCacheSize", 262_144));

    @Shadow @Final private double cityChance;
    @Shadow @Final private int cityMinRadius;
    @Shadow @Final private int cityMaxRadius;
    @Shadow @Final private int spawnDistance1;
    @Shadow @Final private int spawnDistance2;
    @Shadow @Final private double spawnMultiplier1;
    @Shadow @Final private double spawnMultiplier2;
    @Shadow @Final private CityRarityMap rarityMap;
    @Shadow @Final private ApproximateCityPotential.Modifier modifier;

    @Unique
    private final ConcurrentHashMap<Long, Float> lc2h$potentials = new ConcurrentHashMap<>();

    @Unique
    private final ConcurrentLinkedQueue<PotentialToken> lc2h$potentialOrder = new ConcurrentLinkedQueue<>();

    /**
     * @author Admany
     * @reason Preserve Lost Cities' exact V1 potential math while removing the
     * huge short lived Random allocation storm from intercity route planning.
     */
    @Overwrite
    public float getPotential(int chunkX, int chunkZ) {
        long key = lc2h$key(chunkX, chunkZ);
        Float cached = lc2h$potentials.get(key);
        if (cached != null) {
            return cached;
        }

        float factor = cityChance < 0.0D
            ? rarityMap.getCityFactor(chunkX, chunkZ)
            : lc2h$getCenterOverlap(chunkX, chunkZ);
        if (spawnDistance2 > 0) {
            double blockX = (double) chunkX * 16.0D;
            double blockZ = (double) chunkZ * 16.0D;
            double distance = Math.sqrt(blockX * blockX + blockZ * blockZ);
            double multiplier;
            if (distance <= spawnDistance1) {
                multiplier = spawnMultiplier1;
            } else if (distance >= spawnDistance2) {
                multiplier = spawnMultiplier2;
            } else {
                double position = (distance - spawnDistance1) / (spawnDistance2 - spawnDistance1);
                multiplier = spawnMultiplier1 + position * (spawnMultiplier2 - spawnMultiplier1);
            }
            factor *= (float) multiplier;
        }
        factor = modifier.modify(chunkX, chunkZ, factor);
        float result = Math.min(Math.max(factor, 0.0F), 1.0F);

        Float previous = lc2h$potentials.putIfAbsent(key, result);
        if (previous != null) {
            return previous;
        }
        lc2h$potentialOrder.add(new PotentialToken(key, result));
        lc2h$trimPotentials();
        return result;
    }

    @Unique
    private float lc2h$getCenterOverlap(int chunkX, int chunkZ) {
        int offset = (cityMaxRadius + 15) / 16;
        float factor = 0.0F;
        for (int centerX = chunkX - offset; centerX <= chunkX + offset; centerX++) {
            for (int centerZ = chunkZ - offset; centerZ <= chunkZ + offset; centerZ++) {
                long centerSeed = (long) centerZ * 797003437L + (long) centerX * 295075153L;
                if (lc2h$firstDouble(centerSeed) >= cityChance) {
                    continue;
                }
                long radiusSeed = (long) centerZ * 100001653L + (long) centerX * 295075153L;
                int range = Math.max(1, cityMaxRadius - cityMinRadius);
                float radius = cityMinRadius + lc2h$firstInt(radiusSeed, range);
                double dx = (double) (centerX - chunkX) * 16.0D;
                double dz = (double) (centerZ - chunkZ) * 16.0D;
                double squaredDistance = dx * dx + dz * dz;
                if (squaredDistance < radius * radius) {
                    factor += (float) ((radius - Math.sqrt(squaredDistance)) / radius);
                }
            }
        }
        return factor;
    }

    @Unique
    private static double lc2h$firstDouble(long externalSeed) {
        long seed = (externalSeed ^ LC2H_RANDOM_MULTIPLIER) & LC2H_RANDOM_MASK;
        seed = (seed * LC2H_RANDOM_MULTIPLIER + LC2H_RANDOM_ADDEND) & LC2H_RANDOM_MASK;
        long high = seed >>> 22;
        seed = (seed * LC2H_RANDOM_MULTIPLIER + LC2H_RANDOM_ADDEND) & LC2H_RANDOM_MASK;
        long low = seed >>> 21;
        return ((high << 27) + low) * LC2H_DOUBLE_UNIT;
    }

    @Unique
    private static int lc2h$firstInt(long externalSeed, int bound) {
        long seed = (externalSeed ^ LC2H_RANDOM_MULTIPLIER) & LC2H_RANDOM_MASK;
        seed = (seed * LC2H_RANDOM_MULTIPLIER + LC2H_RANDOM_ADDEND) & LC2H_RANDOM_MASK;
        int bits = (int) (seed >>> 17);
        int mask = bound - 1;
        if ((bound & mask) == 0) {
            return (int) ((bound * (long) bits) >> 31);
        }
        int value = bits % bound;
        while (bits - value + mask < 0) {
            seed = (seed * LC2H_RANDOM_MULTIPLIER + LC2H_RANDOM_ADDEND) & LC2H_RANDOM_MASK;
            bits = (int) (seed >>> 17);
            value = bits % bound;
        }
        return value;
    }

    @Unique
    private void lc2h$trimPotentials() {
        while (lc2h$potentials.size() > LC2H_MAX_POTENTIALS) {
            PotentialToken oldest = lc2h$potentialOrder.poll();
            if (oldest == null) {
                return;
            }
            lc2h$potentials.remove(oldest.key(), oldest.value());
        }
    }

    @Unique
    private static long lc2h$key(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | Integer.toUnsignedLong(chunkZ);
    }

    @Unique
    private record PotentialToken(long key, float value) {
    }
}
