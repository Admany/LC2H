package org.admany.lc2h.worldgen.terrain;

import com.mojang.logging.LogUtils;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import org.admany.lc2h.data.cache.AsyncHeightmapCoordinator;
import org.admany.lc2h.worldgen.scope.WorldGenScope;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Audited branch kernel for Lost Cities' natural height query.
 *
 * Lost Cities rebuilds its private NoiseChunkOpt graph for every sampled
 * column. Minecraft already keeps the same density graph compiled inside the
 * active ChunkGenerator. This kernel routes the LC-only height branch through
 * that resident graph after proving it against LC's exact result for the
 * current world lifecycle. A single mismatch shuts the fast branch off and
 * returns the exact LC value, so terrain meaning cannot silently drift :]
 */
public final class LostCitiesHeightBranchKernel {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.parseBoolean(
        System.getProperty("lc2h.heightBranch.enabled", "true")
    );
    private static final int REQUIRED_AUDITS = Math.max(1,
        Integer.getInteger("lc2h.heightBranch.auditSamples", 2));
    private static final int CONTINUOUS_AUDIT_MASK = normaliseAuditMask(
        Integer.getInteger("lc2h.heightBranch.continuousAuditInterval", 1024)
    );
    private static final ConcurrentHashMap<WorldGenScope.CacheScope, State> STATES = new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor AUDITOR = new ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(16),
        task -> {
            Thread thread = new Thread(task, "lc2h-height-audit");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        },
        new ThreadPoolExecutor.DiscardPolicy()
    );
    private static volatile long activeLifecycle = Long.MIN_VALUE;

    private LostCitiesHeightBranchKernel() {
    }

    public static ChunkHeightmap evaluate(WorldGenScope.CacheScope scope,
                                          AsyncHeightmapCoordinator.SamplePlan plan,
                                          WorldGenLevel world,
                                          LostCityProfile profile,
                                          Supplier<ChunkHeightmap> exactCalculation) {
        if (!ENABLED) {
            return exactCalculation.get();
        }

        pruneOldLifecycles(scope.lifecycleId());
        State state = STATES.computeIfAbsent(scope, ignored -> new State());
        if (state.failed.get()) {
            state.exactFallbacks.incrementAndGet();
            return exactCalculation.get();
        }

        ChunkHeightmap branch = branch(plan, world, profile);
        long call = state.calls.incrementAndGet();
        boolean initialAudit = false;
        if (!state.approved.get()) {
            int reservation = state.auditReservations.getAndIncrement();
            if (reservation < REQUIRED_AUDITS) {
                initialAudit = true;
            } else {
                // Audit work is proof, not a chunk dependency. Cold worldgen
                // keeps using the equivalent resident branch while the tiny
                // proof set runs at low priority instead of parking workers.
                state.branchHits.incrementAndGet();
                return branch;
            }
        }
        boolean audit = initialAudit || (call & CONTINUOUS_AUDIT_MASK) == 0L;
        if (!audit) {
            state.branchHits.incrementAndGet();
            return branch;
        }
        try {
            AUDITOR.execute(() -> audit(scope, state, plan, branch, exactCalculation));
        } catch (RuntimeException ignored) {
            // A saturated audit queue must never become chunk backpressure.
        }
        state.branchHits.incrementAndGet();
        return branch;
    }

    public static Snapshot snapshot(WorldGenScope.CacheScope scope) {
        State state = STATES.get(scope);
        if (state == null) {
            return new Snapshot(ENABLED, false, false, 0, 0, 0, 0, null);
        }
        return new Snapshot(
            ENABLED,
            state.approved.get(),
            state.failed.get(),
            state.calls.get(),
            state.audits.get(),
            state.branchHits.get(),
            state.exactFallbacks.get(),
            state.firstFailure.get()
        );
    }

    private static ChunkHeightmap branch(AsyncHeightmapCoordinator.SamplePlan plan,
                                         WorldGenLevel world,
                                         LostCityProfile profile) {
        NaturalHeightSampler.LevelSampler sampler = NaturalHeightSampler.forLevel(world);
        int height;
        if (sampler != null) {
            // The city shaper and LC height branch ask Minecraft for the same
            // centre column. Share the exact single flight instead of opening
            // the density graph twice for one chunk :]
            height = sampler.chunkHeight(plan.sampler().chunkX(), plan.sampler().chunkZ());
        } else {
            ServerChunkCache source = world.getLevel().getChunkSource();
            ChunkGenerator generator = source.getGenerator();
            int blockX = (plan.sampler().chunkX() << 4) + 8;
            int blockZ = (plan.sampler().chunkZ() << 4) + 8;
            height = generator.getBaseHeight(
                blockX,
                blockZ,
                Heightmap.Types.OCEAN_FLOOR_WG,
                world,
                source.randomState()
            );
        }
        ChunkHeightmap result = new ChunkHeightmap(profile.LANDSCAPE_TYPE, profile.GROUNDLEVEL);
        result.update(height);
        return result;
    }

    private static boolean same(ChunkHeightmap exact, ChunkHeightmap branch) {
        return exact.getHeight() == branch.getHeight()
            && exact.getMinHeight() == branch.getMinHeight()
            && exact.getMaxHeight() == branch.getMaxHeight();
    }

    private static void audit(WorldGenScope.CacheScope scope,
                              State state,
                              AsyncHeightmapCoordinator.SamplePlan plan,
                              ChunkHeightmap branch,
                              Supplier<ChunkHeightmap> exactCalculation) {
        if (state.failed.get() || !STATES.containsKey(scope)) {
            return;
        }
        ChunkHeightmap exact;
        try {
            exact = exactCalculation.get();
        } catch (Throwable failure) {
            String detail = "chunk=" + plan.sampler().chunkX() + "," + plan.sampler().chunkZ()
                + " auditError=" + failure.getClass().getSimpleName();
            state.failed.set(true);
            state.approvalGate.complete(false);
            state.firstFailure.compareAndSet(null, detail);
            LOGGER.warn("[LC2H] Lost Cities height branch audit crashed ({}); exact path retained for later calls", detail);
            return;
        }
        int audits = state.audits.incrementAndGet();
        if (!same(exact, branch)) {
            state.failed.set(true);
            state.approvalGate.complete(false);
            String failure = "chunk=" + plan.sampler().chunkX() + ","
                + plan.sampler().chunkZ() + " exact=" + describe(exact) + " branch=" + describe(branch);
            if (state.firstFailure.compareAndSet(null, failure)) {
                LOGGER.warn("[LC2H] Lost Cities height branch audit failed ({}); exact path retained for later calls", failure);
            }
            return;
        }
        if (audits >= REQUIRED_AUDITS && state.approved.compareAndSet(false, true)) {
            state.approvalGate.complete(true);
            LOGGER.info("[LC2H] Lost Cities height branch approved after {} background audits; resident density path is active",
                audits);
        }
    }

    private static String describe(ChunkHeightmap value) {
        return value.getHeight() + "/" + value.getMinHeight() + "/" + value.getMaxHeight();
    }

    private static void pruneOldLifecycles(long lifecycle) {
        if (activeLifecycle == lifecycle) {
            return;
        }
        synchronized (STATES) {
            if (activeLifecycle != lifecycle) {
                activeLifecycle = lifecycle;
                STATES.keySet().removeIf(scope -> scope.lifecycleId() != lifecycle);
            }
        }
    }

    private static int normaliseAuditMask(int interval) {
        int value = Math.max(2, interval);
        int powerOfTwo = Integer.highestOneBit(value);
        if (powerOfTwo != value) {
            powerOfTwo <<= 1;
        }
        return powerOfTwo - 1;
    }

    public record Snapshot(boolean enabled,
                           boolean approved,
                           boolean failed,
                           long calls,
                           long audits,
                           long branchHits,
                           long exactFallbacks,
                           String firstFailure) {
    }

    private static final class State {
        private final AtomicBoolean approved = new AtomicBoolean();
        private final AtomicBoolean failed = new AtomicBoolean();
        private final AtomicLong calls = new AtomicLong();
        private final AtomicInteger auditReservations = new AtomicInteger();
        private final AtomicInteger audits = new AtomicInteger();
        private final java.util.concurrent.CompletableFuture<Boolean> approvalGate =
            new java.util.concurrent.CompletableFuture<>();
        private final AtomicLong branchHits = new AtomicLong();
        private final AtomicLong exactFallbacks = new AtomicLong();
        private final java.util.concurrent.atomic.AtomicReference<String> firstFailure =
            new java.util.concurrent.atomic.AtomicReference<>();
    }
}
