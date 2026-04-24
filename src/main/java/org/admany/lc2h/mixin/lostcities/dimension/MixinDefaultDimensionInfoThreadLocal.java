package org.admany.lc2h.mixin.lostcities.dimension;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.config.ProfileSetup;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.DefaultDimensionInfo;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries;
import mcjty.lostcities.worldgen.lost.cityassets.WorldStyle;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.admany.lc2h.worldgen.lostcities.LostCityProfileOverrideManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Random;

@Mixin(value = DefaultDimensionInfo.class, remap = false)
public abstract class MixinDefaultDimensionInfoThreadLocal implements IDimensionInfo, org.admany.lc2h.util.lostcities.ThreadLocalDimensionInfo {

    @Shadow private WorldGenLevel world;
    @Shadow private LostCityProfile profile;
    @Shadow private LostCityProfile profileOutside;
    @Shadow private WorldStyle style;

    @Unique private final ThreadLocal<WorldGenLevel> lc2h$worldLocal = new ThreadLocal<>();
    @Unique private volatile WorldGenLevel lc2h$worldFallback;

    @Unique private final ThreadLocal<Long> lc2h$featureSeedLocal = new ThreadLocal<>();
    @Unique private final ThreadLocal<String> lc2h$featureProfileTokenLocal = new ThreadLocal<>();
    @Unique private final ThreadLocal<LostCityTerrainFeature> lc2h$featureLocal = new ThreadLocal<>();

    @Unique private final ThreadLocal<Random> lc2h$randomLocal = new ThreadLocal<>();

    @Overwrite
    public void setWorld(WorldGenLevel world) {
        this.world = world;
        this.lc2h$worldFallback = world;
        this.lc2h$worldLocal.set(world);

        try {
            Random random = lc2h$randomLocal.get();
            if (random == null) {
                random = new Random(world.getSeed());
                lc2h$randomLocal.set(random);
            }

            long seed = world.getSeed();
            if (world instanceof WorldGenRegion region) {
                var center = region.getCenter();
                seed = seed + center.z * 341873128712L + center.x * 132897987541L;
            }
            random.setSeed(seed);
        } catch (Throwable ignored) {
        }
    }

    @Overwrite
    public WorldGenLevel getWorld() {
        WorldGenLevel local = lc2h$worldLocal.get();
        if (local != null) {
            return local;
        }
        WorldGenLevel fallback = lc2h$worldFallback;
        if (fallback != null) {
            return fallback;
        }
        return world;
    }

    @Overwrite
    public long getSeed() {
        return getWorld().getSeed();
    }

    @Overwrite
    public ResourceKey<Level> getType() {
        return getWorld().getLevel().dimension();
    }

    @Overwrite
    public Random getRandom() {
        Random random = lc2h$randomLocal.get();
        if (random == null) {
            random = new Random(getSeed());
            lc2h$randomLocal.set(random);
        }
        return random;
    }

    @Overwrite
    public LostCityProfile getProfile() {
        return LostCityProfileOverrideManager.resolveProfile(getWorld(), profile);
    }

    @Overwrite
    public LostCityProfile getOutsideProfile() {
        LostCityProfile effectiveProfile = getProfile();
        if (effectiveProfile == profile) {
            return profileOutside;
        }
        LostCityProfile outside = ProfileSetup.STANDARD_PROFILES.get(effectiveProfile.CITYSPHERE_OUTSIDE_PROFILE);
        return outside != null ? outside : profileOutside;
    }

    @Overwrite
    public WorldStyle getWorldStyle() {
        LostCityProfile effectiveProfile = getProfile();
        if (effectiveProfile == profile) {
            return style;
        }
        try {
            WorldStyle effectiveStyle = (WorldStyle) AssetRegistries.WORLDSTYLES.get(getWorld(), effectiveProfile.getWorldStyle());
            return effectiveStyle != null ? effectiveStyle : style;
        } catch (Throwable ignored) {
            return style;
        }
    }

    @Overwrite
    public LostCityTerrainFeature getFeature() {
        long seed = getSeed();
        LostCityProfile effectiveProfile = getProfile();
        String profileToken = LostCityProfileOverrideManager.profileToken(getWorld(), effectiveProfile);
        Long cachedSeed = lc2h$featureSeedLocal.get();
        String cachedProfileToken = lc2h$featureProfileTokenLocal.get();
        LostCityTerrainFeature cachedFeature = lc2h$featureLocal.get();
        if (cachedSeed != null && cachedSeed == seed && profileToken.equals(cachedProfileToken) && cachedFeature != null) {
            return cachedFeature;
        }

        RandomSource randomSource = new LegacyRandomSource(seed);
        LostCityTerrainFeature feature = new LostCityTerrainFeature((IDimensionInfo) (Object) this, effectiveProfile, randomSource);
        feature.setupStates(effectiveProfile);
        lc2h$featureSeedLocal.set(seed);
        lc2h$featureProfileTokenLocal.set(profileToken);
        lc2h$featureLocal.set(feature);
        return feature;
    }

    @Overwrite
    public ChunkHeightmap getHeightmap(int chunkX, int chunkZ) {
        ChunkCoord coord = new ChunkCoord(getType(), chunkX, chunkZ);
        return getFeature().getHeightmap(coord, getWorld());
    }

    @Overwrite
    public ChunkHeightmap getHeightmap(ChunkCoord coord) {
        return getFeature().getHeightmap(coord, getWorld());
    }

    @Overwrite
    public ResourceKey<Level> dimension() {
        return getWorld().getLevel().dimension();
    }

    @Unique
    @Override
    public void lc2h$clearThreadContext() {
        lc2h$worldLocal.remove();
        // Keep feature/random locals so this thread can reuse them, but stop retaining the current region.
    }
}
