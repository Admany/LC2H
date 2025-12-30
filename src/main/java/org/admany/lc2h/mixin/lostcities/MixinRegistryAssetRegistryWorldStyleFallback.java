package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.api.ILostCityAsset;
import mcjty.lostcities.worldgen.lost.regassets.IAsset;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.CommonLevelAccessor;
import org.admany.lc2h.logging.LCLogger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.Function;

@Mixin(value = mcjty.lostcities.worldgen.lost.cityassets.RegistryAssetRegistry.class, remap = false)
public abstract class MixinRegistryAssetRegistryWorldStyleFallback {

    @Unique
    private static final ResourceLocation LC2H_WORLDSTYLES_REGISTRY_ID = new ResourceLocation("lostcities:worldstyles");

    @Unique
    private static final ResourceLocation LC2H_WORLDSTYLE_STANDARD = new ResourceLocation("lostcities:standard");

    @Unique
    private static final ResourceLocation LC2H_WORLDSTYLE_STANDARD_EVERYWHERE = new ResourceLocation("lostcities:standard_everywhere");

    @Unique
    private static final ResourceLocation LC2H_CITYSTYLES_REGISTRY_ID = new ResourceLocation("lostcities:citystyles");

    @Unique
    private static final ResourceLocation LC2H_CITYSTYLE_STANDARD = new ResourceLocation("lostcities:citystyle_standard");

    @Unique
    private static final ResourceLocation LC2H_CITYSTYLE_BORDER = new ResourceLocation("lostcities:citystyle_border");

    @Shadow @Final private Map<ResourceLocation, Object> assets;
    @Shadow @Final private ResourceKey<Registry<Object>> registryKey;
    @Shadow @Final private Function<Object, Object> assetConstructor;

    @Inject(method = "get(Lnet/minecraft/world/level/CommonLevelAccessor;Lnet/minecraft/resources/ResourceLocation;)Lmcjty/lostcities/api/ILostCityAsset;", at = @At("HEAD"), cancellable = true)
    private void lc2h$fallbackMissingWorldStyle(CommonLevelAccessor level, ResourceLocation name, CallbackInfoReturnable<ILostCityAsset> cir) {
        if (level == null || name == null) {
            return;
        }
        ResourceLocation keyId = registryKey.location();
        boolean isWorldStyle = LC2H_WORLDSTYLES_REGISTRY_ID.equals(keyId);
        boolean isCityStyle = LC2H_CITYSTYLES_REGISTRY_ID.equals(keyId);
        if (!isWorldStyle && !isCityStyle) {
            return;
        }
        if (assets.containsKey(name)) {
            return;
        }

        try {
            Registry<Object> registry = level.registryAccess().registryOrThrow(registryKey);
            Object value = registry.get(ResourceKey.create(registryKey, name));
            if (value != null) {
                return;
            }

            ResourceLocation chosen;
            Object fallback;
            if (isWorldStyle) {
                chosen = LC2H_WORLDSTYLE_STANDARD;
                fallback = registry.get(ResourceKey.create(registryKey, chosen));
                if (fallback == null) {
                    chosen = LC2H_WORLDSTYLE_STANDARD_EVERYWHERE;
                    fallback = registry.get(ResourceKey.create(registryKey, chosen));
                }
            } else {
                chosen = LC2H_CITYSTYLE_STANDARD;
                fallback = registry.get(ResourceKey.create(registryKey, chosen));
                if (fallback == null) {
                    chosen = LC2H_CITYSTYLE_BORDER;
                    fallback = registry.get(ResourceKey.create(registryKey, chosen));
                }
            }
            if (fallback == null) {
                LCLogger.error("[LC2H] [LostCities] Missing resource '" + name + "' in registry '" + keyId + "' and also missing fallbacks!");
                return;
            }

            if (fallback instanceof IAsset asset) {
                asset.setRegistryName(chosen);
            }
            Object built = assetConstructor.apply(fallback);
            assets.put(name, built);
            if (built instanceof ILostCityAsset asset) {
                asset.init(level);
            }
            if (isWorldStyle) {
                LCLogger.warn("[LC2H] [LostCities] Worldstyle '{}' not found; falling back to '{}'", name, chosen);
            } else {
                LCLogger.warn("[LC2H] [LostCities] Citystyle '{}' not found; falling back to '{}'", name, chosen);
            }
            cir.setReturnValue((ILostCityAsset) built);
        } catch (Throwable t) {
            LCLogger.error("[LC2H] [LostCities] Failed asset fallback for '" + name + "'", t);
        }
    }
}
