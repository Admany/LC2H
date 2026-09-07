package org.admany.lc2h.dev.diagnostics;

import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.admany.lc2h.data.cache.FeatureCache;
import org.admany.lc2h.data.cache.LostCitiesCacheBridge;
import org.admany.lc2h.util.chunk.ChunkPostProcessor;
import org.admany.lc2h.worldgen.apply.ShadowBlockMutationApplier;
import org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner;
import org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner;
import org.admany.lc2h.worldgen.async.planner.PlannerBatchQueue;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeQueue;
import org.admany.lc2h.worldgen.lostcities.MultiChunkPlanningCache;
import org.admany.lc2h.worldgen.scope.WorldGenScope;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class LifecycleTortureTracker {
    private static final int MAX_TRANSITIONS = 128;
    private static final int MAX_SNAPSHOTS = 64;
    private static final ArrayDeque<TransitionEvent> TRANSITIONS = new ArrayDeque<>();
    private static final ArrayDeque<LifecycleSnapshot> SNAPSHOTS = new ArrayDeque<>();
    private static final AtomicInteger ASSERTION_FAILURES = new AtomicInteger();
    private static final AtomicLong LAST_LIFECYCLE_ID = new AtomicLong(-1L);

    private LifecycleTortureTracker() {
    }

    public static synchronized void reset(String reason) {
        TRANSITIONS.clear();
        SNAPSHOTS.clear();
        ASSERTION_FAILURES.set(0);
        LAST_LIFECYCLE_ID.set(-1L);
        rememberTransition("reset", reason);
    }

    public static void onServerStarting(MinecraftServer server) {
        rememberTransition("server-starting", worldLabel(server));
        snapshot(server, "server-starting");
    }

    public static void onServerStarted(MinecraftServer server) {
        rememberTransition("server-started", worldLabel(server));
        snapshot(server, "server-started");
    }

    public static void onServerStopping(MinecraftServer server) {
        rememberTransition("server-stopping", worldLabel(server));
        snapshot(server, "server-stopping");
    }

    public static void onServerStopped(MinecraftServer server) {
        rememberTransition("server-stopped", worldLabel(server));
        snapshot(server, "server-stopped");
    }

    public static void onPlayerJoin(ServerPlayer player) {
        if (player == null) {
            return;
        }
        rememberTransition("player-join", player.getGameProfile().getName() + " dim=" + player.serverLevel().dimension().location());
        snapshot(player.getServer(), "player-join:" + player.getGameProfile().getName());
    }

    public static void onPlayerLeave(ServerPlayer player) {
        if (player == null) {
            return;
        }
        rememberTransition("player-leave", player.getGameProfile().getName());
        snapshot(player.getServer(), "player-leave:" + player.getGameProfile().getName());
    }

    public static void onPlayerChangedDimension(ServerPlayer player, ResourceKey<Level> from, ResourceKey<Level> to) {
        if (player == null) {
            return;
        }
        rememberTransition("dimension-switch",
            player.getGameProfile().getName() + " " + safeDimension(from) + " -> " + safeDimension(to));
        snapshot(player.getServer(), "dimension-switch:" + player.getGameProfile().getName());
    }

    public static void onProfileOverride(ResourceKey<Level> dimension, String profileName) {
        rememberTransition("profile-override", safeDimension(dimension) + " -> " + profileName);
    }

    public static void onOutsideProfileOverride(ResourceKey<Level> dimension, String profileName) {
        rememberTransition("outside-profile-override", safeDimension(dimension) + " -> " + profileName);
    }

    public static synchronized LifecycleSnapshot snapshot(MinecraftServer server, String label) {
        LifecycleSnapshot snapshot = buildSnapshot(server, label);
        rememberSnapshot(snapshot);
        validateSnapshot(snapshot);
        return snapshot;
    }

    public static synchronized List<String> summaryLines() {
        ArrayList<String> lines = new ArrayList<>();
        LifecycleSnapshot last = SNAPSHOTS.peekLast();
        lines.add(String.format(Locale.ROOT,
            "LifecycleTorture: transitions=%d snapshots=%d assertionFailures=%d activeLifecycle=%d diskScope=%s",
            TRANSITIONS.size(),
            SNAPSHOTS.size(),
            ASSERTION_FAILURES.get(),
            WorldGenScope.activeLifecycleId(),
            WorldGenScope.activeDiskScope()));
        if (last != null) {
            lines.add(String.format(Locale.ROOT,
                "LifecycleTorture last: label=%s at=%s featureCache[local=%d distributed=%s] planner[pending=%d batches=%d] multichunk[planned=%d pending=%d] shadow[pendingTx=%d heldTickets=%d] trees[pending=%d ready=%d] postProcess[pendingScans=%d]",
                last.label,
                last.capturedAt,
                last.featureCacheLocal,
                last.featureCacheDistributed == null ? "<none>" : Long.toString(last.featureCacheDistributed),
                last.plannerPendingTasks,
                last.plannerBatchCount,
                last.multichunkPlanned,
                last.multichunkPending,
                last.shadowPendingTransactions,
                last.shadowHeldTickets,
                last.pendingTrees,
                last.readyTrees,
                last.pendingScans));
        }
        return lines;
    }

    public static synchronized List<String> detailLines() {
        ArrayList<String> lines = new ArrayList<>(LifecycleStateInventory.summaryLines());
        ArrayList<TransitionEvent> orderedTransitions = new ArrayList<>(TRANSITIONS);
        orderedTransitions.sort(Comparator.comparing(event -> event.at));
        for (TransitionEvent event : orderedTransitions) {
            lines.add("LifecycleTransition: " + event.at + " " + event.kind + " " + event.detail);
        }
        ArrayList<LifecycleSnapshot> orderedSnapshots = new ArrayList<>(SNAPSHOTS);
        orderedSnapshots.sort(Comparator.comparing(snapshot -> snapshot.capturedAt));
        for (LifecycleSnapshot snapshot : orderedSnapshots) {
            lines.add(snapshot.describe());
        }
        return lines;
    }

    private static synchronized void rememberTransition(String kind, String detail) {
        while (TRANSITIONS.size() >= MAX_TRANSITIONS) {
            TRANSITIONS.removeFirst();
        }
        TRANSITIONS.addLast(new TransitionEvent(Instant.now().toString(), kind, detail == null ? "<none>" : detail));
    }

    private static synchronized void rememberSnapshot(LifecycleSnapshot snapshot) {
        while (SNAPSHOTS.size() >= MAX_SNAPSHOTS) {
            SNAPSHOTS.removeFirst();
        }
        SNAPSHOTS.addLast(snapshot);
    }

    private static void validateSnapshot(LifecycleSnapshot snapshot) {
        long previousLifecycle = LAST_LIFECYCLE_ID.getAndSet(snapshot.lifecycleId);
        if (previousLifecycle > snapshot.lifecycleId) {
            ASSERTION_FAILURES.incrementAndGet();
        }
        if (snapshot.label.contains("server-stopped")) {
            if (snapshot.shadowPendingTransactions > 0 || snapshot.shadowHeldTickets > 0L) {
                ASSERTION_FAILURES.incrementAndGet();
            }
            if (snapshot.pendingTrees > 0 || snapshot.readyTrees > 0) {
                ASSERTION_FAILURES.incrementAndGet();
            }
        }
    }

    private static LifecycleSnapshot buildSnapshot(MinecraftServer server, String label) {
        FeatureCache.CacheStats featureCache = FeatureCache.snapshot();
        PlannerBatchQueue.PlannerBatchStats planner = PlannerBatchQueue.snapshotStats();
        AsyncMultiChunkPlanner.MultichunkTelemetrySnapshot multichunk = AsyncMultiChunkPlanner.telemetrySnapshot();
        AsyncBuildingInfoPlanner.BuildingInfoPressureSnapshot buildingInfo = AsyncBuildingInfoPlanner.snapshotPressure();
        ShadowBlockMutationApplier.RuntimeSnapshot shadow = ShadowBlockMutationApplier.runtimeSnapshot();
        ServerLevel overworld = server == null ? null : server.overworld();
        String profile = "<unknown>";
        String outsideProfile = "<unknown>";
        String dimension = overworld == null ? Level.OVERWORLD.location().toString() : overworld.dimension().location().toString();
        if (overworld != null) {
            try {
                IDimensionInfo provider = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(overworld);
                if (provider != null) {
                    provider.setWorld(overworld);
                    profile = provider.getProfile() == null ? "<unknown>" : provider.getProfile().getName();
                    outsideProfile = provider.getOutsideProfile() == null ? "<unknown>" : provider.getOutsideProfile().getName();
                }
            } catch (Throwable ignored) {
            }
        }
        return new LifecycleSnapshot(
            Instant.now().toString(),
            label == null ? "<snapshot>" : label,
            WorldGenScope.activeLifecycleId(),
            WorldGenScope.activeDiskScope(),
            worldLabel(server),
            dimension,
            profile,
            outsideProfile,
            featureCache.localEntries(),
            featureCache.quantifiedEntries(),
            planner.pendingTasks(),
            planner.batchCount(),
            multichunk.planned(),
            multichunk.pendingQueue(),
            buildingInfo.cacheSize(),
            buildingInfo.pendingBuildingInfo(),
            shadow.pendingTransactions(),
            shadow.heldTickets(),
            DeferredTreeQueue.pendingCountAll(),
            DeferredTreeQueue.readyCountAll(),
            ChunkPostProcessor.getPendingScanCount(),
            AsyncMultiChunkPlanner.telemetrySummary(),
            AsyncBuildingInfoPlanner.telemetrySummary(),
            ShadowBlockMutationApplier.diagnostics(),
            DeferredTreeQueue.diagnostics(),
            LostCitiesCacheBridge.diagnostics(),
            MultiChunkPlanningCache.diagnostics()
        );
    }

    private static String worldLabel(MinecraftServer server) {
        if (server == null) {
            return "<no-server>";
        }
        try {
            return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().toString();
        } catch (Throwable ignored) {
            return "<unknown-world>";
        }
    }

    private static String safeDimension(ResourceKey<Level> dimension) {
        return dimension == null || dimension.location() == null ? "unknown" : dimension.location().toString();
    }

    private record TransitionEvent(String at, String kind, String detail) {
    }

    public record LifecycleSnapshot(String capturedAt,
                                    String label,
                                    long lifecycleId,
                                    String diskScope,
                                    String worldPath,
                                    String dimension,
                                    String profile,
                                    String outsideProfile,
                                    long featureCacheLocal,
                                    Long featureCacheDistributed,
                                    int plannerPendingTasks,
                                    int plannerBatchCount,
                                    int multichunkPlanned,
                                    int multichunkPending,
                                    int buildingInfoCacheSize,
                                    int buildingInfoPending,
                                    int shadowPendingTransactions,
                                    long shadowHeldTickets,
                                    int pendingTrees,
                                    int readyTrees,
                                    int pendingScans,
                                    String multichunkTelemetry,
                                    String buildingInfoTelemetry,
                                    String shadowDiagnostics,
                                    String treeDiagnostics,
                                    String lostCitiesDiskCache,
                                    String multiChunkPlanCache) {
        public String describe() {
            return String.format(Locale.ROOT,
                "LifecycleSnapshot: at=%s label=%s lifecycle=%d diskScope=%s world=%s dim=%s profile=%s outside=%s featureCache[local=%d distributed=%s] planner[pending=%d batches=%d] multichunk[planned=%d pending=%d] buildingInfo[cache=%d pending=%d] shadow[pendingTx=%d heldTickets=%d] trees[pending=%d ready=%d] postProcess[pendingScans=%d] multichunkTelemetry={%s} buildingInfoTelemetry={%s} shadow={%s} treesDiag={%s} lostCitiesDisk={%s} multiChunkPlanCache={%s}",
                capturedAt,
                label,
                lifecycleId,
                diskScope,
                worldPath,
                dimension,
                profile,
                outsideProfile,
                featureCacheLocal,
                featureCacheDistributed == null ? "<none>" : Long.toString(featureCacheDistributed),
                plannerPendingTasks,
                plannerBatchCount,
                multichunkPlanned,
                multichunkPending,
                buildingInfoCacheSize,
                buildingInfoPending,
                shadowPendingTransactions,
                shadowHeldTickets,
                pendingTrees,
                readyTrees,
                pendingScans,
                multichunkTelemetry,
                buildingInfoTelemetry,
                shadowDiagnostics,
                treeDiagnostics,
                lostCitiesDiskCache,
                multiChunkPlanCache);
        }
    }
}
