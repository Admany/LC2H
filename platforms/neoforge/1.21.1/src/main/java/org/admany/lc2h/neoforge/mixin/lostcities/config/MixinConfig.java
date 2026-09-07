package org.admany.lc2h.neoforge.mixin.lostcities.config;

import mcjty.lostcities.LostCities;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.config.ProfileSetup;
import mcjty.lostcities.data.LostData;
import mcjty.lostcities.setup.Config;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.fml.loading.FMLPaths;
import org.admany.lc2h.neoforge.LC2H;
import org.admany.lc2h.neoforge.worldgen.lostcities.LostCityProfileOverrideManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps NeoForge profile selection and missing-profile recovery in step with the Forge build. */
@Mixin(value = Config.class, remap = false)
public final class MixinConfig {
    @Shadow
    private static Map<ResourceKey<Level>, String> dimensionProfileCache;

    @Shadow
    private static ModConfigSpec.ConfigValue<List<? extends String>> DIMENSION_PROFILES;

    @Unique
    private static final Object lc2h$profileLock = new Object();
    @Unique
    private static final AtomicBoolean lc2h$missingProfileWarning = new AtomicBoolean();

    @Inject(method = "getProfileForDimension", at = @At("HEAD"), cancellable = true)
    private static void lc2h$resolveProfile(ServerLevel level,
                                             ResourceKey<Level> dimension,
                                             CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(lc2h$resolveProfileName(level, dimension));
    }

    @Unique
    private static String lc2h$resolveProfileName(ServerLevel level, ResourceKey<Level> dimension) {
        if (dimension == null) {
            return null;
        }
        synchronized (lc2h$profileLock) {
            Map<ResourceKey<Level>, String> cache = lc2h$ensureProfileCache(level);
            String forced = LostCityProfileOverrideManager.overrideName(dimension).orElse(null);
            if (forced != null) {
                String selected = lc2h$validateProfile(dimension, forced);
                if (selected != null) {
                    cache.put(dimension, selected);
                    return selected;
                }
            }

            String selected = cache.get(dimension);
            if (selected != null && !selected.isBlank()) {
                String valid = lc2h$validateProfile(dimension, selected);
                if (valid != null) {
                    cache.put(dimension, valid);
                    return valid;
                }
            }

            if (dimension == Level.OVERWORLD && level != null) {
                String worldProfile = lc2h$worldProfile(level);
                String valid = lc2h$validateProfile(dimension, worldProfile);
                if (valid != null) {
                    cache.put(dimension, valid);
                    return valid;
                }
            }

            if (dimension == Level.NETHER) {
                String overworld = cache.get(Level.OVERWORLD);
                LostCityProfile profile = lc2h$resolveStandardProfile(overworld);
                if (profile != null && profile.GENERATE_NETHER) {
                    cache.put(dimension, "cavern");
                    return "cavern";
                }
            }
            return null;
        }
    }

    @Unique
    private static Map<ResourceKey<Level>, String> lc2h$ensureProfileCache(ServerLevel level) {
        if (dimensionProfileCache != null && !dimensionProfileCache.isEmpty()) {
            return dimensionProfileCache;
        }
        Map<ResourceKey<Level>, String> rebuilt = new ConcurrentHashMap<>();
        dimensionProfileCache = rebuilt;
        try {
            List<? extends String> configured = DIMENSION_PROFILES.get();
            if (configured != null) {
                for (String entry : configured) {
                    if (entry == null || entry.isBlank()) {
                        continue;
                    }
                    String[] split = entry.split("=", 2);
                    if (split.length != 2) {
                        LostCities.getLogger().error("Bad format for config value: '{}'!", entry);
                        continue;
                    }
                    ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                        ResourceLocation.parse(split[0]));
                    String profile = lc2h$validateProfile(key, split[1]);
                    if (profile != null) {
                        rebuilt.put(key, profile);
                    }
                }
            }
        } catch (Throwable failure) {
            LC2H.LOGGER.debug("Could not read Lost Cities dimension profiles", failure);
        }

        if (level != null) {
            String selected = lc2h$worldProfile(level);
            String valid = lc2h$validateProfile(Level.OVERWORLD, selected);
            if (valid != null) {
                rebuilt.put(Level.OVERWORLD, valid);
            }
        }
        return rebuilt;
    }

    @Unique
    private static String lc2h$worldProfile(ServerLevel level) {
        try {
            LostData data = LostData.getData(level);
            String profile = data.getSelectedProfile();
            String json = data.getSelectedJson();
            if (Config.profileFromClient != null && !Config.profileFromClient.isBlank()) {
                profile = Config.profileFromClient;
                json = Config.jsonFromClient;
                data.setProfile(profile, json == null ? "" : json);
            }
            if (profile == null || profile.isBlank()) {
                profile = Config.SELECTED_PROFILE.get();
            }
            if (json == null || json.isBlank()) {
                json = Config.SELECTED_CUSTOM_JSON.get();
            }
            if (json != null && !json.isBlank()) {
                LostCityProfile customized = new LostCityProfile("customized", json);
                ProfileSetup.STANDARD_PROFILES.computeIfAbsent("customized",
                    ignored -> new LostCityProfile("customized", false)).copyFrom(customized);
            }
            return profile;
        } catch (Throwable failure) {
            return null;
        }
    }

    @Unique
    private static String lc2h$validateProfile(ResourceKey<Level> dimension, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if ("customized".equals(name)) {
            try {
                String json = Config.SELECTED_CUSTOM_JSON.get();
                if (json != null && !json.isBlank()) {
                    return name;
                }
            } catch (Throwable ignored) {
            }
        }
        if (lc2h$resolveStandardProfile(name) != null) {
            return name;
        }
        if (ProfileSetup.STANDARD_PROFILES.containsKey("default")) {
            if (lc2h$missingProfileWarning.compareAndSet(false, true)) {
                LC2H.LOGGER.warn("Lost Cities profile '{}' was not found for {}; using 'default'",
                    name, dimension.location());
            }
            return "default";
        }
        return null;
    }

    @Unique
    private static LostCityProfile lc2h$resolveStandardProfile(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        LostCityProfile profile = ProfileSetup.STANDARD_PROFILES.get(name);
        if (profile != null) {
            return profile;
        }
        try {
            Path path = FMLPaths.CONFIGDIR.get().resolve("lostcities").resolve("profiles").resolve(name + ".json");
            if (Files.isRegularFile(path)) {
                String json = Files.readString(path);
                if (!json.isBlank()) {
                    profile = new LostCityProfile(name, json);
                    ProfileSetup.STANDARD_PROFILES.putIfAbsent(name, profile);
                    return profile;
                }
            }
        } catch (IOException ignored) {
        } catch (Throwable failure) {
            LC2H.LOGGER.debug("Could not load Lost Cities profile '{}'", name, failure);
        }
        return null;
    }
}
