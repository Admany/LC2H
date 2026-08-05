package org.admany.lc2h.mixin.minecraft.worldgen;

import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.admany.lc2h.worldgen.CityDensityTransform;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies LC2H's city/mountain transition inside Minecraft's density context.
 *
 * <p>The shifted coordinate is deliberately scoped to NoiseChunk's density
 * cache population. Aquifer consumes the resulting density later, but must see
 * the real block coordinate because its internal arrays are indexed against
 * the unshifted noise-cell bounds. Globally changing {@link #blockY()} breaks
 * that invariant and can index one element past the aquifer cache.</p>
 *
 * <p>Within the scope, changing the context Y preserves the original
 * NoiseRouter function. This is fundamentally different from feeding Blender
 * a flat target height: the native density graph is evaluated at a smoothly
 * shifted vertical coordinate and therefore keeps the real mountain shape.</p>
 */
@Mixin(NoiseChunk.class)
public abstract class MixinNoiseChunkCityDensityTransform {

    @Shadow
    @Final
    private Blender blender;

    /**
     * A depth counter rather than a boolean keeps the scope correct if Mojang
     * ever nests one density-cache population method inside the other.
     */
    @Unique
    private int lc2h$densitySamplingDepth;

    @Inject(method = "fillSlice", at = @At("HEAD"))
    private void lc2h$beginSliceDensitySampling(boolean firstSlice, int startCellX, CallbackInfo ci) {
        this.lc2h$densitySamplingDepth++;
    }

    @Inject(method = "fillSlice", at = @At("RETURN"))
    private void lc2h$endSliceDensitySampling(boolean firstSlice, int startCellX, CallbackInfo ci) {
        this.lc2h$densitySamplingDepth = Math.max(0, this.lc2h$densitySamplingDepth - 1);
    }

    @Inject(method = "selectCellYZ", at = @At("HEAD"))
    private void lc2h$beginCellDensitySampling(int cellY, int cellZ, CallbackInfo ci) {
        this.lc2h$densitySamplingDepth++;
    }

    @Inject(method = "selectCellYZ", at = @At("RETURN"))
    private void lc2h$endCellDensitySampling(int cellY, int cellZ, CallbackInfo ci) {
        this.lc2h$densitySamplingDepth = Math.max(0, this.lc2h$densitySamplingDepth - 1);
    }

    @Inject(method = "blockY", at = @At("RETURN"), cancellable = true)
    private void lc2h$shiftNativeDensityY(CallbackInfoReturnable<Integer> cir) {
        if (this.lc2h$densitySamplingDepth <= 0
            || !(this.blender instanceof CityDensityTransform transform)
            || !transform.lc2h$isDensityTransformActive()) {
            return;
        }
        NoiseChunk self = (NoiseChunk) (Object) this;
        double shift = transform.lc2h$verticalDensityShift(self.blockX(), self.blockZ());
        if (shift <= 0.0D) {
            return;
        }
        cir.setReturnValue(cir.getReturnValueI() + (int) Math.round(shift));
    }
}
