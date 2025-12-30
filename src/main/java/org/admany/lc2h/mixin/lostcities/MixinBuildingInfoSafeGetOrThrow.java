package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries;
import mcjty.lostcities.worldgen.lost.cityassets.RegistryAssetRegistry;
import net.minecraft.world.level.CommonLevelAccessor;
import mcjty.lostcities.api.ILostCityAsset;
import org.admany.lc2h.LC2H;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Locale;

/**
 * Prevent hard-crashes during BuildingInfo construction when an asset pack references
 * a missing/typo'd building/part id (e.g. *_0_1_1, structurebundel).
 *
 * This does not "fix" the pack, but it avoids partial/duplicated generation caused by
 * repeated retries with different cached state.
 */
@Mixin(value = mcjty.lostcities.worldgen.lost.BuildingInfo.class, remap = false)
public abstract class MixinBuildingInfoSafeGetOrThrow {

    @Redirect(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/lost/cityassets/RegistryAssetRegistry;getOrThrow(Lnet/minecraft/world/level/CommonLevelAccessor;Ljava/lang/String;)Lmcjty/lostcities/api/ILostCityAsset;"
        )
    )
    private ILostCityAsset lc2h$safeGetOrThrow(RegistryAssetRegistry<?, ?> registry, CommonLevelAccessor world, String name) {
        if (registry == null) {
            return null;
        }

        // First try a warn-only lookup to avoid the throw path.
        ILostCityAsset value = (ILostCityAsset) registry.getOrWarn(world, name);
        if (value != null) {
            return value;
        }

        String fixed = normalizeName(name);
        if (fixed != null && !fixed.equals(name)) {
            value = (ILostCityAsset) registry.getOrWarn(world, fixed);
            if (value != null) {
                return value;
            }
        }

        // As a last resort, use a stable fallback asset so BuildingInfo can complete.
        // This prevents worldgen from repeatedly retrying and creating overlapping/partial results.
        try {
            if (registry == AssetRegistries.BUILDINGS) {
                return AssetRegistries.BUILDINGS.getOrWarn(world, "building1");
            }
            if (registry == AssetRegistries.PARTS) {
                // Minimal/no-op-ish part for missing pieces.
                ILostCityAsset part = AssetRegistries.PARTS.getOrWarn(world, "street_none");
                if (part != null) {
                    return part;
                }
                return AssetRegistries.PARTS.getOrWarn(world, "building1_1");
            }
        } catch (Throwable ignored) {
        }

        // Keep behavior consistent: throw if we can't safely recover.
        LC2H.LOGGER.warn("[LC2H] Missing LostCities asset referenced during BuildingInfo init: {}", name);
        throw new RuntimeException("Error getting resource " + (name == null ? "<null>" : name) + "!");
    }

    private static String normalizeName(String name) {
        if (name == null) {
            return null;
        }
        String s = name.trim();
        if (s.endsWith("!")) {
            s = s.substring(0, s.length() - 1);
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.contains("structurebundel")) {
            s = s.replace("structurebundel", "structurebundle");
        }

        String ns = null;
        String path = s;
        int colon = s.indexOf(':');
        if (colon > 0) {
            ns = s.substring(0, colon);
            path = s.substring(colon + 1);
        }

        // Strip trailing _<number> segments up to 3 times.
        String stripped3 = stripTrailingNumbers(path, 3);
        if (!stripped3.equals(path)) {
            return withNs(ns, stripped3);
        }
        String stripped2 = stripTrailingNumbers(path, 2);
        if (!stripped2.equals(path)) {
            return withNs(ns, stripped2);
        }
        String stripped1 = stripTrailingNumbers(path, 1);
        if (!stripped1.equals(path)) {
            return withNs(ns, stripped1);
        }

        return s;
    }

    private static String withNs(String ns, String path) {
        if (path == null) {
            return null;
        }
        return ns == null || ns.isBlank() ? path : ns + ":" + path;
    }

    private static String stripTrailingNumbers(String path, int count) {
        if (path == null || count <= 0) {
            return path;
        }
        String result = path;
        for (int i = 0; i < count; i++) {
            int idx = result.lastIndexOf('_');
            if (idx < 0) {
                return result;
            }
            String tail = result.substring(idx + 1);
            if (tail.isEmpty() || !tail.chars().allMatch(Character::isDigit)) {
                return result;
            }
            result = result.substring(0, idx);
        }
        return result;
    }
}
