package org.admany.lc2h.mixin.lostcities.highway;

import mcjty.lostcities.worldgen.highway.ApproximateCityPotential;
import mcjty.lostcities.worldgen.lost.CityRarityMap;
import it.unimi.dsi.fastutil.longs.Long2FloatLinkedOpenHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

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
    @Unique private static final int LC2H_CACHE_STRIPES = 64;
    @Unique private static final int LC2H_MAX_PER_STRIPE =
        Math.max(256, (LC2H_MAX_POTENTIALS + LC2H_CACHE_STRIPES - 1) / LC2H_CACHE_STRIPES);

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
    private final PotentialStripe[] lc2h$potentialStripes = lc2h$newStripes();

    /**
     * @author Admany
     * @reason Preserve Lost Cities' exact V1 potential math while removing the
     * huge short lived Random allocation storm from intercity route planning.
     */
    @Overwrite
    public float getPotential(int chunkX, int chunkZ) {
        long key = lc2h$key(chunkX, chunkZ);
        PotentialStripe stripe = lc2h$potentialStripes[lc2h$stripe(key)];
        float cached = stripe.get(key);
        if (!Float.isNaN(cached)) {
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

        return stripe.putIfAbsent(key, result);
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
    private static PotentialStripe[] lc2h$newStripes() {
        PotentialStripe[] stripes = new PotentialStripe[LC2H_CACHE_STRIPES];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new PotentialStripe();
        }
        return stripes;
    }

    @Unique
    private static long lc2h$key(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | Integer.toUnsignedLong(chunkZ);
    }

    @Unique
    private static int lc2h$stripe(long key) {
        long mixed = key ^ (key >>> 33) ^ (key << 11);
        return (int) mixed & (LC2H_CACHE_STRIPES - 1);
    }

    @Unique
    private static final class PotentialStripe {
        private final Long2FloatLinkedOpenHashMap values = new Long2FloatLinkedOpenHashMap();

        private PotentialStripe() {
            values.defaultReturnValue(Float.NaN);
        }

        private synchronized float get(long key) {
            return values.getAndMoveToLast(key);
        }

        private synchronized float putIfAbsent(long key, float value) {
            float existing = values.getAndMoveToLast(key);
            if (!Float.isNaN(existing)) {
                return existing;
            }
            values.putAndMoveToLast(key, value);
            while (values.size() > LC2H_MAX_PER_STRIPE) {
                values.removeFirstFloat();
            }
            return value;
        }
    }
}
