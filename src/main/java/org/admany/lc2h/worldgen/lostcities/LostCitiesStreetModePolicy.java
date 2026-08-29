package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.config.StreetGenerationMode;

import java.util.Locale;

/**
 * Runtime policy for the Lost Cities street planner.
 *
 * Lost Cities 7.5 introduced {@link StreetGenerationMode#HIERARCHICAL_GRID_V1}.
 * LC2H uses the newer planner by default. ChaosZPack profiles are routed to
 * the legacy planner because their layouts were authored for that grid.
 *
 * The selected value is held in a volatile field and updated when the config
 * is loaded or applied.
 */
public final class LostCitiesStreetModePolicy {
    private static final StreetGenerationMode DEFAULT_MODE = StreetGenerationMode.HIERARCHICAL_GRID_V1;
    private static final String CHAOS_Z_PACK_FLAT_PROFILE = "aaaaaaaaz15Flat";
    private static final String CHAOS_Z_PACK_PREFIX = "Azzz";
    private static volatile StreetGenerationMode configuredMode = DEFAULT_MODE;

    private LostCitiesStreetModePolicy() {
    }

    /**
     * Returns the LC2H-selected planner. The upstream value is retained only
     * as a defensive fallback for an unexpected null policy state.
     */
    public static StreetGenerationMode resolve(StreetGenerationMode upstreamMode) {
        StreetGenerationMode selected = configuredMode;
        return selected != null ? selected : (upstreamMode != null ? upstreamMode : DEFAULT_MODE);
    }

    /**
     * ChaosZPack's layouts were authored for the legacy planner. Keep those
     * profiles on it even when the user normally prefers the newer planner.
     */
    public static StreetGenerationMode resolve(StreetGenerationMode upstreamMode, String profileName) {
        if (requiresLegacyMode(profileName)) {
            return StreetGenerationMode.LEGACY;
        }
        return resolve(upstreamMode);
    }

    public static boolean requiresLegacyMode(String profileName) {
        if (profileName == null) {
            return false;
        }
        String normalized = profileName.trim();
        return normalized.equalsIgnoreCase(CHAOS_Z_PACK_FLAT_PROFILE)
            || normalized.regionMatches(true, 0, CHAOS_Z_PACK_PREFIX, 0, CHAOS_Z_PACK_PREFIX.length());
    }

    public static StreetGenerationMode configuredMode() {
        StreetGenerationMode selected = configuredMode;
        return selected != null ? selected : DEFAULT_MODE;
    }

    /** Apply a config-file value immediately; invalid or missing values use the new planner. */
    public static void setConfiguredMode(String rawMode) {
        configuredMode = parse(rawMode);
    }

    /** Canonical value written back into the JSON config during migration. */
    public static String normalizeValue(String rawMode) {
        return parse(rawMode).name();
    }

    public static String modeName() {
        return configuredMode().name();
    }

    private static StreetGenerationMode parse(String rawMode) {
        if (rawMode == null || rawMode.isBlank()) {
            return DEFAULT_MODE;
        }
        String normalized = rawMode.trim().toUpperCase(Locale.ROOT);
        try {
            return StreetGenerationMode.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return DEFAULT_MODE;
        }
    }
}
