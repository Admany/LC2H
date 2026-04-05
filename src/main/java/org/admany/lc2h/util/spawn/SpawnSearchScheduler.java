package org.admany.lc2h.util.spawn;

import mcjty.lostcities.api.LostChunkCharacteristics;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries;
import mcjty.lostcities.worldgen.lost.cityassets.Building;
import mcjty.lostcities.worldgen.lost.cityassets.BuildingPart;
import mcjty.lostcities.worldgen.lost.cityassets.MultiBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;

import javax.annotation.Nonnull;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public final class SpawnSearchScheduler {
    private static final long SKIP_TTL_MS = Math.max(60_000L,
        Long.getLong("lc2h.spawnsearch.skipTtlMs", TimeUnit.HOURS.toMillis(1)));
    private static final int MAX_SKIP_CACHE = Math.max(4096,
        Integer.getInteger("lc2h.spawnsearch.skipCacheMax", 200_000));
    private static final ConcurrentHashMap<SpawnChunkKey, Long> SKIPPED = new ConcurrentHashMap<>();
    private static final int REGION_SIZE_CHUNKS = Math.max(4,
        Integer.getInteger("lc2h.spawnsearch.regionSizeChunks", 8));
    private static final long REGION_SKIP_TTL_MS = Math.max(60_000L,
        Long.getLong("lc2h.spawnsearch.regionSkipTtlMs", TimeUnit.MINUTES.toMillis(20)));
    private static final long REGION_SCAN_TTL_MS = Math.max(10_000L,
        Long.getLong("lc2h.spawnsearch.regionScanTtlMs", TimeUnit.MINUTES.toMillis(10)));
    private static final long REGION_BLOOM_TTL_MS = Math.max(60_000L,
        Long.getLong("lc2h.spawnsearch.regionBloomTtlMs", TimeUnit.MINUTES.toMillis(30)));
    private static final long REGION_PREFETCH_TTL_MS = Math.max(250L,
        Long.getLong("lc2h.spawnsearch.regionPrefetchTtlMs", 5_000L));
    private static final int REGION_PREFETCH_LIMIT = Math.max(32,
        Integer.getInteger("lc2h.spawnsearch.regionPrefetchLimit", 512));
    private static final ConcurrentHashMap<SpawnRegionKey, Long> REGION_SKIPPED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SpawnRegionKey, Long> REGION_PREFETCH = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SpawnRegionKey, RegionScanCache> REGION_SCAN = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SpawnRegionBaseKey, RegionBloom> REGION_BLOOM = new ConcurrentHashMap<>();
    private static final int CITY_CACHE_MAX = Math.max(64,
        Integer.getInteger("lc2h.spawnsearch.cityCacheMax", 2048));
    private static final ConcurrentHashMap<CityChunkKey, CityKey> CITY_CHUNK_INDEX = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CityKey, CityInfo> CITY_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CitySignatureDecisionKey, Boolean> CITY_SIGNATURE_DECISIONS = new ConcurrentHashMap<>();
    private static final long SPAWN_PROGRESS_LOG_INTERVAL_MS = Math.max(1_000L,
        Long.getLong("lc2h.spawnsearch.logIntervalMs", 5_000L));
    private static final AtomicLong SPAWN_PROGRESS_LAST_LOG = new AtomicLong(0L);
    private static final AtomicLong SPAWN_PROGRESS_CHUNKS_SCANNED = new AtomicLong(0L);
    private static final AtomicLong SPAWN_PROGRESS_CITIES_FOUND = new AtomicLong(0L);
    private static final AtomicLong SPAWN_PROGRESS_CITIES_FAILED = new AtomicLong(0L);
    private static final AtomicLong SPAWN_PROGRESS_CHUNKS_REJECTED = new AtomicLong(0L);
    private static final AtomicLong SPAWN_PROGRESS_CITIES_SIGNATURE_REJECTED = new AtomicLong(0L);
    private static final AtomicLong SPAWN_PROGRESS_CITY_CHUNKS_WITH_BUILDING_ID = new AtomicLong(0L);
    private static final AtomicLong SPAWN_PROGRESS_CITY_CHUNKS_WITH_BUILDING_CANDIDATE = new AtomicLong(0L);
    private static final AtomicLong SPAWN_PROGRESS_PENDING_BACKPRESSURE = new AtomicLong(0L);
    private static volatile String SPAWN_PROGRESS_RAW_BUILDINGS = "";
    private static volatile String SPAWN_PROGRESS_RAW_PARTS = "";
    private static volatile String SPAWN_PROGRESS_RESOLVED_BUILDINGS = "";
    private static volatile String SPAWN_PROGRESS_RESOLVED_PARTS = "";
    private static volatile String SPAWN_PROGRESS_REQUIRED_BUILDINGS = "";
    private static volatile String SPAWN_PROGRESS_REQUIRED_PARTS = "";
    private static volatile String SPAWN_PROGRESS_SAMPLE_BUILDINGS = "";
    private static volatile String SPAWN_PROGRESS_STAGE = "init";
    private static final int CITY_SAMPLE_LIMIT = Math.max(1,
        Integer.getInteger("lc2h.spawnsearch.citySampleLimit", 8));
    private static final long CITY_SAMPLE_RETRY_MS = Math.max(50L,
        Long.getLong("lc2h.spawnsearch.citySampleRetryMs", 200L));
    private static final boolean SPAWN_DEBUG_DUMP_ENABLED = false;
    private static final AtomicLong SPAWN_DEBUG_DUMP_LAST_MS = new AtomicLong(0L);
    private static final long PENDING_TTL_MS = Math.max(250L,
        Long.getLong("lc2h.spawnsearch.pendingTtlMs", 2_000L));
    private static final ConcurrentHashMap<SpawnChunkKey, Long> PENDING = new ConcurrentHashMap<>();
    private static final boolean ALLOW_SYNC_BUILDINGINFO_FALLBACK =
        Boolean.getBoolean("lc2h.spawnsearch.allowSyncBuildingInfoFallback");
    private static final int[] BUILDING_SAMPLE_OFFSETS = new int[] {2, 4, 6, 8, 10, 12, 14};
    private static final int DEFERRED_CHUNK_VALIDATION_LIMIT = Math.max(1,
        Integer.getInteger("lc2h.spawnsearch.deferredChunkValidationLimit", 8));
    private static final long PENDING_BACKPRESSURE_SLEEP_MS = Math.max(1L,
        Long.getLong("lc2h.spawnsearch.pendingBackpressureSleepMs", 8L));

    private SpawnSearchScheduler() {
    }

    public static BlockPos findSafeSpawnPoint(Level world,
                                              IDimensionInfo provider,
                                              @Nonnull Predicate<BlockPos> isSuitable,
                                              @Nonnull ServerLevelData serverLevelData,
                                              @Nonnull BiPredicate<Level, BlockPos> isValidStandingPosition) {
        if (world == null || provider == null) {
            throw new IllegalStateException("Spawn search requires a valid world and provider");
        }
        LostCityProfile profile = provider.getProfile();
        if (profile == null) {
            throw new IllegalStateException("Spawn search requires a valid Lost City profile");
        }

        resetSpawnProgress();
        SpawnAssetIndex.ensureLoaded(provider.getWorld());

        String[] buildingArray = profile.FORCE_SPAWN_BUILDINGS != null ? profile.FORCE_SPAWN_BUILDINGS : new String[0];
        String[] partArray = profile.FORCE_SPAWN_PARTS != null ? profile.FORCE_SPAWN_PARTS : new String[0];
        Set<String> rawParts = new HashSet<>(Arrays.asList(partArray));
        // FORCE_SPAWN_BUILDINGS may use "namespace:building/partVariant" composite syntax.
        // Keep the full IDs including variant suffixes - the asset index handles variant resolution properly.
        Set<String> rawBuildings = new HashSet<>(Arrays.asList(buildingArray));
        // Remove nulls/blanks
        rawBuildings.removeIf(e -> e == null || e.isBlank());
        Set<String> resolvedBuildings = resolveIds(rawBuildings, value -> {
            String building = SpawnAssetIndex.resolveBuildingId(value);
            if (building != null && !building.equals(value)) {
                return building;
            }
            String multi = SpawnAssetIndex.resolveMultiBuildingId(value);
            return multi != null ? multi : value;
        });
        Set<String> resolvedParts = resolveIds(rawParts, SpawnAssetIndex::resolvePartId);
        Set<String> requiredBuildings = autoResolveMissing(rawBuildings, resolvedBuildings, true);
        Set<String> requiredParts = autoResolveMissing(rawParts, resolvedParts, false);
        ResolvedAssetFilter assetFilter = ResolvedAssetFilter.resolve(requiredBuildings, requiredParts, java.util.Collections.emptySet());
        SPAWN_PROGRESS_RAW_BUILDINGS = formatIdSet(rawBuildings);
        SPAWN_PROGRESS_RAW_PARTS = formatIdSet(rawParts);
        SPAWN_PROGRESS_RESOLVED_BUILDINGS = formatIdSet(resolvedBuildings);
        SPAWN_PROGRESS_RESOLVED_PARTS = formatIdSet(resolvedParts);
        if (!resolvedBuildings.equals(rawBuildings) || !resolvedParts.equals(rawParts)) {
            LC2H.LOGGER.info(
                "[LC2H] Resolved spawn requirements: buildings={} -> {}, parts={} -> {}",
                formatIdSample(rawBuildings, 6),
                formatIdSample(resolvedBuildings, 6),
                formatIdSample(rawParts, 6),
                formatIdSample(resolvedParts, 6)
            );
        }
        writeSpawnDebugFile(provider, rawBuildings, rawParts, requiredBuildings, requiredParts);
        boolean buildingConstraintsConfigured = !rawBuildings.isEmpty() || !rawParts.isEmpty();
        boolean requiresBuilding = profile.FORCE_SPAWN_IN_BUILDING
            || buildingConstraintsConfigured
            || !requiredBuildings.isEmpty()
            || !requiredParts.isEmpty();
        final boolean strictSpawnConstraints = !requiredBuildings.isEmpty() || !requiredParts.isEmpty();
        if (requiresBuilding && requiredBuildings.isEmpty() && requiredParts.isEmpty()) {
            LC2H.LOGGER.warn("[LC2H] No valid spawn building/part IDs resolved; falling back to any building");
            requiresBuilding = true;
        }
        int constraintHash = computeConstraintHash(profile, requiredBuildings, requiredParts);
        SPAWN_PROGRESS_REQUIRED_BUILDINGS = formatIdSet(requiredBuildings);
        SPAWN_PROGRESS_REQUIRED_PARTS = formatIdSet(requiredParts);
        logMissingAssets(requiredBuildings, requiredParts);

        BlockPos startPos = world.getSharedSpawnPos();
        if (!requiredBuildings.isEmpty()) {
            SPAWN_PROGRESS_STAGE = "exact-required-buildings";
            BlockPos exactBuildingResult = findSpawnInRequiredBuildings(world, provider, profile, isSuitable,
                isValidStandingPosition, requiredBuildings, requiredParts, assetFilter, constraintHash, startPos);
            if (exactBuildingResult != null) {
                return exactBuildingResult;
            }
        }

        if (requiresBuilding) {
            SPAWN_PROGRESS_STAGE = "city-building-search";
            BlockPos cityResult = findSpawnInCity(world, provider, profile, isSuitable, isValidStandingPosition,
                requiredBuildings, requiredParts, assetFilter, constraintHash, startPos);
            if (cityResult != null) {
                return cityResult;
            }
        }

        if (!requiredBuildings.isEmpty() || !requiredParts.isEmpty()) {
            SPAWN_PROGRESS_STAGE = "relaxed-city-fallback";
            LC2H.LOGGER.warn(
                "[LC2H] Failed to find spawn in required buildings/parts {}; falling back to any valid city building near spawn",
                formatIdSample(requiredBuildings.isEmpty() ? requiredParts : requiredBuildings, 6)
            );
            // The original isSuitable predicate may still be constrained to specific building types
            // (e.g. from LC's FORCE_SPAWN_BUILDINGS profile). For a true relaxed fallback we need a
            // permissive predicate that accepts any above-ground position inside any city building.
            final IDimensionInfo relaxedProvider = provider;
            // CRITICAL FIX: Pre-load and cache building info to avoid synchronous calls in predicate loop.
            // Calling getBuildingInfo() in a loop for every tested position can freeze the server.
            final java.util.Map<ChunkCoord, BuildingInfo> buildingCache = new java.util.concurrent.ConcurrentHashMap<>();
            Predicate<BlockPos> relaxedIsSuitable = pos -> {
                ChunkCoord c = new ChunkCoord(relaxedProvider.getType(), pos.getX() >> 4, pos.getZ() >> 4);
                try {
                    // Use cached BuildingInfo that was pre-loaded by the search loop
                    BuildingInfo bi = buildingCache.computeIfAbsent(c, coord -> {
                        // Fallback: compute if not cached (should rarely happen as search loop pre-caches)
                        return getBuildingInfoNow(relaxedProvider, coord);
                    });
                    if (bi == null || !bi.isCity || !bi.hasBuilding) {
                        return false;
                    }
                    int bottom = bi.getBuildingBottomHeight();
                    return bottom != Integer.MIN_VALUE && pos.getY() >= bottom;
                } catch (Throwable ignored) {
                    return false;
                }
            };
            BlockPos relaxedBuildingResult = findSpawnInCityWithBuildingCache(
                world,
                provider,
                profile,
                relaxedIsSuitable,
                isValidStandingPosition,
                java.util.Collections.emptySet(),
                java.util.Collections.emptySet(),
                ResolvedAssetFilter.NONE,
                computeConstraintHash(profile, java.util.Collections.emptySet(), java.util.Collections.emptySet()),
                startPos,
                buildingCache
            );
            if (relaxedBuildingResult != null) {
                return relaxedBuildingResult;
            }
        }

        Random rand = new Random(provider.getSeed());
        boolean didRelaxConstraints = false;
        int radius = profile.SPAWN_CHECK_RADIUS;
        int attempts = 0;
        int maxAttempts = computeSpawnAttemptBudget(profile, requiresBuilding, requiredBuildings, requiredParts);
        Set<Long> localChecked = new HashSet<>();
        java.util.ArrayList<SpawnCandidate> deferredCandidates = new java.util.ArrayList<>(DEFERRED_CHUNK_VALIDATION_LIMIT);
        SPAWN_PROGRESS_STAGE = "generic-search";

        while (true) {
            for (int i = 0; i < 200; i++) {
                int x = rand.nextInt(radius * 2) - radius;
                int z = rand.nextInt(radius * 2) - radius;
                attempts++;
                SPAWN_PROGRESS_CHUNKS_SCANNED.incrementAndGet();
                logSpawnProgress(world.dimension());

                int chunkX = x >> 4;
                int chunkZ = z >> 4;
                long localKey = packChunkKey(chunkX, chunkZ);
                if (!localChecked.add(localKey)) {
                    continue;
                }

                ChunkCoord coord = new ChunkCoord(provider.getType(), chunkX, chunkZ);
                if (requiresBuilding || !requiredBuildings.isEmpty() || !requiredParts.isEmpty()) {
                    if (shouldSkip(world.dimension(), chunkX, chunkZ, constraintHash)) {
                        continue;
                    }
                    if (shouldSkipRegion(world.dimension(), chunkX, chunkZ, constraintHash)) {
                        continue;
                    }
                    BuildingInfo info = getBuildingInfoNow(provider, coord);
                    if (info == null) {
                        continue;
                    }
                    if (!passesBuildingInfo(info, true, assetFilter)) {
                        recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                        SPAWN_PROGRESS_CHUNKS_REJECTED.incrementAndGet();
                        logSpawnProgress(world.dimension());
                        continue;
                    }
                    SpawnCandidate candidate = findInBuilding(info, x, z, isSuitable, requiredParts, assetFilter);
                    if (candidate == null) {
                        recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                        SPAWN_PROGRESS_CHUNKS_REJECTED.incrementAndGet();
                        logSpawnProgress(world.dimension());
                        continue;
                    }
                    BlockPos found = tryValidateSpawnCandidate(world, candidate, isValidStandingPosition, false);
                    if (found != null) {
                        return found;
                    }
                    queueDeferredCandidate(deferredCandidates, candidate);
                    continue;
                }

                if (shouldSkip(world.dimension(), chunkX, chunkZ, constraintHash)) {
                    continue;
                }
                if (shouldSkipRegion(world.dimension(), chunkX, chunkZ, constraintHash)) {
                    continue;
                }
                RegionScanCache scanCache = getRegionScan(world.dimension(), provider, chunkX, chunkZ, constraintHash,
                    requiresBuilding, requiredBuildings, requiredParts, assetFilter);
                if (scanCache != null) {
                    int regionIndex = scanCache.indexFor(chunkX, chunkZ);
                    if (!scanCache.candidates.get(regionIndex)) {
                        continue;
                    }
                } else {
                    maybePrefetchRegion(world.dimension(), provider, chunkX, chunkZ, constraintHash,
                        requiresBuilding, requiredBuildings, requiredParts, assetFilter);
                }

                PrefilterDecision decision = scanCache != null
                    ? (scanCache.needsInfo.get(scanCache.indexFor(chunkX, chunkZ)) ? PrefilterDecision.NEED_INFO : PrefilterDecision.PASS)
                    : prefilter(coord, provider, requiresBuilding, requiredBuildings, requiredParts, assetFilter);
                if (decision == PrefilterDecision.FAIL) {
                    recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                    SPAWN_PROGRESS_CHUNKS_REJECTED.incrementAndGet();
                    logSpawnProgress(world.dimension());
                    continue;
                }
                if (decision == PrefilterDecision.NEED_INFO) {
                    BuildingInfo info = getBuildingInfoNow(provider, coord);
                    if (info == null) {
                        continue;
                    }
                    if (!passesBuildingInfo(info, requiresBuilding, assetFilter)) {
                        recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                        SPAWN_PROGRESS_CHUNKS_REJECTED.incrementAndGet();
                        logSpawnProgress(world.dimension());
                        continue;
                    }
                    SpawnCandidate candidate = findInBuilding(info, x, z, isSuitable, requiredParts, assetFilter);
                    if (candidate == null) {
                        recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                        SPAWN_PROGRESS_CHUNKS_REJECTED.incrementAndGet();
                        logSpawnProgress(world.dimension());
                        continue;
                    }
                    BlockPos found = tryValidateSpawnCandidate(world, candidate, isValidStandingPosition, false);
                    if (found != null) {
                        return found;
                    }
                    queueDeferredCandidate(deferredCandidates, candidate);
                    continue;
                }

                LostCityProfile chunkProfile;
                try {
                    chunkProfile = BuildingInfo.getProfile(coord, provider);
                } catch (Throwable t) {
                    recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                    SPAWN_PROGRESS_CHUNKS_REJECTED.incrementAndGet();
                    logSpawnProgress(world.dimension());
                    continue;
                }

                int startY = chunkProfile.GROUNDLEVEL - 5;
                for (int y = startY; y < 125; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!isSuitable.test(pos)) {
                        continue;
                    }
                    if (isValidStandingPosition.test(world, pos)) {
                        return pos.above();
                    }
                }

                recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                SPAWN_PROGRESS_CHUNKS_REJECTED.incrementAndGet();
                logSpawnProgress(world.dimension());
            }

            BlockPos deferredFound = drainDeferredCandidates(world, deferredCandidates, isValidStandingPosition, constraintHash);
            if (deferredFound != null) {
                return deferredFound;
            }

            radius += profile.SPAWN_RADIUS_INCREASE;
            if (attempts > maxAttempts) {
                if (!didRelaxConstraints && strictSpawnConstraints) {
                    didRelaxConstraints = true;
                    SPAWN_PROGRESS_STAGE = "generic-search-relaxed";
                    LC2H.LOGGER.warn(
                        "[LC2H] No constrained spawn found after {} attempts ({}); relaxing spawn constraints and retrying generic search",
                        attempts,
                        profile.SPAWN_CHECK_ATTEMPTS
                    );
                    requiresBuilding = false;
                    requiredBuildings = java.util.Collections.emptySet();
                    requiredParts = java.util.Collections.emptySet();
                    assetFilter = ResolvedAssetFilter.NONE;
                    constraintHash = computeConstraintHash(profile, requiredBuildings, requiredParts);
                    maxAttempts = computeSpawnAttemptBudget(profile, requiresBuilding, requiredBuildings, requiredParts);
                    attempts = 0;
                    radius = Math.max(16, profile.SPAWN_CHECK_RADIUS);
                    localChecked.clear();
                    deferredCandidates.clear();
                    SPAWN_PROGRESS_REQUIRED_BUILDINGS = formatIdSet(requiredBuildings);
                    SPAWN_PROGRESS_REQUIRED_PARTS = formatIdSet(requiredParts);
                    continue;
                }
                String failureDetails = buildSpawnFailureDetails(world.dimension(), attempts, maxAttempts, radius);
                LC2H.LOGGER.error("Can't find a valid spawn position! {}", failureDetails);
                throw new RuntimeException("Can't find a valid spawn position! " + failureDetails);
            }
        }
    }

    private static BlockPos findSpawnInRequiredBuildings(Level world,
                                                         IDimensionInfo provider,
                                                         LostCityProfile profile,
                                                         Predicate<BlockPos> isSuitable,
                                                         BiPredicate<Level, BlockPos> isValidStandingPosition,
                                                         Set<String> requiredBuildings,
                                                         Set<String> requiredParts,
                                                         ResolvedAssetFilter assetFilter,
                                                         int constraintHash,
                                                         BlockPos startPos) {
        int radiusBlocks = Math.max(16, profile.SPAWN_CHECK_RADIUS);
        int maxAttempts = computeSpawnAttemptBudget(profile, true, requiredBuildings, requiredParts);
        int radiusIncrease = Math.max(1, profile.SPAWN_RADIUS_INCREASE);
        int originChunkX = startPos.getX() >> 4;
        int originChunkZ = startPos.getZ() >> 4;
        int radiusChunks = Math.max(1, radiusBlocks >> 4);
        int attempts = 0;
        Set<Long> visited = new HashSet<>();
        while (attempts <= maxAttempts) {
            for (int r = 0; r <= radiusChunks; r++) {
                java.util.ArrayList<SpawnCandidate> deferredCandidates = new java.util.ArrayList<>(DEFERRED_CHUNK_VALIDATION_LIMIT);
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dz) != r) {
                            continue;
                        }
                        int chunkX = originChunkX + dx;
                        int chunkZ = originChunkZ + dz;
                        long packed = packChunkKey(chunkX, chunkZ);
                        if (!visited.add(packed)) {
                            continue;
                        }
                        attempts++;
                        SPAWN_PROGRESS_CHUNKS_SCANNED.incrementAndGet();
                        logSpawnProgress(world.dimension());
                        if (attempts > maxAttempts) {
                            break;
                        }

                        if (shouldSkip(world.dimension(), chunkX, chunkZ, constraintHash)) {
                            continue;
                        }
                        if (shouldSkipRegion(world.dimension(), chunkX, chunkZ, constraintHash)) {
                            continue;
                        }
                        ChunkCoord coord = new ChunkCoord(provider.getType(), chunkX, chunkZ);
                        BuildingInfo info = getBuildingInfoNow(provider, coord);
                        if (info == null) {
                            continue;
                        }
                        if (!passesBuildingInfo(info, true, assetFilter)) {
                            recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                            continue;
                        }

                        CityInfo cityInfo = getCachedCityInfo(world.dimension(), coord);
                        if (cityInfo == null) {
                            cityInfo = scanCity(provider, world.dimension(), coord, 512, 2048);
                        }
                        if (cityInfo != null) {
                            if (cityInfo.quickReject(requiredBuildings, requiredParts, constraintHash)) {
                                recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                                continue;
                            }
                            if (cityInfo.wasTried(constraintHash)) {
                                recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                                continue;
                            }
                            BlockPos cityResult = searchCityCandidates(world, provider, cityInfo, isSuitable,
                                isValidStandingPosition, requiredBuildings, requiredParts, assetFilter, constraintHash);
                            cityInfo.recordDecision(constraintHash, cityResult != null);
                            if (cityResult != null) {
                                return cityResult;
                            }
                            recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                            continue;
                        }

                        SpawnCandidate candidate = findInBuildingChunk(info, chunkX, chunkZ, isSuitable, requiredParts, assetFilter);
                        if (candidate == null) {
                            recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                            continue;
                        }

                        BlockPos found = tryValidateSpawnCandidate(world, candidate, isValidStandingPosition, false);
                        if (found != null) {
                            return found;
                        }

                        queueDeferredCandidate(deferredCandidates, candidate);
                    }
                }
                BlockPos deferredFound = drainDeferredCandidates(world, deferredCandidates, isValidStandingPosition, constraintHash);
                if (deferredFound != null) {
                    return deferredFound;
                }
            }
            radiusChunks = Math.max(radiusChunks + (radiusIncrease >> 4), radiusChunks + 1);
        }

        return null;
    }

    private static BlockPos findSpawnInCity(Level world,
                                            IDimensionInfo provider,
                                            LostCityProfile profile,
                                            Predicate<BlockPos> isSuitable,
                                            BiPredicate<Level, BlockPos> isValidStandingPosition,
                                            Set<String> requiredBuildings,
                                            Set<String> requiredParts,
                                            ResolvedAssetFilter assetFilter,
                                            int constraintHash,
                                            BlockPos startPos) {
        int radiusBlocks = Math.max(16, profile.SPAWN_CHECK_RADIUS);
        int maxAttempts = computeSpawnAttemptBudget(profile, true, requiredBuildings, requiredParts);
        int radiusIncrease = Math.max(1, profile.SPAWN_RADIUS_INCREASE);
        Set<Long> visited = new HashSet<>();
        int attempts = 0;

        int originChunkX = startPos.getX() >> 4;
        int originChunkZ = startPos.getZ() >> 4;
        int radiusChunks = Math.max(1, radiusBlocks >> 4);
        while (attempts <= maxAttempts) {
            for (int r = 0; r <= radiusChunks; r++) {
                java.util.ArrayList<SpawnCandidate> deferredCandidates = new java.util.ArrayList<>(DEFERRED_CHUNK_VALIDATION_LIMIT);
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dz) != r) {
                            continue;
                        }
                        int chunkX = originChunkX + dx;
                        int chunkZ = originChunkZ + dz;
                        long packed = packChunkKey(chunkX, chunkZ);
                        if (!visited.add(packed)) {
                            continue;
                        }
                        attempts++;
                        SPAWN_PROGRESS_CHUNKS_SCANNED.incrementAndGet();
                        logSpawnProgress(world.dimension());
                        if (attempts > maxAttempts) {
                            break;
                        }

                        if (shouldSkip(world.dimension(), chunkX, chunkZ, constraintHash)) {
                            continue;
                        }
                        if (shouldSkipRegion(world.dimension(), chunkX, chunkZ, constraintHash)) {
                            continue;
                        }

                        ChunkCoord coord = new ChunkCoord(provider.getType(), chunkX, chunkZ);
                        BuildingInfo info = getBuildingInfoNow(provider, coord);
                        if (info == null) {
                            continue;
                        }

                        if (!passesBuildingInfo(info, true, assetFilter)) {
                            recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                            SPAWN_PROGRESS_CHUNKS_REJECTED.incrementAndGet();
                            logSpawnProgress(world.dimension());
                            continue;
                        }

                        CityInfo cityInfo = getCachedCityInfo(world.dimension(), coord);
                        if (cityInfo == null) {
                            cityInfo = scanCity(provider, world.dimension(), coord, 512, 2048);
                        }
                        if (cityInfo != null) {
                            if (cityInfo.quickReject(requiredBuildings, requiredParts, constraintHash)) {
                                recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                                SPAWN_PROGRESS_CHUNKS_REJECTED.incrementAndGet();
                                SPAWN_PROGRESS_CITIES_FAILED.incrementAndGet();
                                logSpawnProgress(world.dimension());
                                continue;
                            }
                            if (cityInfo.wasTried(constraintHash)) {
                                recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                                SPAWN_PROGRESS_CHUNKS_REJECTED.incrementAndGet();
                                logSpawnProgress(world.dimension());
                                continue;
                            }
                            if (cityInfo.needsBuildingIdSamples()) {
                                cityInfo.ensureBuildingIdSamples(provider, requiredBuildings, requiredParts);
                            }
                            BlockPos cityResult = searchCityCandidates(world, provider, cityInfo, isSuitable,
                                isValidStandingPosition, requiredBuildings, requiredParts, assetFilter, constraintHash);
                            cityInfo.recordDecision(constraintHash, cityResult != null);
                            if (cityResult != null) {
                                return cityResult;
                            }
                            SPAWN_PROGRESS_CITIES_FAILED.incrementAndGet();
                            logSpawnProgress(world.dimension());
                            recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                            SPAWN_PROGRESS_CHUNKS_REJECTED.incrementAndGet();
                            continue;
                        }

                        SpawnCandidate candidate = findInBuildingChunk(info, chunkX, chunkZ, isSuitable, requiredParts, assetFilter);
                        if (candidate == null) {
                            recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                            SPAWN_PROGRESS_CHUNKS_REJECTED.incrementAndGet();
                            logSpawnProgress(world.dimension());
                            continue;
                        }

                        BlockPos found = tryValidateSpawnCandidate(world, candidate, isValidStandingPosition, false);
                        if (found != null) {
                            return found;
                        }

                        queueDeferredCandidate(deferredCandidates, candidate);
                    }
                }
                BlockPos deferredFound = drainDeferredCandidates(world, deferredCandidates, isValidStandingPosition, constraintHash);
                if (deferredFound != null) {
                    return deferredFound;
                }
            }
            radiusChunks = Math.max(radiusChunks + (radiusIncrease >> 4), radiusChunks + 1);
        }
        return null;
    }

    /**
     * Variant of findSpawnInCity that maintains a BuildingInfo cache to avoid
     * repeated synchronous calls within predicates. This prevents server freezes
     * when testing many positions in relaxed spawn fallback searches.
     */
    private static BlockPos findSpawnInCityWithBuildingCache(Level world,
                                                             IDimensionInfo provider,
                                                             LostCityProfile profile,
                                                             Predicate<BlockPos> isSuitable,
                                                             BiPredicate<Level, BlockPos> isValidStandingPosition,
                                                             Set<String> requiredBuildings,
                                                             Set<String> requiredParts,
                                                             ResolvedAssetFilter assetFilter,
                                                             int constraintHash,
                                                             BlockPos startPos,
                                                             java.util.Map<ChunkCoord, BuildingInfo> buildingCache) {
        int radiusBlocks = Math.max(16, profile.SPAWN_CHECK_RADIUS);
        int maxAttempts = computeSpawnAttemptBudget(profile, true, requiredBuildings, requiredParts);
        int radiusIncrease = Math.max(1, profile.SPAWN_RADIUS_INCREASE);
        Set<Long> visited = new HashSet<>();
        int attempts = 0;

        int originChunkX = startPos.getX() >> 4;
        int originChunkZ = startPos.getZ() >> 4;
        int radiusChunks = Math.max(1, radiusBlocks >> 4);
        while (attempts <= maxAttempts) {
            for (int r = 0; r <= radiusChunks; r++) {
                java.util.ArrayList<SpawnCandidate> deferredCandidates = new java.util.ArrayList<>(DEFERRED_CHUNK_VALIDATION_LIMIT);
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dz) != r) {
                            continue;
                        }
                        int chunkX = originChunkX + dx;
                        int chunkZ = originChunkZ + dz;
                        long packed = packChunkKey(chunkX, chunkZ);
                        if (!visited.add(packed)) {
                            continue;
                        }
                        attempts++;
                        SPAWN_PROGRESS_CHUNKS_SCANNED.incrementAndGet();
                        logSpawnProgress(world.dimension());
                        if (attempts > maxAttempts) {
                            break;
                        }

                        if (shouldSkip(world.dimension(), chunkX, chunkZ, constraintHash)) {
                            continue;
                        }
                        if (shouldSkipRegion(world.dimension(), chunkX, chunkZ, constraintHash)) {
                            continue;
                        }

                        ChunkCoord coord = new ChunkCoord(provider.getType(), chunkX, chunkZ);
                        // Pre-load and cache building info to avoid repeated synchronous calls
                        BuildingInfo info = buildingCache.computeIfAbsent(coord, c -> getBuildingInfoNow(provider, c));
                        if (info == null) {
                            continue;
                        }

                        if (!passesBuildingInfo(info, true, assetFilter)) {
                            recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                            SPAWN_PROGRESS_CHUNKS_REJECTED.incrementAndGet();
                            logSpawnProgress(world.dimension());
                            continue;
                        }

                        SpawnCandidate candidate = findInBuildingChunk(info, chunkX, chunkZ, isSuitable, requiredParts, assetFilter);
                        if (candidate == null) {
                            recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                            SPAWN_PROGRESS_CHUNKS_REJECTED.incrementAndGet();
                            logSpawnProgress(world.dimension());
                            continue;
                        }

                        BlockPos found = tryValidateSpawnCandidate(world, candidate, isValidStandingPosition, false);
                        if (found != null) {
                            return found;
                        }

                        queueDeferredCandidate(deferredCandidates, candidate);
                    }
                }
                BlockPos deferredFound = drainDeferredCandidates(world, deferredCandidates, isValidStandingPosition, constraintHash);
                if (deferredFound != null) {
                    return deferredFound;
                }
            }
            radiusChunks = Math.max(radiusChunks + (radiusIncrease >> 4), radiusChunks + 1);
        }
        return null;
    }

    private static BlockPos searchCityCandidates(Level world,
                                                 IDimensionInfo provider,
                                                 CityInfo city,
                                                 Predicate<BlockPos> isSuitable,
                                                 BiPredicate<Level, BlockPos> isValidStandingPosition,
                                                 Set<String> requiredBuildings,
                                                 Set<String> requiredParts,
                                                 ResolvedAssetFilter assetFilter,
                                                 int constraintHash) {
        java.util.ArrayList<ChunkCoord> candidates = buildCityCandidates(provider, city, requiredBuildings, requiredParts, assetFilter);
        if (candidates.isEmpty()) {
            return null;
        }
        AsyncBuildingInfoPlanner.preSchedulePriorityBatch(provider, candidates);
        java.util.ArrayList<SpawnCandidate> deferredCandidates = new java.util.ArrayList<>(DEFERRED_CHUNK_VALIDATION_LIMIT);
        for (ChunkCoord coord : candidates) {
            BuildingInfo info = getBuildingInfoNow(provider, coord);
            if (info == null) {
                continue;
            }
            if (!passesBuildingInfo(info, true, assetFilter)) {
                continue;
            }
            SpawnCandidate candidate = findInBuildingChunk(info, coord.chunkX(), coord.chunkZ(), isSuitable, requiredParts, assetFilter);
            if (candidate == null) {
                continue;
            }
            BlockPos found = tryValidateSpawnCandidate(world, candidate, isValidStandingPosition, false);
            if (found != null) {
                return found;
            }
            queueDeferredCandidate(deferredCandidates, candidate);
        }
        return drainDeferredCandidates(world, deferredCandidates, isValidStandingPosition, 0);
    }

    private static CityInfo getCachedCityInfo(ResourceKey<Level> dimension, ChunkCoord coord) {
        CityChunkKey chunkKey = new CityChunkKey(dimension, coord.chunkX(), coord.chunkZ());
        CityKey cityKey = CITY_CHUNK_INDEX.get(chunkKey);
        if (cityKey == null) {
            return null;
        }
        CityInfo cityInfo = CITY_CACHE.get(cityKey);
        if (cityInfo == null) {
            CITY_CHUNK_INDEX.remove(chunkKey, cityKey);
        }
        return cityInfo;
    }

    private static CityInfo scanCity(IDimensionInfo provider,
                                     ResourceKey<Level> dimension,
                                     ChunkCoord seed,
                                     int maxCityChunks,
                                     int maxChecks) {
        java.util.ArrayDeque<ChunkCoord> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<Long> visited = new java.util.HashSet<>();
        java.util.ArrayList<Long> cityChunks = new java.util.ArrayList<>();
        java.util.HashMap<String, Integer> buildingCounts = new java.util.HashMap<>();
        java.util.BitSet buildingMask = new java.util.BitSet(256);
        int cityLevelSum = 0;
        int cityLevelCount = 0;
        boolean hasStreets = false;
        boolean hasRoofs = false;

        queue.add(seed);
        while (!queue.isEmpty() && visited.size() < maxChecks && cityChunks.size() < maxCityChunks) {
            ChunkCoord coord = queue.poll();
            long packed = packChunkKey(coord.chunkX(), coord.chunkZ());
            if (!visited.add(packed)) {
                continue;
            }
            if (!ChunkRoleProbe.isCity(provider, coord.dimension(), coord.chunkX(), coord.chunkZ())) {
                continue;
            }
            cityChunks.add(packed);
            SPAWN_PROGRESS_CHUNKS_SCANNED.incrementAndGet();
            logSpawnProgress(dimension);
            LostChunkCharacteristics characteristics = null;
            try {
                characteristics = BuildingInfo.getChunkCharacteristics(coord, provider);
            } catch (Throwable ignored) {
            }
            if (characteristics != null) {
                cityLevelSum += characteristics.cityLevel;
                cityLevelCount++;
                if (!characteristics.couldHaveBuilding) {
                    hasStreets = true;
                } else {
                    SPAWN_PROGRESS_CITY_CHUNKS_WITH_BUILDING_CANDIDATE.incrementAndGet();
                }
                if (characteristics.buildingTypeId != null) {
                    String id = characteristics.buildingTypeId.toString();
                    buildingCounts.merge(id, 1, Integer::sum);
                    int bit = mix(id.hashCode()) & 0xff;
                    buildingMask.set(bit);
                    hasRoofs = true;
                    SPAWN_PROGRESS_CITY_CHUNKS_WITH_BUILDING_ID.incrementAndGet();
                }
            }
            queue.add(new ChunkCoord(coord.dimension(), coord.chunkX() + 1, coord.chunkZ()));
            queue.add(new ChunkCoord(coord.dimension(), coord.chunkX() - 1, coord.chunkZ()));
            queue.add(new ChunkCoord(coord.dimension(), coord.chunkX(), coord.chunkZ() + 1));
            queue.add(new ChunkCoord(coord.dimension(), coord.chunkX(), coord.chunkZ() - 1));
        }
        if (cityChunks.isEmpty()) {
            return null;
        }
        int avgCityLevel = cityLevelCount > 0 ? (cityLevelSum / cityLevelCount) : 0;
        CityKey cityKey = new CityKey(dimension, seed.chunkX(), seed.chunkZ());
        CityInfo info = new CityInfo(hasRoofs, hasStreets, avgCityLevel, buildingCounts, buildingMask, cityChunks);
        SPAWN_PROGRESS_SAMPLE_BUILDINGS = formatIdSample(buildingCounts.keySet(), 6);

        if (CITY_CACHE.size() >= CITY_CACHE_MAX) {
            CITY_CACHE.clear();
            CITY_CHUNK_INDEX.clear();
        }
        CityInfo existing = CITY_CACHE.putIfAbsent(cityKey, info);
        CityInfo chosen = existing != null ? existing : info;
        if (existing == null) {
            SPAWN_PROGRESS_CITIES_FOUND.incrementAndGet();
            logSpawnProgress(dimension);
        }
        for (long chunkKey : cityChunks) {
            int cx = (int) (chunkKey >> 32);
            int cz = (int) chunkKey;
            CITY_CHUNK_INDEX.putIfAbsent(new CityChunkKey(dimension, cx, cz), cityKey);
        }
        return chosen;
    }

    private static java.util.ArrayList<ChunkCoord> buildCityCandidates(IDimensionInfo provider,
                                                                       CityInfo city,
                                                                       Set<String> requiredBuildings,
                                                                       Set<String> requiredParts,
                                                                       ResolvedAssetFilter assetFilter) {
        java.util.ArrayList<ChunkCoord> candidates = new java.util.ArrayList<>();
        for (long packed : city.cityChunks) {
            int cx = (int) (packed >> 32);
            int cz = (int) packed;
            ChunkCoord coord = new ChunkCoord(provider.getType(), cx, cz);
            PrefilterDecision decision = prefilter(coord, provider, true, requiredBuildings, requiredParts, assetFilter);
            if (decision != PrefilterDecision.FAIL) {
                candidates.add(coord);
            }
        }
        return candidates;
    }

    @SuppressWarnings("unused")
    private static BuildingInfo getBuildingInfoReadyOrSchedule(IDimensionInfo provider, ChunkCoord coord) {
        try {
            Object ready = AsyncBuildingInfoPlanner.getIfReady(coord);
            if (ready instanceof BuildingInfo info) {
                return info;
            }
            if (AsyncBuildingInfoPlanner.isSpawnPrefetchSaturated()) {
                applyPendingBackpressure();
                ready = AsyncBuildingInfoPlanner.getIfReady(coord);
                if (ready instanceof BuildingInfo info) {
                    return info;
                }
            }
            AsyncBuildingInfoPlanner.preSchedulePriority(provider, coord);
            if (AsyncBuildingInfoPlanner.isSpawnPrefetchSaturated()) {
                applyPendingBackpressure();
                ready = AsyncBuildingInfoPlanner.getIfReady(coord);
                if (ready instanceof BuildingInfo info) {
                    return info;
                }
            }
            if (!ALLOW_SYNC_BUILDINGINFO_FALLBACK) {
                return null;
            }
            return BuildingInfo.getBuildingInfo(coord, provider);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Tries the async BuildingInfo cache first. If not ready, schedules async pre-warm and
     * immediately falls back to synchronous computation. Never sleeps the calling thread.
     */
    private static BuildingInfo getBuildingInfoNow(IDimensionInfo provider, ChunkCoord coord) {
        try {
            Object ready = AsyncBuildingInfoPlanner.getIfReady(coord);
            if (ready instanceof BuildingInfo info) {
                return info;
            }
            AsyncBuildingInfoPlanner.preSchedulePriority(provider, coord);
            if (!ALLOW_SYNC_BUILDINGINFO_FALLBACK) {
                return null;
            }
            return BuildingInfo.getBuildingInfo(coord, provider);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean passesBuildingInfo(BuildingInfo info,
                                              boolean requiresBuilding,
                                              ResolvedAssetFilter assetFilter) {
        if (info == null) {
            return false;
        }
        if (!info.isCity || !info.hasBuilding) {
            return !requiresBuilding && (assetFilter == null || assetFilter.isUnconstrained());
        }
        if (assetFilter != null && assetFilter.hasBuildingConstraint()) {
            try {
                String id = info.getBuildingId() != null ? info.getBuildingId().toString() : "";
                if (!assetFilter.buildingMatches(id)) {
                    return false;
                }
            } catch (Throwable ignored) {
                return false;
            }
        }
        return true;
    }

    private static SpawnCandidate findInBuilding(BuildingInfo info,
                                                 int x,
                                                 int z,
                                                 Predicate<BlockPos> isSuitable,
                                                 Set<String> requiredParts,
                                                 ResolvedAssetFilter assetFilter) {
        int bottom = info.getBuildingBottomHeight();
        if (bottom == Integer.MIN_VALUE) {
            return null;
        }
        int floors = Math.max(0, info.getNumFloors());
        int cellars = Math.max(0, info.getNumCellars());
        int floorHeight = 6;
        int start = bottom - (cellars * floorHeight) + 1;
        int end = bottom + (floors * floorHeight) + 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = start; y <= end; y += floorHeight) {
            if (!requiredParts.isEmpty() && assetFilter != null && assetFilter.hasPartConstraint()) {
                BuildingPart part = info.getFloorAtY(bottom, y);
                String partId = part != null ? part.getId().toString() : "";
                if (!assetFilter.partMatches(partId)) {
                    continue;
                }
            }
            pos.set(x, y, z);
            if (!isSuitable.test(pos)) {
                continue;
            }
            return new SpawnCandidate(pos.immutable(), x >> 4, z >> 4);
        }
        return null;
    }

    private static SpawnCandidate findInBuildingChunk(BuildingInfo info,
                                                      int chunkX,
                                                      int chunkZ,
                                                      Predicate<BlockPos> isSuitable,
                                                      Set<String> requiredParts,
                                                      ResolvedAssetFilter assetFilter) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        for (int ox : BUILDING_SAMPLE_OFFSETS) {
            for (int oz : BUILDING_SAMPLE_OFFSETS) {
                SpawnCandidate found = findInBuilding(info, baseX + ox, baseZ + oz, isSuitable, requiredParts, assetFilter);
                if (found != null) {
                    return found;
                }
            }
        }

        for (int x = 1; x < 15; x++) {
            for (int z = 1; z < 15; z++) {
                SpawnCandidate found = findInBuilding(info, baseX + x, baseZ + z, isSuitable, requiredParts, assetFilter);
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    private static BlockPos tryValidateSpawnCandidate(Level world,
                                                      SpawnCandidate candidate,
                                                      BiPredicate<Level, BlockPos> isValidStandingPosition,
                                                      boolean forceChunkLoad) {
        if (candidate == null) {
            return null;
        }
        int chunkX = candidate.chunkX();
        int chunkZ = candidate.chunkZ();

        if (world instanceof ServerLevel serverLevel) {
            if (!forceChunkLoad && serverLevel.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
                return null;
            }
            if (forceChunkLoad) {
                serverLevel.getChunk(chunkX, chunkZ);
            }
        } else {
            if (!forceChunkLoad && !world.hasChunk(chunkX, chunkZ)) {
                return null;
            }
            if (forceChunkLoad) {
                world.getChunk(chunkX, chunkZ);
            }
        }

        return isValidStandingPosition.test(world, candidate.pos()) ? candidate.pos().above() : null;
    }

    private static void queueDeferredCandidate(java.util.ArrayList<SpawnCandidate> deferredCandidates, SpawnCandidate candidate) {
        if (candidate == null || deferredCandidates == null) {
            return;
        }
        if (deferredCandidates.size() >= DEFERRED_CHUNK_VALIDATION_LIMIT) {
            return;
        }
        deferredCandidates.add(candidate);
    }

    private static BlockPos drainDeferredCandidates(Level world,
                                                    java.util.ArrayList<SpawnCandidate> deferredCandidates,
                                                    BiPredicate<Level, BlockPos> isValidStandingPosition,
                                                    int constraintHash) {
        if (deferredCandidates == null || deferredCandidates.isEmpty()) {
            return null;
        }
        for (SpawnCandidate candidate : deferredCandidates) {
            // Never force-load chunks during spawn search. Forcing chunk gen on the server
            // thread triggers LostCityFeature.generate() → withChunkStripeLock, but
            // ForkJoin's work-stealing can leave a gen worker holding the stripe lock while
            // parked in awaitWork, preventing any other worker from acquiring it → deadlock.
            // Use forceChunkLoad=false; if the chunk is already resident, validate normally.
            BlockPos found = tryValidateSpawnCandidate(world, candidate, isValidStandingPosition, false);
            if (found != null) {
                deferredCandidates.clear();
                return found;
            }
            recordSkip(world.dimension(), candidate.chunkX(), candidate.chunkZ(), constraintHash);
            SPAWN_PROGRESS_CHUNKS_REJECTED.incrementAndGet();
            logSpawnProgress(world.dimension());
        }
        // Do not accept unloaded candidates blindly. A predicted BuildingInfo candidate can still
        // drift versus final generated terrain/buildings and place spawn into an invalid location.
        deferredCandidates.clear();
        return null;
    }

    private enum PrefilterDecision {
        PASS,
        FAIL,
        NEED_INFO
    }

    private static PrefilterDecision prefilter(ChunkCoord coord,
                                               IDimensionInfo provider,
                                               boolean requiresBuilding,
                                               Set<String> requiredBuildings,
                                               Set<String> requiredParts,
                                               ResolvedAssetFilter assetFilter) {
        if (!requiresBuilding && requiredBuildings.isEmpty() && requiredParts.isEmpty()) {
        return PrefilterDecision.PASS;
    }
        try {
            LostChunkCharacteristics characteristics = ChunkRoleProbe.getCharacteristics(provider, coord.dimension(), coord.chunkX(), coord.chunkZ());
            if (characteristics == null) {
                return PrefilterDecision.NEED_INFO;
            }
            if (!characteristics.isCity || !characteristics.couldHaveBuilding) {
                return PrefilterDecision.FAIL;
            }
            if (assetFilter != null && assetFilter.hasBuildingConstraint()) {
                if (characteristics.buildingTypeId == null) {
                    return PrefilterDecision.NEED_INFO;
                }
                String buildingId = characteristics.buildingTypeId.toString();
                if (!assetFilter.buildingMatches(buildingId)) {
                    return PrefilterDecision.FAIL;
                }
            }
            if (assetFilter != null && assetFilter.hasPartConstraint()) {
                return PrefilterDecision.NEED_INFO;
            }
            return PrefilterDecision.PASS;
        } catch (Throwable ignored) {
            return PrefilterDecision.NEED_INFO;
        }
    }

    private static boolean shouldSkip(ResourceKey<Level> dimension, int chunkX, int chunkZ, int constraintHash) {
        if (SKIP_TTL_MS <= 0L) {
            return false;
        }
        SpawnChunkKey key = new SpawnChunkKey(dimension, chunkX, chunkZ, constraintHash);
        Long ts = SKIPPED.get(key);
        if (ts == null) {
            return false;
        }
        if ((System.currentTimeMillis() - ts) > SKIP_TTL_MS) {
            SKIPPED.remove(key, ts);
            return false;
        }
        return true;
    }

    private static void recordSkip(ResourceKey<Level> dimension, int chunkX, int chunkZ, int constraintHash) {
        if (SKIP_TTL_MS <= 0L) {
            return;
        }
        if (SKIPPED.size() >= MAX_SKIP_CACHE) {
            SKIPPED.clear();
        }
        SKIPPED.put(new SpawnChunkKey(dimension, chunkX, chunkZ, constraintHash), System.currentTimeMillis());
    }

    private static boolean shouldSkipRegion(ResourceKey<Level> dimension, int chunkX, int chunkZ, int constraintHash) {
        if (REGION_SKIP_TTL_MS <= 0L) {
            return false;
        }
        if (REGION_BLOOM_TTL_MS > 0L && bloomSaysSkip(dimension, chunkX, chunkZ, constraintHash)) {
            return true;
        }
        SpawnRegionKey key = new SpawnRegionKey(dimension, regionCoord(chunkX), regionCoord(chunkZ), constraintHash);
        Long ts = REGION_SKIPPED.get(key);
        if (ts == null) {
            return false;
        }
        if ((System.currentTimeMillis() - ts) > REGION_SKIP_TTL_MS) {
            REGION_SKIPPED.remove(key, ts);
            return false;
        }
        return true;
    }

    private static void recordSkipRegion(ResourceKey<Level> dimension, int regionX, int regionZ, int constraintHash) {
        if (REGION_SKIP_TTL_MS <= 0L) {
            return;
        }
        if (REGION_SKIPPED.size() >= MAX_SKIP_CACHE) {
            REGION_SKIPPED.clear();
        }
        REGION_SKIPPED.put(new SpawnRegionKey(dimension, regionX, regionZ, constraintHash), System.currentTimeMillis());
        recordBloomInvalid(dimension, regionX, regionZ, constraintHash);
    }

    private static RegionScanCache getRegionScan(ResourceKey<Level> dimension,
                                                 IDimensionInfo provider,
                                                 int chunkX,
                                                 int chunkZ,
                                                 int constraintHash,
                                                 boolean requiresBuilding,
                                                 Set<String> requiredBuildings,
                                                 Set<String> requiredParts,
                                                 ResolvedAssetFilter assetFilter) {
        int regionX = regionCoord(chunkX);
        int regionZ = regionCoord(chunkZ);
        SpawnRegionKey key = new SpawnRegionKey(dimension, regionX, regionZ, constraintHash);
        RegionScanCache cached = REGION_SCAN.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.createdMs) <= REGION_SCAN_TTL_MS) {
            return cached;
        }

        RegionScanCache built = buildRegionScan(provider, dimension, regionX, regionZ, constraintHash,
            requiresBuilding, requiredBuildings, requiredParts, assetFilter, now);
        if (built == null) {
            return null;
        }
        REGION_SCAN.put(key, built);
        return built;
    }

    private static RegionScanCache buildRegionScan(IDimensionInfo provider,
                                                   ResourceKey<Level> dimension,
                                                   int regionX,
                                                   int regionZ,
                                                   int constraintHash,
                                                   boolean requiresBuilding,
                                                   Set<String> requiredBuildings,
                                                   Set<String> requiredParts,
                                                   ResolvedAssetFilter assetFilter,
                                                   long now) {
        BitSet candidates = new BitSet(REGION_SIZE_CHUNKS * REGION_SIZE_CHUNKS);
        BitSet needsInfo = new BitSet(REGION_SIZE_CHUNKS * REGION_SIZE_CHUNKS);
        java.util.ArrayList<ChunkCoord> prefetch = new java.util.ArrayList<>();
        boolean anyCandidate = false;
        int startX = regionX * REGION_SIZE_CHUNKS;
        int startZ = regionZ * REGION_SIZE_CHUNKS;

        for (int dx = 0; dx < REGION_SIZE_CHUNKS; dx++) {
            for (int dz = 0; dz < REGION_SIZE_CHUNKS; dz++) {
                int cx = startX + dx;
                int cz = startZ + dz;
                if (shouldSkip(dimension, cx, cz, constraintHash)) {
                    continue;
                }
                ChunkCoord coord = new ChunkCoord(provider.getType(), cx, cz);
                PrefilterDecision decision = prefilter(coord, provider, requiresBuilding, requiredBuildings, requiredParts, assetFilter);
                if (decision == PrefilterDecision.FAIL) {
                    continue;
                }
                int idx = dx * REGION_SIZE_CHUNKS + dz;
                candidates.set(idx);
                anyCandidate = true;
                if (decision == PrefilterDecision.NEED_INFO) {
                    needsInfo.set(idx);
                    if (prefetch.size() < REGION_PREFETCH_LIMIT) {
                        prefetch.add(coord);
                    }
                }
            }
        }

        if (!anyCandidate) {
            recordSkipRegion(dimension, regionX, regionZ, constraintHash);
            return null;
        }

        if (!prefetch.isEmpty()) {
            AsyncBuildingInfoPlanner.preSchedulePriorityBatch(provider, prefetch);
        }
        return new RegionScanCache(regionX, regionZ, now, candidates, needsInfo);
    }

    private static void maybePrefetchRegion(ResourceKey<Level> dimension,
                                            IDimensionInfo provider,
                                            int chunkX,
                                            int chunkZ,
                                            int constraintHash,
                                            boolean requiresBuilding,
                                            Set<String> requiredBuildings,
                                            Set<String> requiredParts,
                                            ResolvedAssetFilter assetFilter) {
        if (REGION_PREFETCH_LIMIT <= 0) {
            return;
        }
        int regionX = regionCoord(chunkX);
        int regionZ = regionCoord(chunkZ);
        SpawnRegionKey key = new SpawnRegionKey(dimension, regionX, regionZ, constraintHash);
        long now = System.currentTimeMillis();
        Long last = REGION_PREFETCH.get(key);
        if (last != null && (now - last) < REGION_PREFETCH_TTL_MS) {
            return;
        }
        REGION_PREFETCH.put(key, now);

        buildRegionScan(provider, dimension, regionX, regionZ, constraintHash,
            requiresBuilding, requiredBuildings, requiredParts, assetFilter, now);
    }

    @SuppressWarnings("unused")
    private static boolean shouldPending(ResourceKey<Level> dimension, int chunkX, int chunkZ, int constraintHash) {
        if (PENDING_TTL_MS <= 0L) {
            return false;
        }
        SpawnChunkKey key = new SpawnChunkKey(dimension, chunkX, chunkZ, constraintHash);
        Long ts = PENDING.get(key);
        if (ts == null) {
            return false;
        }
        if ((System.currentTimeMillis() - ts) > PENDING_TTL_MS) {
            PENDING.remove(key, ts);
            return false;
        }
        return true;
    }

    private static void recordPending(ResourceKey<Level> dimension, int chunkX, int chunkZ, int constraintHash) {
        if (PENDING_TTL_MS <= 0L) {
            return;
        }
        if (PENDING.size() >= MAX_SKIP_CACHE) {
            PENDING.clear();
        }
        PENDING.put(new SpawnChunkKey(dimension, chunkX, chunkZ, constraintHash), System.currentTimeMillis());
    }

    @SuppressWarnings("unused")
    private static void handleUnavailableBuildingInfo(ResourceKey<Level> dimension, int chunkX, int chunkZ, int constraintHash) {
        if (AsyncBuildingInfoPlanner.isSpawnPrefetchSaturated()) {
            applyPendingBackpressure();
            return;
        }
        recordPending(dimension, chunkX, chunkZ, constraintHash);
    }

    private static void applyPendingBackpressure() {
        SPAWN_PROGRESS_PENDING_BACKPRESSURE.incrementAndGet();
        try {
            Thread.sleep(PENDING_BACKPRESSURE_SLEEP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int computeConstraintHash(LostCityProfile profile,
                                             Set<String> requiredBuildings,
                                             Set<String> requiredParts) {
        int hash = 17;
        hash = 31 * hash + (profile.FORCE_SPAWN_IN_BUILDING ? 1 : 0);
        hash = 31 * hash + requiredBuildings.hashCode();
        hash = 31 * hash + requiredParts.hashCode();
        hash = 31 * hash + profile.SPAWN_CHECK_RADIUS;
        hash = 31 * hash + profile.SPAWN_RADIUS_INCREASE;
        return hash;
    }

    private static int computeSpawnAttemptBudget(LostCityProfile profile,
                                                 boolean requiresBuilding,
                                                 Set<String> requiredBuildings,
                                                 Set<String> requiredParts) {
        int baseAttempts = Math.max(1, profile.SPAWN_CHECK_ATTEMPTS);
        if (!requiresBuilding && (requiredBuildings == null || requiredBuildings.isEmpty()) && (requiredParts == null || requiredParts.isEmpty())) {
            return baseAttempts;
        }

        int radiusChunks = Math.max(1, Math.max(16, profile.SPAWN_CHECK_RADIUS) >> 4);
        int initialCoverage = (radiusChunks * 2) + 1;
        initialCoverage *= initialCoverage;
        int constrainedFloor = Math.max(4096, initialCoverage * 8);
        if (requiredBuildings != null && !requiredBuildings.isEmpty()) {
            constrainedFloor = Math.max(constrainedFloor, 8192);
        }
        return Math.max(baseAttempts, constrainedFloor);
    }

    private static long packChunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static int regionCoord(int chunkCoord) {
        if (chunkCoord >= 0) {
            return chunkCoord / REGION_SIZE_CHUNKS;
        }
        return -(((-chunkCoord - 1) / REGION_SIZE_CHUNKS) + 1);
    }

    private static boolean bloomSaysSkip(ResourceKey<Level> dimension, int chunkX, int chunkZ, int constraintHash) {
        int regionX = regionCoord(chunkX);
        int regionZ = regionCoord(chunkZ);
        SpawnRegionBaseKey key = new SpawnRegionBaseKey(dimension, regionX, regionZ);
        RegionBloom bloom = REGION_BLOOM.get(key);
        if (bloom == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if ((now - bloom.updatedMs) > REGION_BLOOM_TTL_MS) {
            REGION_BLOOM.remove(key, bloom);
            return false;
        }
        return bloom.mightContain(constraintHash);
    }

    private static void recordBloomInvalid(ResourceKey<Level> dimension, int regionX, int regionZ, int constraintHash) {
        if (REGION_BLOOM_TTL_MS <= 0L) {
            return;
        }
        SpawnRegionBaseKey key = new SpawnRegionBaseKey(dimension, regionX, regionZ);
        REGION_BLOOM.compute(key, (k, existing) -> {
            RegionBloom bloom = existing != null ? existing : new RegionBloom();
            bloom.put(constraintHash);
            bloom.updatedMs = System.currentTimeMillis();
            return bloom;
        });
    }

    private record SpawnChunkKey(ResourceKey<Level> dimension, int chunkX, int chunkZ, int constraintHash) {
    }

    private record SpawnRegionKey(ResourceKey<Level> dimension, int regionX, int regionZ, int constraintHash) {
    }

    private record SpawnRegionBaseKey(ResourceKey<Level> dimension, int regionX, int regionZ) {
    }

    private record SpawnCandidate(BlockPos pos, int chunkX, int chunkZ) {
    }

    private record CityChunkKey(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
    }

    private record CityKey(ResourceKey<Level> dimension, int anchorX, int anchorZ) {
    }

    private record CitySignatureDecisionKey(int constraintHash, int citySignatureHash) {
    }

    private static final class CityInfo {
        private final java.util.BitSet buildingMask;
        private final java.util.List<Long> cityChunks;
        private final int signatureHash;
        private final java.util.Set<String> sampledBuildingIds = new java.util.HashSet<>();
        private volatile boolean sampledComplete = false;
        private volatile long sampleNextAttemptMs = 0L;
        private volatile long sampleFirstAttemptMs = 0L;
        private final ConcurrentHashMap<Integer, Boolean> triedDecisions = new ConcurrentHashMap<>();

        private CityInfo(boolean hasRoofs,
                         boolean hasStreets,
                         int avgCityLevel,
                         java.util.Map<String, Integer> buildingCounts,
                         java.util.BitSet buildingMask,
                         java.util.List<Long> cityChunks) {
            this.buildingMask = buildingMask;
            this.cityChunks = cityChunks;
            int sig = 17;
            sig = (31 * sig) + buildingMask.hashCode();
            sig = (31 * sig) + avgCityLevel;
            sig = (31 * sig) + (hasStreets ? 1 : 0);
            sig = (31 * sig) + (hasRoofs ? 1 : 0);
            this.signatureHash = sig;
        }

        private boolean quickReject(Set<String> requiredBuildings, Set<String> requiredParts, int constraintHash) {
            return signatureReject(requiredBuildings, requiredParts, constraintHash);
        }

        private boolean needsBuildingIdSamples() {
            return !sampledComplete && sampledBuildingIds.isEmpty();
        }

        private boolean ensureBuildingIdSamples(IDimensionInfo provider,
                                                Set<String> requiredBuildings,
                                                Set<String> requiredParts) {
            long now = System.currentTimeMillis();
            if (now < sampleNextAttemptMs) {
                return false;
            }
            if (sampleFirstAttemptMs == 0L) {
                sampleFirstAttemptMs = now;
            }
            int sampleCount = Math.min(CITY_SAMPLE_LIMIT, cityChunks.size());
            if (sampleCount <= 0) {
                sampledComplete = true;
                return true;
            }
            java.util.ArrayList<ChunkCoord> pending = new java.util.ArrayList<>();
            int stride = Math.max(1, cityChunks.size() / sampleCount);
            for (int i = 0; i < cityChunks.size() && pending.size() < sampleCount; i += stride) {
                long packed = cityChunks.get(i);
                int cx = (int) (packed >> 32);
                int cz = (int) packed;
                ChunkCoord coord = new ChunkCoord(provider.getType(), cx, cz);
                Object ready = AsyncBuildingInfoPlanner.getIfReady(coord);
                if (ready instanceof BuildingInfo info) {
                    try {
                        String id = info.getBuildingId() != null ? info.getBuildingId().toString() : "";
                        if (!id.isBlank()) {
                            sampledBuildingIds.add(id);
                        }
                    } catch (Throwable ignored) {
                    }
                    continue;
                }
                pending.add(coord);
            }
            if (!pending.isEmpty()) {
                AsyncBuildingInfoPlanner.preSchedulePriorityBatch(provider, pending);
                sampleNextAttemptMs = now + CITY_SAMPLE_RETRY_MS;
                return false;
            }
            sampledComplete = true;
            return true;
        }

        private boolean signatureReject(Set<String> requiredBuildings, Set<String> requiredParts, int constraintHash) {
            CitySignatureDecisionKey key = new CitySignatureDecisionKey(constraintHash, signatureHash);
            Boolean cached = CITY_SIGNATURE_DECISIONS.get(key);
            if (cached != null) {
                return !cached;
            }
            if (!requiredBuildings.isEmpty() && !buildingMask.isEmpty()) {
                boolean any = false;
                for (String required : requiredBuildings) {
                    int bit = mix(required.hashCode()) & 0xff;
                    if (buildingMask.get(bit)) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    CITY_SIGNATURE_DECISIONS.put(key, Boolean.FALSE);
                    SPAWN_PROGRESS_CITIES_SIGNATURE_REJECTED.incrementAndGet();
                    return true;
                }
            }
            CITY_SIGNATURE_DECISIONS.put(key, Boolean.TRUE);
            return false;
        }

        private boolean wasTried(int constraintHash) {
            Boolean cached = triedDecisions.get(constraintHash);
            return cached != null && !cached;
        }

        private void recordDecision(int constraintHash, boolean ok) {
            triedDecisions.put(constraintHash, ok);
        }
    }

    private static final class RegionScanCache {
        private final int regionX;
        private final int regionZ;
        private final long createdMs;
        private final BitSet candidates;
        private final BitSet needsInfo;

        private RegionScanCache(int regionX, int regionZ, long createdMs, BitSet candidates, BitSet needsInfo) {
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.createdMs = createdMs;
            this.candidates = candidates;
            this.needsInfo = needsInfo;
        }

        private int indexFor(int chunkX, int chunkZ) {
            int dx = chunkX - (regionX * REGION_SIZE_CHUNKS);
            int dz = chunkZ - (regionZ * REGION_SIZE_CHUNKS);
            if (dx < 0 || dz < 0 || dx >= REGION_SIZE_CHUNKS || dz >= REGION_SIZE_CHUNKS) {
                return 0;
            }
            return dx * REGION_SIZE_CHUNKS + dz;
        }
    }

    private static final class RegionBloom {
        private static final int BIT_COUNT = 256;
        private static final int LONGS = BIT_COUNT / Long.SIZE;
        private final long[] bits = new long[LONGS];
        private volatile long updatedMs = System.currentTimeMillis();

        private void put(int value) {
            int h1 = mix(value);
            int h2 = mix(h1 ^ 0x9e3779b9);
            setBit(h1);
            setBit(h2);
        }

        private boolean mightContain(int value) {
            int h1 = mix(value);
            int h2 = mix(h1 ^ 0x9e3779b9);
            return getBit(h1) && getBit(h2);
        }

        private void setBit(int hash) {
            int idx = (hash & 0x7fffffff) % BIT_COUNT;
            bits[idx / 64] |= (1L << (idx % 64));
        }

        private boolean getBit(int hash) {
            int idx = (hash & 0x7fffffff) % BIT_COUNT;
            return (bits[idx / 64] & (1L << (idx % 64))) != 0L;
        }
    }

    private static int mix(int value) {
        int h = value;
        h ^= (h >>> 16);
        h *= 0x7feb352d;
        h ^= (h >>> 15);
        h *= 0x846ca68b;
        h ^= (h >>> 16);
        return h;
    }

    private static void logSpawnProgress(ResourceKey<Level> dimension) {
        long now = System.currentTimeMillis();
        long last = SPAWN_PROGRESS_LAST_LOG.get();
        if ((now - last) < SPAWN_PROGRESS_LOG_INTERVAL_MS) {
            return;
        }
        if (!SPAWN_PROGRESS_LAST_LOG.compareAndSet(last, now)) {
            return;
        }
        long chunks = SPAWN_PROGRESS_CHUNKS_SCANNED.get();
        long citiesFound = SPAWN_PROGRESS_CITIES_FOUND.get();
        long citiesFailed = SPAWN_PROGRESS_CITIES_FAILED.get();
        long chunksRejected = SPAWN_PROGRESS_CHUNKS_REJECTED.get();
        long citiesSignatureRejected = SPAWN_PROGRESS_CITIES_SIGNATURE_REJECTED.get();
        long cityChunksWithBuildingId = SPAWN_PROGRESS_CITY_CHUNKS_WITH_BUILDING_ID.get();
        long cityChunksWithBuildingCandidate = SPAWN_PROGRESS_CITY_CHUNKS_WITH_BUILDING_CANDIDATE.get();
        long pendingBackpressure = SPAWN_PROGRESS_PENDING_BACKPRESSURE.get();
        LC2H.LOGGER.debug(
            "[LC2H] Spawn search progress (dim={} stage={}): chunksScanned={}, chunksRejected={}, citiesFound={}, citiesFailed={}, citiesSignatureRejected={}, cityChunksWithBuildingId={}, cityChunksWithBuildingCandidate={}, rawBuildings={}, resolvedBuildings={}, requiredBuildings={}, rawParts={}, resolvedParts={}, requiredParts={}, pending={}, spawnPrefetchInflight={}, pendingBackpressure={}, sampleCityBuildings={}, sampleCityBuildingsFromInfo={}",
            dimension.location(),
            SPAWN_PROGRESS_STAGE,
            chunks,
            chunksRejected,
            citiesFound,
            citiesFailed,
            citiesSignatureRejected,
            cityChunksWithBuildingId,
            cityChunksWithBuildingCandidate,
            SPAWN_PROGRESS_RAW_BUILDINGS,
            SPAWN_PROGRESS_RESOLVED_BUILDINGS,
            SPAWN_PROGRESS_REQUIRED_BUILDINGS,
            SPAWN_PROGRESS_RAW_PARTS,
            SPAWN_PROGRESS_RESOLVED_PARTS,
            SPAWN_PROGRESS_REQUIRED_PARTS,
            PENDING.size(),
            AsyncBuildingInfoPlanner.getSpawnPrefetchCount(),
            pendingBackpressure,
            SPAWN_PROGRESS_SAMPLE_BUILDINGS,
            formatIdSampleFromCache(6)
        );
    }

    private static String buildSpawnFailureDetails(ResourceKey<Level> dimension,
                                                   int attempts,
                                                   int maxAttempts,
                                                   int radius) {
        return "dim=" + dimension.location()
            + ",stage=" + SPAWN_PROGRESS_STAGE
            + ",attempts=" + attempts + "/" + maxAttempts
            + ",radius=" + radius
            + ",chunksScanned=" + SPAWN_PROGRESS_CHUNKS_SCANNED.get()
            + ",chunksRejected=" + SPAWN_PROGRESS_CHUNKS_REJECTED.get()
            + ",citiesFound=" + SPAWN_PROGRESS_CITIES_FOUND.get()
            + ",citiesFailed=" + SPAWN_PROGRESS_CITIES_FAILED.get()
            + ",citiesSignatureRejected=" + SPAWN_PROGRESS_CITIES_SIGNATURE_REJECTED.get()
            + ",cityChunksWithBuildingId=" + SPAWN_PROGRESS_CITY_CHUNKS_WITH_BUILDING_ID.get()
            + ",cityChunksWithBuildingCandidate=" + SPAWN_PROGRESS_CITY_CHUNKS_WITH_BUILDING_CANDIDATE.get()
            + ",pending=" + PENDING.size()
            + ",spawnPrefetchInflight=" + AsyncBuildingInfoPlanner.getSpawnPrefetchCount()
            + ",pendingBackpressure=" + SPAWN_PROGRESS_PENDING_BACKPRESSURE.get()
            + ",skipCache=" + SKIPPED.size()
            + ",regionSkipCache=" + REGION_SKIPPED.size()
            + ",rawBuildings=" + SPAWN_PROGRESS_RAW_BUILDINGS
            + ",resolvedBuildings=" + SPAWN_PROGRESS_RESOLVED_BUILDINGS
            + ",requiredBuildings=" + SPAWN_PROGRESS_REQUIRED_BUILDINGS
            + ",rawParts=" + SPAWN_PROGRESS_RAW_PARTS
            + ",resolvedParts=" + SPAWN_PROGRESS_RESOLVED_PARTS
            + ",requiredParts=" + SPAWN_PROGRESS_REQUIRED_PARTS
            + ",sampleCityBuildings=" + SPAWN_PROGRESS_SAMPLE_BUILDINGS
            + ",sampleCityBuildingsFromInfo=" + formatIdSampleFromCache(8);
    }

    private static void resetSpawnProgress() {
        SPAWN_PROGRESS_LAST_LOG.set(0L);
        SPAWN_PROGRESS_CHUNKS_SCANNED.set(0L);
        SPAWN_PROGRESS_CITIES_FOUND.set(0L);
        SPAWN_PROGRESS_CITIES_FAILED.set(0L);
        SPAWN_PROGRESS_CHUNKS_REJECTED.set(0L);
        SPAWN_PROGRESS_CITIES_SIGNATURE_REJECTED.set(0L);
        SPAWN_PROGRESS_CITY_CHUNKS_WITH_BUILDING_ID.set(0L);
        SPAWN_PROGRESS_CITY_CHUNKS_WITH_BUILDING_CANDIDATE.set(0L);
        SPAWN_PROGRESS_PENDING_BACKPRESSURE.set(0L);
        SPAWN_PROGRESS_RAW_BUILDINGS = "[]";
        SPAWN_PROGRESS_RAW_PARTS = "[]";
        SPAWN_PROGRESS_RESOLVED_BUILDINGS = "[]";
        SPAWN_PROGRESS_RESOLVED_PARTS = "[]";
        SPAWN_PROGRESS_REQUIRED_BUILDINGS = "[]";
        SPAWN_PROGRESS_REQUIRED_PARTS = "[]";
        SPAWN_PROGRESS_SAMPLE_BUILDINGS = "[]";
        SPAWN_PROGRESS_STAGE = "init";
    }

    private static String formatIdSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return formatIdSample(values, 6);
    }

    private static String formatIdSample(java.util.Collection<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        int count = 0;
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (count > 0) {
                sb.append(',');
            }
            sb.append(value);
            count++;
            if (count >= limit) {
                break;
            }
        }
        if (values.size() > count) {
            sb.append(",...");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String formatIdSampleFromCache(int limit) {
        if (CITY_CACHE.isEmpty()) {
            return "[]";
        }
        java.util.HashSet<String> merged = new java.util.HashSet<>();
        for (CityInfo info : CITY_CACHE.values()) {
            merged.addAll(info.sampledBuildingIds);
            if (merged.size() >= limit) {
                break;
            }
        }
        return formatIdSample(merged, limit);
    }

    private static void writeSpawnDebugFile(IDimensionInfo provider,
                                            Set<String> rawBuildings,
                                            Set<String> rawParts,
                                            Set<String> resolvedBuildings,
                                            Set<String> resolvedParts) {
        if (provider == null || provider.getWorld() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!SPAWN_DEBUG_DUMP_ENABLED) {
            return;
        }
        long last = SPAWN_DEBUG_DUMP_LAST_MS.get();
        if ((now - last) < TimeUnit.SECONDS.toMillis(2)) {
            return;
        }
        if (!SPAWN_DEBUG_DUMP_LAST_MS.compareAndSet(last, now)) {
            return;
        }
        try {
            Path logDir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                .resolve("logs").resolve("lc2h").resolve("spawn-search");
            Files.createDirectories(logDir);
            String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(now));
            Path out = logDir.resolve("spawn-search-debug-" + ts + ".log");
            try (BufferedWriter writer = Files.newBufferedWriter(out)) {
                writer.write("spawn-search-debug");
                writer.newLine();
                net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimKey =
                    provider.dimension() != null ? provider.dimension() : provider.getType();
                writer.write("dimension=" + (dimKey != null ? dimKey.location() : "unknown"));
                writer.newLine();
                writer.write("forceSpawnBuildingsRaw=" + formatIdSample(rawBuildings, 2048));
                writer.newLine();
                writer.write("forceSpawnPartsRaw=" + formatIdSample(rawParts, 2048));
                writer.newLine();
                writer.write("forceSpawnBuildingsResolved=" + formatIdSample(resolvedBuildings, 2048));
                writer.newLine();
                writer.write("forceSpawnPartsResolved=" + formatIdSample(resolvedParts, 2048));
                writer.newLine();
                writer.newLine();
                writer.write("BUILDINGS:");
                writer.newLine();
                for (Building building : AssetRegistries.BUILDINGS.getIterable()) {
                    writer.write("id=" + building.getId() + " name=" + building.getName());
                    writer.newLine();
                }
                writer.newLine();
                writer.write("MULTI_BUILDINGS:");
                writer.newLine();
                for (MultiBuilding multi : AssetRegistries.MULTI_BUILDINGS.getIterable()) {
                    writer.write("id=" + multi.getId() + " name=" + multi.getName());
                    writer.newLine();
                }
                writer.newLine();
                writer.write("PARTS:");
                writer.newLine();
                for (BuildingPart part : AssetRegistries.PARTS.getIterable()) {
                    writer.write("id=" + part.getId() + " name=" + part.getName());
                    writer.newLine();
                }
            }
            LC2H.LOGGER.info("[LC2H] Wrote spawn search debug file to {}", out.toAbsolutePath());
        } catch (Throwable t) {
            LC2H.LOGGER.warn("[LC2H] Failed to write spawn search debug file", t);
        }
    }

    private static Set<String> resolveIds(Set<String> input, java.util.function.Function<String, String> resolver) {
        if (input == null || input.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        Set<String> resolved = new HashSet<>();
        for (String value : input) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String mapped = resolver.apply(value);
            resolved.add((mapped != null && !mapped.isBlank()) ? mapped : value);
        }
        return resolved;
    }

    private static Set<String> autoResolveMissing(Set<String> original,
                                                  Set<String> resolved,
                                                  boolean isBuilding) {
        if ((original == null || original.isEmpty()) && (resolved == null || resolved.isEmpty())) {
            return java.util.Collections.emptySet();
        }
        Set<String> autoResolved = new HashSet<>();
        if (resolved != null) {
            autoResolved.addAll(resolved);
        }
        java.util.Map<String, String> remapped = new java.util.HashMap<>();
        for (String value : original) {
            if (value == null || value.isBlank()) {
                continue;
            }
            boolean known = isBuilding ? SpawnAssetIndex.isBuildingKnown(value)
                || SpawnAssetIndex.isMultiBuildingKnown(value)
                : SpawnAssetIndex.isPartKnown(value);
            if (known) {
                autoResolved.add(value);
                continue;
            }
            String candidate = isBuilding ? suggestBestBuilding(value) : SpawnAssetIndex.suggestBestPartId(value);
            if (candidate != null && !candidate.isBlank()) {
                autoResolved.add(candidate);
                remapped.put(value, candidate);
            } else {
                // No remapping found; keep original
                autoResolved.add(value);
            }
        }
        if (!remapped.isEmpty()) {
            LC2H.LOGGER.info(
                "[LC2H] Auto-resolved spawn requirements: {}",
                formatIdSample(remapped.entrySet().stream()
                    .map(entry -> entry.getKey() + "->" + entry.getValue())
                    .toList(),
                    6)
            );
        }
        return autoResolved;
    }

    private static String suggestBestBuilding(String value) {
        String building = SpawnAssetIndex.suggestBestBuildingId(value);
        if (building != null && !building.isBlank()) {
            return building;
        }
        return SpawnAssetIndex.suggestBestMultiBuildingId(value);
    }

    private static void logMissingAssets(Set<String> requiredBuildings, Set<String> requiredParts) {
        if (requiredBuildings.isEmpty() && requiredParts.isEmpty()) {
            return;
        }
        java.util.Set<String> missingBuildings = new java.util.HashSet<>();
        java.util.Set<String> missingParts = new java.util.HashSet<>();
        for (String building : requiredBuildings) {
            if (!SpawnAssetIndex.isBuildingKnown(building)) {
                missingBuildings.add(building);
            }
        }
        for (String part : requiredParts) {
            if (!SpawnAssetIndex.isPartKnown(part)) {
                missingParts.add(part);
            }
        }
        if (missingBuildings.isEmpty() && missingParts.isEmpty()) {
            return;
        }
        java.util.List<String> buildingSuggestions = java.util.List.of();
        java.util.List<String> partSuggestions = java.util.List.of();
        if (!missingBuildings.isEmpty()) {
            String first = missingBuildings.iterator().next();
            String suggestion = suggestBestBuilding(first);
            buildingSuggestions = suggestion != null ? java.util.List.of(suggestion) : java.util.List.of();
        }
        if (!missingParts.isEmpty()) {
            String first = missingParts.iterator().next();
            String suggestion = SpawnAssetIndex.suggestBestPartId(first);
            partSuggestions = suggestion != null ? java.util.List.of(suggestion) : java.util.List.of();
        }
        LC2H.LOGGER.warn(
            "[LC2H] Spawn search requirements not found in pack: missingBuildings={}, missingParts={}, buildingSuggestions={}, partSuggestions={}",
            formatIdSample(missingBuildings, 6),
            formatIdSample(missingParts, 6),
            formatIdSample(buildingSuggestions, 6),
            formatIdSample(partSuggestions, 6)
        );
    }
}
