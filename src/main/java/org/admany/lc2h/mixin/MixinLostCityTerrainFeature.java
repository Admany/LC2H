package org.admany.lc2h.mixin;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
	import net.minecraft.server.level.WorldGenRegion;
	import net.minecraft.world.level.chunk.ChunkAccess;
	
	import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;
	import org.admany.lc2h.worldgen.lostcities.LostCitiesGenerationLocks;
	import org.admany.lc2h.worldgen.lostcities.LostCityTerrainFeatureGuards;
	import org.spongepowered.asm.mixin.Mixin;
	import org.spongepowered.asm.mixin.injection.At;
	import org.spongepowered.asm.mixin.injection.Inject;
	import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
	import org.spongepowered.asm.mixin.Unique;

	@Mixin(value = LostCityTerrainFeature.class, remap = false)
	public class MixinLostCityTerrainFeature {

	    @Unique
	    private static final ThreadLocal<LostCitiesGenerationLocks.LockToken> LC2H_GENERATE_LOCK =
	        ThreadLocal.withInitial(() -> null);

	    @Inject(method = "generate(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/chunk/ChunkAccess;)V", at = @At("HEAD"), cancellable = true, remap = false)
	    private void lc2h$warmupGeneration(WorldGenRegion region, ChunkAccess chunk, CallbackInfo ci) {
	        LostCityTerrainFeature self = (LostCityTerrainFeature) (Object) this;
        if (self.provider.getWorld() == null) return;
        ChunkCoord coord = new ChunkCoord(self.provider.getType(), chunk.getPos().x, chunk.getPos().z);
        try {
            if (region != null && chunk != null) {
                var token = LostCitiesGenerationLocks.acquireChunkStripeLock(region.getLevel().dimension(), chunk.getPos().x, chunk.getPos().z);
                LC2H_GENERATE_LOCK.set(token);
            }
        } catch (Throwable ignored) {
        }

	        if (LostCityTerrainFeatureGuards.GENERATED_CHUNKS.containsKey(coord)) {
	            if (LostCityTerrainFeatureGuards.TRACE_GENERATE) {
	                org.admany.lc2h.LC2H.LOGGER.info("[LC2H] LostCityTerrainFeature.generate skipped (already generated) coord={} thread={}", coord, Thread.currentThread().getName());
	            }
	            ci.cancel();
	            return;
	        }
	        long now = System.currentTimeMillis();
	        Long inFlight = LostCityTerrainFeatureGuards.IN_FLIGHT_GENERATE_MS.putIfAbsent(coord, now);
	        if (inFlight != null) {
	            if ((now - inFlight) < LostCityTerrainFeatureGuards.GENERATE_GUARD_MS) {
	                if (LostCityTerrainFeatureGuards.TRACE_GENERATE) {
	                    org.admany.lc2h.LC2H.LOGGER.info("[LC2H] LostCityTerrainFeature.generate skipped (in-flight) coord={} thread={}", coord, Thread.currentThread().getName());
	                }
	                ci.cancel();
	                return;
	            }
	            LostCityTerrainFeatureGuards.IN_FLIGHT_GENERATE_MS.put(coord, now);
	        }
	        Long last = LostCityTerrainFeatureGuards.LAST_SUCCESSFUL_GENERATE_MS.get(coord);
	        if (last != null && (now - last) < LostCityTerrainFeatureGuards.GENERATE_GUARD_MS) {
	            LostCityTerrainFeatureGuards.IN_FLIGHT_GENERATE_MS.remove(coord);
	            if (LostCityTerrainFeatureGuards.TRACE_GENERATE) {
	                org.admany.lc2h.LC2H.LOGGER.info("[LC2H] LostCityTerrainFeature.generate skipped (recent) coord={} thread={}", coord, Thread.currentThread().getName());
	            }
	            ci.cancel();
	            return;
	        }
	        if (LostCityTerrainFeatureGuards.TRACE_GENERATE) {
	            org.admany.lc2h.LC2H.LOGGER.info("[LC2H] LostCityTerrainFeature.generate begin coord={} thread={}", coord, Thread.currentThread().getName());
	        }

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
	        LostCityTerrainFeatureGuards.GENERATED_CHUNKS.put(coord, Boolean.TRUE);
	        LostCityTerrainFeatureGuards.LAST_SUCCESSFUL_GENERATE_MS.put(coord, System.currentTimeMillis());
	        if (LostCityTerrainFeatureGuards.TRACE_GENERATE) {
	            org.admany.lc2h.LC2H.LOGGER.info("[LC2H] LostCityTerrainFeature.generate end coord={} thread={}", coord, Thread.currentThread().getName());
	        }

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
	}
