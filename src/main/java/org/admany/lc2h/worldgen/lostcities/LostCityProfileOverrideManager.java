package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.config.ProfileSetup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class LostCityProfileOverrideManager {
    private static final ConcurrentHashMap<ResourceKey<Level>, Override> OVERRIDES = new ConcurrentHashMap<>();
    private static final AtomicLong VERSION = new AtomicLong();

    private LostCityProfileOverrideManager() {
    }

    private record Override(String profileName, long version) {
    }

    public static String setOverride(ResourceKey<Level> dimension, String profileName) {
        String normalized = normalize(profileName);
        if (dimension == null || normalized == null) {
            return null;
        }
        OVERRIDES.put(dimension, new Override(normalized, VERSION.incrementAndGet()));
        return normalized;
    }

    public static Optional<String> overrideName(ResourceKey<Level> dimension) {
        Override override = dimension == null ? null : OVERRIDES.get(dimension);
        return override == null ? Optional.empty() : Optional.of(override.profileName());
    }

    public static LostCityProfile resolveProfile(WorldGenLevel world, LostCityProfile fallback) {
        if (world == null) {
            return fallback;
        }
        return resolveProfile(world.getLevel().dimension(), fallback);
    }

    public static LostCityProfile resolveProfile(ResourceKey<Level> dimension, LostCityProfile fallback) {
        Override override = dimension == null ? null : OVERRIDES.get(dimension);
        if (override == null) {
            return fallback;
        }
        LostCityProfile profile = ProfileSetup.STANDARD_PROFILES.get(override.profileName());
        return profile != null ? profile : fallback;
    }

    public static String profileToken(WorldGenLevel world, LostCityProfile effectiveProfile) {
        ResourceKey<Level> dimension = world == null ? null : world.getLevel().dimension();
        Override override = dimension == null ? null : OVERRIDES.get(dimension);
        String name = effectiveProfile == null ? "<null>" : effectiveProfile.getName();
        if (override == null) {
            return "base:" + name;
        }
        return "override:" + override.profileName() + ":" + override.version() + ":" + name;
    }

    public static boolean hasKnownProfile(String profileName) {
        String normalized = normalize(profileName);
        return normalized != null && ProfileSetup.STANDARD_PROFILES.containsKey(normalized);
    }

    public static Optional<LostCityProfile> loadProfileFromDisk(String profileName, Collection<Path> profileDirs) {
        String normalized = normalize(profileName);
        if (normalized == null || profileDirs == null) {
            return Optional.empty();
        }

        for (Path dir : profileDirs) {
            if (dir == null) {
                continue;
            }
            Path profilePath = dir.resolve(normalized + ".json");
            if (!Files.isRegularFile(profilePath)) {
                continue;
            }
            try {
                String json = Files.readString(profilePath);
                if (!json.isBlank()) {
                    return Optional.of(new LostCityProfile(normalized, json));
                }
            } catch (IOException ignored) {
                return Optional.empty();
            }
        }

        return Optional.empty();
    }

    public static Set<String> discoverProfileNames(Collection<Path> profileDirs) {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(ProfileSetup.STANDARD_PROFILES.keySet());

        if (profileDirs != null) {
            for (Path dir : profileDirs) {
                if (dir == null || !Files.isDirectory(dir)) {
                    continue;
                }
                try (var stream = Files.list(dir)) {
                    stream
                        .filter(Files::isRegularFile)
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".json"))
                        .map(name -> name.substring(0, name.length() - ".json".length()))
                        .filter(name -> !name.isBlank())
                        .forEach(names::add);
                } catch (IOException ignored) {
                    // Suggestions should never fail the command tree.
                }
            }
        }

        return names;
    }

    private static String normalize(String profileName) {
        if (profileName == null) {
            return null;
        }
        String trimmed = profileName.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
