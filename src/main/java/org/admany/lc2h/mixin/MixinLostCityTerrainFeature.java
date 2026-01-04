package org.admany.lc2h.mixin;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.varia.Tools;
import mcjty.lostcities.worldgen.ChunkDriver;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.LostTags;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.DamageArea;
import mcjty.lostcities.worldgen.lost.Explosion;
import mcjty.lostcities.worldgen.lost.cityassets.CompiledPalette;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.admany.lc2h.diagnostics.ChunkGenTracker;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;
import org.admany.lc2h.worldgen.lostcities.LostCitiesGenerationLocks;
import org.admany.lc2h.worldgen.lostcities.LostCityTerrainFeatureGuards;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Mixin(value = LostCityTerrainFeature.class, remap = false)
public class MixinLostCityTerrainFeature {

	    @Shadow public ChunkDriver driver;
	    @Shadow public IDimensionInfo provider;
	    @Shadow public BlockState air;
	    @Shadow public BlockState liquid;

    @Unique
    private static final ThreadLocal<Long> LC2H_DAMAGE_SEED = new ThreadLocal<>();

    @Unique
    private static final ThreadLocal<int[]> LC2H_DAMAGE_CONTEXT_POS = new ThreadLocal<>();

    @Unique
    private static final ThreadLocal<List<double[]>> LC2H_DAMAGE_CONTEXT_PATHS = new ThreadLocal<>();

    @Unique
    private static final ConcurrentHashMap<ChunkCoord, List<Explosion>> LC2H_SHARED_DAMAGE = new ConcurrentHashMap<>();

    @Unique
    private static final ConcurrentHashMap<ChunkCoord, Long> LC2H_SHARED_DAMAGE_TS = new ConcurrentHashMap<>();

    @Unique
    private static final long LC2H_DAMAGE_CACHE_TTL_MS = Math.max(30_000L,
        Long.getLong("lc2h.damage.cacheTtlMs", TimeUnit.MINUTES.toMillis(10)));

	    @Unique
	    private static final ThreadLocal<LostCitiesGenerationLocks.LockToken> LC2H_GENERATE_LOCK =
	        ThreadLocal.withInitial(() -> null);

	    @Unique
    private static void releaseGenerateLock() {
        try {
            LostCitiesGenerationLocks.LockToken token = LC2H_GENERATE_LOCK.get();
            if (token != null) {
                token.close();
            }
	        } catch (Throwable ignored) {
        } finally {
            LC2H_GENERATE_LOCK.remove();
        }
    }

	    @Inject(method = "generate(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/chunk/ChunkAccess;)V", at = @At("HEAD"), cancellable = true, remap = false)
	    private void lc2h$warmupGeneration(WorldGenRegion region, ChunkAccess chunk, CallbackInfo ci) {
	        LostCityTerrainFeature self = (LostCityTerrainFeature) (Object) this;
        if (self.provider.getWorld() == null) return;
        ChunkCoord coord = new ChunkCoord(self.provider.getType(), chunk.getPos().x, chunk.getPos().z);

	        long now = System.currentTimeMillis();
	        if (LostCityTerrainFeatureGuards.isGeneratedRecently(coord, now)) {
	            if (LostCityTerrainFeatureGuards.TRACE_GENERATE) {
	                org.admany.lc2h.LC2H.LOGGER.debug("[LC2H] LostCityTerrainFeature.generate skipped (already generated) coord={} thread={}", coord, Thread.currentThread().getName());
	            }
	            ChunkGenTracker.recordGenerateSkip(coord, "already-generated");
	            releaseGenerateLock();
	            ci.cancel();
	            return;
	        }
	        Long inFlight = LostCityTerrainFeatureGuards.IN_FLIGHT_GENERATE_MS.putIfAbsent(coord, now);
	        if (inFlight != null) {
	            if ((now - inFlight) < LostCityTerrainFeatureGuards.GENERATE_GUARD_MS) {
	                if (LostCityTerrainFeatureGuards.TRACE_GENERATE) {
	                    org.admany.lc2h.LC2H.LOGGER.debug("[LC2H] LostCityTerrainFeature.generate skipped (in-flight) coord={} thread={}", coord, Thread.currentThread().getName());
	                }
	                ChunkGenTracker.recordGenerateSkip(coord, "in-flight");
	                releaseGenerateLock();
	                ci.cancel();
	                return;
	            }
	            LostCityTerrainFeatureGuards.IN_FLIGHT_GENERATE_MS.put(coord, now);
	        }
	        Long last = LostCityTerrainFeatureGuards.getLastSuccess(coord, now);
	        if (last != null && (now - last) < LostCityTerrainFeatureGuards.GENERATE_GUARD_MS) {
	            LostCityTerrainFeatureGuards.IN_FLIGHT_GENERATE_MS.remove(coord);
	            if (LostCityTerrainFeatureGuards.TRACE_GENERATE) {
	                org.admany.lc2h.LC2H.LOGGER.debug("[LC2H] LostCityTerrainFeature.generate skipped (recent) coord={} thread={}", coord, Thread.currentThread().getName());
	            }
	            ChunkGenTracker.recordGenerateSkip(coord, "recent");
	            releaseGenerateLock();
	            ci.cancel();
	            return;
	        }
	        if (LostCityTerrainFeatureGuards.TRACE_GENERATE) {
            org.admany.lc2h.LC2H.LOGGER.debug("[LC2H] LostCityTerrainFeature.generate begin coord={} thread={}", coord, Thread.currentThread().getName());
	        }

        try {
            if (region != null && chunk != null) {
                var token = LostCitiesGenerationLocks.acquireChunkStripeLock(((ServerLevelAccessor) region).getLevel().dimension(),
                    chunk.getPos().x, chunk.getPos().z);
                LC2H_GENERATE_LOCK.set(token);
            }
        } catch (Throwable ignored) {
        }

        ChunkGenTracker.recordGenerateStart(coord);

        if (!AsyncChunkWarmup.isPreScheduled(coord)) {
            AsyncChunkWarmup.preSchedule(self.provider, coord);
        }
    }

	    @Inject(method = "generate(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/chunk/ChunkAccess;)V", at = @At("RETURN"), remap = false)
	    private void lc2h$markGenerated(WorldGenRegion region, ChunkAccess chunk, CallbackInfo ci) {
	        LostCityTerrainFeature self = (LostCityTerrainFeature) (Object) this;
	        if (self.provider.getWorld() == null) return;
	        ChunkCoord coord = new ChunkCoord(self.provider.getType(), chunk.getPos().x, chunk.getPos().z);
	        LostCityTerrainFeatureGuards.IN_FLIGHT_GENERATE_MS.remove(coord);
	        LostCityTerrainFeatureGuards.markGenerated(coord, System.currentTimeMillis());
	        if (LostCityTerrainFeatureGuards.TRACE_GENERATE) {
	            org.admany.lc2h.LC2H.LOGGER.debug("[LC2H] LostCityTerrainFeature.generate end coord={} thread={}", coord, Thread.currentThread().getName());
	        }

        ChunkGenTracker.recordGenerateEnd(coord);
        releaseGenerateLock();
	    }

	    @Inject(method = "breakBlocksForDamageNew", at = @At("HEAD"), remap = false)
	    private void lc2h$initDeterministicDamageSeed(int chunkX, int chunkZ, BuildingInfo info, CallbackInfo ci) {
	        long seed = 0L;
	        try {
	            if (provider != null) {
	                seed = provider.getSeed();
	                if (provider.getType() != null) {
	                    seed ^= (long) provider.getType().location().hashCode() * 0x9E3779B97F4A7C15L;
	                }
	            }
	        } catch (Throwable ignored) {
	        }
	        if (info != null && info.multiBuildingPos != null && info.multiBuildingPos.isMulti() && info.coord != null) {
	            ChunkCoord topLeft = info.coord.offset(-info.multiBuildingPos.x(), -info.multiBuildingPos.z());
	            seed ^= ((long) topLeft.chunkX() * 73471L) ^ ((long) topLeft.chunkZ() * 91283L);
        } else {
            seed ^= ((long) chunkX * 73471L) ^ ((long) chunkZ * 91283L);
        }
        LC2H_DAMAGE_SEED.set(seed);
        List<Explosion> sharedExplosions = lc2h$getSharedExplosions(info);
        List<Explosion> baseExplosions = sharedExplosions;
        if (baseExplosions == null && info != null) {
            try {
                baseExplosions = info.getDamageArea().getExplosions();
            } catch (Throwable ignored) {
            }
        }
        List<double[]> paths = lc2h$buildMeteorPaths(baseExplosions, seed);
        LC2H_DAMAGE_CONTEXT_POS.set(new int[] { chunkX, chunkZ });
        LC2H_DAMAGE_CONTEXT_PATHS.set(paths);
    }

    @Inject(method = "breakBlocksForDamageNew", at = @At("RETURN"), remap = false)
    private void lc2h$clearDeterministicDamageSeed(int chunkX, int chunkZ, BuildingInfo info, CallbackInfo ci) {
        LC2H_DAMAGE_SEED.remove();
        LC2H_DAMAGE_CONTEXT_POS.remove();
        LC2H_DAMAGE_CONTEXT_PATHS.remove();
    }

    @Redirect(
        method = "breakBlocksForDamageNew",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/lost/DamageArea;hasExplosions(I)Z"
        ),
        remap = false
    )
    private boolean lc2h$sharedHasExplosions(DamageArea area, int y) {
        int[] pos = LC2H_DAMAGE_CONTEXT_POS.get();
        List<double[]> paths = LC2H_DAMAGE_CONTEXT_PATHS.get();
        if (pos == null || paths == null) {
            return area.hasExplosions(y);
        }
        return lc2h$hasExplosions(paths, pos[0], pos[1], y);
    }

    @Redirect(
        method = "breakBlocksForDamageNew",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/lost/DamageArea;getDamage(III)F"
        ),
        remap = false
    )
    private float lc2h$sharedGetDamage(DamageArea area, int x, int y, int z) {
        List<double[]> paths = LC2H_DAMAGE_CONTEXT_PATHS.get();
        if (paths == null) {
            return area.getDamage(x, y, z);
        }
        return lc2h$getDamage(paths, x, y, z);
    }

    @Redirect(
        method = "breakBlocksForDamageNew",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/lost/DamageArea;damageBlock(Lnet/minecraft/world/level/block/state/BlockState;Lmcjty/lostcities/worldgen/IDimensionInfo;IFLmcjty/lostcities/worldgen/lost/cityassets/CompiledPalette;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"
        ),
	        remap = false
	    )
	    private BlockState lc2h$deterministicDamageBlock(DamageArea area,
	                                                     BlockState state,
	                                                     IDimensionInfo provider,
	                                                     int y,
	                                                     float damage,
	                                                     CompiledPalette palette,
	                                                     BlockState liquidState) {
	        if (state == null) {
	            return state;
	        }
	        if (Tools.hasTag(state.getBlock(), LostTags.NOT_BREAKABLE_TAG)) {
	            return state;
	        }

	        if (Tools.hasTag(state.getBlock(), LostTags.EASY_BREAKABLE_TAG)) {
	            damage *= 2.5f;
	        }

	        int x = 0;
	        int z = 0;
	        try {
	            if (driver != null) {
	                x = driver.getX();
	                z = driver.getZ();
	            }
	        } catch (Throwable ignored) {
	        }

	        long seed = 0L;
	        Long ctxSeed = LC2H_DAMAGE_SEED.get();
	        if (ctxSeed != null) {
	            seed = ctxSeed;
	        } else if (provider != null) {
	            try {
	                seed = provider.getSeed();
	            } catch (Throwable ignored) {
	            }
	        }

	        float roll = lc2h$randFloat(seed, x, y, z, 0);
	        if (roll > damage) {
	            return state;
	        }

	        BlockState damaged = palette.canBeDamagedToIronBars(state);
	        int waterLevel = 0;
	        try {
	            if (provider != null) {
	                waterLevel = Tools.getSeaLevel(provider.getWorld());
	            }
	        } catch (Throwable ignored) {
	        }

	        if (damage < DamageArea.BLOCK_DAMAGE_CHANCE && damaged != null) {
	            float roll2 = lc2h$randFloat(seed, x, y, z, 1);
	            if (roll2 < 0.7f) {
	                return damaged;
	            }
	            return y <= waterLevel ? liquidState : air;
        }

        return y <= waterLevel ? liquidState : air;
    }

    @Unique
    private boolean lc2h$hasExplosions(List<double[]> paths, int chunkX, int chunkZ, int y) {
        if (paths.isEmpty()) {
            return false;
        }
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int minY = y << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        int maxY = minY + 15;
        for (double[] path : paths) {
            if (path == null || path.length < 8) {
                continue;
            }
            double sx = path[0];
            double sy = path[1];
            double sz = path[2];
            double ex = path[3];
            double ey = path[4];
            double ez = path[5];
            double radius = path[6];
            double minPx = Math.min(sx, ex) - radius;
            double minPy = Math.min(sy, ey) - radius;
            double minPz = Math.min(sz, ez) - radius;
            double maxPx = Math.max(sx, ex) + radius;
            double maxPy = Math.max(sy, ey) + radius;
            double maxPz = Math.max(sz, ez) + radius;
            if (maxPx < minX || minPx > maxX || maxPy < minY || minPy > maxY || maxPz < minZ || minPz > maxZ) {
                continue;
            }
            return true;
        }
        return false;
    }

    @Unique
    private float lc2h$getDamage(List<double[]> paths, int x, int y, int z) {
        if (paths.isEmpty()) {
            return 0.0f;
        }
        double px = x + 0.5;
        double py = y + 0.5;
        double pz = z + 0.5;
        for (double[] path : paths) {
            if (path == null || path.length < 8) {
                continue;
            }
            double sx = path[0];
            double sy = path[1];
            double sz = path[2];
            double ex = path[3];
            double ey = path[4];
            double ez = path[5];
            double radiusSq = path[7];
            double vx = ex - sx;
            double vy = ey - sy;
            double vz = ez - sz;
            double denom = vx * vx + vy * vy + vz * vz;
            if (denom <= 1.0e-6) {
                double dx = px - ex;
                double dy = py - ey;
                double dz = pz - ez;
                if ((dx * dx + dy * dy + dz * dz) <= radiusSq) {
                    return 1.0f;
                }
                continue;
            }
            double t = ((px - sx) * vx + (py - sy) * vy + (pz - sz) * vz) / denom;
            if (t < 0.0) {
                t = 0.0;
            } else if (t > 1.0) {
                t = 1.0;
            }
            double cx = sx + t * vx;
            double cy = sy + t * vy;
            double cz = sz + t * vz;
            double dx = px - cx;
            double dy = py - cy;
            double dz = pz - cz;
            if ((dx * dx + dy * dy + dz * dz) <= radiusSq) {
                return 1.0f;
            }
        }
        return 0.0f;
    }

    @Unique
    private List<Explosion> lc2h$getSharedExplosions(BuildingInfo info) {
        if (info == null || info.multiBuildingPos == null || !info.multiBuildingPos.isMulti()) {
            return null;
        }
        if (info.coord == null || info.multiBuilding == null) {
            return null;
        }
        int dimX = info.multiBuilding.getDimX();
        int dimZ = info.multiBuilding.getDimZ();
        if (dimX <= 1 && dimZ <= 1) {
            return null;
        }
        ChunkCoord topLeft = info.coord.offset(-info.multiBuildingPos.x(), -info.multiBuildingPos.z());
        long now = System.currentTimeMillis();
        Long ts = LC2H_SHARED_DAMAGE_TS.get(topLeft);
        List<Explosion> cached = LC2H_SHARED_DAMAGE.get(topLeft);
        if (cached != null && ts != null && (LC2H_DAMAGE_CACHE_TTL_MS <= 0L || (now - ts) <= LC2H_DAMAGE_CACHE_TTL_MS)) {
            return cached;
        }
        List<Explosion> explosions = lc2h$computeSharedExplosions(info, topLeft, dimX, dimZ);
        LC2H_SHARED_DAMAGE.put(topLeft, explosions);
        LC2H_SHARED_DAMAGE_TS.put(topLeft, now);
        return explosions;
    }

    @Unique
    private List<double[]> lc2h$buildMeteorPaths(List<Explosion> explosions, long seed) {
        if (explosions == null || explosions.isEmpty()) {
            return null;
        }
        List<double[]> paths = new ArrayList<>(explosions.size());
        for (Explosion explosion : explosions) {
            if (explosion == null || explosion.getCenter() == null) {
                continue;
            }
            double radius = explosion.getRadius() + 0.5;
            double radiusSq = radius * radius;
            int cx = explosion.getCenter().getX();
            int cy = explosion.getCenter().getY();
            int cz = explosion.getCenter().getZ();

            float yawRand = lc2h$randFloat(seed, cx, cy, cz, 31);
            float pitchRand = lc2h$randFloat(seed, cx, cy, cz, 57);
            double yaw = yawRand * (Math.PI * 2.0);
            double pitch = Math.toRadians(20.0 + pitchRand * 50.0);
            double y = -Math.sin(pitch);
            double horiz = Math.cos(pitch);
            double x = Math.cos(yaw) * horiz;
            double z = Math.sin(yaw) * horiz;

            double len = Math.sqrt(x * x + y * y + z * z);
            if (len < 1.0e-6) {
                x = 0.0;
                y = -1.0;
                z = 0.0;
                len = 1.0;
            }
            x /= len;
            y /= len;
            z /= len;

            double pathLen = Math.max(radius * 3.0, 24.0);
            double sx = cx - x * pathLen;
            double sy = cy - y * pathLen;
            double sz = cz - z * pathLen;

            double[] path = new double[] { sx, sy, sz, cx, cy, cz, radius, radiusSq };
            paths.add(path);
        }
        return paths;
    }

    @Unique
    private List<Explosion> lc2h$computeSharedExplosions(BuildingInfo info, ChunkCoord topLeft, int dimX, int dimZ) {
        if (provider == null || dimX <= 0 || dimZ <= 0) {
            return Collections.emptyList();
        }
        Set<Long> seen = new HashSet<>();
        List<Explosion> result = new ArrayList<>();
        for (int x = 0; x < dimX; x++) {
            for (int z = 0; z < dimZ; z++) {
                ChunkCoord coord = topLeft.offset(x, z);
                DamageArea area = new DamageArea(coord.chunkX(), coord.chunkZ(), provider, info);
                for (Explosion explosion : area.getExplosions()) {
                    long key = lc2h$explosionKey(explosion);
                    if (seen.add(key)) {
                        result.add(explosion);
                    }
                }
            }
        }
        return result;
    }

    @Unique
    private static long lc2h$explosionKey(Explosion explosion) {
        if (explosion == null) {
            return 0L;
        }
        long center = explosion.getCenter().asLong();
        long radius = (long) explosion.getRadius();
        return center ^ (radius * 0x9E3779B97F4A7C15L);
    }

    @Unique
    private static float lc2h$randFloat(long seed, int x, int y, int z, int salt) {
        long h = seed;
        h ^= (long) x * 341873128712L;
        h ^= (long) y * 132897987541L;
	        h ^= (long) z * 42317861L;
	        h ^= (long) salt * 0x9E3779B97F4A7C15L;
	        h = lc2h$mix64(h);
	        int bits = (int) ((h >>> 40) & 0xFFFFFFL);
	        return bits / (float) 0x1000000;
	    }

	    @Unique
	    private static long lc2h$mix64(long z) {
	        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
	        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
	        return z ^ (z >>> 31);
	    }
	}
