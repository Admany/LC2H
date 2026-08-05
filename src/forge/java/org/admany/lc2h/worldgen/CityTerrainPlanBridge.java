package org.admany.lc2h.worldgen;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridges Lost Cities' late floor query to the terrain plan already selected
 * for Minecraft's density graph.
 *
 * <p>This class performs no shaping, scanning, interpolation, or independent
 * detection. The vanilla density hook is the only terrain shaper. This bridge
 * merely prevents Lost Cities' later block-placement pass from assuming a
 * different floor height than the authoritative density plan.</p>
 */
public final class CityTerrainPlanBridge {

    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong NOT_CITY = new AtomicLong();
    private static final AtomicLong REINFORCED = new AtomicLong();

    private CityTerrainPlanBridge() {
    }

    /**
     * @return the density plan's city target, or {@code null} when the queried
     * chunk is not an authoritative stable city and vanilla Lost Cities should
     * execute its original method.
     */
    public static Integer plannedMinHeight(BuildingInfo info) {
        CALLS.incrementAndGet();
        if (info == null || info.provider == null || info.coord == null) {
            NOT_CITY.incrementAndGet();
            return null;
        }
        ChunkRoleProbe.Probe role = ChunkRoleProbe.getStableTerrainProbe(
            info.provider, info.coord.dimension(), info.coord.chunkX(), info.coord.chunkZ());
        if (!role.isCity()) {
            NOT_CITY.incrementAndGet();
            return null;
        }
        LostCityProfile profile = info.provider.getProfile();
        if (profile == null) {
            NOT_CITY.incrementAndGet();
            return null;
        }
        if (MountainCityReservationPlanner.plan(
            info.provider,
            info.coord.dimension(),
            info.coord.chunkX(),
            info.coord.chunkZ(),
            profile
        ).removesBuildingCell()) {
            NOT_CITY.incrementAndGet();
            return null;
        }
        int cityGround = profile.GROUNDLEVEL
            + role.cityLevel() * LostCityTerrainFeature.FLOORHEIGHT;
        ConnectedMountainPlanner.MountainPlan plan = ConnectedMountainPlanner.plan(
            info.provider,
            info.coord.dimension(),
            info.coord.chunkX(),
            info.coord.chunkZ(),
            cityGround);
        REINFORCED.incrementAndGet();
        return plan.targetHeight();
    }

    public static String diagnostics() {
        return "calls=" + CALLS.get()
            + ", originalLostCitiesPath=" + NOT_CITY.get()
            + ", reinforcedDensityPlan=" + REINFORCED.get()
            + ", independentShaping=false";
    }
}
