package org.admany.lc2h.util.spawn;

import mcjty.lostcities.api.LostChunkCharacteristics;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.setup.ForgeEventHandlers;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.cityassets.BuildingPart;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.storage.ServerLevelData;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.data.cache.LostCitiesCacheBridge;
import org.admany.lc2h.mixin.accessor.ForgeEventHandlersAccessor;
import org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

public final class SpawnSearchScheduler {
    private static final long MAX_BUDGET_NS = Math.max(0L,
        Long.getLong("lc2h.spawnsearch.budgetNs", 4_000_000L));
    private static final int MAX_ATTEMPTS_PER_TICK = Math.max(1,
        Integer.getInteger("lc2h.spawnsearch.attemptsPerTick", 80));
    private static final int TELEPORT_RADIUS_BLOCKS = Math.max(32,
        Integer.getInteger("lc2h.spawnsearch.teleportRadius", 128));
    private static final int TELEPORT_MIN_DELTA_BLOCKS = Math.max(16,
        Integer.getInteger("lc2h.spawnsearch.teleportMinDelta", 64));
    private static final int TELEPORT_DELAY_TICKS = Math.max(1,
        Integer.getInteger("lc2h.spawnsearch.teleportDelayTicks", 5));
    private static final int TELEPORT_LOGIN_GRACE_TICKS = Math.max(0,
        Integer.getInteger("lc2h.spawnsearch.teleportLoginGraceTicks", 0));
    private static final int TELEPORT_CONFIRM_RADIUS_BLOCKS = Math.max(8,
        Integer.getInteger("lc2h.spawnsearch.teleportConfirmRadius", 16));
    private static final int TELEPORT_RETRY_TICKS = Math.max(2,
        Integer.getInteger("lc2h.spawnsearch.teleportRetryTicks", 10));
    private static final int TELEPORT_SETTLE_TICKS = Math.max(5,
        Integer.getInteger("lc2h.spawnsearch.teleportSettleTicks", 40));
    private static final int TELEPORT_FORCE_TICKS = Math.max(20,
        Integer.getInteger("lc2h.spawnsearch.teleportForceTicks", 600));
    private static final boolean FAST_BUILDING_TYPE_MATCH =
        Boolean.parseBoolean(System.getProperty("lc2h.spawnsearch.fastBuildingType", "true"));
    private static final boolean FAST_SPAWN_IN_BUILDING =
        Boolean.parseBoolean(System.getProperty("lc2h.spawnsearch.fastSpawnInBuilding", "true"));
    private static final boolean ENABLE_TIMESLICE =
        Boolean.parseBoolean(System.getProperty("lc2h.spawnsearch.timeslice", "false"));

    private static final Map<ResourceKey<Level>, SearchState> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Map<UUID, PendingTeleport>> PENDING_TELEPORTS = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, SpawnTarget> RESOLVED_SPAWNS = new ConcurrentHashMap<>();
    private static final AtomicBoolean LOADING_STATE = new AtomicBoolean(false);

    private SpawnSearchScheduler() {
    }

    public static boolean isSearchActive(ServerLevel level) {
        return level != null && ACTIVE.containsKey(level.dimension());
    }

    public static boolean isSearchActive(MinecraftServer server) {
        if (server == null || ACTIVE.isEmpty()) {
            return false;
        }
        for (Map.Entry<ResourceKey<Level>, SearchState> entry : ACTIVE.entrySet()) {
            SearchState state = entry.getValue();
            if (state != null && state.isValid(server)) {
                return true;
            }
        }
        return false;
    }

    public static void registerPendingPlayer(ServerPlayer player) {
        if (player == null) {
            return;
        }
        ServerLevel level = player.serverLevel();
        if (!isSearchActive(level) && !RESOLVED_SPAWNS.containsKey(level.dimension())) {
            return;
        }
        long resolvedReadyTick = 0L;
        long resolvedForceUntilTick = 0L;
        try {
            long nowTick = player.getServer().getTickCount();
            resolvedReadyTick = nowTick + TELEPORT_DELAY_TICKS;
            resolvedForceUntilTick = nowTick + TELEPORT_FORCE_TICKS;
        } catch (Throwable ignored) {
        }
        final long readyTick = resolvedReadyTick;
        final long forceUntilTick = resolvedForceUntilTick;
        PENDING_TELEPORTS
            .computeIfAbsent(level.dimension(), key -> new ConcurrentHashMap<>())
            .compute(player.getUUID(), (id, existing) -> {
                if (existing == null) {
                    PendingTeleport created = new PendingTeleport(readyTick);
                    if (forceUntilTick > 0L) {
                        created.forceUntilTick = forceUntilTick;
                    }
                    return created;
                }
                if (existing.readyTick > readyTick) {
                    existing.readyTick = readyTick;
                }
                if (forceUntilTick > 0L && existing.forceUntilTick < forceUntilTick) {
                    existing.forceUntilTick = forceUntilTick;
                }
                return existing;
            });
    }

    public static boolean shouldTimeSlice(IDimensionInfo provider) {
        if (provider == null) {
            return false;
        }
        try {
            LostCityProfile profile = provider.getProfile();
            if (profile == null) {
                return false;
            }
            if (profile.FORCE_SPAWN_IN_BUILDING) {
                return true;
            }
            if (profile.FORCE_SPAWN_BUILDINGS != null && profile.FORCE_SPAWN_BUILDINGS.length > 0) {
                return true;
            }
            return profile.FORCE_SPAWN_PARTS != null && profile.FORCE_SPAWN_PARTS.length > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static BlockPos findOrSchedule(ForgeEventHandlers handler,
                                          Level world,
                                          IDimensionInfo provider,
                                          Predicate<BlockPos> isSuitable,
                                          ServerLevelData serverLevelData) {
        if (!(world instanceof ServerLevel serverLevel) || provider == null) {
            return fallback(world);
        }

        if (!ENABLE_TIMESLICE) {
            return fallback(world);
        }
        ResourceKey<Level> dimension = serverLevel.dimension();
        BlockPos cached = loadCachedSpawn(serverLevel, provider);
        if (cached != null) {
            applySpawn(handler, serverLevel, provider, serverLevelData, cached, serverLevel.getSharedSpawnPos());
            return cached;
        }
        SearchState state = ACTIVE.computeIfAbsent(dimension, key -> new SearchState(handler, serverLevel, provider, isSuitable, serverLevelData));
        BlockPos found = state.runBudget(MAX_BUDGET_NS, MAX_ATTEMPTS_PER_TICK);
        if (found != null) {
            state.applyFound(found);
            ACTIVE.remove(dimension, state);
            return found;
        }
        if (state.isExhausted()) {
            state.logFailure();
            ACTIVE.remove(dimension, state);
            clearPending(dimension);
        }
        return fallback(world);
    }

    public static BlockPos findCachedSpawn(ForgeEventHandlers handler,
                                           Level world,
                                           IDimensionInfo provider,
                                           ServerLevelData serverLevelData) {
        if (!(world instanceof ServerLevel serverLevel) || provider == null) {
            return null;
        }
        BlockPos cached = loadCachedSpawn(serverLevel, provider);
        if (cached == null) {
            return null;
        }
        applySpawn(handler, serverLevel, provider, serverLevelData, cached, serverLevel.getSharedSpawnPos());
        return cached;
    }

    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (ACTIVE.isEmpty()) {
            flushResolvedTeleports(server);
            return;
        }
        Iterator<Map.Entry<ResourceKey<Level>, SearchState>> iterator = ACTIVE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ResourceKey<Level>, SearchState> entry = iterator.next();
            SearchState state = entry.getValue();
            if (!state.isValid(server)) {
                iterator.remove();
                continue;
            }
            BlockPos found = state.runBudget(MAX_BUDGET_NS, MAX_ATTEMPTS_PER_TICK);
            if (found != null) {
                state.applyFound(found);
                iterator.remove();
                continue;
            }
            if (state.isExhausted()) {
                state.logFailure();
                iterator.remove();
                clearPending(entry.getKey());
            }
        }
        flushResolvedTeleports(server);
    }

    public static void appendDiagnostics(StringBuilder sb, MinecraftServer server) {
        if (sb == null) {
            return;
        }
        sb.append("== SPAWN SEARCH DIAGNOSTICS ==\n");
        sb.append("enabled=").append(ENABLE_TIMESLICE)
            .append(" active=").append(isSearchActive(server))
            .append(" activeDimensions=").append(ACTIVE.size())
            .append(" resolved=").append(RESOLVED_SPAWNS.size())
            .append(" pendingTeleports=").append(countPendingTeleports())
            .append(" loadingActive=").append(isLoadingActive())
            .append('\n');
        sb.append("budgetNs=").append(MAX_BUDGET_NS)
            .append(" attemptsPerTick=").append(MAX_ATTEMPTS_PER_TICK)
            .append(" maxPending=").append(SearchState.MAX_PENDING)
            .append('\n');
        sb.append("teleportDelay=").append(TELEPORT_DELAY_TICKS)
            .append(" loginGrace=").append(TELEPORT_LOGIN_GRACE_TICKS)
            .append(" confirmRadius=").append(TELEPORT_CONFIRM_RADIUS_BLOCKS)
            .append(" retryTicks=").append(TELEPORT_RETRY_TICKS)
            .append(" settleTicks=").append(TELEPORT_SETTLE_TICKS)
            .append(" forceTicks=").append(TELEPORT_FORCE_TICKS)
            .append('\n');

        for (Map.Entry<ResourceKey<Level>, SearchState> entry : ACTIVE.entrySet()) {
            SearchState state = entry.getValue();
            if (state == null) {
                continue;
            }
            sb.append("activeSearch=").append(entry.getKey())
                .append(" attempts=").append(state.attempts)
                .append('/').append(state.maxAttempts)
                .append(" radius=").append(state.radius)
                .append(" radiusIncrease=").append(state.radiusIncrease)
                .append(" pending=").append(state.pending.size())
                .append(" pendingChunks=").append(state.pendingChunkKeys.size())
                .append(" needsBuildingInfo=").append(state.needsBuildingInfo)
                .append(" spawnNotInBuilding=").append(state.spawnNotInBuilding)
                .append(" spawnInBuilding=").append(state.spawnInBuilding)
                .append(" reqBuildings=").append(state.requiredBuildings.size())
                .append(" reqParts=").append(state.requiredParts.size())
                .append('\n');
        }

        if (server != null) {
            double maxDistSq = (double) TELEPORT_RADIUS_BLOCKS * TELEPORT_RADIUS_BLOCKS;
            double confirmDistSq = (double) TELEPORT_CONFIRM_RADIUS_BLOCKS * TELEPORT_CONFIRM_RADIUS_BLOCKS;
            for (Map.Entry<ResourceKey<Level>, SpawnTarget> entry : RESOLVED_SPAWNS.entrySet()) {
                ResourceKey<Level> dimension = entry.getKey();
                SpawnTarget target = entry.getValue();
                if (dimension == null || target == null) {
                    continue;
                }
                ServerLevel world = server.getLevel(dimension);
                if (world == null) {
                    continue;
                }
                sb.append("resolvedSpawn=").append(dimension)
                    .append(" from=").append(formatPos(target.from()))
                    .append(" to=").append(formatPos(target.to()))
                    .append(" pending=").append(getPendingCount(dimension))
                    .append('\n');
                Map<UUID, PendingTeleport> pending = PENDING_TELEPORTS.get(dimension);
                for (ServerPlayer player : world.players()) {
                    if (player == null) {
                        continue;
                    }
                    PendingTeleport teleport = pending != null ? pending.get(player.getUUID()) : null;
                    double distToTarget = player.distanceToSqr(target.to().getX() + 0.5, target.to().getY(), target.to().getZ() + 0.5);
                    double distToFrom = target.from() != null
                        ? player.distanceToSqr(target.from().getX() + 0.5, target.from().getY(), target.from().getZ() + 0.5)
                        : Double.MAX_VALUE;
                    double distToZero = player.distanceToSqr(0.5, player.getY(), 0.5);
                    boolean nearOldSpawn = distToFrom <= maxDistSq || distToZero <= maxDistSq;
                    boolean missingPending = teleport == null;
                    boolean flingRisk = distToTarget > confirmDistSq && nearOldSpawn && missingPending;
                    sb.append(" player=").append(player.getGameProfile().getName())
                        .append(" at=").append(formatPos(player.blockPosition()))
                        .append(" distToTargetSq=").append((long) distToTarget)
                        .append(" pending=").append(teleport != null)
                        .append(" flingBackRisk=").append(flingRisk)
                        .append('\n');
                    if (teleport != null) {
                        sb.append("  teleportState readyTick=").append(teleport.readyTick)
                            .append(" lastTeleportTick=").append(teleport.lastTeleportTick)
                            .append(" confirmSinceTick=").append(teleport.confirmSinceTick)
                            .append(" forceUntilTick=").append(teleport.forceUntilTick)
                            .append('\n');
                    }
                }
            }
        }
        sb.append('\n');
    }

    public static SpawnSearchSnapshot snapshot(MinecraftServer server) {
        boolean active = isSearchActive(server);
        int pendingCandidates = 0;
        int pendingChunks = 0;
        boolean needsBuildingInfo = false;
        int requiredBuildings = 0;
        int requiredParts = 0;
        for (SearchState state : ACTIVE.values()) {
            if (state == null) {
                continue;
            }
            pendingCandidates += state.pending.size();
            pendingChunks += state.pendingChunkKeys.size();
            needsBuildingInfo |= state.needsBuildingInfo;
            requiredBuildings += state.requiredBuildings.size();
            requiredParts += state.requiredParts.size();
        }
        return new SpawnSearchSnapshot(
            active,
            ACTIVE.size(),
            RESOLVED_SPAWNS.size(),
            countPendingTeleports(),
            pendingCandidates,
            pendingChunks,
            needsBuildingInfo,
            requiredBuildings,
            requiredParts
        );
    }

    public static int countFlingBackRisk(MinecraftServer server) {
        if (server == null || RESOLVED_SPAWNS.isEmpty()) {
            return 0;
        }
        int count = 0;
        double maxDistSq = (double) TELEPORT_RADIUS_BLOCKS * TELEPORT_RADIUS_BLOCKS;
        double confirmDistSq = (double) TELEPORT_CONFIRM_RADIUS_BLOCKS * TELEPORT_CONFIRM_RADIUS_BLOCKS;
        for (Map.Entry<ResourceKey<Level>, SpawnTarget> entry : RESOLVED_SPAWNS.entrySet()) {
            ResourceKey<Level> dimension = entry.getKey();
            SpawnTarget target = entry.getValue();
            if (dimension == null || target == null || target.to() == null) {
                continue;
            }
            ServerLevel world = server.getLevel(dimension);
            if (world == null) {
                continue;
            }
            Map<UUID, PendingTeleport> pending = PENDING_TELEPORTS.get(dimension);
            for (ServerPlayer player : world.players()) {
                if (player == null) {
                    continue;
                }
                PendingTeleport teleport = pending != null ? pending.get(player.getUUID()) : null;
                double distToTarget = player.distanceToSqr(target.to().getX() + 0.5, target.to().getY(), target.to().getZ() + 0.5);
                double distToFrom = target.from() != null
                    ? player.distanceToSqr(target.from().getX() + 0.5, target.from().getY(), target.from().getZ() + 0.5)
                    : Double.MAX_VALUE;
                double distToZero = player.distanceToSqr(0.5, player.getY(), 0.5);
                boolean nearOldSpawn = distToFrom <= maxDistSq || distToZero <= maxDistSq;
                boolean missingPending = teleport == null;
                boolean flingRisk = distToTarget > confirmDistSq && nearOldSpawn && missingPending;
                if (flingRisk) {
                    count++;
                }
            }
        }
        return count;
    }

    private static BlockPos fallback(Level world) {
        try {
            return world.getSharedSpawnPos();
        } catch (Throwable ignored) {
            return new BlockPos(0, 64, 0);
        }
    }

    private static final class SearchState {
        private final ForgeEventHandlers handler;
        private final ServerLevel world;
        private final IDimensionInfo provider;
        private final Predicate<BlockPos> isSuitable;
        private final Predicate<BlockPos> basePredicate;
        private final ServerLevelData serverLevelData;
        private final Random rand;
        private final int radiusIncrease;
        private final int maxAttempts;
        private int radius;
        private int attempts;
        private int triesThisRadius;
        private boolean loggedDeferred;
        private final boolean spawnNotInBuilding;
        private final boolean spawnInBuilding;
        private final Set<String> requiredBuildings;
        private final Set<String> requiredParts;
        private boolean needsBuildingInfo;
        private final BlockPos initialSpawn;
        private final ArrayDeque<PendingCandidate> pending = new ArrayDeque<>();
        private final Set<Long> pendingChunkKeys = new HashSet<>();
        private static final int MAX_PENDING = Math.max(256,
            Integer.getInteger("lc2h.spawnsearch.maxPending", 1024));

        private SearchState(ForgeEventHandlers handler,
                            ServerLevel world,
                            IDimensionInfo provider,
                            Predicate<BlockPos> isSuitable,
                            ServerLevelData serverLevelData) {
            this.handler = handler;
            this.world = world;
            this.provider = provider;
            this.isSuitable = isSuitable;
            this.basePredicate = isSuitable;
            this.serverLevelData = serverLevelData;
            LostCityProfile profile = provider.getProfile();
            this.radius = profile.SPAWN_CHECK_RADIUS;
            this.radiusIncrease = Math.max(1, profile.SPAWN_RADIUS_INCREASE);
            this.maxAttempts = Math.max(1, profile.SPAWN_CHECK_ATTEMPTS);
            this.rand = new Random(provider.getSeed());
            this.spawnNotInBuilding = profile.SPAWN_NOT_IN_BUILDING;
            this.spawnInBuilding = profile.FORCE_SPAWN_IN_BUILDING;
            this.requiredBuildings = new HashSet<>();
            if (profile.FORCE_SPAWN_BUILDINGS != null) {
                for (String id : profile.FORCE_SPAWN_BUILDINGS) {
                    if (id != null && !id.isEmpty()) {
                        requiredBuildings.add(id);
                    }
                }
            }
            this.requiredParts = new HashSet<>();
            if (profile.FORCE_SPAWN_PARTS != null) {
                for (String id : profile.FORCE_SPAWN_PARTS) {
                    if (id != null && !id.isEmpty()) {
                        requiredParts.add(id);
                    }
                }
            }
            this.needsBuildingInfo = spawnNotInBuilding
                || spawnInBuilding
                || !requiredBuildings.isEmpty()
                || !requiredParts.isEmpty();
            BlockPos spawn = null;
            try {
                spawn = world.getSharedSpawnPos();
            } catch (Throwable ignored) {
            }
            if (spawn == null) {
                try {
                    spawn = world.getSharedSpawnPos();
                } catch (Throwable ignored) {
                }
            }
            this.initialSpawn = spawn != null ? spawn : new BlockPos(0, 64, 0);
        }

        private boolean isValid(MinecraftServer server) {
            if (server == null) {
                return false;
            }
            if (world.isClientSide) {
                return false;
            }
            return server.getLevel(world.dimension()) != null;
        }

        private boolean isExhausted() {
            return attempts > maxAttempts;
        }

        private BlockPos runBudget(long budgetNs, int maxAttemptsPerTick) {
            if (isExhausted()) {
                return null;
            }
            long start = System.nanoTime();
            int attemptsThisTick = 0;
            if (!pending.isEmpty()) {
                int pendingChecks = Math.min(pending.size(), Math.max(4, maxAttemptsPerTick / 2));
                for (int i = 0; i < pendingChecks; i++) {
                    if (budgetNs > 0L && (System.nanoTime() - start) >= budgetNs) {
                        break;
                    }
                    PendingCandidate candidate = pending.pollFirst();
                    if (candidate == null) {
                        break;
                    }
                    if (!isChunkLoaded(candidate.x, candidate.z)) {
                        pending.addLast(candidate);
                        continue;
                    }
                    pendingChunkKeys.remove(candidate.chunkKey);
                    BlockPos found = scanCandidate(candidate.x, candidate.z);
                    if (found != null) {
                        return found;
                    }
                }
            }
            while (attempts <= maxAttempts) {
                if (maxAttemptsPerTick > 0 && attemptsThisTick >= maxAttemptsPerTick) {
                    break;
                }
                if (budgetNs > 0L && (System.nanoTime() - start) >= budgetNs) {
                    break;
                }
                if (triesThisRadius >= 200) {
                    triesThisRadius = 0;
                    radius += radiusIncrease;
                }

                int x = rand.nextInt(radius * 2) - radius;
                int z = rand.nextInt(radius * 2) - radius;
                attempts++;
                attemptsThisTick++;
                triesThisRadius++;

                BlockPos probe = new BlockPos(x, 128, z);
                try {
                    if (!isSuitable.test(probe)) {
                        continue;
                    }
                } catch (Throwable ignored) {
                    continue;
                }

                if (!isChunkLoaded(x, z)) {
                    requestChunkLoad(x, z);
                    continue;
                }

                ChunkCoord coord = new ChunkCoord(provider.getType(), x >> 4, z >> 4);
                LostCityProfile chunkProfile = BuildingInfo.getProfile(coord, provider);
                int startY = chunkProfile.GROUNDLEVEL - 5;
                for (int y = startY; y < 125; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isValidStandingPosition(pos)) {
                        return pos.above();
                    }
                }
            }

            if (!loggedDeferred) {
                loggedDeferred = true;
                if (org.admany.lc2h.logging.config.ConfigManager.ENABLE_DEBUG_LOGGING) {
                    LC2H.LOGGER.debug("[LC2H] Deferring Lost Cities spawn search for {} (attempts={}/{})",
                        world.dimension().location(), attempts, maxAttempts);
                }
            }
            return null;
        }

        private void applyFound(BlockPos pos) {
            applySpawn(handler, world, provider, serverLevelData, pos, initialSpawn);
        }

        private void logFailure() {
            LC2H.LOGGER.error("[LC2H] Timed out searching for a forced spawn position in {} after {} attempts",
                world.dimension().location(), attempts);
        }

        private boolean matchesBasePredicate(BlockPos pos) {
            if (basePredicate == null) {
                return true;
            }
            try {
                return basePredicate.test(pos);
            } catch (Throwable ignored) {
                return false;
            }
        }

        private boolean matchesOriginalPredicate(BlockPos pos) {
            if (isSuitable == null) {
                return true;
            }
            try {
                return isSuitable.test(pos);
            } catch (Throwable ignored) {
                return false;
            }
        }

        private BuildingCheck checkBuildingConstraints(int x, int z, BlockPos probe) {
            ChunkCoord coord = new ChunkCoord(provider.getType(), x >> 4, z >> 4);
            Object cached = AsyncBuildingInfoPlanner.getIfReady(coord);
            if (!(cached instanceof BuildingInfo info)) {
                LostChunkCharacteristics characteristics = null;
                try {
                    characteristics = BuildingInfo.getChunkCharacteristics(coord, provider);
                } catch (Throwable ignored) {
                }
                boolean isCity = false;
                try {
                    isCity = BuildingInfo.isCity(coord, provider);
                } catch (Throwable ignored) {
                }
                if (characteristics != null) {
                    if (!characteristics.isCity) {
                        return spawnNotInBuilding ? BuildingCheck.PASS : BuildingCheck.FAIL;
                    }
                    if (!characteristics.couldHaveBuilding) {
                        return spawnNotInBuilding ? BuildingCheck.PASS : BuildingCheck.FAIL;
                    }
                    if (!requiredBuildings.isEmpty() && characteristics.buildingTypeId != null) {
                        String buildingId = characteristics.buildingTypeId.toString();
                        if (!requiredBuildings.contains(buildingId)) {
                            return BuildingCheck.FAIL;
                        }
                        if (FAST_BUILDING_TYPE_MATCH && requiredParts.isEmpty()) {
                            return BuildingCheck.PASS;
                        }
                    }
                    if (spawnInBuilding && FAST_SPAWN_IN_BUILDING) {
                        return BuildingCheck.PASS;
                    }
                }
                if (characteristics == null && !isCity) {
                    return spawnNotInBuilding ? BuildingCheck.PASS : BuildingCheck.FAIL;
                }
                AsyncBuildingInfoPlanner.preSchedulePriority(provider, coord);
                return BuildingCheck.RETRY;
            }

            if (spawnNotInBuilding) {
                return (!info.isCity || !info.hasBuilding) ? BuildingCheck.PASS : BuildingCheck.FAIL;
            }

            if (!requiredBuildings.isEmpty() || !requiredParts.isEmpty()) {
                if (!info.isCity || !info.hasBuilding) {
                    return BuildingCheck.FAIL;
                }
                if (!requiredBuildings.isEmpty()) {
                    String buildingId = info.buildingType != null
                        ? info.buildingType.getId().toString()
                        : "";
                    if (!requiredBuildings.contains(buildingId)) {
                        return BuildingCheck.FAIL;
                    }
                }
                if (!requiredParts.isEmpty()) {
                    int bottom = info.getBuildingBottomHeight();
                    if (bottom == Integer.MIN_VALUE) {
                        return BuildingCheck.FAIL;
                    }
                    BuildingPart part = info.getFloorAtY(bottom, probe.getY());
                    String partId = part != null ? part.getId().toString() : "";
                    if (!requiredParts.contains(partId)) {
                        return BuildingCheck.FAIL;
                    }
                }
                return BuildingCheck.PASS;
            }

            if (spawnInBuilding) {
                return (info.isCity && info.hasBuilding) ? BuildingCheck.PASS : BuildingCheck.FAIL;
            }

            return BuildingCheck.PASS;
        }

        private enum BuildingCheck {
            PASS,
            FAIL,
            RETRY
        }

        private boolean isChunkLoaded(int x, int z) {
            try {
                return world.isLoaded(new BlockPos(x, 128, z));
            } catch (Throwable ignored) {
                return false;
            }
        }

        private void requestChunkLoad(int x, int z) {
            ChunkPos chunkPos = new ChunkPos(x >> 4, z >> 4);
            long key = chunkPos.toLong();
            if (!pendingChunkKeys.add(key)) {
                return;
            }
            if (pending.size() >= MAX_PENDING) {
                PendingCandidate dropped = pending.pollFirst();
                if (dropped != null) {
                    pendingChunkKeys.remove(dropped.chunkKey);
                }
            }
            pending.addLast(new PendingCandidate(x, z, key));
            try {
                ServerChunkCache cache = world.getChunkSource();
                cache.getChunkFuture(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true);
            } catch (Throwable ignored) {
            }
        }

        private BlockPos scanCandidate(int x, int z) {
            ChunkCoord coord = new ChunkCoord(provider.getType(), x >> 4, z >> 4);
            LostCityProfile chunkProfile = BuildingInfo.getProfile(coord, provider);
            int startY = chunkProfile.GROUNDLEVEL - 5;
            for (int y = startY; y < 125; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (isValidStandingPosition(pos)) {
                    return pos.above();
                }
            }
            return null;
        }

        private boolean isValidStandingPosition(BlockPos pos) {
            if (!world.isLoaded(pos)) {
                return false;
            }
            BlockState state = world.getBlockState(pos);
            if (!state.isFaceSturdy(world, pos, net.minecraft.core.Direction.UP)) {
                return false;
            }
            if (state.is(Blocks.BEDROCK)) {
                return false;
            }
            return world.getBlockState(pos.above()).isAir() && world.getBlockState(pos.above(2)).isAir();
        }
    }

    private record PendingCandidate(int x, int z, long chunkKey) {
    }

    private static void applySpawn(ForgeEventHandlers handler,
                                   ServerLevel world,
                                   IDimensionInfo provider,
                                   ServerLevelData serverLevelData,
                                   BlockPos pos,
                                   BlockPos previousSpawn) {
        if (world == null || pos == null) {
            return;
        }
        try {
            if (serverLevelData != null) {
                serverLevelData.setSpawn(pos, 0.0f);
            }
        } catch (Throwable ignored) {
        }
        try {
            world.setDefaultSpawnPos(pos, 0.0f);
        } catch (Throwable ignored) {
        }
        try {
            if (handler instanceof ForgeEventHandlersAccessor accessor) {
                accessor.lc2h$getSpawnPositions().put(world.dimension(), pos);
            }
        } catch (Throwable ignored) {
        }
        cacheSpawn(world, provider, pos);
        markResolvedSpawn(world, previousSpawn, pos);
    }

    private static void markResolvedSpawn(ServerLevel world, BlockPos from, BlockPos to) {
        if (world == null || to == null) {
            return;
        }
        BlockPos previous = from != null ? from : world.getSharedSpawnPos();
        RESOLVED_SPAWNS.put(world.dimension(), new SpawnTarget(previous, to));
        flushResolvedTeleports(world.getServer());
        sendLoadingState(world.getServer(), isLoadingActive());
    }

    private static boolean isLoadingActive() {
        if (!ACTIVE.isEmpty()) {
            return true;
        }
        if (PENDING_TELEPORTS.isEmpty()) {
            return false;
        }
        for (Map<UUID, PendingTeleport> pending : PENDING_TELEPORTS.values()) {
            if (pending == null || pending.isEmpty()) {
                continue;
            }
            for (PendingTeleport teleport : pending.values()) {
                if (teleport != null && teleport.confirmSinceTick < 0L) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countPendingTeleports() {
        int total = 0;
        for (Map<UUID, PendingTeleport> pending : PENDING_TELEPORTS.values()) {
            if (pending != null) {
                total += pending.size();
            }
        }
        return total;
    }

    private static int getPendingCount(ResourceKey<Level> dimension) {
        Map<UUID, PendingTeleport> pending = dimension != null ? PENDING_TELEPORTS.get(dimension) : null;
        return pending != null ? pending.size() : 0;
    }

    private static String formatPos(BlockPos pos) {
        if (pos == null) {
            return "null";
        }
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static void sendLoadingState(MinecraftServer server, boolean active) {
        if (server == null) {
            return;
        }
        LOADING_STATE.set(active);
    }

    private static void flushResolvedTeleports(MinecraftServer server) {
        if (server == null || RESOLVED_SPAWNS.isEmpty()) {
            return;
        }
        long nowTick = 0L;
        try {
            nowTick = server.getTickCount();
        } catch (Throwable ignored) {
        }
        for (Map.Entry<ResourceKey<Level>, SpawnTarget> entry : RESOLVED_SPAWNS.entrySet()) {
            ResourceKey<Level> dimension = entry.getKey();
            SpawnTarget target = entry.getValue();
            if (dimension == null || target == null) {
                continue;
            }
            ServerLevel world = server.getLevel(dimension);
            if (world == null) {
                continue;
            }
            teleportPending(world, target, nowTick);
        }
    }

    private static void teleportPending(ServerLevel world, SpawnTarget target, long nowTick) {
        if (world == null || target == null) {
            return;
        }
        BlockPos from = target.from();
        BlockPos to = target.to();
        if (from == null || to == null) {
            clearPending(world.dimension());
            return;
        }
        double deltaSq = from.distSqr(to);
        double minDeltaSq = (double) TELEPORT_MIN_DELTA_BLOCKS * TELEPORT_MIN_DELTA_BLOCKS;
        if (deltaSq < minDeltaSq) {
            clearPending(world.dimension());
            return;
        }
        Map<UUID, PendingTeleport> pending = PENDING_TELEPORTS.get(world.dimension());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        double maxDistSq = (double) TELEPORT_RADIUS_BLOCKS * TELEPORT_RADIUS_BLOCKS;
        double confirmDistSq = (double) TELEPORT_CONFIRM_RADIUS_BLOCKS * TELEPORT_CONFIRM_RADIUS_BLOCKS;
        Iterator<Map.Entry<UUID, PendingTeleport>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingTeleport> entry = it.next();
            PendingTeleport pendingTeleport = entry.getValue();
            long readyTick = pendingTeleport != null ? pendingTeleport.readyTick : 0L;
            if (nowTick < readyTick) {
                continue;
            }
            if (pendingTeleport == null) {
                pendingTeleport = new PendingTeleport(readyTick);
                entry.setValue(pendingTeleport);
            }
            if (pendingTeleport.forceUntilTick <= 0L) {
                pendingTeleport.forceUntilTick = nowTick + TELEPORT_FORCE_TICKS;
            }
            boolean forceActive = nowTick < pendingTeleport.forceUntilTick;
            ServerPlayer player = world.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.level() != world) {
                if (!forceActive) {
                    it.remove();
                }
                continue;
            }
            double distSqFrom = player.distanceToSqr(from.getX() + 0.5, from.getY(), from.getZ() + 0.5);
            double distSqTo = player.distanceToSqr(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
            if (distSqTo <= confirmDistSq) {
                applyPlayerSpawn(player, to);
                if (pendingTeleport.confirmSinceTick < 0L) {
                    pendingTeleport.confirmSinceTick = nowTick;
                } else if (!forceActive && (nowTick - pendingTeleport.confirmSinceTick) >= TELEPORT_SETTLE_TICKS) {
                    it.remove();
                }
                continue;
            }
            pendingTeleport.confirmSinceTick = -1L;
            if (!forceActive && distSqFrom > maxDistSq && pendingTeleport.lastTeleportTick <= 0L) {
                it.remove();
                continue;
            }
            if (pendingTeleport.lastTeleportTick > 0 && (nowTick - pendingTeleport.lastTeleportTick) < TELEPORT_RETRY_TICKS) {
                continue;
            }
            if (!isChunkLoaded(world, to)) {
                requestChunkLoad(world, to, pendingTeleport, nowTick);
                continue;
            }
            player.teleportTo(world, to.getX() + 0.5, to.getY(), to.getZ() + 0.5, player.getYRot(), player.getXRot());
            pendingTeleport.lastTeleportTick = nowTick;
            pendingTeleport.forceUntilTick = Math.max(pendingTeleport.forceUntilTick, nowTick + TELEPORT_FORCE_TICKS);
            applyPlayerSpawn(player, to);
        }
        if (pending.isEmpty()) {
            return;
        }
    }

    private static void clearPending(ResourceKey<Level> dimension) {
        if (dimension != null) {
            PENDING_TELEPORTS.remove(dimension);
        }
    }

    private static BlockPos loadCachedSpawn(ServerLevel world, IDimensionInfo provider) {
        try {
            String key = spawnCacheKey(world, provider);
            Long packed = LostCitiesCacheBridge.getDisk("spawn", key, Long.class);
            if (packed == null) {
                return null;
            }
            return BlockPos.of(packed);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void cacheSpawn(ServerLevel world, IDimensionInfo provider, BlockPos pos) {
        try {
            if (world == null || provider == null || pos == null) {
                return;
            }
            String key = spawnCacheKey(world, provider);
            LostCitiesCacheBridge.putDisk("spawn", key, pos.asLong());
        } catch (Throwable ignored) {
        }
    }

    private static String spawnCacheKey(ServerLevel world, IDimensionInfo provider) {
        StringBuilder key = new StringBuilder();
        try {
            key.append(world.dimension().location());
        } catch (Throwable ignored) {
            key.append("unknown_dim");
        }
        key.append('|').append(provider.getSeed());
        try {
            LostCityProfile profile = provider.getProfile();
            if (profile != null) {
                key.append('|').append(profile.toString());
            }
        } catch (Throwable ignored) {
        }
        return key.toString();
    }

    private record SpawnTarget(BlockPos from, BlockPos to) {
    }

    public record SpawnSearchSnapshot(boolean active,
                                      int activeDimensions,
                                      int resolved,
                                      int pendingTeleports,
                                      int pendingCandidates,
                                      int pendingChunks,
                                      boolean needsBuildingInfo,
                                      int requiredBuildings,
                                      int requiredParts) {
    }

    private static final class PendingTeleport {
        private long readyTick;
        private long lastTeleportTick;
        private long confirmSinceTick;
        private long forceUntilTick;
        private long lastLoadRequestTick;

        private PendingTeleport(long readyTick) {
            this.readyTick = readyTick;
            this.lastTeleportTick = -1L;
            this.confirmSinceTick = -1L;
            this.forceUntilTick = -1L;
            this.lastLoadRequestTick = -1L;
        }
    }

    private static void applyPlayerSpawn(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) {
            return;
        }
        try {
            player.setRespawnPosition(player.level().dimension(), pos, player.getYRot(), true, false);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isChunkLoaded(ServerLevel world, BlockPos pos) {
        try {
            return world.isLoaded(pos);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void requestChunkLoad(ServerLevel world, BlockPos pos, PendingTeleport pending, long nowTick) {
        if (world == null || pos == null || pending == null) {
            return;
        }
        if (pending.lastLoadRequestTick > 0 && (nowTick - pending.lastLoadRequestTick) < TELEPORT_RETRY_TICKS) {
            return;
        }
        try {
            ChunkPos chunkPos = new ChunkPos(pos);
            ServerChunkCache cache = world.getChunkSource();
            cache.getChunkFuture(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true);
            pending.lastLoadRequestTick = nowTick;
        } catch (Throwable ignored) {
        }
    }

    public static BlockPos getTeleportOverride(ServerPlayer player, ServerLevel level, double x, double y, double z) {
        if (player == null || level == null) {
            return null;
        }
        SpawnTarget target = RESOLVED_SPAWNS.get(level.dimension());
        if (target == null || target.to() == null) {
            return null;
        }
        Map<UUID, PendingTeleport> pending = PENDING_TELEPORTS.get(level.dimension());
        if (pending == null) {
            return null;
        }
        PendingTeleport pendingTeleport = pending.get(player.getUUID());
        if (pendingTeleport == null) {
            return null;
        }
        long nowTick = 0L;
        try {
            nowTick = level.getServer().getTickCount();
        } catch (Throwable ignored) {
        }
        if (pendingTeleport.forceUntilTick <= 0L) {
            pendingTeleport.forceUntilTick = nowTick + TELEPORT_FORCE_TICKS;
        }
        if (pendingTeleport.forceUntilTick > 0L && nowTick > pendingTeleport.forceUntilTick) {
            return null;
        }
        double maxDistSq = (double) TELEPORT_RADIUS_BLOCKS * TELEPORT_RADIUS_BLOCKS;
        BlockPos from = target.from();
        if (from != null && distanceSq(from, x, y, z) <= maxDistSq) {
            return target.to();
        }
        if (distanceSq(BlockPos.ZERO, x, y, z) <= maxDistSq) {
            return target.to();
        }
        return null;
    }

    private static double distanceSq(BlockPos pos, double x, double y, double z) {
        double dx = x - (pos.getX() + 0.5);
        double dy = y - pos.getY();
        double dz = z - (pos.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }
}
