package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.cityassets.BuildingPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.admany.lc2h.logging.LCLogger;
import org.admany.lc2h.logging.config.ConfigManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BuildingPart.class, remap = false)
public abstract class MixinBuildingPartSliceCompat {

    @Unique
    private static final java.util.Set<ResourceLocation> LC2H_REPORTED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    @Unique
    private boolean lc2h$cachedEnabled;

    @Unique
    private int lc2h$cachedEffectiveSliceCount = Integer.MIN_VALUE;

    @Shadow @Final private ResourceLocation name;
    @Shadow @Final private String[] slices;

    @Unique
    private int lc2h$effectiveSliceCount() {
        boolean enabled = ConfigManager.ENABLE_LOSTCITIES_PART_SLICE_COMPAT;
        int actual = slices != null ? slices.length : 0;

        if (!enabled) {
            return actual;
        }
        if (lc2h$cachedEffectiveSliceCount != Integer.MIN_VALUE && lc2h$cachedEnabled == enabled) {
            return lc2h$cachedEffectiveSliceCount;
        }
        lc2h$cachedEnabled = enabled;
        if (actual <= 0) {
            lc2h$cachedEffectiveSliceCount = 0;
            return 0;
        }
        if (actual == 6) {
            lc2h$cachedEffectiveSliceCount = 6;
            return 6;
        }
        if (actual < 6) {
            lc2h$cachedEffectiveSliceCount = 6;
            return 6;
        }
        if (actual < 12) {
            lc2h$cachedEffectiveSliceCount = 6;
            return 6;
        }
        if (actual % 6 == 0) {
            boolean repeated = true;
            for (int base = 0; base < 6; base++) {
                String baseSlice = slices[base];
                for (int i = base + 6; i < actual; i += 6) {
                    if (!java.util.Objects.equals(baseSlice, slices[i])) {
                        repeated = false;
                        break;
                    }
                }
                if (!repeated) {
                    break;
                }
            }
            if (repeated) {
                if (name != null && LC2H_REPORTED.add(name)) {
                    LCLogger.warn("[LC2H] [LostCities] Detected repeated 6-slice groups in {}; truncating {} -> 6", name, actual);
                }
                lc2h$cachedEffectiveSliceCount = 6;
                return 6;
            }
            lc2h$cachedEffectiveSliceCount = actual;
            return actual;
        }
        int floored = (actual / 6) * 6;
        lc2h$cachedEffectiveSliceCount = Math.max(6, floored);
        return lc2h$cachedEffectiveSliceCount;
    }

    @Inject(method = "getSliceCount", at = @At("HEAD"), cancellable = true)
    private void lc2h$getSliceCountCompat(CallbackInfoReturnable<Integer> cir) {
        if (!ConfigManager.ENABLE_LOSTCITIES_PART_SLICE_COMPAT) {
            return;
        }
        int effective = lc2h$effectiveSliceCount();
        if (slices != null && effective != slices.length) {
            if (name != null && LC2H_REPORTED.add(name)) {
                LCLogger.warn("[LC2H] [LostCities] Adjusting BuildingPart slices for {}: {} -> {}", name, slices.length, effective);
            }
            cir.setReturnValue(effective);
        }
    }

    @Inject(method = "getC", at = @At("HEAD"), cancellable = true)
    private void lc2h$getCCompat(int x, int slice, int z, CallbackInfoReturnable<Character> cir) {
        if (!ConfigManager.ENABLE_LOSTCITIES_PART_SLICE_COMPAT) {
            return;
        }
        int effective = lc2h$effectiveSliceCount();
        int actual = slices != null ? slices.length : 0;
        if (slice < 0 || slice >= effective || slice >= actual) {
            cir.setReturnValue(' ');
        }
    }

    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private void lc2h$getCompat(BuildingInfo info, int x, int slice, int z, CallbackInfoReturnable<BlockState> cir) {
        if (!ConfigManager.ENABLE_LOSTCITIES_PART_SLICE_COMPAT) {
            return;
        }
        int effective = lc2h$effectiveSliceCount();
        int actual = slices != null ? slices.length : 0;
        if (slice < 0 || slice >= effective || slice >= actual) {
            BlockState air = null;
            if (info != null) {
                try {
                    air = info.getCompiledPalette().get(' ');
                } catch (Throwable ignored) {
                }
            }
            cir.setReturnValue(air != null ? air : Blocks.AIR.defaultBlockState());
        }
    }
}
