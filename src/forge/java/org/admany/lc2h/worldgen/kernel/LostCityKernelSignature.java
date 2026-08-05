package org.admany.lc2h.worldgen.kernel;

import mcjty.lostcities.worldgen.IDimensionInfo;
import org.admany.lc2h.LC2H;

import java.util.Locale;

public record LostCityKernelSignature(
    int schemaVersion,
    String dimensionId,
    long seed,
    String profile,
    String worldStyle,
    String multiSettings,
    String kernelBackend,
    String kernelVersion
) {
    public static final int CURRENT_SCHEMA = 4;

    public static LostCityKernelSignature from(IDimensionInfo provider, KernelCapabilities capabilities) {
        String dimensionId = "unknown";
        long seed = 0L;
        String profileName = "unknown";
        String worldStyleName = "unknown";
        String multiSettingsSignature = "unknown";

        if (provider != null) {
            try {
                if (provider.getType() != null) {
                    dimensionId = String.valueOf(provider.getType().location());
                }
            } catch (Throwable ignored) {
            }
            try {
                seed = provider.getSeed();
            } catch (Throwable ignored) {
            }
            try {
                var profile = provider.getProfile();
                if (profile != null && profile.getName() != null) {
                    profileName = profile.getName();
                }
            } catch (Throwable ignored) {
            }
            try {
                var worldStyle = provider.getWorldStyle();
                if (worldStyle != null && worldStyle.getName() != null) {
                    worldStyleName = worldStyle.getName();
                }
                if (worldStyle != null && worldStyle.getMultiSettings() != null) {
                    var settings = worldStyle.getMultiSettings();
                    multiSettingsSignature = settings.areasize() + ":"
                        + settings.minimum() + ":"
                        + settings.maximum() + ":"
                        + settings.attempts() + ":"
                        + String.format(Locale.ROOT, "%.6f", settings.correctStyleFactor());
                }
            } catch (Throwable ignored) {
            }
        }

        String backend = capabilities == null ? "unknown" : capabilities.backendName();
        String version = capabilities == null ? modVersion() : capabilities.backendVersion();
        return new LostCityKernelSignature(
            CURRENT_SCHEMA,
            sanitize(dimensionId),
            seed,
            sanitize(profileName),
            sanitize(worldStyleName),
            sanitize(multiSettingsSignature),
            sanitize(backend),
            sanitize(version)
        );
    }

    public String cacheNamespace() {
        return "lc2h:v4:" + schemaVersion + ":" + dimensionId + ":" + seed + ":" + profile + ":" + worldStyle
            + ":" + multiSettings + ":" + kernelBackend + ":" + kernelVersion;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replace(':', '_').replace('|', '_').replace(' ', '_');
    }

    private static String modVersion() {
        try {
            Package pkg = LC2H.class.getPackage();
            if (pkg != null && pkg.getImplementationVersion() != null) {
                return pkg.getImplementationVersion();
            }
        } catch (Throwable ignored) {
        }
        return "dev";
    }
}
