package org.admany.lc2h.mixin.lostcities.config;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.config.ProfileSetup;
import mcjty.lostcities.setup.Config;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.loading.FMLPaths;
import org.admany.lc2h.LC2H;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Mixin(value = Config.class, remap = false)
public class MixinConfig {

    @Shadow
    private static Map<ResourceKey<Level>, String> dimensionProfileCache;

    @Unique
    private static final java.util.concurrent.atomic.AtomicBoolean lc2h$earlyProfileCacheWarning = new java.util.concurrent.atomic.AtomicBoolean(false);

    @Unique
    private static final java.util.concurrent.atomic.AtomicBoolean lc2h$missingSelectedProfileWarning = new java.util.concurrent.atomic.AtomicBoolean(false);

    @Inject(method = "getProfileForDimension", at = @At("HEAD"), cancellable = true)
    private static void lc2h$avoidPoisoningProfileCache(ResourceKey<Level> type, CallbackInfoReturnable<String> cir) {
        if (dimensionProfileCache == null && ProfileSetup.STANDARD_PROFILES.isEmpty()) {
            if (lc2h$earlyProfileCacheWarning.compareAndSet(false, true)) {
                LC2H.LOGGER.info("Lost Cities profiles not initialized yet; deferring profile lookup to avoid disabling worldgen");
            }
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "getProfileForDimension", at = @At("RETURN"), cancellable = true)
    private static void lc2h$validateReturnedProfile(ResourceKey<Level> type, CallbackInfoReturnable<String> cir) {
        String profileName = cir.getReturnValue();
        if (profileName == null || profileName.isBlank()) {
            return;
        }

        // If profiles aren't ready, don't fix anything yet.
        if (ProfileSetup.STANDARD_PROFILES.isEmpty()) {
            return;
        }

        if ("customized".equals(profileName)) {
            try {
                String json = Config.SELECTED_CUSTOM_JSON.get();
                if (json != null && !json.isBlank()) {
                    return;
                }
            } catch (Throwable ignored) {
                // If we can't read it, fall through to validation.
            }
        }

        LostCityProfile profile = ProfileSetup.STANDARD_PROFILES.get(profileName);
        if (profile == null) {
            // Try to load it from disk (config/lostcities/profiles/<name>.json).
            LostCityProfile loaded = lc2h$loadProfileFromDisk(profileName);
            if (loaded != null) {
                ProfileSetup.STANDARD_PROFILES.put(profileName, loaded);
                return;
            }

            // Hard fallback: keep worldgen alive by forcing a known profile.
            String fallback = ProfileSetup.STANDARD_PROFILES.containsKey("default") ? "default" : null;
            if (fallback != null) {
                if (lc2h$missingSelectedProfileWarning.compareAndSet(false, true)) {
                    LC2H.LOGGER.warn(
                        "Lost Cities profile '{}' was requested for dimension '{}' but is missing; forcing '{}' so Lost Cities generation works",
                        profileName, type.location(), fallback
                    );
                }

                try {
                    if (dimensionProfileCache != null) {
                        dimensionProfileCache.put(type, fallback);
                    }
                } catch (Throwable ignored) {
                }

                cir.setReturnValue(fallback);
            }
        }
    }

    @Redirect(
        method = "getProfileForDimension",
        at = @At(value = "FIELD", target = "Lmcjty/lostcities/config/LostCityProfile;GENERATE_NETHER:Z")
    )
    private static boolean lc2h$guardMissingProfile(LostCityProfile profile) {
        if (profile == null) {
            LC2H.LOGGER.warn("Lost Cities profile missing while checking nether generation; defaulting to disabled.");
            return false;
        }
        return profile.GENERATE_NETHER;
    }

    @Redirect(
        method = "getProfileForDimension",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            ordinal = 1,
            remap = false
        )
    )
    private static Object lc2h$validateSelectedOverworldProfile(Map<?, ?> cache, Object key, Object value) {
        if (key == Level.OVERWORLD && value instanceof String selectedProfile && !selectedProfile.isBlank()) {
            boolean allowAsCustomized = false;
            if ("customized".equals(selectedProfile)) {
                try {
                    String json = Config.SELECTED_CUSTOM_JSON.get();
                    allowAsCustomized = json != null && !json.isBlank();
                } catch (Throwable ignored) {
                    allowAsCustomized = false;
                }
            }

            if (!allowAsCustomized) {
                LostCityProfile profile = ProfileSetup.STANDARD_PROFILES.get(selectedProfile);
                if (profile == null) {
                    LostCityProfile loaded = lc2h$loadProfileFromDisk(selectedProfile);
                    if (loaded != null) {
                        ProfileSetup.STANDARD_PROFILES.put(selectedProfile, loaded);
                        profile = loaded;
                    }
                }

                if (profile == null) {
                    String fallback = ProfileSetup.STANDARD_PROFILES.containsKey("default") ? "default" : "";
                    if (lc2h$missingSelectedProfileWarning.compareAndSet(false, true)) {
                        LC2H.LOGGER.warn("Selected Lost Cities profile '{}' was not found; falling back to '{}' to keep worldgen working (check config/lostcities and config/lostcities/profiles)", selectedProfile, fallback);
                    }
                    value = fallback;
                }
            }
        }

        @SuppressWarnings("unchecked")
        Map<Object, Object> raw = (Map<Object, Object>) cache;
        return raw.put(key, value);
    }

    @Redirect(
        method = "getProfileForDimension",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;",
            ordinal = 2,
            remap = false
        )
    )
    private static Object lc2h$loadMissingStandardProfile(Map<?, ?> profiles, Object key) {
        Object existing = profiles.get(key);

        if (existing == null && key instanceof String profileName && !profileName.isBlank()) {
            LostCityProfile loaded = lc2h$loadProfileFromDisk(profileName);
            if (loaded != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, LostCityProfile> cast = (Map<String, LostCityProfile>) profiles;
                    cast.put(profileName, loaded);
                    LC2H.LOGGER.info("Loaded missing Lost Cities profile '{}' from disk to satisfy world generation", profileName);
                    return loaded;
                } catch (ClassCastException ignored) {
                }
            }
            else {
                LC2H.LOGGER.debug("Unable to find Lost Cities profile '{}' on disk during world generation", profileName);
            }
        }

        return existing;
    }

    private static LostCityProfile lc2h$loadProfileFromDisk(String profileName) {
        try {
            Path profilesDir = FMLPaths.CONFIGDIR.get()
                .resolve("lostcities")
                .resolve("profiles");
            Path profilePath = profilesDir.resolve(profileName + ".json");
            if (!Files.exists(profilePath)) {
                return null;
            }
            String json = Files.readString(profilePath);
            if (json.isBlank()) {
                return null;
            }
            return new LostCityProfile(profileName, json);
        } catch (IOException ioe) {
            LC2H.LOGGER.error("Failed to load Lost Cities profile '{}' from {}", profileName, ioe.getMessage());
            return null;
        } catch (Throwable t) {
            LC2H.LOGGER.error("Unexpected error while loading Lost Cities profile '{}': {}", profileName, t.getMessage());
            return null;
        }
    }
}
