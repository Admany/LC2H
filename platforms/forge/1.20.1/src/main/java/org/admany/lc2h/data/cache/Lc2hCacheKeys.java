package org.admany.lc2h.data.cache;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import net.minecraft.SharedConstants;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.worldgen.kernel.LostCityKernelSignature;
import org.admany.lc2h.worldgen.kernel.LostCityKernelStage;
import org.admany.lc2h.worldgen.scope.WorldGenScope;
import org.admany.quantified.api.QuantifiedAPI;

public final class Lc2hCacheKeys {

    public static final int CACHE_SCHEMA_VERSION = WorldGenScope.CACHE_SCHEMA_VERSION;
    private static final String CACHE_ROOT = "lc2h_v4";
    private static final String LC2H_VERSION = implementationVersion(LC2H.class, "dev");
    private static final String QAPI_VERSION = implementationVersion(QuantifiedAPI.class, "dev");
    private static final String LOSTCITIES_VERSION = implementationVersion(MultiChunk.class, "unknown");
    private static final String MINECRAFT_VERSION = sanitize(resolveMinecraftVersion());
    private static final String FORGE_VERSION = sanitize(resolveForgeVersion());

    private Lc2hCacheKeys() {
    }

    public static String bucket(String namespace, CacheTier tier) {
        return CACHE_ROOT + "." + sanitize(namespace) + "." + sanitize(tier.id());
    }

    public static String stageBucket(LostCityKernelStage stage, CacheTier tier) {
        return bucket("stage:" + stage.name().toLowerCase(), tier);
    }

    public static String stageKey(LostCityKernelSignature signature,
                                  LostCityKernelStage stage,
                                  ChunkCoord coord,
                                  String coordinateScope) {
        StringBuilder key = new StringBuilder(256);
        key.append("schema=").append(CACHE_SCHEMA_VERSION)
            .append("|mc=").append(MINECRAFT_VERSION)
            .append("|forge=").append(FORGE_VERSION)
            .append("|lostcities=").append(LOSTCITIES_VERSION)
            .append("|lc2h=").append(LC2H_VERSION)
            .append("|qapi=").append(QAPI_VERSION)
            .append("|kernelSchema=").append(signature.schemaVersion())
            .append("|dimension=").append(safe(signature.dimensionId()))
            .append("|seed=").append(signature.seed())
            .append("|profile=").append(safe(signature.profile()))
            .append("|worldStyle=").append(safe(signature.worldStyle()))
            .append("|multiSettings=").append(safe(signature.multiSettings()))
            .append("|backend=").append(safe(signature.kernelBackend()))
            .append("|kernel=").append(safe(signature.kernelVersion()))
            .append("|stage=").append(stage.name())
            .append("|scope=").append(sanitize(coordinateScope));
        if (coord != null) {
            key.append("|coord=").append(chunkScope(coord));
        }
        return key.toString();
    }

    public static String multiChunkScope(ChunkCoord coord, int areaSize) {
        if (coord == null) {
            return "multi:unknown:unknown:size=" + Math.max(1, areaSize);
        }
        return "multi:" + coord.chunkX() + ":" + coord.chunkZ() + ":size=" + Math.max(1, areaSize);
    }

    public static String chunkScope(ChunkCoord coord) {
        if (coord == null) {
            return "chunk:unknown:unknown";
        }
        return "chunk:" + coord.chunkX() + ":" + coord.chunkZ();
    }

    private static String resolveMinecraftVersion() {
        try {
            return SharedConstants.getCurrentVersion().getName();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String resolveForgeVersion() {
        try {
            Class<?> forgeVersion = Class.forName("net.minecraftforge.versions.forge.ForgeVersion");
            Object value = forgeVersion.getMethod("getVersion").invoke(null);
            if (value != null) {
                return value.toString();
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    private static String implementationVersion(Class<?> type, String fallback) {
        try {
            Package pkg = type.getPackage();
            if (pkg != null && pkg.getImplementationVersion() != null && !pkg.getImplementationVersion().isBlank()) {
                return sanitize(pkg.getImplementationVersion());
            }
        } catch (Throwable ignored) {
        }
        return sanitize(fallback);
    }

    private static String safe(String value) {
        return sanitize(value == null ? "unknown" : value);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value
            .replace(':', '_')
            .replace('|', '_')
            .replace(' ', '_')
            .replace('/', '_')
            .replace('\\', '_');
    }

    public enum CacheTier {
        HOT_RAM("hot"),
        WARM_RAM("warm"),
        DISK("disk");

        private final String id;

        CacheTier(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }
}
