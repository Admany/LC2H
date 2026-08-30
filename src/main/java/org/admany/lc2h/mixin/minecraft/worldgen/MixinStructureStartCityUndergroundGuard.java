package org.admany.lc2h.mixin.minecraft.worldgen;

import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.dev.diagnostics.CriticalMixinHookValidator;
import org.admany.lc2h.worldgen.lostcities.CityStructureProtection;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

@Mixin(StructureStart.class)
public class MixinStructureStartCityUndergroundGuard {
    /** Cache one decision for the whole start; weak keys follow Minecraft's lifetime. */
    private static final Map<StructureStart, CityStructureProtection.StructureDecision> LC2H_DECISIONS =
        Collections.synchronizedMap(new WeakHashMap<>());

    @Inject(method = "placeInChunk", at = @At("HEAD"), cancellable = true)
    private void lc2h$skipStructuresNearCityGround(WorldGenLevel level,
                                                   StructureManager structureManager,
                                                   ChunkGenerator generator,
                                                   RandomSource random,
                                                   BoundingBox box,
                                                   ChunkPos chunkPos,
                                                   CallbackInfo ci) {
        CriticalMixinHookValidator.markObserved(CriticalMixinHookValidator.STRUCTURE_START_CITY_GUARD);
        if (level == null || chunkPos == null) {
            return;
        }

        IDimensionInfo dimInfo;
        try {
            dimInfo = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(level);
        } catch (Throwable ignored) {
            return;
        }
        if (dimInfo == null) {
            return;
        }

        StructureStart start = (StructureStart) (Object) this;
        CityStructureProtection.StructureDecision decision;
        synchronized (LC2H_DECISIONS) {
            decision = LC2H_DECISIONS.get(start);
            if (decision == null) {
                decision = CityStructureProtection.inspectStructureBox(
                    dimInfo,
                    dimInfo.getType(),
                    start.getBoundingBox(),
                    ConfigManager.CITY_STRUCTURE_REJECTION_BUFFER_CHUNKS);
                /*
                 * Keep an unresolved result for the whole start.  Rechecking
                 * each touched chunk can change unknown into reject halfway
                 * through placement, leaving a multichunk structure cut off.
                 * Allowing one unresolved start is preferable to writing a
                 * partial structure and is consistent for every chunk.
                 */
                LC2H_DECISIONS.put(start, decision);
            }
        }
        if (decision.reject()) {
            ci.cancel();
        }
    }

}
