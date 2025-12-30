package org.admany.lc2h.worldgen.async.generator;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class AsyncPaletteGenerator {

    private static final ConcurrentHashMap<ChunkCoord, Boolean> COMPUTATION_CACHE = new ConcurrentHashMap<>();

    public static final ConcurrentHashMap<ChunkCoord, float[]> GPU_DATA_CACHE = new ConcurrentHashMap<>();

    private AsyncPaletteGenerator() {
    }

    public static void preSchedule(IDimensionInfo provider, ChunkCoord coord) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coord, "coord");

        if (COMPUTATION_CACHE.putIfAbsent(coord, Boolean.TRUE) != null) {
            return;
        }

        boolean debugLogging = AsyncChunkWarmup.isWarmupDebugLoggingEnabled();

        if (GPU_DATA_CACHE.containsKey(coord)) {
            if (debugLogging) {
                LC2H.LOGGER.debug("Used GPU data for palette generation in {}", coord);
            }
            return;
        }

        if (debugLogging) {
            LC2H.LOGGER.debug("Starting preSchedule for {}", coord);
        }
        long startTime = System.nanoTime();

        try {
            computePaletteVariations(coord, provider);
            long endTime = System.nanoTime();
            if (debugLogging) {
                LC2H.LOGGER.debug("Finished preSchedule for {} in {} ms", coord, (endTime - startTime) / 1_000_000);
            }
        } catch (Throwable t) {
            LC2H.LOGGER.error("Synchronous palette generation failed for {}: {}", coord, t.getMessage());
            throw t;
        }
    }

    private static void computePaletteVariations(ChunkCoord coord, IDimensionInfo provider) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                String palette = determinePalette(coord, x, z, provider);
                cachePalette(coord, x, z, palette);
            }
        }

        computeBuildingPalettes(coord, provider);
        computeStreetPalettes(coord, provider);
        computeAddonPalettes(coord, provider);
    }

    private static void computeBuildingPalettes(ChunkCoord coord, IDimensionInfo provider) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (isBuildingPosition(coord, x, z, provider)) {
                    String buildingPalette = determineBuildingPalette(coord, x, z, provider);
                    cacheBuildingPalette(coord, x, z, buildingPalette);
                }
            }
        }
    }

    private static void computeStreetPalettes(ChunkCoord coord, IDimensionInfo provider) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (isStreetPosition(coord, x, z, provider)) {
                    String streetPalette = determineStreetPalette(coord, x, z, provider);
                    cacheStreetPalette(coord, x, z, streetPalette);
                }
            }
        }
    }

    private static void computeAddonPalettes(ChunkCoord coord, IDimensionInfo provider) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                String addonPalette = determineAddonPalette(coord, x, z, provider);
                if (addonPalette != null) {
                    cacheAddonPalette(coord, x, z, addonPalette);
                }
            }
        }
    }

    private static String determinePalette(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        String addonPalette = determineAddonPalette(coord, x, z, provider);
        if (addonPalette != null) {
            return addonPalette;
        }

        if (isBuildingPosition(coord, x, z, provider)) {
            return determineBuildingPalette(coord, x, z, provider);
        }

        if (isStreetPosition(coord, x, z, provider)) {
            return determineStreetPalette(coord, x, z, provider);
        }

        return "default_terrain";
    }

    private static String determineBuildingPalette(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        return "czp_building";
    }

    private static String determineStreetPalette(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        return "stone_street";
    }

    private static String determineAddonPalette(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        try {
            Object profile = getProfile(provider);
            if (profile != null) {
                String paletteName = determinePaletteViaReflection(coord, x, z, provider, profile);
                if (paletteName != null && !paletteName.isEmpty()) {
                    return paletteName;
                }
            }
        } catch (Throwable t) {
        }
        return null;
    }

    private static String determinePaletteViaReflection(ChunkCoord coord, int x, int z, IDimensionInfo provider, Object profile) {
        try {
            String directPalette = getPaletteFromProfile(profile, coord, x, z);
            if (directPalette != null) {
                return directPalette;
            }

            if (isBuildingPosition(coord, x, z, provider)) {
                return getBuildingPaletteFromProfile(profile, coord, x, z);
            } else if (isStreetPosition(coord, x, z, provider)) {
                return getStreetPaletteFromProfile(profile, coord, x, z);
            } else {
                return getTerrainPaletteFromProfile(profile, coord, x, z);
            }

        } catch (Throwable t) {
            return null;
        }
    }

    private static String getPaletteFromProfile(Object profile, ChunkCoord coord, int x, int z) {
        try {
            Class<?> profileClass = profile.getClass();

            try {
                Object palette = profileClass.getMethod("getPalette", int.class, int.class).invoke(profile, coord.chunkX() * 16 + x, coord.chunkZ() * 16 + z);
                if (palette instanceof String) {
                    return (String) palette;
                }
            } catch (NoSuchMethodException ignored) {}

            try {
                Object palette = profileClass.getMethod("getAddonPalette", int.class, int.class).invoke(profile, coord.chunkX() * 16 + x, coord.chunkZ() * 16 + z);
                if (palette instanceof String) {
                    return (String) palette;
                }
            } catch (NoSuchMethodException ignored) {}

            try {
                Object palette = profileClass.getField("palette").get(profile);
                if (palette instanceof String) {
                    return (String) palette;
                }
            } catch (NoSuchFieldException ignored) {}

        } catch (Throwable t) {
        }
        return null;
    }

    private static String getBuildingPaletteFromProfile(Object profile, ChunkCoord coord, int x, int z) {
        try {
            Class<?> profileClass = profile.getClass();

            try {
                Object palette = profileClass.getMethod("getBuildingPalette", int.class, int.class).invoke(profile, coord.chunkX() * 16 + x, coord.chunkZ() * 16 + z);
                if (palette instanceof String) {
                    return (String) palette;
                }
            } catch (NoSuchMethodException ignored) {}

            try {
                Object palette = profileClass.getField("buildingPalette").get(profile);
                if (palette instanceof String) {
                    return (String) palette;
                }
            } catch (NoSuchFieldException ignored) {}

            try {
                Object palette = profileClass.getMethod("getAddonBuildingPalette", int.class, int.class).invoke(profile, coord.chunkX() * 16 + x, coord.chunkZ() * 16 + z);
                if (palette instanceof String) {
                    return (String) palette;
                }
            } catch (NoSuchMethodException ignored) {}

        } catch (Throwable t) {
        }
        return null;
    }

    private static String getStreetPaletteFromProfile(Object profile, ChunkCoord coord, int x, int z) {
        try {
            Class<?> profileClass = profile.getClass();

            try {
                Object palette = profileClass.getMethod("getStreetPalette", int.class, int.class).invoke(profile, coord.chunkX() * 16 + x, coord.chunkZ() * 16 + z);
                if (palette instanceof String) {
                    return (String) palette;
                }
            } catch (NoSuchMethodException ignored) {}

            try {
                Object palette = profileClass.getField("streetPalette").get(profile);
                if (palette instanceof String) {
                    return (String) palette;
                }
            } catch (NoSuchFieldException ignored) {}

        } catch (Throwable t) {
        }
        return null;
    }

    private static String getTerrainPaletteFromProfile(Object profile, ChunkCoord coord, int x, int z) {
        try {
            Class<?> profileClass = profile.getClass();

            try {
                Object palette = profileClass.getMethod("getTerrainPalette", int.class, int.class).invoke(profile, coord.chunkX() * 16 + x, coord.chunkZ() * 16 + z);
                if (palette instanceof String) {
                    return (String) palette;
                }
            } catch (NoSuchMethodException ignored) {}

            try {
                Object palette = profileClass.getField("terrainPalette").get(profile);
                if (palette instanceof String) {
                    return (String) palette;
                }
            } catch (NoSuchFieldException ignored) {}

        } catch (Throwable t) {
        }
        return null;
    }

    private static Object getProfile(IDimensionInfo provider) {
        try {
            Object worldStyle = provider.getWorldStyle();
            return worldStyle.getClass().getMethod("getProfile").invoke(worldStyle);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isBuildingPosition(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        return !isStreetPosition(coord, x, z, provider) && (x % 4 == 1) && (z % 4 == 1);
    }

    private static boolean isStreetPosition(ChunkCoord coord, int x, int z, IDimensionInfo provider) {
        return (x % 8 == 0) || (z % 8 == 0);
    }

    private static void cachePalette(ChunkCoord coord, int x, int z, String palette) {
    }

    private static void cacheBuildingPalette(ChunkCoord coord, int x, int z, String palette) {
    }

    private static void cacheStreetPalette(ChunkCoord coord, int x, int z, String palette) {
    }

    private static void cacheAddonPalette(ChunkCoord coord, int x, int z, String palette) {
    }
}
