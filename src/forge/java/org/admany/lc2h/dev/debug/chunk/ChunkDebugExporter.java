package org.admany.lc2h.dev.debug.chunk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mcjty.lostcities.api.LostChunkCharacteristics;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.api.MultiPos;
import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.DamageArea;
import mcjty.lostcities.worldgen.lost.Explosion;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.dev.diagnostics.ChunkGenTracker;
import org.admany.lc2h.mixin.accessor.lostcities.MultiChunkAccessor;
import org.admany.lc2h.util.lostcities.MultiChunkCacheAccess;
import org.admany.lc2h.worldgen.async.snapshot.MultiChunkSnapshot;
import org.admany.lc2h.util.chunk.ChunkPostProcessor;
import org.admany.lc2h.worldgen.lostcities.MultiChunkBoundaryRegistry;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;
import org.admany.lc2h.worldgen.terrain.CityShiftField;
import org.admany.lc2h.worldgen.terrain.MountainCityBlendDiagnostics;
import org.admany.lc2h.worldgen.terrain.NaturalHeightSampler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class ChunkDebugExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault());

    private ChunkDebugExporter() {
    }

    /** Build the in-game report for the selected chunk. */
    public static List<String> explainSelection(ServerPlayer player,
                                                 ChunkDebugManager.ChunkSelection selection) {
        if (player == null) {
            return List.of("chunkdebug: no player");
        }
        ChunkPos selected = selection == null ? null : selection.primary();
        if (selected == null && selection != null) {
            selected = selection.secondary();
        }
        if (selected == null) {
            selected = player.chunkPosition();
        }
        Integer anchorY = selection != null
            ? (selected.equals(selection.primary()) ? selection.primaryY() : selection.secondaryY())
            : null;
        int y = anchorY != null
            ? anchorY
            : player.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                selected.getMiddleBlockX(), selected.getMiddleBlockZ());
        return org.admany.lc2h.worldgen.terrain.CityBlendDebugger.explain(
            player.serverLevel(), new BlockPos(selected.getMiddleBlockX(), y, selected.getMiddleBlockZ()));
    }

    public static Path exportSelection(ServerPlayer player, ChunkDebugManager.ChunkSelection selection, String label) throws Exception {
        if (player == null) {
            throw new IllegalArgumentException("player");
        }

        ChunkPos primary = selection != null ? selection.primary() : null;
        ChunkPos secondary = selection != null ? selection.secondary() : null;
        if (primary == null && secondary == null) {
            primary = player.chunkPosition();
            secondary = primary;
        } else if (primary == null) {
            primary = secondary;
        } else if (secondary == null) {
            secondary = primary;
        }

        ResourceLocation dimension = selection != null && selection.dimension() != null
            ? selection.dimension()
            : player.level().dimension().location();

        int minChunkX = Math.min(primary.x, secondary.x);
        int maxChunkX = Math.max(primary.x, secondary.x);
        int minChunkZ = Math.min(primary.z, secondary.z);
        int maxChunkZ = Math.max(primary.z, secondary.z);

        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", 2);
        root.addProperty("decisionTraceVersion", 1);
        root.addProperty("dimension", dimension.toString());
        root.addProperty("exportedAt", Instant.now().toString());
        root.addProperty("player", player.getGameProfile().getName());

        JsonObject selectionObj = new JsonObject();
        selectionObj.addProperty("primaryChunkX", primary.x);
        selectionObj.addProperty("primaryChunkZ", primary.z);
        selectionObj.addProperty("secondaryChunkX", secondary.x);
        selectionObj.addProperty("secondaryChunkZ", secondary.z);
        selectionObj.addProperty("minChunkX", minChunkX);
        selectionObj.addProperty("minChunkZ", minChunkZ);
        selectionObj.addProperty("maxChunkX", maxChunkX);
        selectionObj.addProperty("maxChunkZ", maxChunkZ);
        selectionObj.addProperty("chunkCount", (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1));
        root.add("selection", selectionObj);

        IDimensionInfo provider = resolveProvider(player);
        root.addProperty("providerAvailable", provider != null);
        JsonObject runtimeDiagnostics = new JsonObject();
        runtimeDiagnostics.addProperty("mountainBlend", MountainCityBlendDiagnostics.diagnostics());
        runtimeDiagnostics.addProperty("shiftField", CityShiftField.diagnostics());
        runtimeDiagnostics.addProperty("snowReconciliation", ChunkPostProcessor.snowDiagnostics());
        runtimeDiagnostics.addProperty("floatingCleanup", ChunkPostProcessor.floatingDiagnostics());
        root.add("runtimeDiagnostics", runtimeDiagnostics);

        JsonArray chunks = new JsonArray();
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                ChunkCoord coord = new ChunkCoord(player.level().dimension(), x, z);
                JsonObject chunk = new JsonObject();
                chunk.addProperty("chunkX", x);
                chunk.addProperty("chunkZ", z);
                chunk.add("chunkinfo", ChunkGenTracker.buildReportJson(coord));
                chunk.add("multichunk", buildMultiChunkJson(provider, coord));
                BuildingInfo buildingInfo = null;
                try {
                    if (provider != null) {
                        buildingInfo = BuildingInfo.getBuildingInfo(coord, provider);
                    }
                } catch (Throwable ignored) {
                }
                chunk.add("characteristics", buildCharacteristicsJson(provider, coord, buildingInfo));
                chunk.add("multichunkBoundary", buildMultiChunkBoundaryJson(provider, coord));
                chunk.add("undergroundScan", buildUndergroundScanJson(player, coord, buildingInfo));
                chunk.add("treeSeam", buildTreeSeamDebugJson(player.level(), provider, coord));
                chunk.add("terrainDecision", buildTerrainDecisionJson(player, provider, coord));
                chunk.add("structures", buildStructureDebugJson(player.level(), coord));
                chunks.add(chunk);
            }
        }
        root.add("chunks", chunks);

        String timestamp = FILE_TS.format(Instant.now());
        String baseName = "chunkdebug_" + timestamp + "_" + minChunkX + "_" + minChunkZ + "_to_" + maxChunkX + "_" + maxChunkZ;
        if (label != null && !label.isBlank()) {
            baseName += "_" + sanitize(label);
        }

        Path outDir = FMLPaths.GAMEDIR.get().resolve("lc2h").resolve("chunkdebug");
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve(baseName + ".json");
        Files.writeString(outFile, GSON.toJson(root), StandardCharsets.UTF_8);
        return outFile;
    }

    private static JsonObject buildMultiChunkBoundaryJson(IDimensionInfo provider, ChunkCoord coord) {
        JsonObject obj = new JsonObject();
        if (provider == null || coord == null) {
            obj.addProperty("available", false);
            obj.addProperty("reason", "provider-missing");
            return obj;
        }
        try {
            MultiChunkBoundaryRegistry.Decision decision = MultiChunkBoundaryRegistry.boundaryDecision(provider, coord);
            obj.addProperty("available", true);
            obj.addProperty("reserved", decision.reserved());
            obj.addProperty("reason", decision.reason());
            obj.addProperty("offset", decision.offset());
            if (decision.edge() != null) {
                obj.addProperty("edge", decision.edge().name());
            }
            if (decision.contractKey() != null) {
                obj.addProperty("contractKey", decision.contractKey());
            }
        } catch (Throwable t) {
            obj.addProperty("available", false);
            obj.addProperty("error", t.getClass().getSimpleName());
        }
        return obj;
    }

    private static IDimensionInfo resolveProvider(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        try {
            if (player.level() instanceof WorldGenLevel worldGenLevel) {
                IDimensionInfo info = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(worldGenLevel);
                if (info != null) {
                    info.setWorld(worldGenLevel);
                }
                return info;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Export the terrain report for the selected chunk. */
    private static JsonObject buildTerrainDecisionJson(ServerPlayer player,
                                                        IDimensionInfo provider,
                                                        ChunkCoord coord) {
        JsonObject obj = new JsonObject();
        if (player == null || coord == null || !(player.level() instanceof ServerLevel level)) {
            obj.addProperty("available", false);
            obj.addProperty("reason", "server-level-missing");
            return obj;
        }

        final int chunkX = coord.chunkX();
        final int chunkZ = coord.chunkZ();
        obj.addProperty("available", true);
        obj.addProperty("chunkX", chunkX);
        obj.addProperty("chunkZ", chunkZ);
        obj.addProperty("cityBlendConfigEnabled", ConfigManager.CITY_BLEND_ENABLED);

        LevelChunk resident = null;
        try {
            resident = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        } catch (Throwable ignored) {
        }
        JsonObject residency = new JsonObject();
        residency.addProperty("loaded", resident != null);
        if (resident != null) {
            try {
                residency.addProperty("status", String.valueOf(resident.getStatus()));
                residency.addProperty("full", resident.getStatus() == ChunkStatus.FULL);
            } catch (Throwable ignored) {
            }
        }
        obj.add("residency", residency);

        LostCityProfile profile = null;
        try {
            if (provider != null) {
                profile = provider.getProfile();
            }
        } catch (Throwable ignored) {
        }

        JsonObject role = new JsonObject();
        role.addProperty("available", provider != null && profile != null);
        if (provider == null) {
            role.addProperty("reason", "provider-missing");
        } else if (profile == null) {
            role.addProperty("reason", "profile-missing");
        } else {
            addRoleFacts(role, provider, profile, coord);
            JsonArray neighbors = new JsonArray();
            int[][] offsets = {{0, -1}, {1, 0}, {0, 1}, {-1, 0},
                {-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
            for (int[] offset : offsets) {
                ChunkCoord neighbor = new ChunkCoord(coord.dimension(), chunkX + offset[0], chunkZ + offset[1]);
                JsonObject fact = new JsonObject();
                fact.addProperty("dx", offset[0]);
                fact.addProperty("dz", offset[1]);
                addRoleFacts(fact, provider, profile, neighbor);
                neighbors.add(fact);
            }
            role.add("neighbors", neighbors);
        }
        obj.add("role", role);

        NaturalHeightSampler.LevelSampler heights = NaturalHeightSampler.forLevel(level);
        CityShiftField.Context context = CityShiftField.context(provider, profile, heights);
        JsonObject field = new JsonObject();
        double targetShift = 0.0D;
        Integer plannedNatural = null;
        Integer plannedReference = null;
        if (context == null) {
            field.addProperty("available", false);
            field.addProperty("reason", provider == null ? "provider-missing" : "no-height-sampler");
        } else {
            CityShiftField.ShiftSettings settings = context.settings();
            field.addProperty("available", true);
            field.addProperty("enabled", settings.enabled());
            field.addProperty("slopeFlat", settings.slopeFlat());
            field.addProperty("slopeSteep", settings.slopeSteep());
            field.addProperty("maxShift", settings.maxShift());
            field.addProperty("reliefStrength", settings.reliefStrength());
            field.addProperty("haloChunks", settings.halo());
            field.addProperty("maxRunOutBlocks", settings.maxRunOutBlocks());
            field.addProperty("settingsVersion", settings.version());
            plannedNatural = CityShiftField.plannedNaturalSurface(context, chunkX, chunkZ);
            plannedReference = CityShiftField.plannedReferenceSurface(context, chunkX, chunkZ);
            boolean readyBeforeWait = plannedNatural != null;
            long waitStarted = System.nanoTime();
            String waitOutcome = readyBeforeWait ? "ALREADY_READY" : "NOT_NEEDED";
            if (!readyBeforeWait && settings.enabled()) {
                try {
                    CityShiftField.readyForChunk(context, chunkX, chunkZ)
                        .get(20L, TimeUnit.SECONDS);
                    waitOutcome = "READY";
                } catch (java.util.concurrent.TimeoutException timeout) {
                    waitOutcome = "TIMEOUT";
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    waitOutcome = "INTERRUPTED";
                } catch (Throwable failure) {
                    waitOutcome = "FAILED_" + failure.getClass().getSimpleName();
                }
                plannedNatural = CityShiftField.plannedNaturalSurface(context, chunkX, chunkZ);
                plannedReference = CityShiftField.plannedReferenceSurface(context, chunkX, chunkZ);
            }
            field.addProperty("regionReadyBeforeDebugWait", readyBeforeWait);
            field.addProperty("debugWaitOutcome", waitOutcome);
            field.addProperty("debugWaitMillis",
                (System.nanoTime() - waitStarted) / 1_000_000L);
            targetShift = CityShiftField.cachedShiftAtChunk(context, chunkX, chunkZ);
            field.addProperty("regionCacheReady", plannedNatural != null);
            field.addProperty("targetShiftCached", targetShift);
            if (plannedNatural != null) {
                field.addProperty("plannedNaturalSurface", plannedNatural);
            }
            if (plannedReference != null) {
                field.addProperty("plannedReferenceSurface", plannedReference);
            }
            JsonObject targetBuilderTrace = new JsonObject();
            addBuilderTrace(targetBuilderTrace,
                CityShiftField.debugCell(context, chunkX, chunkZ));
            field.add("targetBuilderTrace", targetBuilderTrace);

            JsonArray shifts = new JsonArray();
            double minShift = Double.POSITIVE_INFINITY;
            double maxShift = Double.NEGATIVE_INFINITY;
            int positive = 0;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    double shift = CityShiftField.cachedShiftAtChunk(context, chunkX + dx, chunkZ + dz);
                    minShift = Math.min(minShift, shift);
                    maxShift = Math.max(maxShift, shift);
                    if (shift > 0.0D) {
                        positive++;
                    }
                    JsonObject sample = new JsonObject();
                    sample.addProperty("dx", dx);
                    sample.addProperty("dz", dz);
                    sample.addProperty("shift", shift);
                    JsonObject builderTrace = new JsonObject();
                    addBuilderTrace(builderTrace,
                        CityShiftField.debugCell(context, chunkX + dx, chunkZ + dz));
                    sample.add("builderTrace", builderTrace);
                    shifts.add(sample);
                }
            }
            field.add("neighborShifts", shifts);
            field.addProperty("neighborPositiveCount", positive);
            field.addProperty("neighborMinShift", minShift == Double.POSITIVE_INFINITY ? 0.0D : minShift);
            field.addProperty("neighborMaxShift", maxShift == Double.NEGATIVE_INFINITY ? 0.0D : maxShift);
        }
        obj.add("field", field);

        JsonObject heightsObj = new JsonObject();
        Integer naturalCached = heights == null ? null : heights.cachedChunkHeight(chunkX, chunkZ);
        heightsObj.addProperty("naturalSamplerCached", naturalCached != null);
        if (naturalCached != null) {
            heightsObj.addProperty("naturalSamplerSurface", naturalCached);
        }
        if (resident != null) {
            addResidentHeight(heightsObj, resident, Heightmap.Types.OCEAN_FLOOR_WG, "oceanFloorWg");
            addResidentHeight(heightsObj, resident, Heightmap.Types.WORLD_SURFACE_WG, "worldSurfaceWg");
            addResidentHeight(heightsObj, resident, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, "motionBlockingNoLeaves");
            addResidentHeight(heightsObj, resident, Heightmap.Types.WORLD_SURFACE, "worldSurface");
        }
        obj.add("heights", heightsObj);

        MountainCityBlendDiagnostics.ChunkTraceSnapshot trace =
            MountainCityBlendDiagnostics.chunkTrace(level.dimension(), chunkX, chunkZ);
        JsonObject runtime = new JsonObject();
        runtime.addProperty("recorded", trace != null);
        if (trace != null) {
            runtime.addProperty("gateSeen", trace.gateSeen());
            runtime.addProperty("gateAlreadyActive", trace.gateAlreadyActive());
            runtime.addProperty("gateReadyRebuilds", trace.gateReadyRebuilds());
            runtime.addProperty("gateDeferred", trace.gateDeferred());
            runtime.addProperty("gateResumed", trace.gateResumed());
            runtime.addProperty("gateNoRegion", trace.gateNoRegion());
            runtime.addProperty("gateFailures", trace.gateFailures());
            runtime.addProperty("blenderRegionSeen", trace.blenderSeen());
            runtime.addProperty("blenderRegionApplied", trace.blenderApplied());
            runtime.addProperty("noiseTransformCalls", trace.noiseTransformCalls());
            runtime.addProperty("noiseTransformShift", trace.noiseTransformShift());
            runtime.addProperty("targetFieldPositive", trace.targetFieldPositive());
            runtime.addProperty("targetShift", trace.targetShift());
            if (trace.lastGateDecision() != null) {
                runtime.addProperty("lastGateDecision", trace.lastGateDecision());
            }
            runtime.addProperty("updatedAtMs", trace.updatedAtMs());
        }
        MountainCityBlendDiagnostics.GenerationOutcome generation =
            MountainCityBlendDiagnostics.recordedGenerationOutcome(level.dimension(), chunkX, chunkZ);
        if (generation != null) {
            JsonObject outcome = new JsonObject();
            outcome.addProperty("summary", generation.summary());
            outcome.addProperty("centreShift", generation.centreShift());
            outcome.addProperty("keyedBy", "blender-region-centre");
            runtime.add("regionCentreOutcome", outcome);
        }
        obj.add("runtime", runtime);

        JsonObject post = new JsonObject();
        post.addProperty("pendingChunkScans", ChunkPostProcessor.getPendingScanCount());
        if (resident != null) {
            post.addProperty("treeProtectedBlocks",
                ChunkPostProcessor.getProtectedTreeBlockCount(level, chunkX, chunkZ));
            post.addProperty("treeSeamChunk", ChunkPostProcessor.isSeamChunk(level, chunkX, chunkZ));
        }
        obj.add("postProcessing", post);

        JsonArray reasons = new JsonArray();
        String state;
        String reason;
        if (provider == null) {
            state = "DISABLED";
            reason = "NO_LOST_CITIES_PROVIDER";
        } else if (profile == null) {
            state = "REJECTED";
            reason = "PROFILE_UNAVAILABLE";
        } else if (context == null) {
            state = "REJECTED";
            reason = "NO_NATURAL_HEIGHT_SAMPLER";
        } else if (!ConfigManager.CITY_BLEND_ENABLED || !context.settings().enabled()) {
            state = "DISABLED";
            reason = "CITY_BLEND_DISABLED";
        } else if (!(targetShift > 0.0D)) {
            state = "NO_DEMAND";
            reason = "NO_POSITIVE_SHIFT_AT_TARGET";
        } else if (trace == null) {
            state = "NOT_OBSERVED";
            reason = "TARGET_NOT_SEEN_BY_TERRAIN_GATE";
        } else if (trace.gateFailures() > 0) {
            state = "REJECTED";
            reason = "TERRAIN_GATE_FAILURE";
        } else if (trace.gateNoRegion() > 0
            && trace.gateAlreadyActive() == 0
            && trace.gateReadyRebuilds() == 0
            && trace.gateResumed() == 0) {
            state = "REJECTED";
            reason = "ACTIVE_BLENDER_MISSING";
        } else if (trace.noiseTransformCalls() > 0) {
            state = "APPLIED_TO_TARGET";
            reason = "NATIVE_NOISECHUNK_TRANSFORM_OBSERVED";
        } else if (trace.gateAlreadyActive() > 0
            || trace.gateReadyRebuilds() > 0
            || trace.gateResumed() > 0) {
            state = "APPLIED_TO_TARGET";
            reason = "TARGET_GATE_BOUND_TO_CITY_SHIFT_FIELD_NO_SAMPLE_RETAINED";
        } else if (trace.gateDeferred() > 0) {
            state = "DEFERRED";
            reason = "WAITING_FOR_IMMUTABLE_SHIFT_REGIONS";
        } else {
            state = "NOT_OBSERVED";
            reason = "TARGET_TRACE_HAS_NO_APPLICATION_EVENT";
        }
        obj.addProperty("decision", state);
        obj.addProperty("reason", reason);
        obj.addProperty("terrainTransformApplied", "APPLIED_TO_TARGET".equals(state));
        obj.addProperty("flattened", "APPLIED_TO_TARGET".equals(state));
        if (targetShift > 0.0D) {
            reasons.add("positive shift demand reaches this chunk: " + fmt(targetShift) + " blocks");
        } else {
            reasons.add("no positive shift demand is published for this chunk");
        }
        if (trace == null) {
            reasons.add("no per-target gate event is retained; this is not proof that an old chunk was reshaped");
        } else if (trace.noiseTransformCalls() > 0) {
            reasons.add("a positive native NoiseChunk transform was observed for this exact target chunk");
        } else if (trace.lastGateDecision() != null) {
            reasons.add("last target gate decision: " + trace.lastGateDecision());
        }
        if (plannedNatural == null && context != null && context.settings().enabled()) {
            reasons.add("shift region did not become resident during the bounded manual-debug wait");
        }
        reasons.add("flattened=true means native density was bound to LC2H's shift field; LC2H does not create a flat plane");
        obj.add("explanation", reasons);
        return obj;
    }

    private static void addResidentHeight(JsonObject target,
                                          LevelChunk chunk,
                                          Heightmap.Types type,
                                          String name) {
        try {
            target.addProperty(name, chunk.getHeight(type, 8, 8) + 1);
        } catch (Throwable ignored) {
        }
    }

    private static void addRoleFacts(JsonObject target,
                                     IDimensionInfo provider,
                                     LostCityProfile profile,
                                     ChunkCoord coord) {
        try {
            ChunkRoleProbe.Probe cached = ChunkRoleProbe.peekStableTerrainProbe(
                provider, coord.dimension(), coord.chunkX(), coord.chunkZ());
            ChunkRoleProbe.Probe probe = cached != null
                ? cached
                : ChunkRoleProbe.getStableTerrainProbe(provider, coord.dimension(), coord.chunkX(), coord.chunkZ());
            target.addProperty("stableProbeCached", cached != null);
            target.addProperty("isCity", probe.isCity());
            target.addProperty("cityLevel", probe.cityLevel());
            target.addProperty("hasHighway", probe.hasHighway());
            target.addProperty("highwayLevel", probe.highwayLevel());
            target.addProperty("highwayTunnel", probe.highwayTunnel());
            target.addProperty("hasRailway", probe.hasRailway());
            target.addProperty("hasSurfaceRailway", probe.hasSurfaceRailway());
            target.addProperty("buildingTypeKnown", probe.buildingTypeKnown());
            target.addProperty("unsafeForSurfaceTree", probe.isUnsafe());
        } catch (Throwable failure) {
            target.addProperty("probeError", failure.getClass().getSimpleName());
        }
        try {
            target.addProperty("isCityRaw", BuildingInfo.isCityRaw(coord, provider, profile));
        } catch (Throwable failure) {
            target.addProperty("isCityRawError", failure.getClass().getSimpleName());
        }
        try {
            target.addProperty("cityFactor", City.getCityFactor(coord, provider, profile));
            target.addProperty("cityThreshold", profile.CITY_THRESHOLD);
        } catch (Throwable failure) {
            target.addProperty("cityFactorError", failure.getClass().getSimpleName());
        }
    }

    private static JsonObject buildMultiChunkJson(IDimensionInfo provider, ChunkCoord coord) {
        JsonObject obj = new JsonObject();
        if (provider == null || coord == null) {
            obj.addProperty("available", false);
            obj.addProperty("reason", "provider-missing");
            return obj;
        }
        try {
            int areaSize = provider.getWorldStyle().getMultiSettings().areasize();
            if (areaSize <= 0) {
                obj.addProperty("available", false);
                obj.addProperty("reason", "invalid-area-size");
                return obj;
            }

            ChunkCoord multiCoord = new ChunkCoord(coord.dimension(),
                Math.floorDiv(coord.chunkX(), areaSize),
                Math.floorDiv(coord.chunkZ(), areaSize));

            obj.addProperty("available", true);
            obj.addProperty("multiCoordX", multiCoord.chunkX());
            obj.addProperty("multiCoordZ", multiCoord.chunkZ());
            obj.addProperty("areaSize", areaSize);

            MultiChunk multiChunk = MultiChunkCacheAccess.get(multiCoord);
            obj.addProperty("cached", multiChunk != null);
            if (multiChunk == null) {
                return obj;
            }

            MultiChunkAccessor accessor = (MultiChunkAccessor) multiChunk;
            ChunkCoord topLeft = accessor.lc2h$getTopLeft();
            if (topLeft != null) {
                obj.addProperty("topLeftChunkX", topLeft.chunkX());
                obj.addProperty("topLeftChunkZ", topLeft.chunkZ());
            }
            obj.addProperty("areaSizeResolved", accessor.lc2h$getAreaSize());

            MultiChunkSnapshot.MultiChunkCell cell = MultiChunkSnapshot.describeCell(multiChunk, coord);
            if (cell != null) {
                JsonObject cellObj = new JsonObject();
                cellObj.addProperty("relX", cell.relX());
                cellObj.addProperty("relZ", cell.relZ());
                cellObj.addProperty("name", cell.name());
                cellObj.addProperty("offsetX", cell.offsetX());
                cellObj.addProperty("offsetZ", cell.offsetZ());
                obj.add("cell", cellObj);
            }
        } catch (Throwable t) {
            obj.addProperty("available", false);
            obj.addProperty("error", t.getClass().getSimpleName());
        }
        return obj;
    }

    private static JsonObject buildCharacteristicsJson(IDimensionInfo provider, ChunkCoord coord, BuildingInfo buildingInfo) {
        JsonObject obj = new JsonObject();
        if (provider == null || coord == null) {
            obj.addProperty("available", false);
            obj.addProperty("reason", "provider-missing");
            return obj;
        }
        try {
            LostChunkCharacteristics info = BuildingInfo.getChunkCharacteristics(coord, provider);
            if (info == null) {
                obj.addProperty("available", false);
                obj.addProperty("reason", "no-characteristics");
                return obj;
            }
            obj.addProperty("available", true);
            obj.addProperty("isCity", info.isCity);
            obj.addProperty("couldHaveBuilding", info.couldHaveBuilding);
            obj.addProperty("cityLevel", info.cityLevel);
            int groundLevel = 0;
            int profileGround = 0;
            try {
                if (buildingInfo != null) {
                    groundLevel = buildingInfo.getCityGroundLevel();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (provider != null && provider.getProfile() != null) {
                    profileGround = provider.getProfile().GROUNDLEVEL;
                }
            } catch (Throwable ignored) {
            }
            int minDamageOffset = Math.max(0, Integer.getInteger("lc2h.damage.minCityOffset", 0));
            int baseY = groundLevel > 0 ? groundLevel : (profileGround > 0 ? profileGround : info.cityLevel);
            int minDamageY = baseY - minDamageOffset;
            obj.addProperty("damageMinCityOffset", minDamageOffset);
            obj.addProperty("damageMinCityY", minDamageY);
            obj.addProperty("cityGroundLevel", groundLevel);
            obj.addProperty("profileGroundLevel", profileGround);
            obj.addProperty("debrisEnabled", ConfigManager.ENABLE_EXPLOSION_DEBRIS);
            if (info.cityStyleId != null) {
                obj.addProperty("cityStyleId", info.cityStyleId.toString());
            }
            if (info.cityStyle != null) {
                obj.addProperty("cityStyleName", safeName(info.cityStyle));
            }
            if (info.multiBuildingId != null) {
                obj.addProperty("multiBuildingId", info.multiBuildingId.toString());
            }
            if (info.multiBuilding != null) {
                obj.addProperty("multiBuildingName", safeName(info.multiBuilding));
            }
            if (info.buildingTypeId != null) {
                obj.addProperty("buildingTypeId", info.buildingTypeId.toString());
            }
            if (info.buildingType != null) {
                obj.addProperty("buildingTypeName", safeName(info.buildingType));
            }
            MultiPos multiPos = info.multiPos;
            if (multiPos != null) {
                JsonObject mp = new JsonObject();
                mp.addProperty("x", multiPos.x());
                mp.addProperty("z", multiPos.z());
                mp.addProperty("w", multiPos.w());
                mp.addProperty("h", multiPos.h());
                mp.addProperty("isSingle", multiPos.isSingle());
                mp.addProperty("isTopLeft", multiPos.isTopLeft());
                obj.add("multiPos", mp);
            }
            try {
                if (buildingInfo != null) {
                    DamageArea area = buildingInfo.getDamageArea();
                    List<Explosion> explosions = area != null ? area.getExplosions() : null;
                    JsonObject damageObj = new JsonObject();
                    if (explosions == null || explosions.isEmpty()) {
                        damageObj.addProperty("explosionCount", 0);
                    } else {
                        int minY = Integer.MAX_VALUE;
                        int maxY = Integer.MIN_VALUE;
                        for (Explosion explosion : explosions) {
                            if (explosion == null || explosion.getCenter() == null) {
                                continue;
                            }
                            int y = explosion.getCenter().getY();
                            if (y < minY) minY = y;
                            if (y > maxY) maxY = y;
                        }
                        damageObj.addProperty("explosionCount", explosions.size());
                        if (minY != Integer.MAX_VALUE) {
                            damageObj.addProperty("explosionMinY", minY);
                        }
                        if (maxY != Integer.MIN_VALUE) {
                            damageObj.addProperty("explosionMaxY", maxY);
                        }
                    }
                    if (area != null) {
                        try {
                            damageObj.addProperty("damageFactor", area.getDamageFactor());
                        } catch (Throwable ignored) {
                        }
                    }
                    obj.add("damage", damageObj);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            obj.addProperty("available", false);
            obj.addProperty("error", t.getClass().getSimpleName());
        }
        return obj;
    }

    private static JsonObject buildTreeSeamDebugJson(Level level, IDimensionInfo provider, ChunkCoord coord) {
        JsonObject obj = new JsonObject();
        if (level == null || coord == null || provider == null) {
            obj.addProperty("available", false);
            obj.addProperty("reason", "missing-data");
            return obj;
        }
        try {
            int cx = coord.chunkX();
            int cz = coord.chunkZ();
            boolean city = BuildingInfo.isCity(new ChunkCoord(coord.dimension(), cx, cz), provider);
            boolean north = BuildingInfo.isCity(new ChunkCoord(coord.dimension(), cx, cz - 1), provider);
            boolean south = BuildingInfo.isCity(new ChunkCoord(coord.dimension(), cx, cz + 1), provider);
            boolean west = BuildingInfo.isCity(new ChunkCoord(coord.dimension(), cx - 1, cz), provider);
            boolean east = BuildingInfo.isCity(new ChunkCoord(coord.dimension(), cx + 1, cz), provider);
            boolean seam = (north != city) || (south != city) || (west != city) || (east != city);

            obj.addProperty("available", true);
            obj.addProperty("isCity", city);
            obj.addProperty("isSeam", seam);
            obj.addProperty("neighborCityNorth", north);
            obj.addProperty("neighborCitySouth", south);
            obj.addProperty("neighborCityWest", west);
            obj.addProperty("neighborCityEast", east);
            obj.addProperty("treeSeamFixEnabled", ConfigManager.CITY_BLEND_TREE_SEAM_FIX);
            obj.addProperty("treeSeamBuffer", ConfigManager.CITY_BLEND_TREE_SEAM_BUFFER);

            int protectedCount = 0;
            if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                protectedCount = ChunkPostProcessor.getProtectedTreeBlockCount(serverLevel, cx, cz);
                obj.addProperty("protectedTreeBlocks", protectedCount);
                obj.addProperty("seamCacheFlag", ChunkPostProcessor.isSeamChunk(serverLevel, cx, cz));
            }

            int minY = level.getMinBuildHeight();
            int maxY = level.getMaxBuildHeight() - 1;
            int baseX = cx << 4;
            int baseZ = cz << 4;
            int edgeBuffer = Math.max(1, ConfigManager.CITY_BLEND_TREE_SEAM_BUFFER);

            int treeBlocks = 0;
            int treeBlocksEdge = 0;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    boolean edge = x < edgeBuffer || z < edgeBuffer || x >= 16 - edgeBuffer || z >= 16 - edgeBuffer;
                    for (int y = minY; y <= maxY; y++) {
                        BlockState state = level.getBlockState(new BlockPos(baseX + x, y, baseZ + z));
                        if (ChunkPostProcessor.isTreeProtectedBlockForDebug(state)) {
                            treeBlocks++;
                            if (edge) {
                                treeBlocksEdge++;
                            }
                        }
                    }
                }
            }
            obj.addProperty("treeBlocksTotal", treeBlocks);
            obj.addProperty("treeBlocksEdge", treeBlocksEdge);
        } catch (Throwable t) {
            obj.addProperty("available", false);
            obj.addProperty("error", t.getClass().getSimpleName());
        }
        return obj;
    }

    private static JsonArray buildStructureDebugJson(Level level, ChunkCoord coord) {
        JsonArray out = new JsonArray();
        if (level == null || coord == null) {
            return out;
        }
        try {
            LevelChunk chunk = level.getChunk(coord.chunkX(), coord.chunkZ());
            if (chunk == null) {
                return out;
            }
            Map<Structure, StructureStart> starts = chunk.getAllStarts();
            if (starts == null || starts.isEmpty()) {
                return out;
            }
            for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
                Structure structure = entry.getKey();
                StructureStart start = entry.getValue();
                if (start == null || !start.isValid()) {
                    continue;
                }
                JsonObject obj = new JsonObject();
                ResourceLocation key = null;
                try {
                    key = level.registryAccess()
                        .registryOrThrow(Registries.STRUCTURE)
                        .getKey(structure);
                } catch (Throwable ignored) {
                }
                if (key != null) {
                    obj.addProperty("id", key.toString());
                } else if (structure != null) {
                    obj.addProperty("id", structure.toString());
                }
                try {
                    var bb = start.getBoundingBox();
                    if (bb != null) {
                        obj.addProperty("minX", bb.minX());
                        obj.addProperty("minY", bb.minY());
                        obj.addProperty("minZ", bb.minZ());
                        obj.addProperty("maxX", bb.maxX());
                        obj.addProperty("maxY", bb.maxY());
                        obj.addProperty("maxZ", bb.maxZ());
                    }
                } catch (Throwable ignored) {
                }
                out.add(obj);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static JsonObject buildUndergroundScanJson(ServerPlayer player, ChunkCoord coord, BuildingInfo buildingInfo) {
        JsonObject obj = new JsonObject();
        if (player == null || coord == null) {
            obj.addProperty("available", false);
            obj.addProperty("reason", "player-missing");
            return obj;
        }

        Level level = player.level();
        if (level == null) {
            obj.addProperty("available", false);
            obj.addProperty("reason", "level-missing");
            return obj;
        }

        int baseY = 0;
        try {
            if (buildingInfo != null) {
                baseY = buildingInfo.getCityGroundLevel();
            }
        } catch (Throwable ignored) {
        }
        if (baseY <= 0 && buildingInfo != null) {
            try {
                baseY = buildingInfo.profile != null ? buildingInfo.profile.GROUNDLEVEL : 0;
            } catch (Throwable ignored) {
            }
        }

        if (baseY <= 0) {
            obj.addProperty("available", false);
            obj.addProperty("reason", "no-baseY");
            return obj;
        }

        int scanMaxY = baseY - 1;
        int scanMinY = Math.max(level.getMinBuildHeight(), baseY - 64);
        if (scanMaxY < scanMinY) {
            obj.addProperty("available", false);
            obj.addProperty("reason", "invalid-scan-range");
            return obj;
        }

        int airCount = 0;
        int airMinY = Integer.MAX_VALUE;
        int airMaxY = Integer.MIN_VALUE;
        int stairCount = 0;
        int mossyStoneBricksCount = 0;
        int mossyStoneBrickStairsCount = 0;
        int stoneBrickStairsCount = 0;
        int total = 0;

        Map<String, Integer> blockCounts = new HashMap<>();
        Map<String, Integer> airNeighborCounts = new HashMap<>();

        int baseX = coord.chunkX() << 4;
        int baseZ = coord.chunkZ() << 4;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = scanMinY; y <= scanMaxY; y++) {
                    BlockState state = level.getBlockState(new BlockPos(baseX + x, y, baseZ + z));
                    total++;
                    if (state == null || state.isAir()) {
                        airCount++;
                        if (y < airMinY) {
                            airMinY = y;
                        }
                        if (y > airMaxY) {
                            airMaxY = y;
                        }
                        // Track what surrounds air pockets to identify carving sources.
                        collectAirNeighbors(level, baseX + x, y, baseZ + z, airNeighborCounts);
                        continue;
                    }
                    Block block = state.getBlock();
                    if (block != null) {
                        if (state.is(BlockTags.STAIRS)) {
                            stairCount++;
                        }
                        if (block == Blocks.MOSSY_STONE_BRICKS) {
                            mossyStoneBricksCount++;
                        } else if (block == Blocks.MOSSY_STONE_BRICK_STAIRS) {
                            mossyStoneBrickStairsCount++;
                        } else if (block == Blocks.STONE_BRICK_STAIRS) {
                            stoneBrickStairsCount++;
                        }

                        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
                        if (key != null) {
                            String id = key.toString();
                            blockCounts.put(id, blockCounts.getOrDefault(id, 0) + 1);
                        }
                    }
                }
            }
        }

        obj.addProperty("available", true);
        obj.addProperty("scanMinY", scanMinY);
        obj.addProperty("scanMaxY", scanMaxY);
        obj.addProperty("totalBlocks", total);
        obj.addProperty("airCount", airCount);
        if (airCount > 0) {
            obj.addProperty("airMinY", airMinY);
            obj.addProperty("airMaxY", airMaxY);
            obj.addProperty("airDepthMin", baseY - airMaxY);
            obj.addProperty("airDepthMax", baseY - airMinY);
        }
        obj.addProperty("stairCount", stairCount);
        obj.addProperty("mossyStoneBricksCount", mossyStoneBricksCount);
        obj.addProperty("mossyStoneBrickStairsCount", mossyStoneBrickStairsCount);
        obj.addProperty("stoneBrickStairsCount", stoneBrickStairsCount);

        List<Map.Entry<String, Integer>> topBlocks = blockCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .limit(8)
            .collect(Collectors.toList());
        JsonArray top = new JsonArray();
        for (Map.Entry<String, Integer> entry : topBlocks) {
            JsonObject item = new JsonObject();
            item.addProperty("id", entry.getKey());
            item.addProperty("count", entry.getValue());
            top.add(item);
        }
        obj.add("topBlocks", top);

        List<Map.Entry<String, Integer>> topNeighbors = airNeighborCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .limit(8)
            .collect(Collectors.toList());
        JsonArray airNeighbors = new JsonArray();
        for (Map.Entry<String, Integer> entry : topNeighbors) {
            JsonObject item = new JsonObject();
            item.addProperty("id", entry.getKey());
            item.addProperty("count", entry.getValue());
            airNeighbors.add(item);
        }
        obj.add("airNeighborTopBlocks", airNeighbors);
        return obj;
    }

    private static void collectAirNeighbors(Level level, int x, int y, int z, Map<String, Integer> counts) {
        if (level == null || counts == null) {
            return;
        }
        collectNeighbor(level, x + 1, y, z, counts);
        collectNeighbor(level, x - 1, y, z, counts);
        collectNeighbor(level, x, y + 1, z, counts);
        collectNeighbor(level, x, y - 1, z, counts);
        collectNeighbor(level, x, y, z + 1, counts);
        collectNeighbor(level, x, y, z - 1, counts);
    }

    private static void collectNeighbor(Level level, int x, int y, int z, Map<String, Integer> counts) {
        try {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (state == null || state.isAir()) {
                return;
            }
            Block block = state.getBlock();
            if (block == null) {
                return;
            }
            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
            if (key == null) {
                return;
            }
            String id = key.toString();
            counts.put(id, counts.getOrDefault(id, 0) + 1);
        } catch (Throwable ignored) {
        }
    }

    private static String safeName(Object target) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod("getName");
            Object v = m.invoke(target);
            if (v != null) {
                return String.valueOf(v);
            }
        } catch (Throwable ignored) {
        }
        return String.valueOf(target);
    }

    private static void addBuilderTrace(JsonObject obj, CityShiftField.DebugCell trace) {
        obj.addProperty("available", trace != null);
        if (trace == null) {
            return;
        }
        obj.addProperty("lockedSource", trace.lockedSource());
        obj.addProperty("roleType", trace.roleType());
        obj.addProperty("roleName", switch (trace.roleType()) {
            case 2 -> "CITY";
            case 3 -> "HIGHWAY";
            default -> "NONE";
        });
        obj.addProperty("roleLevel", trace.roleLevel());
        if (trace.sourceFloor() != Integer.MIN_VALUE) {
            obj.addProperty("sourceFloor", trace.sourceFloor());
        }
        addFinite(obj, "sourceDemand", trace.sourceDemand());
        addFinite(obj, "sourceDistance", trace.sourceDistance());
        addFinite(obj, "fadeDistance", trace.fadeDistance());
        addFinite(obj, "naturalSurface", trace.naturalSurface());
        addFinite(obj, "latticeStep", trace.latticeStep());
        addFinite(obj, "preRecoveryShift", trace.preRecoveryShift());
        obj.addProperty("zeroDemandCityEdge", trace.zeroDemandCityEdge());
        obj.addProperty("exactReceiverEdge", trace.exactReceiverEdge());
        obj.addProperty("recoverySampled", trace.recoverySampled());
        obj.addProperty("recoveryCandidateSeen", trace.recoveryCandidateSeen());
        obj.addProperty("recoveryApplied", trace.recoveryApplied());
        addFinite(obj, "recoveryCandidate", trace.recoveryCandidate());
        if (trace.recoveryFloor() != Integer.MIN_VALUE) {
            obj.addProperty("recoveryFloor", trace.recoveryFloor());
        }
        if (trace.recoveryNatural() != Integer.MIN_VALUE) {
            obj.addProperty("recoveryNatural", trace.recoveryNatural());
        }
        addFinite(obj, "propagatedShift", trace.propagatedShift());
        addFinite(obj, "finalShift", trace.finalShift());
    }

    private static void addFinite(JsonObject obj, String name, double value) {
        if (Double.isFinite(value)) {
            obj.addProperty(name, value);
        }
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static String sanitize(String label) {
        String cleaned = label.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() > 48 ? cleaned.substring(0, 48) : cleaned;
    }
}
