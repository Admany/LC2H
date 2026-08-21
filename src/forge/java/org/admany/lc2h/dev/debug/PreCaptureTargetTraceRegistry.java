package org.admany.lc2h.dev.debug;

import mcjty.lostcities.config.LandscapeType;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.CitySphere;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.MultifaceGrowthConfiguration;
import org.admany.lc2h.runtime.Lc2hRuntimeModes;
import org.admany.lc2h.util.ResourceLocations;
import org.admany.lc2h.worldgen.scope.WorldGenScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class PreCaptureTargetTraceRegistry {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("lc2h.precaptureTrace.enabled", "false"));
    private static final String OUTPUT = System.getProperty("lc2h.precaptureTrace.output", "logs/lc2h-precapture-trace.txt").trim();
    private static final boolean TRACE_WRITES = Boolean.parseBoolean(System.getProperty("lc2h.precaptureTrace.captureWrites", "true"));
    private static final boolean TRACE_CALLER = Boolean.parseBoolean(System.getProperty("lc2h.precaptureTrace.captureCaller", "false"));
    private static final int TRACE_CALLER_DEPTH = Math.max(1, Integer.getInteger("lc2h.precaptureTrace.captureCallerDepth", 4));
    private static final ResourceLocation TARGET_DIMENSION = ResourceLocations.tryParse(
        System.getProperty("lc2h.precaptureTrace.dimension", "minecraft:overworld").trim());
    private static final BlockPos TARGET_POS = parseBlockPos(System.getProperty("lc2h.precaptureTrace.pos", "-39,4,-31"));
    private static final int TARGET_CHUNK_X = TARGET_POS == null ? 0 : TARGET_POS.getX() >> 4;
    private static final int TARGET_CHUNK_Z = TARGET_POS == null ? 0 : TARGET_POS.getZ() >> 4;
    private static final int CHUNK_RADIUS = Math.max(0, Integer.getInteger("lc2h.precaptureTrace.chunkRadius", 1));
    private static final boolean ALLOW_PARITY_WIDE_RADIUS =
        Boolean.parseBoolean(System.getProperty("lc2h.precaptureTrace.allowParityWideRadius", "false"));

    private static final ThreadLocal<ArrayDeque<TraceContext>> CURRENT = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<AmbientChunkContext>> AMBIENT_CHUNK_CONTEXT = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<PendingSectionTarget> PENDING_SECTION_TARGET = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, AtomicLong> CHUNK_INVOCATIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, TraceRun> RUNS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> AMBIENT_RUNS = new ConcurrentHashMap<>();
    private static final AtomicLong RUN_SEQUENCE = new AtomicLong();
    private static final AtomicLong GLOBAL_ORDER = new AtomicLong();

    private PreCaptureTargetTraceRegistry() {
    }

    public static boolean enabled() {
        return ENABLED && TARGET_DIMENSION != null && TARGET_POS != null;
    }

    public static boolean shouldTracePosition(BlockPos pos) {
        return enabled() && Objects.equals(pos, TARGET_POS);
    }

    public static boolean shouldTraceChunk(WorldGenLevel world, ChunkAccess chunk) {
        if (!enabled() || world == null || chunk == null || world.getLevel() == null) {
            return false;
        }
        return world.getLevel().dimension().location().equals(TARGET_DIMENSION)
            && chunk.getPos().x == TARGET_CHUNK_X
            && chunk.getPos().z == TARGET_CHUNK_Z;
    }

    public static boolean shouldTraceChunk(ChunkAccess chunk) {
        return enabled()
            && chunk != null
            && chunk.getPos().x == TARGET_CHUNK_X
            && chunk.getPos().z == TARGET_CHUNK_Z;
    }

    public static void reset() {
        CURRENT.remove();
        CHUNK_INVOCATIONS.clear();
        RUNS.clear();
        AMBIENT_RUNS.clear();
        AMBIENT_CHUNK_CONTEXT.remove();
        PENDING_SECTION_TARGET.remove();
        RUN_SEQUENCE.set(0L);
        GLOBAL_ORDER.set(0L);
    }

    public static void armForUpcomingRun() {
        if (!enabled()) {
            return;
        }
        reset();
    }

    public static void beginGenerate(IDimensionInfo provider, ChunkCoord coord, WorldGenLevel world, ChunkAccess chunk) {
        beginPhase("terrain.generate", provider, coord, world, chunk);
    }

    public static void beginFeaturePlace(IDimensionInfo provider, ChunkCoord coord, WorldGenLevel world) {
        beginPhase("feature.place", provider, coord, world, null);
    }

    public static void beginSpherePlace(IDimensionInfo provider, ChunkCoord coord, WorldGenLevel world) {
        beginPhase("sphere.place", provider, coord, world, null);
    }

    public static void beginSphereGenerate(IDimensionInfo provider, ChunkCoord coord, WorldGenLevel world, ChunkAccess chunk) {
        beginPhase("sphere.generate", provider, coord, world, chunk);
    }

    private static void beginPhase(String phase, IDimensionInfo provider, ChunkCoord coord, WorldGenLevel world, ChunkAccess chunk) {
        if (!isRelevantChunk(provider, coord, world, chunk)) {
            return;
        }
        ChunkCoord effectiveCoord = effectiveCoord(provider, coord, world, chunk);
        if (effectiveCoord == null) {
            return;
        }
        long runId = RUN_SEQUENCE.incrementAndGet();
        long invocation = CHUNK_INVOCATIONS
            .computeIfAbsent(chunkKey(provider, effectiveCoord, world), ignored -> new AtomicLong())
            .incrementAndGet();
        TraceRun run = new TraceRun(runId, effectiveCoord, invocation);
        run.putMeta("mode", Lc2hRuntimeModes.modeSummary());
        run.putMeta("baselineMode", String.valueOf(Lc2hRuntimeModes.baselineMode()));
        run.putMeta("dimension", dimensionString(resolveDimension(provider, effectiveCoord, world)));
        run.putMeta("chunk", effectiveCoord.chunkX() + "," + effectiveCoord.chunkZ());
        run.putMeta("targetChunk", TARGET_CHUNK_X + "," + TARGET_CHUNK_Z);
        run.putMeta("targetPos", posString(TARGET_POS));
        run.putMeta("lifecycleId", String.valueOf(WorldGenScope.activeLifecycleId()));
        run.putMeta("featurePhase", phase);
        run.putMeta("seed", safeString(() -> String.valueOf(provider == null ? 0L : provider.getSeed())));
        run.putMeta("providerClass", provider == null ? "<null>" : provider.getClass().getName());
        run.putMeta("terrainFeatureClass", safeString(() -> provider == null || provider.getFeature() == null ? "<null>" : provider.getFeature().getClass().getName()));
        run.putMeta("profile", safeProfile(provider == null ? null : provider.getProfile()));
        run.putMeta("outsideProfile", safeProfile(provider == null ? null : provider.getOutsideProfile()));
        run.putMeta("worldStyle", safeString(() -> provider == null || provider.getWorldStyle() == null ? "unknown" : provider.getWorldStyle().getName()));
        run.putMeta("landscape", safeLandscape(provider == null ? null : provider.getProfile()));
        run.putMeta("chunkStatus", safeString(() -> chunk == null || chunk.getStatus() == null ? "unknown" : chunk.getStatus().toString()));
        run.putMeta("thread", Thread.currentThread().getName());
        run.putMeta("initialTargetState", safeState(world == null || !matchesTargetDimension(provider, effectiveCoord, world) ? null : world.getBlockState(TARGET_POS)));
        run.putMeta("targetBiome", safeBiome(world, TARGET_POS));
        run.putMeta("generatedAt", Instant.now().toString());
        run.phase = phase;
        run.event("generate-start", "worldgen entry phase=" + phase);
        RUNS.put(runId, run);
        CURRENT.get().push(new TraceContext(runId));
    }

    public static void recordHeightmap(ChunkCoord coord, ChunkHeightmap heightmap) {
        TraceRun run = currentRun();
        if (run == null || heightmap == null) {
            return;
        }
        run.putMeta("heightmap.height", String.valueOf(heightmap.getHeight()));
        run.putMeta("heightmap.min", String.valueOf(heightmap.getMinHeight()));
        run.putMeta("heightmap.max", String.valueOf(heightmap.getMaxHeight()));
        run.event("heightmap", "coord=" + coord.chunkX() + "," + coord.chunkZ()
            + " height=" + heightmap.getHeight()
            + " min=" + heightmap.getMinHeight()
            + " max=" + heightmap.getMaxHeight());
    }

    public static void recordBuildingInfo(BuildingInfo info) {
        TraceRun run = currentRun();
        if (run == null || info == null) {
            return;
        }
        run.putMeta("building.isCity", String.valueOf(info.isCity));
        run.putMeta("building.outsideChunk", String.valueOf(info.outsideChunk));
        run.putMeta("building.cityLevel", String.valueOf(info.cityLevel));
        run.putMeta("building.hasBuilding", String.valueOf(info.hasBuilding));
        run.putMeta("building.type", safeString(info.getBuildingType()));
        run.putMeta("building.sphere", safeString(info.getSphere()));
        run.event("building-info", "isCity=" + info.isCity
            + " outsideChunk=" + info.outsideChunk
            + " cityLevel=" + info.cityLevel
            + " hasBuilding=" + info.hasBuilding
            + " building=" + safeString(info.getBuildingType())
            + " sphere=" + safeString(info.getSphere()));
    }

    public static void recordPath(String phase, BuildingInfo info) {
        TraceRun run = currentRun();
        if (run == null) {
            return;
        }
        run.phase = phase;
        String detail = info == null
            ? "phase=" + phase
            : "phase=" + phase
                + " isCity=" + info.isCity
                + " outsideChunk=" + info.outsideChunk
                + " cityLevel=" + info.cityLevel
                + " building=" + safeString(info.getBuildingType());
        run.event("terrain-path", detail);
    }

    public static void recordFeaturePlaceContext(String featureKind,
                                                 boolean providerPresent,
                                                 BlockPos centerPos,
                                                 String biomeId,
                                                 boolean voidBiome) {
        TraceRun run = currentRun();
        if (run == null) {
            return;
        }
        run.putMeta("place.kind", featureKind);
        run.putMeta("place.providerPresent", String.valueOf(providerPresent));
        run.putMeta("place.centerPos", posString(centerPos));
        run.putMeta("place.centerBiome", safeString(biomeId));
        run.putMeta("place.centerBiomeIsVoid", String.valueOf(voidBiome));
        run.event("place-context",
            "kind=" + featureKind
                + " providerPresent=" + providerPresent
                + " centerPos=" + posString(centerPos)
                + " centerBiome=" + safeString(biomeId)
                + " centerBiomeIsVoid=" + voidBiome);
    }

    public static void recordFeaturePlaceReturn(String featureKind, boolean result, String reason) {
        TraceRun run = currentRun();
        if (run == null) {
            return;
        }
        run.putMeta("place.result", String.valueOf(result));
        run.putMeta("place.returnReason", safeString(reason));
        run.event("place-return",
            "kind=" + featureKind
                + " result=" + result
                + " reason=" + safeString(reason));
    }

    public static void recordSphereGenerateStart(ChunkCoord coord, LostCityProfile profile) {
        TraceRun run = currentRun();
        if (run == null) {
            return;
        }
        run.phase = "sphere.generate";
        run.putMeta("sphere.profile", safeProfile(profile));
        run.putMeta("sphere.landscape", safeLandscape(profile));
        run.event("sphere-generate-start",
            "coord=" + coord.chunkX() + "," + coord.chunkZ()
                + " profile=" + safeProfile(profile)
                + " landscape=" + safeLandscape(profile));
    }

    public static void recordSphereLookup(CitySphere sphere) {
        TraceRun run = currentRun();
        if (run == null || sphere == null) {
            return;
        }
        run.putMeta("sphere.centerChunk", sphere.getCenter().chunkX() + "," + sphere.getCenter().chunkZ());
        run.putMeta("sphere.centerPos", posString(sphere.getCenterPos()));
        run.putMeta("sphere.radius", String.format(Locale.ROOT, "%.3f", sphere.getRadius()));
        run.putMeta("sphere.enabled", String.valueOf(sphere.isEnabled()));
        run.event("sphere-lookup",
            "enabled=" + sphere.isEnabled()
                + " centerChunk=" + sphere.getCenter().chunkX() + "," + sphere.getCenter().chunkZ()
                + " centerPos=" + posString(sphere.getCenterPos())
                + " radius=" + String.format(Locale.ROOT, "%.3f", sphere.getRadius()));
    }

    public static void recordSphereInit(CitySphere sphere) {
        TraceRun run = currentRun();
        if (run == null || sphere == null) {
            return;
        }
        run.putMeta("sphere.glass", safeState(sphere.getGlassBlock()));
        run.putMeta("sphere.base", safeState(sphere.getBaseBlock()));
        run.putMeta("sphere.side", safeState(sphere.getSideBlock()));
        run.event("sphere-init",
            "glass=" + safeState(sphere.getGlassBlock())
                + " base=" + safeState(sphere.getBaseBlock())
                + " side=" + safeState(sphere.getSideBlock()));
    }

    public static void recordSphereFill(int centerx, int centery, int centerz, int radius, BlockState glass, BlockState sideBlock) {
        TraceRun run = currentRun();
        if (run == null) {
            return;
        }
        run.phase = "sphere.fill";
        int targetLocalX = TARGET_POS.getX() - (run.coord.chunkX() << 4);
        int targetLocalZ = TARGET_POS.getZ() - (run.coord.chunkZ() << 4);
        int dx = targetLocalX - centerx;
        int dy = TARGET_POS.getY() - centery;
        int dz = targetLocalZ - centerz;
        int sqDist = dx * dx + dy * dy + dz * dz;
        run.putMeta("sphere.fill.centerLocal", centerx + "," + centery + "," + centerz);
        run.putMeta("sphere.fill.radius", String.valueOf(radius));
        run.putMeta("sphere.fill.glass", safeState(glass));
        run.putMeta("sphere.fill.side", safeState(sideBlock));
        run.putMeta("sphere.fill.targetLocal", targetLocalX + "," + TARGET_POS.getY() + "," + targetLocalZ);
        run.putMeta("sphere.fill.targetSqDist", String.valueOf(sqDist));
        run.event("sphere-fill",
            "centerLocal=" + centerx + "," + centery + "," + centerz
                + " radius=" + radius
                + " glass=" + safeState(glass)
                + " side=" + safeState(sideBlock)
                + " targetLocal=" + targetLocalX + "," + TARGET_POS.getY() + "," + targetLocalZ
                + " targetSqDist=" + sqDist);
    }

    public static void recordTerrainCorrectionEnter(ChunkCoord coord, ChunkHeightmap heightmap) {
        TraceRun run = currentRun();
        if (run == null) {
            return;
        }
        run.phase = "correctTerrainShape";
        run.event("terrain-correction-enter",
            "coord=" + coord.chunkX() + "," + coord.chunkZ()
                + " height=" + (heightmap == null ? "<null>" : heightmap.getHeight()));
    }

    public static void recordTerrainCorrectionExit(ChunkCoord coord, ChunkHeightmap heightmap) {
        TraceRun run = currentRun();
        if (run == null) {
            return;
        }
        run.event("terrain-correction-exit",
            "coord=" + coord.chunkX() + "," + coord.chunkZ()
                + " height=" + (heightmap == null ? "<null>" : heightmap.getHeight()));
    }

    public static void recordControlFlowAltered(String stage, ChunkCoord coord, String detail) {
        TraceRun run = currentRun();
        if (run == null) {
            return;
        }
        run.controlFlowAltered = true;
        run.event("control-flow-altered",
            "stage=" + stage
                + " coord=" + (coord == null ? "<null>" : coord.chunkX() + "," + coord.chunkZ())
                + (detail == null || detail.isBlank() ? "" : " " + detail));
    }

    public static void recordChunkPhaseSnapshot(String phase, String moment, ChunkAccess chunk, Throwable error) {
        if (!enabled() || chunk == null || chunk.getPos().x != TARGET_CHUNK_X || chunk.getPos().z != TARGET_CHUNK_Z) {
            return;
        }
        TraceRun run = ensureActiveRun("ambient." + phase);
        if (run == null) {
            return;
        }
        run.phase = phase;
        String targetState = safeState(safeChunkState(chunk, TARGET_POS));
        String chunkStatus = safeString(() -> chunk.getStatus() == null ? "unknown" : chunk.getStatus().toString());
        run.putMeta("phase." + phase + "." + moment + ".targetState", targetState);
        run.putMeta("phase." + phase + "." + moment + ".chunkStatus", chunkStatus);
        if (error != null) {
            run.putMeta("phase." + phase + "." + moment + ".error", error.getClass().getName());
        }
        run.event("phase-snapshot",
            "phase=" + phase
                + " moment=" + moment
                + " targetState=" + targetState
                + " chunkStatus=" + chunkStatus
                + (error == null ? "" : " error=" + error.getClass().getName() + ":" + safeString(error.getMessage())));
    }

    public static boolean shouldTraceAmbientChunk(WorldGenLevel world, ChunkAccess chunk) {
        if (!enabled() || world == null || chunk == null || world.getLevel() == null) {
            return false;
        }
        return matchesTargetDimension(null, new ChunkCoord(world.getLevel().dimension(), chunk.getPos().x, chunk.getPos().z), world)
            && Math.abs(chunk.getPos().x - TARGET_CHUNK_X) <= effectiveChunkRadius()
            && Math.abs(chunk.getPos().z - TARGET_CHUNK_Z) <= effectiveChunkRadius();
    }

    public static void beginAmbientChunkPhase(String phase, WorldGenLevel world, ChunkAccess chunk) {
        if (!shouldTraceAmbientChunk(world, chunk)) {
            return;
        }
        ArrayDeque<AmbientChunkContext> stack = AMBIENT_CHUNK_CONTEXT.get();
        stack.push(new AmbientChunkContext(phase, chunk.getPos().x, chunk.getPos().z));
    }

    public static void endAmbientChunkPhase(String phase, ChunkAccess chunk) {
        ArrayDeque<AmbientChunkContext> stack = AMBIENT_CHUNK_CONTEXT.get();
        if (stack.isEmpty()) {
            return;
        }
        AmbientChunkContext current = stack.peek();
        if (current == null) {
            return;
        }
        if (!Objects.equals(current.phase, phase)) {
            return;
        }
        if (chunk != null && (current.chunkX != chunk.getPos().x || current.chunkZ != chunk.getPos().z)) {
            return;
        }
        stack.pop();
        if (stack.isEmpty()) {
            AMBIENT_CHUNK_CONTEXT.remove();
        }
    }

    public static void beginSectionAccessTarget(BlockPos pos) {
        if (!enabled()) {
            return;
        }
        if (!Objects.equals(pos, TARGET_POS)) {
            PENDING_SECTION_TARGET.remove();
            return;
        }
        PENDING_SECTION_TARGET.set(new PendingSectionTarget(pos, pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15));
    }

    public static boolean shouldTracePendingSectionLocal(int localX, int localY, int localZ) {
        PendingSectionTarget pending = PENDING_SECTION_TARGET.get();
        return pending != null
            && pending.localX == localX
            && pending.localY == localY
            && pending.localZ == localZ;
    }

    public static void recordPendingSectionWrite(int localX, int localY, int localZ, BlockState oldState, BlockState newState, String caller) {
        PendingSectionTarget pending = PENDING_SECTION_TARGET.get();
        if (pending == null || pending.localX != localX || pending.localY != localY || pending.localZ != localZ) {
            return;
        }
        try {
            recordTargetWrite(pending.pos, oldState, newState, caller + " via " + BulkSectionAccess.class.getSimpleName());
        } finally {
            PENDING_SECTION_TARGET.remove();
        }
    }

    public static void recordTargetWrite(BlockPos pos, BlockState oldState, BlockState newState, String caller) {
        if (!TRACE_WRITES || !Objects.equals(pos, TARGET_POS)) {
            return;
        }
        TraceRun run = ensureActiveRun("ambient.worldgen");
        if (run == null) {
            return;
        }
        long order = GLOBAL_ORDER.incrementAndGet();
        run.event("target-write",
            "order=" + order
                + " phase=" + run.phase
                + " sourceChunk=" + currentAmbientChunkString()
                + " caller=" + caller
                + " thread=" + Thread.currentThread().getName()
                + " flags=driver-cache"
                + " old=" + safeState(oldState)
                + " new=" + safeState(newState)
                + " mixinAltered=" + run.controlFlowAltered);
    }

    public static void recordSkippedTargetWrite(BlockPos pos, BlockState currentState, BlockState attemptedState, String caller, String reason) {
        if (!TRACE_WRITES || !Objects.equals(pos, TARGET_POS)) {
            return;
        }
        TraceRun run = ensureActiveRun("ambient.worldgen");
        if (run == null) {
            return;
        }
        long order = GLOBAL_ORDER.incrementAndGet();
        run.event("target-write-skipped",
            "order=" + order
                + " phase=" + run.phase
                + " sourceChunk=" + currentAmbientChunkString()
                + " caller=" + caller
                + " thread=" + Thread.currentThread().getName()
                + " current=" + safeState(currentState)
                + " attempted=" + safeState(attemptedState)
                + " reason=" + safeString(reason)
                + " mixinAltered=" + run.controlFlowAltered);
    }

    public static void recordTargetPaletteChoice(BlockPos pos,
                                                 String paletteKind,
                                                 int paletteIndex,
                                                 int gSeedBefore,
                                                 int gSeedAfter,
                                                 BlockState chosenState,
                                                 String detail) {
        if (!TRACE_WRITES || !Objects.equals(pos, TARGET_POS)) {
            return;
        }
        TraceRun run = ensureActiveRun("ambient.worldgen");
        if (run == null) {
            return;
        }
        long order = GLOBAL_ORDER.incrementAndGet();
        run.event("target-palette-choice",
            "order=" + order
                + " phase=" + run.phase
                + " sourceChunk=" + currentAmbientChunkString()
                + " kind=" + safeString(paletteKind)
                + " index=" + paletteIndex
                + " gSeedBefore=" + gSeedBefore
                + " gSeedAfter=" + gSeedAfter
                + " chosen=" + safeState(chosenState)
                + " thread=" + Thread.currentThread().getName()
                + (detail == null || detail.isBlank() ? "" : " detail=" + detail)
                + " mixinAltered=" + run.controlFlowAltered);
    }

    public static void recordDecorationPlacedFeatureEnter(String placedFeature,
                                                          String configuredFeature,
                                                          String featureClass,
                                                          BlockPos origin) {
        if (!shouldTraceDecorationSequence()) {
            return;
        }
        TraceRun run = ensureActiveRun("ambient.applyBiomeDecoration.feature");
        if (run == null) {
            return;
        }
        run.phase = "applyBiomeDecoration.feature";
        run.event("placed-feature-enter",
            "sourceChunk=" + currentAmbientChunkString()
                + " origin=" + posString(origin)
                + " placedFeature=" + safeString(placedFeature)
                + " configuredFeature=" + safeString(configuredFeature)
                + " featureClass=" + safeString(featureClass));
    }

    public static void recordDecorationFeatureSeed(long decorationSeed, int featureIndex, int step) {
        if (!shouldTraceDecorationSequence()) {
            return;
        }
        TraceRun run = ensureActiveRun("ambient.applyBiomeDecoration.feature");
        if (run != null) {
            run.event("placed-feature-seed",
                "sourceChunk=" + currentAmbientChunkString()
                    + " decorationSeed=" + decorationSeed
                    + " featureIndex=" + featureIndex
                    + " step=" + step);
        }
    }

    public static void recordDecorationPlacedFeatureExit(String placedFeature,
                                                         String configuredFeature,
                                                         String featureClass,
                                                         BlockPos origin,
                                                         boolean result) {
        if (!shouldTraceDecorationSequence()) {
            return;
        }
        TraceRun run = ensureActiveRun("ambient.applyBiomeDecoration.feature");
        if (run == null) {
            return;
        }
        run.phase = "applyBiomeDecoration.feature";
        run.event("placed-feature-exit",
            "sourceChunk=" + currentAmbientChunkString()
                + " origin=" + posString(origin)
                + " result=" + result
                + " placedFeature=" + safeString(placedFeature)
                + " configuredFeature=" + safeString(configuredFeature)
                + " featureClass=" + safeString(featureClass));
    }

    /**
     * Records the vanilla multiface decision without reading or advancing its random source.
     * This is deliberately trace-only and is active solely for the configured parity target.
     */
    public static void recordMultifacePlaceEnter(FeaturePlaceContext<MultifaceGrowthConfiguration> context) {
        if (!shouldTraceDecorationSequence() || context == null) {
            return;
        }
        TraceRun run = ensureActiveRun("ambient.applyBiomeDecoration.multiface");
        if (run == null) {
            return;
        }
        MultifaceGrowthConfiguration config = context.config();
        BlockPos origin = context.origin();
        run.phase = "applyBiomeDecoration.multiface";
        run.event("multiface-place-enter",
            "sourceChunk=" + currentAmbientChunkString()
                + " origin=" + posString(origin)
                + " originState=" + safeState(context.level().getBlockState(origin))
                + " placeBlock=" + safeString(config == null ? null : config.placeBlock)
                + " searchRange=" + (config == null ? "unknown" : config.searchRange)
                + " spreadChance=" + (config == null ? "unknown" : config.chanceOfSpreading)
                + " randomClass=" + safeString(context.random() == null ? null : context.random().getClass().getName())
                + " randomIdentity=" + (context.random() == null ? "unknown" : System.identityHashCode(context.random())));
    }

    public static void recordMultifaceTargetAttempt(WorldGenLevel level,
                                                    BlockPos candidate,
                                                    BlockState candidateState,
                                                    MultifaceGrowthConfiguration config,
                                                    List<Direction> directions) {
        if (!shouldTraceDecorationSequence() || level == null || config == null) {
            return;
        }
        TraceRun run = ensureActiveRun("ambient.applyBiomeDecoration.multiface");
        if (run == null) {
            return;
        }
        String faces = directions == null ? "[]" : directions.stream().map(Direction::getSerializedName).collect(Collectors.joining(",", "[", "]"));
        String supports = directions == null ? "[]" : directions.stream()
            .map(direction -> {
                BlockState support = level.getBlockState(candidate.relative(direction));
                boolean allowed = config.canBePlacedOn.stream().anyMatch(holder -> holder.value() == support.getBlock());
                return direction.getSerializedName() + ":" + safeState(support)
                    + ":allowed=" + allowed;
            })
            .collect(Collectors.joining(",", "[", "]"));
        run.phase = "applyBiomeDecoration.multiface";
        run.event(Objects.equals(candidate, TARGET_POS) ? "multiface-target-attempt" : "multiface-candidate-attempt",
            "sourceChunk=" + currentAmbientChunkString()
                + " candidate=" + posString(candidate)
                + " candidateState=" + safeState(candidateState)
                + " targetState=" + safeState(level.getBlockState(candidate))
                + " directions=" + faces
                + " supports=" + supports);
    }

    public static void recordMultifaceTargetResult(BlockPos candidate, boolean result) {
        if (!shouldTraceDecorationSequence() || candidate == null) {
            return;
        }
        TraceRun run = ensureActiveRun("ambient.applyBiomeDecoration.multiface");
        if (run != null) {
            run.event(Objects.equals(candidate, TARGET_POS) ? "multiface-target-result" : "multiface-candidate-result",
                "candidate=" + posString(candidate) + " result=" + result);
        }
    }

    public static void endGenerate(WorldGenLevel world) {
        TraceRun run = currentRun();
        try {
            if (run != null) {
                run.event("generate-end", "finalTargetState=" + safeState(world == null || !matchesTargetDimension(null, run.coord, world) ? null : world.getBlockState(TARGET_POS)));
            }
        } finally {
            popCurrentRun();
        }
    }

    public static List<String> summaryLines() {
        ArrayList<String> lines = new ArrayList<>();
        if (!ENABLED) {
            lines.add("PreCaptureTrace: disabled");
            return lines;
        }
        if (TARGET_DIMENSION == null || TARGET_POS == null) {
            lines.add("PreCaptureTrace: invalid target configuration");
            return lines;
        }
        List<TraceRun> runs = snapshotRuns();
        lines.add("PreCaptureTrace: target=" + TARGET_DIMENSION + "@" + posString(TARGET_POS)
            + " chunk=" + TARGET_CHUNK_X + "," + TARGET_CHUNK_Z
            + " chunkRadius=" + CHUNK_RADIUS
            + " effectiveChunkRadius=" + effectiveChunkRadius()
            + " runs=" + runs.size());
        for (TraceRun run : runs) {
            lines.add("PreCaptureTraceRun id=" + run.runId
                + " chunk=" + run.coord.chunkX() + "," + run.coord.chunkZ()
                + " invocation=" + run.invocation
                + " controlFlowAltered=" + run.controlFlowAltered
                + " writes=" + run.targetWriteCount()
                + " lastPhase=" + run.phase);
        }
        return lines;
    }

    public static void writeReport(MinecraftServer server) {
        if (!enabled() || server == null) {
            return;
        }
        Path path = server.getFile(OUTPUT).toPath();
        Path parent = path.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, detailedLines());
        } catch (IOException ignored) {
        }
    }

    public static List<String> detailedLines() {
        ArrayList<String> lines = new ArrayList<>();
        if (!ENABLED) {
            lines.add("PreCaptureTrace disabled");
            return lines;
        }
        lines.add("PreCaptureTrace targetDimension=" + TARGET_DIMENSION);
        lines.add("PreCaptureTrace targetChunk=" + TARGET_CHUNK_X + "," + TARGET_CHUNK_Z);
        lines.add("PreCaptureTrace targetPos=" + posString(TARGET_POS));
        lines.add("PreCaptureTrace mode=" + Lc2hRuntimeModes.modeSummary());
        for (TraceRun run : snapshotRuns()) {
            lines.add("");
            lines.add("Run id=" + run.runId + " chunk=" + run.coord.chunkX() + "," + run.coord.chunkZ() + " invocation=" + run.invocation);
            for (Map.Entry<String, String> entry : run.meta.entrySet()) {
                lines.add("  meta " + entry.getKey() + "=" + entry.getValue());
            }
            for (String event : run.eventsSnapshot()) {
                lines.add("  " + event);
            }
        }
        return lines;
    }

    public static String captureCaller() {
        return captureCallerIfEnabled();
    }

    public static String captureCallerIfEnabled() {
        if (!enabled()) {
            return "unknown";
        }
        if (!TRACE_CALLER) {
            return "caller-disabled";
        }
        String callerChain = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
            .walk(stream -> stream
                .filter(frame -> !frame.getClassName().equals(PreCaptureTargetTraceRegistry.class.getName()))
                .filter(frame -> !shouldSkipCallerFrame(frame.getClassName()))
                .limit(TRACE_CALLER_DEPTH)
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName())
                .collect(Collectors.joining(" <= ")));
        return callerChain == null || callerChain.isBlank() ? "unknown" : callerChain;
    }

    private static boolean shouldSkipCallerFrame(String className) {
        if (className == null || className.isBlank()) {
            return true;
        }
        if (className.startsWith("org.admany.lc2h.mixin.")) {
            return true;
        }
        return className.equals("mcjty.lostcities.worldgen.ChunkDriver")
            || className.equals("net.minecraft.world.level.LevelWriter")
            || className.equals("net.minecraft.world.level.LevelAccessor")
            || className.equals("net.minecraft.world.level.CommonLevelAccessor")
            || className.equals("net.minecraft.world.level.WorldGenLevel")
            || className.equals("net.minecraft.world.level.chunk.ProtoChunk")
            || className.equals("net.minecraft.world.level.chunk.LevelChunk")
            || className.equals("net.minecraft.world.level.chunk.LevelChunkSection")
            || className.equals("net.minecraft.world.level.chunk.BulkSectionAccess")
            || className.equals("net.minecraft.server.level.WorldGenRegion");
    }

    private static boolean isRelevantChunk(IDimensionInfo provider, ChunkCoord coord, WorldGenLevel world, ChunkAccess chunk) {
        ChunkCoord effectiveCoord = effectiveCoord(provider, coord, world, chunk);
        return enabled()
            && effectiveCoord != null
            && matchesTargetDimension(provider, effectiveCoord, world)
            && effectiveCoord.chunkX() == TARGET_CHUNK_X
            && effectiveCoord.chunkZ() == TARGET_CHUNK_Z;
    }

    private static boolean matchesTargetDimension(IDimensionInfo provider, ChunkCoord coord, WorldGenLevel world) {
        ResourceKey<Level> dimension = resolveDimension(provider, coord, world);
        return dimension != null
            && dimension.location() != null
            && dimension.location().equals(TARGET_DIMENSION);
    }

    private static TraceRun currentRun() {
        ArrayDeque<TraceContext> stack = CURRENT.get();
        TraceContext context = stack.peek();
        return context == null ? null : RUNS.get(context.runId);
    }

    private static void popCurrentRun() {
        ArrayDeque<TraceContext> stack = CURRENT.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            CURRENT.remove();
        }
    }

    private static TraceRun ensureActiveRun(String phase) {
        TraceRun run = currentRun();
        if (run != null) {
            return run;
        }
        if (!enabled()) {
            return null;
        }
        String ambientKey = TARGET_DIMENSION + "|" + TARGET_CHUNK_X + "," + TARGET_CHUNK_Z + "|" + phase;
        long runId = AMBIENT_RUNS.computeIfAbsent(ambientKey, ignored -> {
            long createdRunId = RUN_SEQUENCE.incrementAndGet();
            ChunkCoord coord = new ChunkCoord(ResourceKey.create(Registries.DIMENSION, TARGET_DIMENSION), TARGET_CHUNK_X, TARGET_CHUNK_Z);
            TraceRun ambient = new TraceRun(createdRunId, coord, 0L);
            ambient.phase = phase;
            ambient.putMeta("mode", Lc2hRuntimeModes.modeSummary());
            ambient.putMeta("baselineMode", String.valueOf(Lc2hRuntimeModes.baselineMode()));
            ambient.putMeta("dimension", TARGET_DIMENSION.toString());
            ambient.putMeta("chunk", TARGET_CHUNK_X + "," + TARGET_CHUNK_Z);
            ambient.putMeta("targetChunk", TARGET_CHUNK_X + "," + TARGET_CHUNK_Z);
            ambient.putMeta("targetPos", posString(TARGET_POS));
            ambient.putMeta("lifecycleId", String.valueOf(WorldGenScope.activeLifecycleId()));
            ambient.putMeta("featurePhase", phase);
            ambient.putMeta("thread", Thread.currentThread().getName());
            ambient.putMeta("generatedAt", Instant.now().toString());
            ambient.event("ambient-start", "phase=" + phase + " target write captured outside Lost Cities traced phase");
            RUNS.put(createdRunId, ambient);
            return createdRunId;
        });
        return RUNS.get(runId);
    }

    private static List<TraceRun> snapshotRuns() {
        return RUNS.values().stream()
            .sorted(Comparator.comparingLong(run -> run.runId))
            .toList();
    }

    private static boolean shouldTraceDecorationSequence() {
        if (!enabled()) {
            return false;
        }
        AmbientChunkContext current = currentAmbientChunkContext();
        return current != null
            && "applyBiomeDecoration".equals(current.phase)
            && current.chunkX == TARGET_CHUNK_X
            && current.chunkZ == TARGET_CHUNK_Z;
    }

    private static int effectiveChunkRadius() {
        if (Lc2hRuntimeModes.anyWorldParityRun() && !ALLOW_PARITY_WIDE_RADIUS) {
            return 0;
        }
        return CHUNK_RADIUS;
    }

    private static String currentAmbientChunkString() {
        AmbientChunkContext current = currentAmbientChunkContext();
        if (current == null) {
            return "unknown";
        }
        return current.chunkX + "," + current.chunkZ + "@" + current.phase;
    }

    private static AmbientChunkContext currentAmbientChunkContext() {
        ArrayDeque<AmbientChunkContext> stack = AMBIENT_CHUNK_CONTEXT.get();
        return stack.peek();
    }

    private static BlockPos parseBlockPos(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String safeProfile(LostCityProfile profile) {
        return profile == null || profile.getName() == null ? "unknown" : profile.getName();
    }

    private static String safeLandscape(LostCityProfile profile) {
        LandscapeType type = profile == null ? null : profile.LANDSCAPE_TYPE;
        return type == null ? "unknown" : type.name();
    }

    private static String safeBiome(WorldGenLevel world, BlockPos pos) {
        if (world == null || pos == null) {
            return "unknown";
        }
        try {
            return biomeId(world, world.getBiome(pos));
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    public static String biomeId(WorldGenLevel world, Holder<Biome> holder) {
        if (holder == null) {
            return "unknown";
        }
        ResourceKey<Biome> key = holder.unwrapKey().orElse(null);
        if (key != null && key.location() != null) {
            return key.location().toString();
        }
        if (world instanceof ServerLevel serverLevel) {
            Registry<Biome> registry = serverLevel.registryAccess().registryOrThrow(Registries.BIOME);
            ResourceLocation id = registry.getKey(holder.value());
            if (id != null) {
                return id.toString();
            }
        }
        return holder.toString();
    }

    private static String safeState(BlockState state) {
        return state == null ? "<null>" : state.toString();
    }

    private static BlockState safeChunkState(ChunkAccess chunk, BlockPos pos) {
        try {
            return chunk.getBlockState(pos);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safeString(Object value) {
        return value == null ? "unknown" : String.valueOf(value);
    }

    private static String safeString(ValueSupplier supplier) {
        try {
            return safeString(supplier.get());
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String chunkKey(IDimensionInfo provider, ChunkCoord coord, WorldGenLevel world) {
        return dimensionString(resolveDimension(provider, coord, world)) + "|" + coord.chunkX() + "," + coord.chunkZ();
    }

    private static String dimensionString(ResourceKey<Level> dimension) {
        return dimension == null || dimension.location() == null ? "unknown" : dimension.location().toString();
    }

    private static ChunkCoord effectiveCoord(IDimensionInfo provider, ChunkCoord coord, WorldGenLevel world, ChunkAccess chunk) {
        if (chunk == null && coord != null) {
            return coord;
        }
        ResourceKey<Level> dimension = resolveDimension(provider, coord, world);
        if (chunk != null) {
            return new ChunkCoord(dimension, chunk.getPos().x, chunk.getPos().z);
        }
        return coord;
    }

    private static ResourceKey<Level> resolveDimension(IDimensionInfo provider, ChunkCoord coord, WorldGenLevel world) {
        if (world != null && world.getLevel() != null) {
            return world.getLevel().dimension();
        }
        if (provider != null) {
            ResourceKey<Level> dimension = provider.dimension();
            if (dimension != null) {
                return dimension;
            }
            return provider.getType();
        }
        return coord == null ? null : coord.dimension();
    }

    private static String posString(BlockPos pos) {
        return pos == null ? "<null>" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private interface ValueSupplier {
        Object get() throws Exception;
    }

    private record TraceContext(long runId) {
    }

    private record AmbientChunkContext(String phase, int chunkX, int chunkZ) {
    }

    private record PendingSectionTarget(BlockPos pos, int localX, int localY, int localZ) {
    }

    private static final class TraceRun {
        private final long runId;
        private final ChunkCoord coord;
        private final long invocation;
        private final LinkedHashMap<String, String> meta = new LinkedHashMap<>();
        private final List<String> events = new ArrayList<>();
        private volatile String phase = "generate";
        private volatile boolean controlFlowAltered;

        private TraceRun(long runId, ChunkCoord coord, long invocation) {
            this.runId = runId;
            this.coord = coord;
            this.invocation = invocation;
        }

        private void putMeta(String key, String value) {
            synchronized (meta) {
                meta.put(key, value == null ? "unknown" : value);
            }
        }

        private void event(String stage, String detail) {
            String line = String.format(Locale.ROOT,
                "%s stage=%s order=%d thread=%s detail=%s",
                Instant.now(),
                stage,
                GLOBAL_ORDER.incrementAndGet(),
                Thread.currentThread().getName(),
                detail == null ? "" : detail);
            synchronized (events) {
                events.add(line);
            }
        }

        private long targetWriteCount() {
            synchronized (events) {
                return events.stream().filter(line -> line.contains("stage=target-write")).count();
            }
        }

        private List<String> eventsSnapshot() {
            synchronized (events) {
                return new ArrayList<>(events);
            }
        }
    }
}
