package org.admany.lc2h.mixin;

import org.admany.lc2h.runtime.Lc2hRuntimeModes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class Lc2hMixinConfigPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger("LC2H-MixinPlugin");
    private static final Set<String> ALWAYS_ALLOWED = Set.of(
        "org.admany.lc2h.mixin.lostcities.config.MixinConfig",
        "org.admany.lc2h.mixin.lostcities.dimension.MixinDefaultDimensionInfoThreadLocal",
        "org.admany.lc2h.mixin.accessor.minecraft.ServerChunkCacheInvoker"
    );
    private static volatile boolean announced;

    @Override
    public void onLoad(String mixinPackage) {
        if (Lc2hRuntimeModes.baselineMode() && !announced) {
            announced = true;
            LOGGER.warn("[LC2H] Baseline mode active. LC2H gameplay mixins are disabled for A/B world parity export, except profile-resolution support mixins.");
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if ("org.admany.lc2h.mixin.minecraft.worldgen.MixinWorldGenRegionPreCaptureTrace".equals(mixinClassName)
            || "org.admany.lc2h.mixin.minecraft.worldgen.MixinPlacedFeaturePreCaptureTrace".equals(mixinClassName)) {
            return Boolean.parseBoolean(System.getProperty("lc2h.precaptureTrace.enabled", "false"));
        }
        // BuildingInfo is the dominant Lost Cities generation choke point.
        // Its caches are provider-scoped and the remaining construction lock is
        // per chunk, so normal generation no longer shares the upstream global
        // monitor. Keep an explicit startup opt-out for fault isolation.
        if ("org.admany.lc2h.mixin.lostcities.building.MixinBuildingInfo".equals(mixinClassName)) {
            return Boolean.parseBoolean(System.getProperty("lc2h.concurrentBuildingInfo", "true"));
        }
        if (!Lc2hRuntimeModes.baselineMode()) {
            return true;
        }
        return ALWAYS_ALLOWED.contains(mixinClassName);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
