package org.admany.lc2h.mixin.mods.dh;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Optional, zero-reflection bridge to DH's authoritative worldgen marker.
 * The target is only transformed when Distant Horizons is installed.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.worldGeneration.BatchGenerationEnvironment_forge", remap = false)
public interface DhBatchGenerationEnvironmentAccessor {

    @Invoker("isThisDhWorldGenThread")
    static boolean lc2h$isThisDhWorldGenThread() {
        throw new AssertionError("Distant Horizons accessor was not transformed");
    }
}
