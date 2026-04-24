package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import org.admany.lc2h.mixin.accessor.lostcities.MultiChunkAccessor;
import org.admany.lc2h.util.lostcities.MultiChunkCacheAccess;
import org.admany.lc2h.worldgen.async.snapshot.MultiChunkSnapshot;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class MultiChunkBoundaryRegistry {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("lc2h.multichunk.boundaryStitch", "true"));
    private static final int MAX_RADIUS = Math.max(1, Math.min(4, Integer.getInteger("lc2h.multichunk.boundaryLaneCount", 2)));
    private static final long TTL_MS = Math.max(30_000L,
        Long.getLong("lc2h.multichunk.boundaryTtlMs", TimeUnit.MINUTES.toMillis(10)));
    private static final int MAX_CACHE = Math.max(256, Integer.getInteger("lc2h.multichunk.boundaryCacheMax", 4096));
    private static final int PRUNE_EVERY = Math.max(128, Integer.getInteger("lc2h.multichunk.boundaryPruneEvery", 512));

    private static final ConcurrentHashMap<ChunkCoord, SummaryEntry> SUMMARIES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BoundaryKey, ContractEntry> CONTRACTS = new ConcurrentHashMap<>();
    private static final AtomicInteger OPS = new AtomicInteger();

    private MultiChunkBoundaryRegistry() {
    }

    public enum Edge {
        WEST,
        EAST,
        NORTH,
        SOUTH
    }

    public record Decision(boolean reserved, String reason, Edge edge, int offset, String contractKey) {
        public static final Decision NONE = new Decision(false, "none", null, -1, null);
    }

    private record SummaryEntry(AreaSummary summary, long timestampMs) {
    }

    private record ContractEntry(BoundaryContract contract, long timestampMs) {
    }

    private record AreaSummary(ChunkCoord multiCoord, ChunkCoord topLeft, int areaSize, EdgeCell[][] edges) {
        EdgeCell cell(Edge edge, int offset) {
            if (edge == null || offset < 0 || offset >= areaSize) {
                return EdgeCell.EMPTY;
            }
            EdgeCell[] cells = edges[edge.ordinal()];
            if (cells == null || offset >= cells.length) {
                return EdgeCell.EMPTY;
            }
            EdgeCell cell = cells[offset];
            return cell == null ? EdgeCell.EMPTY : cell;
        }
    }

    private record EdgeCell(boolean occupied, boolean openLike, String name, int offsetX, int offsetZ) {
        static final EdgeCell EMPTY = new EdgeCell(false, true, null, 0, 0);
    }

    private record BoundaryKey(String dimension, long seed, String profile, int ax, int az, int bx, int bz, boolean vertical) {
        String id() {
            return dimension + '|' + profile + '|' + seed + '|' + ax + ',' + az + ':' + bx + ',' + bz + '|' + (vertical ? "x" : "z");
        }
    }

    private record BoundaryContract(BoundaryKey key, boolean[] openOffsets, String reason) {
        boolean open(int offset) {
            return offset >= 0 && offset < openOffsets.length && openOffsets[offset];
        }
    }

    public static void register(IDimensionInfo provider, ChunkCoord multiCoord, MultiChunk multiChunk) {
        if (!ENABLED || provider == null || multiCoord == null || multiChunk == null) {
            return;
        }
        AreaSummary summary = summarize(multiCoord, multiChunk);
        if (summary == null) {
            return;
        }
        long now = System.currentTimeMillis();
        SUMMARIES.put(multiCoord, new SummaryEntry(summary, now));
        buildNeighborContracts(provider, summary, now);
        prune(now);
    }

    public static Decision boundaryDecision(IDimensionInfo provider, ChunkCoord coord) {
        if (!ENABLED || provider == null || coord == null) {
            return Decision.NONE;
        }
        int areaSize = areaSize(provider);
        if (areaSize <= 1) {
            return Decision.NONE;
        }
        LostCityProfile profile = profile(provider);
        boolean ownCity = isCity(coord, provider, profile);
        if (!ownCity) {
            return Decision.NONE;
        }

        int localX = Math.floorMod(coord.chunkX(), areaSize);
        int localZ = Math.floorMod(coord.chunkZ(), areaSize);
        ChunkCoord multiCoord = toMultiCoord(coord, areaSize);
        long now = System.currentTimeMillis();
        ensureSummary(provider, multiCoord, now);

        Decision decision = checkEdge(provider, coord, profile, areaSize, multiCoord, Edge.WEST, localX, localZ, now);
        if (decision.reserved()) {
            return decision;
        }
        decision = checkEdge(provider, coord, profile, areaSize, multiCoord, Edge.EAST, localX, localZ, now);
        if (decision.reserved()) {
            return decision;
        }
        decision = checkEdge(provider, coord, profile, areaSize, multiCoord, Edge.NORTH, localX, localZ, now);
        if (decision.reserved()) {
            return decision;
        }
        return checkEdge(provider, coord, profile, areaSize, multiCoord, Edge.SOUTH, localX, localZ, now);
    }

    public static boolean shouldReserveBoundaryCorridor(IDimensionInfo provider, ChunkCoord coord) {
        return boundaryDecision(provider, coord).reserved();
    }

    public static void invalidateArea(ChunkCoord topLeft, int areaSize) {
        if (topLeft == null || areaSize <= 0) {
            return;
        }
        int minMultiX = Math.floorDiv(topLeft.chunkX(), areaSize) - 1;
        int maxMultiX = Math.floorDiv(topLeft.chunkX() + areaSize - 1, areaSize) + 1;
        int minMultiZ = Math.floorDiv(topLeft.chunkZ(), areaSize) - 1;
        int maxMultiZ = Math.floorDiv(topLeft.chunkZ() + areaSize - 1, areaSize) + 1;
        for (int mx = minMultiX; mx <= maxMultiX; mx++) {
            for (int mz = minMultiZ; mz <= maxMultiZ; mz++) {
                SUMMARIES.remove(new ChunkCoord(topLeft.dimension(), mx, mz));
            }
        }
        CONTRACTS.keySet().removeIf(key -> key.dimension().equals(String.valueOf(topLeft.dimension().location()))
            && key.ax() >= minMultiX && key.ax() <= maxMultiX
            && key.az() >= minMultiZ && key.az() <= maxMultiZ);
    }

    private static Decision checkEdge(IDimensionInfo provider,
                                      ChunkCoord coord,
                                      LostCityProfile profile,
                                      int areaSize,
                                      ChunkCoord multiCoord,
                                      Edge edge,
                                      int localX,
                                      int localZ,
                                      long now) {
        int offset;
        ChunkCoord neighborMulti;
        ChunkCoord neighborChunk;
        switch (edge) {
            case WEST -> {
                if (localX != 0) return Decision.NONE;
                offset = localZ;
                neighborMulti = multiCoord.offset(-1, 0);
                neighborChunk = coord.offset(-1, 0);
            }
            case EAST -> {
                if (localX != areaSize - 1) return Decision.NONE;
                offset = localZ;
                neighborMulti = multiCoord.offset(1, 0);
                neighborChunk = coord.offset(1, 0);
            }
            case NORTH -> {
                if (localZ != 0) return Decision.NONE;
                offset = localX;
                neighborMulti = multiCoord.offset(0, -1);
                neighborChunk = coord.offset(0, -1);
            }
            case SOUTH -> {
                if (localZ != areaSize - 1) return Decision.NONE;
                offset = localX;
                neighborMulti = multiCoord.offset(0, 1);
                neighborChunk = coord.offset(0, 1);
            }
            default -> {
                return Decision.NONE;
            }
        }

        if (!isCity(neighborChunk, provider, profile)) {
            return Decision.NONE;
        }
        ensureSummary(provider, neighborMulti, now);
        BoundaryContract contract = contract(provider, multiCoord, neighborMulti, areaSize, now);
        if (contract == null || !contract.open(offset)) {
            return Decision.NONE;
        }
        return new Decision(true, contract.reason(), edge, offset, contract.key().id());
    }

    private static BoundaryContract contract(IDimensionInfo provider,
                                             ChunkCoord first,
                                             ChunkCoord second,
                                             int areaSize,
                                             long now) {
        BoundaryKey key = key(provider, first, second);
        ContractEntry cached = CONTRACTS.get(key);
        if (cached != null && isFresh(cached.timestampMs(), now)) {
            return cached.contract();
        }
        AreaSummary a = summary(first, now);
        AreaSummary b = summary(second, now);
        BoundaryContract contract = buildContract(provider, key, areaSize, a, b);
        CONTRACTS.put(key, new ContractEntry(contract, now));
        return contract;
    }

    private static void buildNeighborContracts(IDimensionInfo provider, AreaSummary summary, long now) {
        ChunkCoord multi = summary.multiCoord();
        int areaSize = summary.areaSize();
        contract(provider, multi, multi.offset(-1, 0), areaSize, now);
        contract(provider, multi, multi.offset(1, 0), areaSize, now);
        contract(provider, multi, multi.offset(0, -1), areaSize, now);
        contract(provider, multi, multi.offset(0, 1), areaSize, now);
    }

    private static BoundaryContract buildContract(IDimensionInfo provider,
                                                  BoundaryKey key,
                                                  int areaSize,
                                                  AreaSummary a,
                                                  AreaSummary b) {
        boolean[] open = new boolean[areaSize];
        String reason = "deterministic";
        if (a != null && b != null) {
            Edge edgeA = edgeFromTo(a.multiCoord(), b.multiCoord());
            Edge edgeB = opposite(edgeA);
            if (edgeA != null && edgeB != null) {
                for (int i = 0; i < areaSize; i++) {
                    EdgeCell ca = a.cell(edgeA, i);
                    EdgeCell cb = b.cell(edgeB, i);
                    open[i] = ca.openLike() || cb.openLike();
                }
                reason = "edge-summary";
            }
        }
        ensureDeterministicLanes(provider, key, open);
        return new BoundaryContract(key, open, reason);
    }

    private static void ensureDeterministicLanes(IDimensionInfo provider, BoundaryKey key, boolean[] open) {
        if (open.length == 0) {
            return;
        }
        int lanes = Math.min(MAX_RADIUS, Math.max(1, open.length / 8));
        long state = mix64(key.seed()
            ^ ((long) key.ax() * 0x9E3779B97F4A7C15L)
            ^ ((long) key.az() * 0xC2B2AE3D27D4EB4FL)
            ^ ((long) key.bx() * 0x165667B19E3779F9L)
            ^ ((long) key.bz() * 0x85EBCA77C2B2AE63L)
            ^ (key.vertical() ? 0xD1B54A32D192ED03L : 0xABC98388FB8FAC03L));
        for (int i = 0; i < lanes; i++) {
            state = mix64(state + i * 0x9E3779B97F4A7C15L);
            int offset = Math.floorMod((int) state, open.length);
            open[offset] = true;
            if (offset > 0 && open.length >= 12) {
                open[offset - 1] = true;
            }
            if (offset + 1 < open.length && open.length >= 12) {
                open[offset + 1] = true;
            }
        }
    }

    private static AreaSummary summarize(ChunkCoord multiCoord, MultiChunk multiChunk) {
        try {
            MultiChunkAccessor accessor = (MultiChunkAccessor) multiChunk;
            ChunkCoord topLeft = accessor.lc2h$getTopLeft();
            int areaSize = accessor.lc2h$getAreaSize();
            if (topLeft == null || areaSize <= 0) {
                return null;
            }
            EdgeCell[][] edges = new EdgeCell[Edge.values().length][areaSize];
            for (int i = 0; i < areaSize; i++) {
                edges[Edge.WEST.ordinal()][i] = cell(multiChunk, new ChunkCoord(topLeft.dimension(), topLeft.chunkX(), topLeft.chunkZ() + i));
                edges[Edge.EAST.ordinal()][i] = cell(multiChunk, new ChunkCoord(topLeft.dimension(), topLeft.chunkX() + areaSize - 1, topLeft.chunkZ() + i));
                edges[Edge.NORTH.ordinal()][i] = cell(multiChunk, new ChunkCoord(topLeft.dimension(), topLeft.chunkX() + i, topLeft.chunkZ()));
                edges[Edge.SOUTH.ordinal()][i] = cell(multiChunk, new ChunkCoord(topLeft.dimension(), topLeft.chunkX() + i, topLeft.chunkZ() + areaSize - 1));
            }
            return new AreaSummary(multiCoord, topLeft, areaSize, edges);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static EdgeCell cell(MultiChunk multiChunk, ChunkCoord coord) {
        MultiChunkSnapshot.MultiChunkCell cell = MultiChunkSnapshot.describeCell(multiChunk, coord);
        if (cell == null) {
            return EdgeCell.EMPTY;
        }
        String name = cell.name();
        return new EdgeCell(true, isOpenLike(name), name, cell.offsetX(), cell.offsetZ());
    }

    private static boolean isOpenLike(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("street")
            || n.contains("road")
            || n.contains("bridge")
            || n.contains("park")
            || n.contains("plaza")
            || n.contains("cross")
            || n.contains("none")
            || n.contains("empty")
            || n.contains("rubble");
    }

    private static void ensureSummary(IDimensionInfo provider, ChunkCoord multiCoord, long now) {
        if (summary(multiCoord, now) != null) {
            return;
        }
        MultiChunk cached = MultiChunkCacheAccess.get(multiCoord);
        if (cached != null) {
            register(provider, multiCoord, cached);
        }
    }

    private static AreaSummary summary(ChunkCoord multiCoord, long now) {
        SummaryEntry entry = SUMMARIES.get(multiCoord);
        if (entry == null || !isFresh(entry.timestampMs(), now)) {
            return null;
        }
        return entry.summary();
    }

    private static BoundaryKey key(IDimensionInfo provider, ChunkCoord first, ChunkCoord second) {
        ChunkCoord a = first;
        ChunkCoord b = second;
        if (compare(a, b) > 0) {
            a = second;
            b = first;
        }
        return new BoundaryKey(
            String.valueOf(a.dimension().location()),
            seed(provider),
            profileName(provider),
            a.chunkX(),
            a.chunkZ(),
            b.chunkX(),
            b.chunkZ(),
            a.chunkX() != b.chunkX()
        );
    }

    private static Edge edgeFromTo(ChunkCoord from, ChunkCoord to) {
        int dx = to.chunkX() - from.chunkX();
        int dz = to.chunkZ() - from.chunkZ();
        if (dx == 1 && dz == 0) return Edge.EAST;
        if (dx == -1 && dz == 0) return Edge.WEST;
        if (dx == 0 && dz == 1) return Edge.SOUTH;
        if (dx == 0 && dz == -1) return Edge.NORTH;
        return null;
    }

    private static Edge opposite(Edge edge) {
        if (edge == null) return null;
        return switch (edge) {
            case WEST -> Edge.EAST;
            case EAST -> Edge.WEST;
            case NORTH -> Edge.SOUTH;
            case SOUTH -> Edge.NORTH;
        };
    }

    private static ChunkCoord toMultiCoord(ChunkCoord coord, int areaSize) {
        return new ChunkCoord(coord.dimension(), Math.floorDiv(coord.chunkX(), areaSize), Math.floorDiv(coord.chunkZ(), areaSize));
    }

    private static int areaSize(IDimensionInfo provider) {
        try {
            return Math.max(1, provider.getWorldStyle().getMultiSettings().areasize());
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private static LostCityProfile profile(IDimensionInfo provider) {
        try {
            return provider.getProfile();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isCity(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        try {
            return BuildingInfo.isCityRaw(coord, provider, profile != null ? profile : provider.getProfile());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long seed(IDimensionInfo provider) {
        try {
            return provider.getSeed();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String profileName(IDimensionInfo provider) {
        try {
            LostCityProfile profile = provider.getProfile();
            return profile == null ? "<unknown>" : profile.getName();
        } catch (Throwable ignored) {
            return "<unknown>";
        }
    }

    private static int compare(ChunkCoord left, ChunkCoord right) {
        int x = Integer.compare(left.chunkX(), right.chunkX());
        if (x != 0) {
            return x;
        }
        return Integer.compare(left.chunkZ(), right.chunkZ());
    }

    private static boolean isFresh(long timestampMs, long now) {
        return (now - timestampMs) <= TTL_MS;
    }

    private static void prune(long now) {
        int op = OPS.incrementAndGet();
        if (op < PRUNE_EVERY && SUMMARIES.size() <= MAX_CACHE && CONTRACTS.size() <= MAX_CACHE) {
            return;
        }
        OPS.set(0);
        SUMMARIES.entrySet().removeIf(entry -> entry.getValue() == null || !isFresh(entry.getValue().timestampMs(), now) || SUMMARIES.size() > MAX_CACHE);
        CONTRACTS.entrySet().removeIf(entry -> entry.getValue() == null || !isFresh(entry.getValue().timestampMs(), now) || CONTRACTS.size() > MAX_CACHE);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }
}
