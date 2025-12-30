package org.admany.lc2h.worldgen.async.planner;

public enum PlannerTaskKind {
    CITY_LAYOUT("city"),
    TERRAIN_FEATURE("terrain"),
    BUILDING_INFO("building"),
    TERRAIN_CORRECTION("terrainCorrection");

    private final String displayName;

    PlannerTaskKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
