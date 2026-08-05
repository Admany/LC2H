package org.admany.lc2h.worldgen.kernel;

import mcjty.lostcities.varia.ChunkCoord;

public final class LostCityStagePayloads {

    private LostCityStagePayloads() {
    }

    public record BuildingInfoPlan(ChunkCoord coord, Object buildingInfo) {
    }

    public record TerrainFeatureIntentPlan(ChunkCoord coord,
                                           int heightChecksum,
                                           int excavationChecksum,
                                           int ruinCount) {
        public static TerrainFeatureIntentPlan from(ChunkCoord coord, JavaScalarLostCityKernel.TerrainFeatureSummary summary) {
            return new TerrainFeatureIntentPlan(coord, summary.heightChecksum(), summary.excavationChecksum(), summary.ruinCount());
        }
    }

    public record TerrainCorrectionIntentPlan(ChunkCoord coord, int correctionChecksum) {
        public static TerrainCorrectionIntentPlan from(ChunkCoord coord, JavaScalarLostCityKernel.TerrainCorrectionSummary summary) {
            return new TerrainCorrectionIntentPlan(coord, summary.correctionChecksum());
        }
    }

    public record CityLayoutPlan(ChunkCoord coord, int chunkX, int chunkZ) {
        public static CityLayoutPlan from(ChunkCoord coord, JavaScalarLostCityKernel.CityLayoutSummary summary) {
            return new CityLayoutPlan(coord, summary.chunkX(), summary.chunkZ());
        }
    }
}
