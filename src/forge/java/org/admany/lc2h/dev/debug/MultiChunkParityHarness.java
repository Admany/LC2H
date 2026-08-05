package org.admany.lc2h.dev.debug;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import org.admany.lc2h.mixin.accessor.lostcities.MultiChunkAccessor;
import org.admany.lc2h.worldgen.async.snapshot.MultiChunkSnapshot;
import org.admany.lc2h.worldgen.lostcities.FastMultiChunkPlanner;
import org.admany.lc2h.worldgen.scope.WorldGenScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MultiChunkParityHarness {
    private static final String MATRIX_ID = "deterministic_v1";
    private static final long CELL_FIELDS_COMPARED = 4L; // occupied, name, offsetX, offsetZ
    private static volatile ParityReport LAST_REPORT = ParityReport.notRun();

    private MultiChunkParityHarness() {
    }

    public static ParityReport run(IDimensionInfo dimInfo, ChunkCoord centerMulti, int areaSize, int radius, int samples) {
        Objects.requireNonNull(dimInfo, "dimInfo");
        Objects.requireNonNull(centerMulti, "centerMulti");

        long startedNs = System.nanoTime();
        List<ChunkCoord> matrix = deterministicMatrix(centerMulti, radius, samples);
        Set<ChunkCoord> auditDisabledBefore = FastMultiChunkPlanner.auditDisabledCoords();
        LinkedHashMap<String, Integer> mismatchCounts = new LinkedHashMap<>();
        int checked = 0;
        int mismatches = 0;
        long totalCellsCompared = 0L;
        long totalFieldComparisons = 0L;
        Mismatch firstMismatch = null;

        for (ChunkCoord multiCoord : matrix) {
            checked++;
            MultiChunk original = FastMultiChunkPlanner.calculateOriginalForAudit(dimInfo, multiCoord, areaSize);
            MultiChunk fast = FastMultiChunkPlanner.calculateFastForAudit(dimInfo, multiCoord, areaSize);
            totalCellsCompared += (long) areaSize * (long) areaSize;
            totalFieldComparisons += (long) areaSize * (long) areaSize * CELL_FIELDS_COMPARED;
            Mismatch mismatch = compare(dimInfo, multiCoord, areaSize, original, fast);
            if (mismatch != null) {
                mismatches++;
                mismatchCounts.merge(mismatch.classification(), 1, Integer::sum);
                if (firstMismatch == null) {
                    firstMismatch = mismatch;
                }
            }
        }

        ParityReport report = new ParityReport(
            MATRIX_ID,
            centerMulti.dimension() == null || centerMulti.dimension().location() == null
                ? "unknown"
                : centerMulti.dimension().location().toString(),
            safeSeed(dimInfo),
            safeProfile(dimInfo),
            safeOutsideProfile(dimInfo),
            safeWorldStyle(dimInfo),
            centerMulti.chunkX(),
            centerMulti.chunkZ(),
            radius,
            samples,
            areaSize,
            checked,
            totalCellsCompared,
            totalFieldComparisons,
            mismatches,
            mismatchCounts,
            WorldGenScope.cache(dimInfo).stableText(),
            auditDisabledBefore,
            FastMultiChunkPlanner.auditDisabledCoords(),
            firstMismatch,
            Math.round((System.nanoTime() - startedNs) / 1_000_000.0D)
        );
        LAST_REPORT = report;
        return report;
    }

    public static ParityReport lastReport() {
        return LAST_REPORT;
    }

    public static List<String> summaryLines() {
        return LAST_REPORT.summaryLines();
    }

    public static List<ChunkCoord> deterministicMatrix(ChunkCoord centerMulti, int radius, int samples) {
        LinkedHashSet<ChunkCoord> coords = new LinkedHashSet<>();
        addRegressionCoords(coords, centerMulti);
        addTargetedCoords(coords, centerMulti);
        addAuditDisabledCoords(coords, centerMulti);
        addGrid(coords, centerMulti, radius);

        ArrayList<ChunkCoord> ordered = new ArrayList<>(coords);
        if (samples > 0 && ordered.size() > samples) {
            return ordered.subList(0, samples);
        }
        return ordered;
    }

    private static void addRegressionCoords(LinkedHashSet<ChunkCoord> coords, ChunkCoord centerMulti) {
        coords.add(new ChunkCoord(centerMulti.dimension(), -3, -2));
        coords.add(new ChunkCoord(centerMulti.dimension(), -3, 0));
    }

    private static void addTargetedCoords(LinkedHashSet<ChunkCoord> coords, ChunkCoord centerMulti) {
        int[][] absolute = {
            {0, 0}, {-1, 0}, {0, -1}, {1, 0}, {0, 1},
            {-8, -8}, {-8, 8}, {8, -8}, {8, 8},
            {-16, 0}, {16, 0}, {0, -16}, {0, 16},
            {-31, -31}, {31, 31}, {-32, 32}, {32, -32},
            {-33, 0}, {33, 0}, {0, -33}, {0, 33},
            {-64, -64}, {-64, 64}, {64, -64}, {64, 64},
            {-128, 0}, {128, 0}, {0, -128}, {0, 128}
        };
        for (int[] pair : absolute) {
            coords.add(new ChunkCoord(centerMulti.dimension(), pair[0], pair[1]));
            coords.add(new ChunkCoord(centerMulti.dimension(), centerMulti.chunkX() + pair[0], centerMulti.chunkZ() + pair[1]));
        }
    }

    private static void addAuditDisabledCoords(LinkedHashSet<ChunkCoord> coords, ChunkCoord centerMulti) {
        for (ChunkCoord coord : FastMultiChunkPlanner.auditDisabledCoords()) {
            if (coord != null) {
                coords.add(new ChunkCoord(centerMulti.dimension(), coord.chunkX(), coord.chunkZ()));
            }
        }
    }

    private static void addGrid(LinkedHashSet<ChunkCoord> coords, ChunkCoord centerMulti, int radius) {
        coords.add(centerMulti);
        for (int ring = 1; ring <= radius; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    coords.add(new ChunkCoord(centerMulti.dimension(), centerMulti.chunkX() + dx, centerMulti.chunkZ() + dz));
                }
            }
        }
    }

    private static Mismatch compare(IDimensionInfo dimInfo, ChunkCoord multiCoord, int areaSize, MultiChunk original, MultiChunk fast) {
        if (original == null || fast == null) {
            return new Mismatch(
                multiCoord,
                "null_result",
                "stage=null_result original=" + (original != null) + " fast=" + (fast != null));
        }
        MultiChunkAccessor originalAccessor = (MultiChunkAccessor) original;
        MultiChunkAccessor fastAccessor = (MultiChunkAccessor) fast;
        ChunkCoord originalTopLeft = originalAccessor.lc2h$getTopLeft();
        ChunkCoord fastTopLeft = fastAccessor.lc2h$getTopLeft();
        int originalAreaSize = originalAccessor.lc2h$getAreaSize();
        if (originalTopLeft == null || fastTopLeft == null) {
            return new Mismatch(multiCoord, "bounds", "stage=bounds missing top-left coord");
        }
        if (originalAreaSize != fastAccessor.lc2h$getAreaSize()
            || !originalTopLeft.dimension().equals(fastTopLeft.dimension())
            || originalTopLeft.chunkX() != fastTopLeft.chunkX()
            || originalTopLeft.chunkZ() != fastTopLeft.chunkZ()) {
            return new Mismatch(
                multiCoord,
                "bounds",
                "stage=bounds original=" + originalTopLeft + "/" + originalAreaSize
                    + " fast=" + fastTopLeft + "/" + fastAccessor.lc2h$getAreaSize());
        }
        for (int z = 0; z < originalAreaSize; z++) {
            for (int x = 0; x < originalAreaSize; x++) {
                ChunkCoord cellCoord = new ChunkCoord(
                    originalTopLeft.dimension(),
                    originalTopLeft.chunkX() + x,
                    originalTopLeft.chunkZ() + z);
                MultiChunkSnapshot.MultiChunkCell originalCell = MultiChunkSnapshot.describeCell(original, cellCoord);
                MultiChunkSnapshot.MultiChunkCell fastCell = MultiChunkSnapshot.describeCell(fast, cellCoord);
                if (!sameCell(originalCell, fastCell)) {
                    String styleMismatch = FastMultiChunkPlanner.auditStyleGridMismatch(dimInfo, multiCoord, areaSize);
                    String factMismatch = styleMismatch == null ? FastMultiChunkPlanner.auditFactGridMismatch(dimInfo, multiCoord, areaSize) : null;
                    String classification = styleMismatch != null ? "style_grid" : (factMismatch != null ? "facts" : "placement");
                    String detail = (styleMismatch != null ? styleMismatch : (factMismatch != null ? factMismatch : "stage=placement"))
                        + " cell=" + x + "," + z
                        + " original=" + describeCell(originalCell)
                        + " fast=" + describeCell(fastCell)
                        + " " + FastMultiChunkPlanner.auditPlacementTrace(dimInfo, multiCoord, areaSize, x, z);
                    return new Mismatch(multiCoord, classification, detail);
                }
            }
        }
        return null;
    }

    private static boolean sameCell(MultiChunkSnapshot.MultiChunkCell original, MultiChunkSnapshot.MultiChunkCell fast) {
        if (original == fast) {
            return true;
        }
        if (original == null || fast == null) {
            return false;
        }
        return original.offsetX() == fast.offsetX()
            && original.offsetZ() == fast.offsetZ()
            && Objects.equals(original.name(), fast.name());
    }

    private static String describeCell(MultiChunkSnapshot.MultiChunkCell cell) {
        if (cell == null) {
            return "empty";
        }
        return cell.name() + "@(" + cell.offsetX() + "," + cell.offsetZ() + ")";
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

    private static String safeWorldStyle(IDimensionInfo dimInfo) {
        try {
            return dimInfo.getWorldStyle() != null && dimInfo.getWorldStyle().getName() != null
                ? dimInfo.getWorldStyle().getName()
                : "unknown";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    public record Mismatch(ChunkCoord multiCoord, String classification, String detail) {
    }

    public record ParityReport(String matrixId,
                               String dimension,
                               long seed,
                               String profile,
                               String outsideProfile,
                               String worldStyle,
                               int centerMultiX,
                               int centerMultiZ,
                               int radius,
                               int samples,
                               int areaSize,
                               int checked,
                               long totalCellsCompared,
                               long totalFieldComparisons,
                               int mismatches,
                               Map<String, Integer> mismatchCounts,
                               String configSignature,
                               Set<ChunkCoord> auditDisabledBefore,
                               Set<ChunkCoord> auditDisabledAfter,
                               Mismatch firstMismatch,
                               long durationMs) {
        private static ParityReport notRun() {
            return new ParityReport(
                MATRIX_ID,
                "unknown",
                0L,
                "unknown",
                "unknown",
                "unknown",
                0,
                0,
                0,
                0,
                0,
                0,
                0L,
                0L,
                0,
                Map.of(),
                "unknown",
                Set.of(),
                Set.of(),
                null,
                0L
            );
        }

        public String summary() {
            if (mismatches <= 0) {
                return String.format(Locale.ROOT,
                    "matrix=%s dim=%s seed=%d profile=%s outside=%s style=%s center=%d,%d area=%d checked=%d cells=%d fields=%d mismatches=0 durationMs=%d auditDisabledBefore=%d auditDisabledAfter=%d",
                    matrixId, dimension, seed, profile, outsideProfile, worldStyle, centerMultiX, centerMultiZ, areaSize, checked, totalCellsCompared, totalFieldComparisons, durationMs, auditDisabledBefore.size(), auditDisabledAfter.size());
            }
            return String.format(Locale.ROOT,
                "matrix=%s dim=%s seed=%d profile=%s outside=%s style=%s center=%d,%d area=%d checked=%d cells=%d fields=%d mismatches=%d durationMs=%d classes=%s firstCoord=%s %s",
                matrixId,
                dimension,
                seed,
                profile,
                outsideProfile,
                worldStyle,
                centerMultiX,
                centerMultiZ,
                areaSize,
                checked,
                totalCellsCompared,
                totalFieldComparisons,
                mismatches,
                durationMs,
                mismatchCounts,
                firstMismatch == null ? "unknown" : firstMismatch.multiCoord(),
                firstMismatch == null ? "stage=unknown" : firstMismatch.detail());
        }

        public List<String> summaryLines() {
            ArrayList<String> lines = new ArrayList<>();
            lines.add(String.format(Locale.ROOT,
                "MultiChunkParity: matrix=%s dim=%s seed=%d profile=%s outside=%s style=%s center=%d,%d area=%d radius=%d samples=%d checked=%d cells=%d fields=%d mismatches=%d durationMs=%d classes=%s",
                matrixId, dimension, seed, profile, outsideProfile, worldStyle, centerMultiX, centerMultiZ, areaSize, radius, samples, checked, totalCellsCompared, totalFieldComparisons, mismatches, durationMs, mismatchCounts));
            lines.add("MultiChunkParity fields: perCell=[occupied,name,offsetX,offsetZ] mismatchAudit=[style_grid,facts,placement_trace]");
            lines.add("MultiChunkParity config: " + configSignature);
            lines.add(String.format(Locale.ROOT,
                "MultiChunkParity auditDisabled: before=%d after=%d coords=%s",
                auditDisabledBefore.size(), auditDisabledAfter.size(), auditDisabledAfter));
            if (firstMismatch != null) {
                lines.add("MultiChunkParity firstMismatch: coord=" + firstMismatch.multiCoord() + " " + firstMismatch.detail());
            }
            return lines;
        }
    }
}
