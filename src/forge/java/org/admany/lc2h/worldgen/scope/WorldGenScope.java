package org.admany.lc2h.worldgen.scope;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.io.Serializable;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class WorldGenScope {
    public static final int CACHE_SCHEMA_VERSION = 2;

    private static final AtomicLong NEXT_LIFECYCLE_ID = new AtomicLong();
    private static volatile long activeLifecycleId = NEXT_LIFECYCLE_ID.incrementAndGet();
    private static volatile String activeDiskScope = "inactive";

    private WorldGenScope() {
    }

    public static long beginServer(MinecraftServer server) {
        long id = NEXT_LIFECYCLE_ID.incrementAndGet();
        activeLifecycleId = id;
        activeDiskScope = computeServerDiskScope(server, id);
        return id;
    }

    /** Refreshes the persistent scope once the overworld and its seed exist. */
    public static void refreshServer(MinecraftServer server) {
        activeDiskScope = computeServerDiskScope(server, activeLifecycleId());
    }

    public static void endServer() {
        activeLifecycleId = NEXT_LIFECYCLE_ID.incrementAndGet();
        activeDiskScope = "inactive-" + activeLifecycleId;
    }

    public static long activeLifecycleId() {
        return activeLifecycleId;
    }

    public static DimensionKey dimension(ServerLevel level) {
        if (level == null) {
            return dimension(null, 0L);
        }
        return dimension(level.dimension(), level.getSeed());
    }

    public static DimensionKey dimension(ResourceKey<Level> dimension) {
        return dimension(dimension, 0L);
    }

    public static DimensionKey dimension(ResourceKey<Level> dimension, long seed) {
        return new DimensionKey(activeLifecycleId(), seed, dimensionId(dimension));
    }

    public static DimensionKey dimension(ResourceKey<Level> dimension, long lifecycleId, long seed) {
        long lifecycle = lifecycleId <= 0L ? activeLifecycleId() : lifecycleId;
        return new DimensionKey(lifecycle, seed, dimensionId(dimension));
    }

    public static CacheScope cache(IDimensionInfo provider) {
        ResourceKey<Level> dimension = null;
        long seed = 0L;
        LostCityProfile profile = null;
        LostCityProfile outsideProfile = null;
        String worldStyle = "unknown";
        if (provider != null) {
            try {
                dimension = provider.getType();
            } catch (Throwable ignored) {
            }
            try {
                seed = provider.getSeed();
            } catch (Throwable ignored) {
            }
            try {
                profile = provider.getProfile();
            } catch (Throwable ignored) {
            }
            try {
                outsideProfile = provider.getOutsideProfile();
            } catch (Throwable ignored) {
            }
            try {
                if (provider.getWorldStyle() != null && provider.getWorldStyle().getName() != null) {
                    worldStyle = provider.getWorldStyle().getName();
                }
            } catch (Throwable ignored) {
            }
        }
        return new CacheScope(
            activeLifecycleId(),
            seed,
            dimensionId(dimension),
            profileSignature(profile),
            profileSignature(outsideProfile),
            sanitize(worldStyle),
            "registryEpoch=runtime",
            CACHE_SCHEMA_VERSION
        );
    }

    public static String bridgeDiskKey(Object rawKey) {
        return "schema=" + CACHE_SCHEMA_VERSION
            + "|lifecycle=" + activeLifecycleId()
            + "|server=" + activeDiskScope
            + "|raw=" + sanitize(String.valueOf(rawKey));
    }

    public static String activeDiskScope() {
        return activeDiskScope;
    }

    public static boolean isDiskScopeReady() {
        String scope = activeDiskScope;
        return scope != null
            && !scope.startsWith("inactive")
            && !scope.contains(":path=unknown")
            && !scope.contains(":seed=unknown");
    }

    public static boolean matches(ServerLevel level, DimensionKey expected) {
        if (expected == null || level == null) {
            return false;
        }
        DimensionKey current = dimension(level);
        return current.lifecycleId() == expected.lifecycleId()
            && current.seed() == expected.seed()
            && current.dimension().equals(expected.dimension());
    }

    public static String dimensionId(ResourceKey<Level> dimension) {
        if (dimension == null || dimension.location() == null) {
            return "unknown";
        }
        return sanitize(dimension.location().toString());
    }

    public static String profileSignature(LostCityProfile profile) {
        if (profile == null) {
            return "unknown";
        }
        String name = "unknown";
        String style = "unknown";
        try {
            if (profile.getName() != null) {
                name = profile.getName();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (profile.getWorldStyle() != null) {
                style = profile.getWorldStyle();
            }
        } catch (Throwable ignored) {
        }
        return sanitize(name)
            + ":style=" + sanitize(style)
            + ":chance=" + Double.doubleToLongBits(profile.CITY_CHANCE)
            + ":minR=" + profile.CITY_MINRADIUS
            + ":maxR=" + profile.CITY_MAXRADIUS
            + ":minH=" + profile.CITY_MINHEIGHT
            + ":maxH=" + profile.CITY_MAXHEIGHT
            + ":cityLevel=" + profile.CITY_LEVEL0_HEIGHT
            + "," + profile.CITY_LEVEL1_HEIGHT
            + "," + profile.CITY_LEVEL2_HEIGHT
            + "," + profile.CITY_LEVEL3_HEIGHT
            + "," + profile.CITY_LEVEL4_HEIGHT
            + "," + profile.CITY_LEVEL5_HEIGHT
            + "," + profile.CITY_LEVEL6_HEIGHT
            + "," + profile.CITY_LEVEL7_HEIGHT;
    }

    public static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.toLowerCase(Locale.ROOT)
            .replace(':', '_')
            .replace('|', '_')
            .replace(' ', '_')
            .replace('/', '_')
            .replace('\\', '_')
            .replace('\n', '_')
            .replace('\r', '_');
    }

    private static String computeServerDiskScope(MinecraftServer server, long lifecycleId) {
        String path = "unknown";
        String seed = "unknown";
        if (server != null) {
            try {
                Path root = server.getWorldPath(LevelResource.ROOT);
                if (root != null) {
                    path = root.toAbsolutePath().normalize().toString();
                }
            } catch (Throwable ignored) {
            }
            try {
                ServerLevel overworld = server.overworld();
                if (overworld != null) {
                    seed = Long.toString(overworld.getSeed());
                }
            } catch (Throwable ignored) {
            }
        }
        return "life=" + lifecycleId
            + ":path=" + sanitize(path)
            + ":seed=" + sanitize(seed);
    }

    public record DimensionKey(long lifecycleId, long seed, String dimension) implements Serializable {
        public String shortText() {
            return "life=" + lifecycleId + ",seed=" + seed + ",dim=" + dimension;
        }
    }

    public record CacheScope(long lifecycleId,
                             long seed,
                             String dimension,
                             String profile,
                             String outsideProfile,
                             String worldStyle,
                             String registryEpoch,
                             int schemaVersion) implements Serializable {
        public String stableText() {
            return "schema=" + schemaVersion
                + "|life=" + lifecycleId
                + "|seed=" + seed
                + "|dim=" + dimension
                + "|profile=" + profile
                + "|outside=" + outsideProfile
                + "|style=" + worldStyle
                + "|" + registryEpoch;
        }
    }
}
