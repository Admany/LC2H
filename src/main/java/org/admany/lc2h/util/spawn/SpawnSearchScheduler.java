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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner;

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
    private static volatile String SPAWN_PROGRESS_REQUIRED_BUILDINGS = "";
    private static volatile String SPAWN_PROGRESS_REQUIRED_PARTS = "";
    private static volatile String SPAWN_PROGRESS_SAMPLE_BUILDINGS = "";
    private static final int CITY_SAMPLE_LIMIT = Math.max(1,
        Integer.getInteger("lc2h.spawnsearch.citySampleLimit", 8));
    private static final long CITY_SAMPLE_RETRY_MS = Math.max(50L,
        Long.getLong("lc2h.spawnsearch.citySampleRetryMs", 200L));
    private static final boolean SPAWN_DEBUG_DUMP_ENABLED = false;
    private static final AtomicLong SPAWN_DEBUG_DUMP_LAST_MS = new AtomicLong(0L);
    private static final long PENDING_TTL_MS = Math.max(250L,
        Long.getLong("lc2h.spawnsearch.pendingTtlMs", 2_000L));
    private static final ConcurrentHashMap<SpawnChunkKey, Long> PENDING = new ConcurrentHashMap<>();

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

        SpawnAssetIndex.ensureLoaded(provider.getWorld());

        String[] buildingArray = profile.FORCE_SPAWN_BUILDINGS != null ? profile.FORCE_SPAWN_BUILDINGS : new String[0];
        String[] partArray = profile.FORCE_SPAWN_PARTS != null ? profile.FORCE_SPAWN_PARTS : new String[0];
        Set<String> rawBuildings = new HashSet<>(Arrays.asList(buildingArray));
        Set<String> rawParts = new HashSet<>(Arrays.asList(partArray));
        Set<String> requiredBuildings = new HashSet<>(rawBuildings);
        Set<String> requiredParts = new HashSet<>(rawParts);
        Set<String> resolvedBuildings = resolveIds(requiredBuildings, value -> {
            String building = SpawnAssetIndex.resolveBuildingId(value);
            if (building != null && !building.equals(value)) {
                return building;
            }
            String multi = SpawnAssetIndex.resolveMultiBuildingId(value);
            return multi != null ? multi : value;
        });
        Set<String> resolvedParts = resolveIds(requiredParts, SpawnAssetIndex::resolvePartId);
        if (!resolvedBuildings.equals(requiredBuildings) || !resolvedParts.equals(requiredParts)) {
            LC2H.LOGGER.info(
                "[LC2H] Resolved spawn requirements: buildings={} -> {}, parts={} -> {}",
                formatIdSample(requiredBuildings, 6),
                formatIdSample(resolvedBuildings, 6),
                formatIdSample(requiredParts, 6),
                formatIdSample(resolvedParts, 6)
            );
        }
        requiredBuildings = autoResolveMissing(requiredBuildings, resolvedBuildings, true);
        requiredParts = autoResolveMissing(requiredParts, resolvedParts, false);
        writeSpawnDebugFile(provider, rawBuildings, rawParts, requiredBuildings, requiredParts);
        boolean requiresBuilding = profile.FORCE_SPAWN_IN_BUILDING || !requiredBuildings.isEmpty() || !requiredParts.isEmpty();
        if (profile.FORCE_SPAWN_IN_BUILDING && requiredBuildings.isEmpty() && requiredParts.isEmpty()) {
            LC2H.LOGGER.warn("[LC2H] No valid spawn building/part IDs resolved; falling back to any building");
            requiresBuilding = true;
        }
        int constraintHash = computeConstraintHash(profile, requiredBuildings, requiredParts);
        SPAWN_PROGRESS_REQUIRED_BUILDINGS = formatIdSet(requiredBuildings);
        SPAWN_PROGRESS_REQUIRED_PARTS = formatIdSet(requiredParts);
        logMissingAssets(requiredBuildings, requiredParts);

        if (requiresBuilding) {
            BlockPos startPos = world.getSharedSpawnPos();
            BlockPos cityResult = findSpawnInCity(world, provider, profile, isSuitable, isValidStandingPosition,
                requiredBuildings, requiredParts, constraintHash, startPos);
            if (cityResult != null) {
                return cityResult;
            }
        }

        Random rand = new Random(provider.getSeed());
        int radius = profile.SPAWN_CHECK_RADIUS;
        int attempts = 0;
        Set<Long> localChecked = new HashSet<>();

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

                if (shouldSkip(world.dimension(), chunkX, chunkZ, constraintHash)) {
                    continue;
                }
                if (shouldSkipRegion(world.dimension(), chunkX, chunkZ, constraintHash)) {
                    continue;
                }
                RegionScanCache scanCache = getRegionScan(world.dimension(), provider, chunkX, chunkZ, constraintHash,
                    requiresBuilding, requiredBuildings, requiredParts);
                if (scanCache != null) {
                    int regionIndex = scanCache.indexFor(chunkX, chunkZ);
                    if (!scanCache.candidates.get(regionIndex)) {
                        continue;
                    }
                    if (scanCache.needsInfo.get(regionIndex)) {
                        ChunkCoord coord = new ChunkCoord(provider.getType(), chunkX, chunkZ);
                        if (shouldPending(world.dimension(), chunkX, chunkZ, constraintHash)) {
                            continue;
                        }
                        Object ready = AsyncBuildingInfoPlanner.getIfReady(coord);
                        if (ready == null) {
                            AsyncBuildingInfoPlanner.preSchedulePriority(provider, coord);
                            recordPending(world.dimension(), chunkX, chunkZ, constraintHash);
                            continue;
                        }
                    }
                } else {
                    maybePrefetchRegion(world.dimension(), provider, chunkX, chunkZ, constraintHash,
                        requiresBuilding, requiredBuildings, requiredParts);
                }

                ChunkCoord coord = new ChunkCoord(provider.getType(), chunkX, chunkZ);
                PrefilterDecision decision = scanCache != null
                    ? (scanCache.needsInfo.get(scanCache.indexFor(chunkX, chunkZ)) ? PrefilterDecision.NEED_INFO : PrefilterDecision.PASS)
                    : prefilter(coord, provider, requiresBuilding, requiredBuildings, requiredParts);
                if (decision == PrefilterDecision.FAIL) {
                    recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                    SPAWN_PROGRESS_CHUNKS_REJECTED.incrementAndGet();
                    logSpawnProgress(world.dimension());
                    continue;
                }
                if (decision == PrefilterDecision.NEED_INFO) {
                    BuildingInfo info = getBuildingInfoSync(provider, coord);
                    if (info == null) {
                        continue;
                    }
                    if (!passesBuildingInfo(info, requiresBuilding, requiredBuildings, requiredParts)) {
                        recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                        SPAWN_PROGRESS_CHUNKS_REJECTED.incrementAndGet();
                        logSpawnProgress(world.dimension());
                        continue;
                    }
                    BlockPos found = findInBuilding(world, info, x, z, isSuitable, isValidStandingPosition, requiredParts);
                    if (found != null) {
                        return found;
                    }
                    recordSkip(world.dimension(), chunkX, chunkZ, constraintHash);
                    SPAWN_PROGRESS_CHUNKS_REJECTED.incrementAndGet();
                    logSpawnProgress(world.dimension());
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

            radius += profile.SPAWN_RADIUS_INCREASE;
            if (attempts > profile.SPAWN_CHECK_ATTEMPTS) {
                LC2H.LOGGER.error("Can't find a valid spawn position!");
                throw new RuntimeException("Can't find a valid spawn position!");
            }
        }
    }

    private static BlockPos findSpawnInCity(Level world,
                                            IDimensionInfo provider,
                                            LostCityProfile profile,
                                            Predicate<BlockPos> isSuitable,
                                            BiPredicate<Level, BlockPos> isValidStandingPosition,
                                            Set<String> requiredBuildings,
                                            Set<String> requiredParts,
                                            int constraintHash,
                                            BlockPos startPos) {
        int radiusBlocks = Math.max(16, profile.SPAWN_CHECK_RADIUS);
        int maxAttempts = Math.max(1, profile.SPAWN_CHECK_ATTEMPTS);
        int radiusIncrease = Math.max(1, profile.SPAWN_RADIUS_INCREASE);
        int maxCityChunks = Math.max(128, Integer.getInteger("lc2h.spawnsearch.cityMaxChunks", 1024));
        int maxCityChecks = Math.max(256, Integer.getInteger("lc2h.spawnsearch.cityMaxChecks", 4096));

        Set<Long> visitedCityChunks = new HashSet<>();
        int attempts = 0;

        int originChunkX = startPos.getX() >> 4;
        int originChunkZ = startPos.getZ() >> 4;
        int radiusChunks = Math.max(1, radiusBlocks >> 4);
        while (attempts <= maxAttempts) {
            for (int r = 0; r <= radiusChunks; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dz) != r) {
                            continue;
                        }
                        int chunkX = originChunkX + dx;
                        int chunkZ = originChunkZ + dz;
                        long packed = packChunkKey(chunkX, chunkZ);
                        if (!visitedCityChunks.add(packed)) {
                            continue;
                        }
                        attempts++;
                        SPAWN_PROGRESS_CHUNKS_SCANNED.incrementAndGet();
                        logSpawnProgress(world.dimension());
                        ChunkCoord coord = new ChunkCoord(provider.getType(), chunkX, chunkZ);
                        CityInfo cityInfo = getCachedCityInfo(world.dimension(), coord);
                        if (cityInfo == null && !BuildingInfo.isCity(coord, provider)) {
                            continue;
                        }
                        if (cityInfo == null) {
                            cityInfo = scanCity(provider, world.dimension(), coord, maxCityChunks, maxCityChecks);
                        }
                        if (cityInfo == null || cityInfo.cityChunks.isEmpty()) {
                            continue;
                        }
                        if (cityInfo.wasTried(constraintHash)) {
                            SPAWN_PROGRESS_CITIES_FAILED.incrementAndGet();
                            logSpawnProgress(world.dimension());
                            continue;
                        }
                        if (!requiredBuildings.isEmpty() && cityInfo.needsBuildingIdSamples()) {
                            cityInfo.ensureBuildingIdSamples(provider, requiredBuildings, requiredParts);
                        }
                        if (cityInfo.quickReject(requiredBuildings, requiredParts, constraintHash)) {
                            cityInfo.recordDecision(constraintHash, false);
                            SPAWN_PROGRESS_CITIES_FAILED.incrementAndGet();
                            logSpawnProgress(world.dimension());
                            continue;
                        }
                        for (long cityChunk : cityInfo.cityChunks) {
                            visitedCityChunks.add(cityChunk);
                        }
                        BlockPos found = searchCityCandidates(world, provider, cityInfo, isSuitable, isValidStandingPosition,
                            requiredBuildings, requiredParts, constraintHash);
                        if (found != null) {
                            cityInfo.recordDecision(constraintHash, true);
                            return found;
                        }
                        cityInfo.recordDecision(constraintHash, false);
                        SPAWN_PROGRESS_CITIES_FAILED.incrementAndGet();
                        logSpawnProgress(world.dimension());
                        for (long cityChunk : cityInfo.cityChunks) {
                            int cx = (int) (cityChunk >> 32);
                            int cz = (int) cityChunk;
                            recordSkip(world.dimension(), cx, cz, constraintHash);
                        }
                    }
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
                                                 int constraintHash) {
        java.util.ArrayList<ChunkCoord> candidates = buildCityCandidates(provider, city, requiredBuildings, requiredParts);
        if (candidates.isEmpty()) {
            return null;
        }
        int[] offsets = new int[] {4, 8, 12};
        for (ChunkCoord coord : candidates) {
            BuildingInfo info = getBuildingInfoSync(provider, coord);
            if (info == null) {
                continue;
            }
            if (!passesBuildingInfo(info, true, requiredBuildings, requiredParts)) {
                continue;
            }
            int baseX = coord.chunkX() << 4;
            int baseZ = coord.chunkZ() << 4;
            for (int dx : offsets) {
                for (int dz : offsets) {
                    int worldX = baseX + dx;
                    int worldZ = baseZ + dz;
                    BlockPos found = findInBuilding(world, info, worldX, worldZ, isSuitable, isValidStandingPosition, requiredParts);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
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
            if (!BuildingInfo.isCity(coord, provider)) {
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
                                                                       Set<String> requiredParts) {
        java.util.ArrayList<ChunkCoord> candidates = new java.util.ArrayList<>();
        for (long packed : city.cityChunks) {
            int cx = (int) (packed >> 32);
            int cz = (int) packed;
            ChunkCoord coord = new ChunkCoord(provider.getType(), cx, cz);
            PrefilterDecision decision = prefilter(coord, provider, true, requiredBuildings, requiredParts);
            if (decision != PrefilterDecision.FAIL) {
                candidates.add(coord);
            }
        }
        return candidates;
    }

    private static BuildingInfo getBuildingInfoSync(IDimensionInfo provider, ChunkCoord coord) {
        try {
            Object ready = AsyncBuildingInfoPlanner.getIfReady(coord);
            if (ready instanceof BuildingInfo info) {
                return info;
            }
            return BuildingInfo.getBuildingInfo(coord, provider);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean passesBuildingInfo(BuildingInfo info,
                                              boolean requiresBuilding,
                                              Set<String> requiredBuildings,
                                              Set<String> requiredParts) {
        if (info == null) {
            return false;
        }
        if (!info.isCity || !info.hasBuilding) {
            return !requiresBuilding && requiredBuildings.isEmpty() && requiredParts.isEmpty();
        }
        if (!requiredBuildings.isEmpty()) {
            try {
                String id = info.getBuildingId() != null ? info.getBuildingId().toString() : "";
                if (!requiredBuildings.contains(id)) {
                    return false;
                }
            } catch (Throwable ignored) {
                return false;
            }
        }
        return true;
    }

    private static BlockPos findInBuilding(Level world,
                                           BuildingInfo info,
                                           int x,
                                           int z,
                                           Predicate<BlockPos> isSuitable,
                                           BiPredicate<Level, BlockPos> isValidStandingPosition,
                                           Set<String> requiredParts) {
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
            if (!requiredParts.isEmpty()) {
                BuildingPart part = info.getFloorAtY(bottom, y);
                String partId = part != null ? part.getId().toString() : "";
                if (!requiredParts.contains(partId)) {
                    continue;
                }
            }
            pos.set(x, y, z);
            if (!isSuitable.test(pos)) {
                continue;
            }
            if (isValidStandingPosition.test(world, pos)) {
                return pos.above();
            }
        }
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
                                               Set<String> requiredParts) {
        if (!requiresBuilding && requiredBuildings.isEmpty() && requiredParts.isEmpty()) {
        return PrefilterDecision.PASS;
    }
        try {
            LostChunkCharacteristics characteristics = BuildingInfo.getChunkCharacteristics(coord, provider);
            if (characteristics == null) {
                return PrefilterDecision.NEED_INFO;
            }
            if (!characteristics.isCity || !characteristics.couldHaveBuilding) {
                return PrefilterDecision.FAIL;
            }
            if (!requiredBuildings.isEmpty()) {
                if (characteristics.buildingTypeId == null) {
                    return PrefilterDecision.NEED_INFO;
                }
                String buildingId = characteristics.buildingTypeId.toString();
                if (!requiredBuildings.contains(buildingId)) {
                    return PrefilterDecision.FAIL;
                }
            }
            if (!requiredParts.isEmpty()) {
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
                                                 Set<String> requiredParts) {
        int regionX = regionCoord(chunkX);
        int regionZ = regionCoord(chunkZ);
        SpawnRegionKey key = new SpawnRegionKey(dimension, regionX, regionZ, constraintHash);
        RegionScanCache cached = REGION_SCAN.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.createdMs) <= REGION_SCAN_TTL_MS) {
            return cached;
        }

        RegionScanCache built = buildRegionScan(provider, dimension, regionX, regionZ, constraintHash,
            requiresBuilding, requiredBuildings, requiredParts, now);
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
                PrefilterDecision decision = prefilter(coord, provider, requiresBuilding, requiredBuildings, requiredParts);
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
                                            Set<String> requiredParts) {
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
            requiresBuilding, requiredBuildings, requiredParts, now);
    }

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
        private volatile boolean sampleSyncTried = false;
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
                if (!sampleSyncTried && (now - sampleFirstAttemptMs) > CITY_SAMPLE_RETRY_MS) {
                    sampleSyncTried = true;
                    ChunkCoord coord = pending.get(0);
                    try {
                        BuildingInfo info = BuildingInfo.getBuildingInfo(coord, provider);
                        if (info != null) {
                            String id = info.getBuildingId() != null ? info.getBuildingId().toString() : "";
                            if (!id.isBlank()) {
                                sampledBuildingIds.add(id);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    if (!sampledBuildingIds.isEmpty()) {
                        sampledComplete = true;
                        return true;
                    }
                }
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
        LC2H.LOGGER.debug(
            "[LC2H] Spawn search progress (dim={}): chunksScanned={}, chunksRejected={}, citiesFound={}, citiesFailed={}, citiesSignatureRejected={}, cityChunksWithBuildingId={}, cityChunksWithBuildingCandidate={}, requiredBuildings={}, requiredParts={}, sampleCityBuildings={}, sampleCityBuildingsFromInfo={}",
            dimension.location(),
            chunks,
            chunksRejected,
            citiesFound,
            citiesFailed,
            citiesSignatureRejected,
            cityChunksWithBuildingId,
            cityChunksWithBuildingCandidate,
            SPAWN_PROGRESS_REQUIRED_BUILDINGS,
            SPAWN_PROGRESS_REQUIRED_PARTS,
            SPAWN_PROGRESS_SAMPLE_BUILDINGS,
            formatIdSampleFromCache(6)
        );
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
            if (mapped != null && !mapped.isBlank()) {
                resolved.add(mapped);
            }
        }
        return resolved;
    }

    private static Set<String> autoResolveMissing(Set<String> original,
                                                  Set<String> resolved,
                                                  boolean isBuilding) {
        if (resolved.isEmpty()) {
            return resolved;
        }
        Set<String> autoResolved = new HashSet<>(resolved);
        java.util.Map<String, String> remapped = new java.util.HashMap<>();
        for (String value : original) {
            if (value == null || value.isBlank()) {
                continue;
            }
            boolean known = isBuilding ? SpawnAssetIndex.isBuildingKnown(value)
                || SpawnAssetIndex.isMultiBuildingKnown(value)
                : SpawnAssetIndex.isPartKnown(value);
            if (known) {
                continue;
            }
            String candidate = isBuilding ? suggestBestBuilding(value) : SpawnAssetIndex.suggestBestPartId(value);
            if (candidate != null && !candidate.isBlank()) {
                if (candidate != null && !candidate.isBlank()) {
                    autoResolved.add(candidate);
                    remapped.put(value, candidate);
                }
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
