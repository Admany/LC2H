package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.config.StreetGenerationMode;

import java.util.Locale;

/**
 * Runtime policy for the Lost Cities street planner.
 *
 * Lost Cities 7.5 introduced {@link StreetGenerationMode#HIERARCHICAL_GRID_V1}.
 * LC2H keeps the legacy planner as the safe default because the hierarchical
 * planner changes the building grid and is the source of the small-structure
 * and gridding regression reported in issue #12.
 *
 * The selected value is deliberately held in a volatile field. ConfigManager
 * updates it when the LC2H config is loaded or applied, so the mixin does not
 * require a JVM property or a client/server restart to select the planner for
 * subsequently planned chunks.
 */
public final class LostCitiesStreetModePolicy {
    private static final StreetGenerationMode DEFAULT_MODE = StreetGenerationMode.LEGACY;
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

    public static StreetGenerationMode configuredMode() {
        StreetGenerationMode selected = configuredMode;
        return selected != null ? selected : DEFAULT_MODE;
    }

    /** Apply a config-file value immediately; invalid or missing values use LEGACY. */
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
