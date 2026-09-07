package org.admany.lc2h.dev.debug;

import mcjty.lostcities.api.ILostChunkInfo;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import net.minecraft.resources.ResourceLocation;
import org.admany.lc2h.dev.diagnostics.ChunkGenTracker;
import org.admany.lc2h.worldgen.async.snapshot.MultiChunkSnapshot;
import org.admany.lc2h.worldgen.lostcities.BuildingInfoSnapshotStore;
import org.admany.lc2h.util.ResourceLocations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class GeneratedWorldAgreementHarness {
    private GeneratedWorldAgreementHarness() {
    }

    public static AgreementReport run(IDimensionInfo dimInfo, ChunkCoord centerMulti, int areaSize) {
        Objects.requireNonNull(dimInfo, "dimInfo");
        Objects.requireNonNull(centerMulti, "centerMulti");

        long startedNs = System.nanoTime();
        ChunkCoord topLeft = new ChunkCoord(
            centerMulti.dimension(),
            centerMulti.chunkX() * areaSize,
            centerMulti.chunkZ() * areaSize
        );
        LinkedHashMap<String, Integer> mismatchCounts = new LinkedHashMap<>();
        int inspected = 0;
        int mismatches = 0;
        int runtimeEvidence = 0;
        int generatedChunks = 0;
        int buildingInfoChunks = 0;
        int coverageGaps = 0;
        int multichunkRelevantChunks = 0;
        AgreementMismatch firstMismatch = null;

        for (int dx = 0; dx < areaSize; dx++) {
            for (int dz = 0; dz < areaSize; dz++) {
                inspected++;
                ChunkCoord coord = new ChunkCoord(centerMulti.dimension(), topLeft.chunkX() + dx, topLeft.chunkZ() + dz);
                ChunkGenTracker.ChunkGenEvidence evidence = ChunkGenTracker.evidence(coord);
                if (evidence != null && evidence.hasRuntimeEvidence()) {
                    runtimeEvidence++;
                    if (evidence.generateEndCount() > 0) {
                        generatedChunks++;
                    }
                    if (evidence.buildingInfoCount() > 0) {
                        buildingInfoChunks++;
                    }
                }

                Comparison comparison = compare(dimInfo, centerMulti, areaSize, coord, evidence);
                if (!comparison.covered()) {
                    coverageGaps++;
                }
                if (comparison.multichunkRelevant()) {
                    multichunkRelevantChunks++;
                }
                AgreementMismatch mismatch = comparison.mismatch();
                if (mismatch != null) {
                    mismatches++;
                    mismatchCounts.merge(mismatch.field(), 1, Integer::sum);
                    if (firstMismatch == null) {
                        firstMismatch = mismatch;
                    }
                }
            }
        }

        return new AgreementReport(
            centerMulti.dimension() == null || centerMulti.dimension().location() == null
                ? "unknown"
                : centerMulti.dimension().location().toString(),
            safeSeed(dimInfo),
            safeProfile(dimInfo),
            safeOutsideProfile(dimInfo),
            centerMulti.chunkX(),
            centerMulti.chunkZ(),
            areaSize,
            inspected,
            runtimeEvidence,
            generatedChunks,
            buildingInfoChunks,
            coverageGaps,
            multichunkRelevantChunks,
            mismatches,
            mismatchCounts,
            firstMismatch,
            Math.round((System.nanoTime() - startedNs) / 1_000_000.0D)
        );
    }

    private static Comparison compare(IDimensionInfo dimInfo,
                                      ChunkCoord centerMulti,
                                      int areaSize,
                                      ChunkCoord coord,
                                      ChunkGenTracker.ChunkGenEvidence evidence) {
        BuildingInfo info = BuildingInfo.getBuildingInfo(coord, dimInfo);
        MultiChunk multiChunk = MultiChunk.getOrCreate(dimInfo, coord);
        MultiChunkSnapshot.MultiChunkCell cell = MultiChunkSnapshot.describeCell(multiChunk, coord);
        BuildingInfoSnapshotStore.Snapshot snapshot = BuildingInfoSnapshotStore.get(coord);
        ILostChunkInfo.MultiBuildingInfo multiInfo = info.getMultiBuildingInfo();
        boolean plannerOccupied = cell != null;
        boolean actualOccupied = info.getBuildingType() != null;
        boolean snapshotMulti = snapshot != null && snapshot.multiChunk();
        boolean multichunkRelevant = plannerOccupied || multiInfo != null || snapshotMulti;
        boolean covered = evidence != null && evidence.hasRuntimeEvidence();

        if (snapshot == null) {
            if (evidence != null && evidence.buildingInfoCount() > 0) {
                return new Comparison(covered, multichunkRelevant,
                    mismatch(centerMulti, coord, "characteristics_snapshot", "<present>", "<missing>",
                        detailPrefix(dimInfo, areaSize, centerMulti, coord, cell, info, null, evidence)));
            }
            return new Comparison(covered, multichunkRelevant, null);
        }

        if (multichunkRelevant) {
            if (plannerOccupied != actualOccupied) {
                return new Comparison(covered, true,
                    mismatch(centerMulti, coord, "occupied", String.valueOf(plannerOccupied), String.valueOf(actualOccupied),
                        detailPrefix(dimInfo, areaSize, centerMulti, coord, cell, info, snapshot, evidence)));
            }

            String plannerBuilding = cell == null ? "<none>" : safeString(cell.name());
            String actualBuilding = multiInfo != null
                ? safeString(multiInfo.buildingType())
                : safeString(info.getBuildingType());
            if (!canonicalBuildingKey(plannerBuilding).equals(canonicalBuildingKey(actualBuilding))) {
                return new Comparison(covered, true,
                    mismatch(centerMulti, coord, "building", plannerBuilding, actualBuilding,
                        detailPrefix(dimInfo, areaSize, centerMulti, coord, cell, info, snapshot, evidence)));
            }
        }

        if (snapshot.isCity() != info.isCity()) {
            return new Comparison(covered, multichunkRelevant,
                mismatch(centerMulti, coord, "city", String.valueOf(snapshot.isCity()), String.valueOf(info.isCity()),
                    detailPrefix(dimInfo, areaSize, centerMulti, coord, cell, info, snapshot, evidence)));
        }

        if (snapshot.cityLevel() != info.getCityLevel()) {
            return new Comparison(covered, multichunkRelevant,
                mismatch(centerMulti, coord, "city_level", String.valueOf(snapshot.cityLevel()), String.valueOf(info.getCityLevel()),
                    detailPrefix(dimInfo, areaSize, centerMulti, coord, cell, info, snapshot, evidence)));
        }

        if (multichunkRelevant) {
            ResourceLocation snapshotBuildingId = snapshot.buildingTypeId();
            ResourceLocation actualBuildingId = info.getBuildingId();
            if (snapshotBuildingId != null && !Objects.equals(snapshotBuildingId, actualBuildingId)) {
                return new Comparison(covered, true,
                    mismatch(centerMulti, coord, "building_id", String.valueOf(snapshotBuildingId), String.valueOf(actualBuildingId),
                        detailPrefix(dimInfo, areaSize, centerMulti, coord, cell, info, snapshot, evidence)));
            }

            boolean plannerMulti = cell != null && snapshot != null && snapshot.multiChunk();
            boolean actualMulti = multiInfo != null;
            if (plannerMulti != actualMulti) {
                return new Comparison(covered, true,
                    mismatch(centerMulti, coord, "multi_chunk", String.valueOf(plannerMulti), String.valueOf(actualMulti),
                        detailPrefix(dimInfo, areaSize, centerMulti, coord, cell, info, snapshot, evidence)));
            }
            if (multiInfo != null && cell != null) {
                if (multiInfo.offsetX() != cell.offsetX()) {
                    return new Comparison(covered, true,
                        mismatch(centerMulti, coord, "multi_offset_x", String.valueOf(cell.offsetX()), String.valueOf(multiInfo.offsetX()),
                            detailPrefix(dimInfo, areaSize, centerMulti, coord, cell, info, snapshot, evidence)));
                }
                if (multiInfo.offsetZ() != cell.offsetZ()) {
                    return new Comparison(covered, true,
                        mismatch(centerMulti, coord, "multi_offset_z", String.valueOf(cell.offsetZ()), String.valueOf(multiInfo.offsetZ()),
                            detailPrefix(dimInfo, areaSize, centerMulti, coord, cell, info, snapshot, evidence)));
                }
                if (snapshot.multiBuildingId() != null
                    && !canonicalBuildingKey(snapshot.multiBuildingId()).equals(canonicalBuildingKey(multiInfo.buildingType()))) {
                    return new Comparison(covered, true,
                        mismatch(centerMulti, coord, "multi_building_id", String.valueOf(snapshot.multiBuildingId()), String.valueOf(multiInfo.buildingType()),
                            detailPrefix(dimInfo, areaSize, centerMulti, coord, cell, info, snapshot, evidence)));
                }
            }
        }

        return new Comparison(covered, multichunkRelevant, null);
    }

    private static AgreementMismatch mismatch(ChunkCoord centerMulti,
                                              ChunkCoord coord,
                                              String field,
                                              String expected,
                                              String actual,
                                              String detail) {
        return new AgreementMismatch(centerMulti, coord, field, expected, actual, detail);
    }

    private static String detailPrefix(IDimensionInfo dimInfo,
                                       int areaSize,
                                       ChunkCoord centerMulti,
                                       ChunkCoord coord,
                                       MultiChunkSnapshot.MultiChunkCell cell,
                                       BuildingInfo info,
                                       BuildingInfoSnapshotStore.Snapshot snapshot,
                                       ChunkGenTracker.ChunkGenEvidence evidence) {
        List<String> eventSummary = evidence == null ? List.of() : evidence.recentEvents();
        String events = eventSummary.isEmpty() ? "[]" : eventSummary.toString();
        String plannerCell = cell == null ? "empty" : cell.name() + "@(" + cell.offsetX() + "," + cell.offsetZ() + ")";
        String actualBuilding = safeString(info.getBuildingType());
        return String.format(Locale.ROOT,
            "dim=%s seed=%d profile=%s outside=%s multi=%d,%d area=%d chunk=%d,%d plannerCell=%s actual[city=%s building=%s floors=%d cellars=%d rail=%s@%d highway=%d street=%s bridge=%s sphere=%s outside=%s] snapshot[city=%s cityLevel=%d buildingId=%s multiBuildingId=%s multiChunk=%s] chunkGen[start=%d end=%d skip=%d buildingInfo=%d events=%s]",
            coord.dimension() == null || coord.dimension().location() == null ? "unknown" : coord.dimension().location(),
            safeSeed(dimInfo),
            safeProfile(dimInfo),
            safeOutsideProfile(dimInfo),
            centerMulti.chunkX(),
            centerMulti.chunkZ(),
            areaSize,
            coord.chunkX(),
            coord.chunkZ(),
            plannerCell,
            info.isCity(),
            actualBuilding,
            info.getNumFloors(),
            info.getNumCellars(),
            info.getRailType(),
            info.getRailLevel(),
            info.getMaxHighwayLevel(),
            info.isStreetOrParkSection() ? safeString(info.streetType) : "<none>",
            info.hasBridge(dimInfo),
            info.getSphere() != null,
            info.outsideChunk,
            snapshot == null ? "<missing>" : snapshot.isCity(),
            snapshot == null ? Integer.MIN_VALUE : snapshot.cityLevel(),
            snapshot == null ? "<missing>" : snapshot.buildingTypeId(),
            snapshot == null ? "<missing>" : snapshot.multiBuildingId(),
            snapshot == null ? "<missing>" : snapshot.multiChunk(),
            evidence == null ? 0 : evidence.generateStartCount(),
            evidence == null ? 0 : evidence.generateEndCount(),
            evidence == null ? 0 : evidence.generateSkipCount(),
            evidence == null ? 0 : evidence.buildingInfoCount(),
            events
        );
    }

    private static long safeSeed(IDimensionInfo dimInfo) {
        try {
            return dimInfo.getSeed();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String safeProfile(IDimensionInfo dimInfo) {
        try {
            return dimInfo.getProfile() != null && dimInfo.getProfile().getName() != null
                ? dimInfo.getProfile().getName()
                : "unknown";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String safeOutsideProfile(IDimensionInfo dimInfo) {
        try {
            return dimInfo.getOutsideProfile() != null && dimInfo.getOutsideProfile().getName() != null
                ? dimInfo.getOutsideProfile().getName()
                : "unknown";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String safeString(Object value) {
        return value == null ? "<none>" : String.valueOf(value);
    }

    private static String canonicalBuildingKey(Object value) {
        if (value == null) {
            return "<none>";
        }
        if (value instanceof ResourceLocation location) {
            return location.getPath();
        }
        String raw = String.valueOf(value);
        ResourceLocation parsed = ResourceLocations.tryParse(raw);
        return parsed == null ? raw : parsed.getPath();
    }

    public record AgreementMismatch(ChunkCoord centerMulti,
                                    ChunkCoord coord,
                                    String field,
                                    String expected,
                                    String actual,
                                    String detail) {
    }

    private record Comparison(boolean covered, boolean multichunkRelevant, AgreementMismatch mismatch) {
    }

    public record AgreementReport(String dimension,
                                  long seed,
                                  String profile,
                                  String outsideProfile,
                                  int centerMultiX,
                                  int centerMultiZ,
                                  int areaSize,
                                  int inspectedChunks,
                                  int runtimeEvidenceChunks,
                                  int generatedChunks,
                                  int buildingInfoChunks,
                                  int coverageGapChunks,
                                  int multichunkRelevantChunks,
                                  int mismatches,
                                  Map<String, Integer> mismatchCounts,
                                  AgreementMismatch firstMismatch,
                                  long durationMs) {
        public String summary() {
            if (mismatches <= 0) {
                return String.format(Locale.ROOT,
                    "dim=%s seed=%d profile=%s outside=%s center=%d,%d area=%d inspected=%d runtimeEvidence=%d generated=%d buildingInfo=%d coverageGaps=%d multichunkRelevant=%d mismatches=0 durationMs=%d",
                    dimension,
                    seed,
                    profile,
                    outsideProfile,
                    centerMultiX,
                    centerMultiZ,
                    areaSize,
                    inspectedChunks,
                    runtimeEvidenceChunks,
                    generatedChunks,
                    buildingInfoChunks,
                    coverageGapChunks,
                    multichunkRelevantChunks,
                    durationMs
                );
            }
            return String.format(Locale.ROOT,
                "dim=%s seed=%d profile=%s outside=%s center=%d,%d area=%d inspected=%d runtimeEvidence=%d generated=%d buildingInfo=%d coverageGaps=%d multichunkRelevant=%d mismatches=%d durationMs=%d fields=%s first=%s expected=%s actual=%s %s",
                dimension,
                seed,
                profile,
                outsideProfile,
                centerMultiX,
                centerMultiZ,
                areaSize,
                inspectedChunks,
                runtimeEvidenceChunks,
                generatedChunks,
                buildingInfoChunks,
                coverageGapChunks,
                multichunkRelevantChunks,
                mismatches,
                durationMs,
                mismatchCounts,
                firstMismatch == null ? "unknown" : firstMismatch.field(),
                firstMismatch == null ? "unknown" : firstMismatch.expected(),
                firstMismatch == null ? "unknown" : firstMismatch.actual(),
                firstMismatch == null ? "" : firstMismatch.detail()
            );
        }

        public List<String> summaryLines() {
            ArrayList<String> lines = new ArrayList<>();
            lines.add(String.format(Locale.ROOT,
                "GeneratedWorldAgreement: dim=%s seed=%d profile=%s outside=%s center=%d,%d area=%d inspected=%d runtimeEvidence=%d generated=%d buildingInfo=%d coverageGaps=%d multichunkRelevant=%d mismatches=%d durationMs=%d fields=%s",
                dimension,
                seed,
                profile,
                outsideProfile,
                centerMultiX,
                centerMultiZ,
                areaSize,
                inspectedChunks,
                runtimeEvidenceChunks,
                generatedChunks,
                buildingInfoChunks,
                coverageGapChunks,
                multichunkRelevantChunks,
                mismatches,
                durationMs,
                mismatchCounts
            ));
            lines.add("GeneratedWorldAgreement fields: [occupied, building, city, city_level, building_id, multi_chunk, multi_offset_x, multi_offset_z, multi_building_id]");
            lines.add("GeneratedWorldAgreement coverage: runtimeEvidence is tracked separately and only multichunk-relevant chunks participate in building/layout checks");
            if (firstMismatch != null) {
                lines.add("GeneratedWorldAgreement firstMismatch: field=" + firstMismatch.field()
                    + " expected=" + firstMismatch.expected()
                    + " actual=" + firstMismatch.actual()
                    + " " + firstMismatch.detail());
            }
            return lines;
        }
    }
}
