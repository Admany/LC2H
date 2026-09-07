package org.admany.lc2h.worldgen.terrain;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;
import org.admany.lc2h.worldgen.terrain.CityShiftField;
import org.admany.lc2h.worldgen.terrain.NaturalHeightSampler;

import java.util.concurrent.atomic.AtomicLong;

/** Keeps Lost Cities' late floor query in step with the density plan. The
 * Minecraft density hook remains the only terrain shaper. */
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
        NaturalHeightSampler.LevelSampler heights =
            NaturalHeightSampler.forLevel(info.provider.getWorld());
        CityShiftField.Context context = CityShiftField.context(info.provider, profile, heights);
        if (context == null || !context.settings().enabled()) {
            NOT_CITY.incrementAndGet();
            return null;
        }
        /* The late Lost Cities hook is still on a worldgen worker. If the
         * natural height is not already resident, leave the original hook in
         * place rather than sampling vanilla noise or joining another worker's
         * flight. The density plan will publish the authoritative result once
         * its asynchronous region is ready. */
        if (heights.cachedChunkHeight(info.coord.chunkX(), info.coord.chunkZ()) == null) {
            NOT_CITY.incrementAndGet();
            return null;
        }
        /* Reservation publication is also asynchronous. The old call to
         * plan() could synchronously build a cold reservation region from the
         * late Lost Cities hook, defeating the non blocking terrain path. A
         * missing publication keeps Lost Cities' original decision for this
         * query and lets the next chunk reuse the completed immutable plan. */
        if (MountainCityReservationPlanner.peekRemovesBuildingCell(
            info.provider, info.coord, profile)) {
            NOT_CITY.incrementAndGet();
            return null;
        }
        REINFORCED.incrementAndGet();
        return CityShiftField.plannedFloor(context, info.coord.chunkX(), info.coord.chunkZ());
    }

    public static String diagnostics() {
        return "calls=" + CALLS.get()
            + ", originalLostCitiesPath=" + NOT_CITY.get()
            + ", reinforcedShiftField=" + REINFORCED.get()
            + ", independentShaping=false";
    }
}
