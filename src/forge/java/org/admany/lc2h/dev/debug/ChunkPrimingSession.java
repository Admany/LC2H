package org.admany.lc2h.dev.debug;

import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.mixin.accessor.minecraft.ServerChunkCacheInvoker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

final class ChunkPrimingSession implements AutoCloseable {
    private static final TicketType<ChunkPos> LC2H_PARITY_TICKET =
        TicketType.create("lc2h_world_parity", Comparator.comparingLong(ChunkPos::toLong));
    private static final long PRIME_IDLE_TIMEOUT_MS = Math.max(10_000L, longProperty(
        "lc2h.worldparity.primeTimeoutMs",
        "lc2h.parity.primeTimeoutMs",
        90_000L
    ));
    private static final long PRIME_HARD_TIMEOUT_MS = Math.max(PRIME_IDLE_TIMEOUT_MS, longProperty(
        "lc2h.worldparity.primeHardTimeoutMs",
        "lc2h.parity.primeHardTimeoutMs",
        Math.max(180_000L, PRIME_IDLE_TIMEOUT_MS * 3L)
    ));
    private static final long FUTURE_RETRY_INTERVAL_MS = Math.max(50L, longProperty(
        "lc2h.worldparity.primeRetryMs",
        "lc2h.parity.primeRetryMs",
        250L
    ));
    private static final int MAX_IN_FLIGHT_REQUESTS = Math.max(32, Integer.getInteger(
        "lc2h.worldparity.maxInFlightChunkRequests",
        Integer.getInteger("lc2h.parity.maxInFlightChunkRequests", 192)
    ));
    private static final int OBSERVED_APPEND_PER_TICK = Math.max(1, Integer.getInteger("lc2h.worldparity.primeObservedAppendPerTick", 128));
    private static final int OBSERVED_TOTAL_LIMIT = Math.max(OBSERVED_APPEND_PER_TICK, Integer.getInteger("lc2h.worldparity.primeObservedChunkLimit", 4096));
    private static final int OBSERVED_MARGIN_CHUNKS = Math.max(0, Integer.getInteger("lc2h.worldparity.primeObservedMarginChunks", 0));
    private static final boolean EXPLICIT_PARITY_TICKETS = Boolean.parseBoolean(
        System.getProperty("lc2h.worldparity.explicitTickets", "false")
    );

    private final ServerLevel level;
    private final String label;
    private final int submitPerTick;
    private final Consumer<String> logLine;
    private final boolean releaseTicketsOnSuccess;
    private final boolean holdPrimeRuntime;
    private final WorldParityPrimeGate.Scope primeGateScope;
    private final org.admany.lc2h.worldgen.lostcities.DeferredTreeEventHandler.ReplaySuppression replaySuppression;
    private final ArrayList<ChunkPos> queue;
    private final LinkedHashMap<Long, PendingChunk> pending = new LinkedHashMap<>();
    private final LinkedHashMap<Long, PendingChunk> allChunks = new LinkedHashMap<>();
    private final ArrayList<ChunkPos> submitted = new ArrayList<>();
    private final LinkedHashSet<Long> knownChunks = new LinkedHashSet<>();
    private final long startedAtMs = System.currentTimeMillis();
    private long lastProgressAtMs = startedAtMs;
    private int submitIndex;
    private int completed;
    private int failed;
    private int timedOut;
    private int observedExtensions;
    private boolean started;
    private volatile boolean closed;
    private boolean primeRuntimeReleased;
    private CompletionStatus completionStatus = CompletionStatus.RUNNING;
    private final int observedMinChunkX;
    private final int observedMaxChunkX;
    private final int observedMinChunkZ;
    private final int observedMaxChunkZ;

    ChunkPrimingSession(ServerLevel level, String label, List<ChunkPos> queue, int submitPerTick, Consumer<String> logLine) {
        this(level, label, queue, submitPerTick, logLine, true, true);
    }

    ChunkPrimingSession(ServerLevel level,
                        String label,
                        List<ChunkPos> queue,
                        int submitPerTick,
                        Consumer<String> logLine,
                        boolean releaseTicketsOnSuccess) {
        this(level, label, queue, submitPerTick, logLine, releaseTicketsOnSuccess, true);
    }

    ChunkPrimingSession(ServerLevel level,
                        String label,
                        List<ChunkPos> queue,
                        int submitPerTick,
                        Consumer<String> logLine,
                        boolean releaseTicketsOnSuccess,
                        boolean holdPrimeRuntime) {
        this.level = level;
        this.label = label;
        this.queue = new ArrayList<>(queue);
        for (ChunkPos pos : this.queue) {
            if (pos != null) {
                knownChunks.add(pos.toLong());
            }
        }
        this.submitPerTick = Math.max(1, submitPerTick);
        this.logLine = logLine;
        this.releaseTicketsOnSuccess = releaseTicketsOnSuccess;
        this.holdPrimeRuntime = holdPrimeRuntime;
        this.primeGateScope = holdPrimeRuntime ? WorldParityPrimeGate.hold() : WorldParityPrimeGate.Scope.noop();
        this.replaySuppression = holdPrimeRuntime
            ? org.admany.lc2h.worldgen.lostcities.DeferredTreeEventHandler.holdReplaySuppression()
            : org.admany.lc2h.worldgen.lostcities.DeferredTreeEventHandler.suppressChunkLoadReplay();
        int minChunkX = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;
        for (ChunkPos pos : this.queue) {
            if (pos == null) {
                continue;
            }
            minChunkX = Math.min(minChunkX, pos.x);
            maxChunkX = Math.max(maxChunkX, pos.x);
            minChunkZ = Math.min(minChunkZ, pos.z);
            maxChunkZ = Math.max(maxChunkZ, pos.z);
        }
        if (minChunkX == Integer.MAX_VALUE) {
            minChunkX = 0;
            maxChunkX = -1;
            minChunkZ = 0;
            maxChunkZ = -1;
        }
        this.observedMinChunkX = minChunkX - OBSERVED_MARGIN_CHUNKS;
        this.observedMaxChunkX = maxChunkX + OBSERVED_MARGIN_CHUNKS;
        this.observedMinChunkZ = minChunkZ - OBSERVED_MARGIN_CHUNKS;
        this.observedMaxChunkZ = maxChunkZ + OBSERVED_MARGIN_CHUNKS;
        WorldParityObservedChunkTracker.activate(
            level.dimension(),
            observedMinChunkX,
            observedMaxChunkX,
            observedMinChunkZ,
            observedMaxChunkZ
        );
    }

    boolean step() {
        if (closed) {
            return true;
        }
        if (!started) {
            started = true;
            emit("prime start " + label
                + " chunkCount=" + queue.size()
                + " submitPerTick=" + submitPerTick
                + " maxInFlight=" + MAX_IN_FLIGHT_REQUESTS
                + " idleTimeoutMs=" + PRIME_IDLE_TIMEOUT_MS
                + " hardTimeoutMs=" + PRIME_HARD_TIMEOUT_MS);
        }

        appendObservedChunks();

        int submittedThisTick = 0;
        while (submittedThisTick < submitPerTick
            && submitIndex < queue.size()
            && pending.size() < MAX_IN_FLIGHT_REQUESTS) {
            ChunkPos pos = queue.get(submitIndex++);
            request(pos);
            submittedThisTick++;
        }

        List<Long> done = new ArrayList<>();
        for (Map.Entry<Long, PendingChunk> entry : pending.entrySet()) {
            PendingChunk pendingChunk = entry.getValue();
            if (pendingChunk.hardFailed()) {
                done.add(entry.getKey());
                failed++;
                continue;
            }
            pendingChunk.observeProgress(level);
            if (pendingChunk.shouldRetryFuture()) {
                refreshRequest(pendingChunk);
                pendingChunk.observeProgress(level);
            }
            if (!pendingChunk.isReadyObserved()) {
                continue;
            }
            done.add(entry.getKey());
            completed++;
            lastProgressAtMs = System.currentTimeMillis();
        }
        for (Long key : done) {
            pending.remove(key);
        }

        if (submitIndex >= queue.size() && pending.isEmpty()) {
            completionStatus = failed > 0 ? CompletionStatus.FAILED : CompletionStatus.SUCCESS;
            emit("prime done " + label + " submitted=" + submitted.size()
                + " completed=" + completed
                + " failed=" + failed
                + " timedOut=" + timedOut
                + " observedExtensions=" + observedExtensions
                + " tickets=" + ticketSummary()
                + " futures=" + futureSummary()
                + " statuses=" + loadedStatusSummary()
                + " timeline=" + timelineSummary()
                + " pendingDetails=[]"
                + " failureDetails=" + failureSummary());
            if (releaseTicketsOnSuccess) {
                close();
                emit("prime released " + label
                    + " tickets=" + ticketSummary()
                    + " futures=" + futureSummary()
                    + " statuses=" + loadedStatusSummary()
                    + " timeline=" + timelineSummary()
                    + " failureDetails=" + failureSummary());
            } else {
                pending.clear();
                releasePrimeRuntime();
                emit("prime retained " + label
                    + " tickets=" + ticketSummary()
                    + " futures=" + futureSummary()
                    + " statuses=" + loadedStatusSummary()
                    + " timeline=" + timelineSummary()
                    + " failureDetails=" + failureSummary());
            }
            return true;
        }

        long now = System.currentTimeMillis();
        long ageMs = now - startedAtMs;
        long idleMs = now - lastProgressAtMs;
        if (idleMs >= PRIME_IDLE_TIMEOUT_MS || ageMs >= PRIME_HARD_TIMEOUT_MS) {
            timedOut = pending.size();
            completionStatus = CompletionStatus.TIMED_OUT;
            emit(timeoutLine(now, ageMs, idleMs));
            close();
            emit("prime released-after-timeout " + label
                + " tickets=" + ticketSummary()
                + " futures=" + futureSummary()
                + " statuses=" + loadedStatusSummary()
                + " timeline=" + timelineSummary());
            return true;
        }
        return false;
    }

    boolean completedSuccessfully() {
        return completionStatus == CompletionStatus.SUCCESS;
    }

    boolean timedOut() {
        return completionStatus == CompletionStatus.TIMED_OUT;
    }

    CompletionStatus completionStatus() {
        return completionStatus;
    }

    List<ChunkPos> requestedChunks() {
        return List.copyOf(submitted);
    }

    private void request(ChunkPos pos) {
        submitted.add(pos);
        PendingChunk pendingChunk = new PendingChunk(pos, System.currentTimeMillis());
        pending.put(pos.toLong(), pendingChunk);
        allChunks.put(pos.toLong(), pendingChunk);
        queueFutureRequest(pendingChunk);
    }

    private void refreshRequest(PendingChunk pendingChunk) {
        queueFutureRequest(pendingChunk);
    }

    private void queueFutureRequest(PendingChunk pendingChunk) {
        try {
            ChunkPos pos = pendingChunk.pos();
            ServerChunkCacheInvoker cache = (ServerChunkCacheInvoker) (Object) level.getChunkSource();
            if (EXPLICIT_PARITY_TICKETS && pendingChunk.queueTicketAdd()) {
                // DistanceManager is owned by the server thread.  Queue the
                // optional diagnostic ticket there rather than mutating it
                // from the request worker.
                level.getServer().execute(() -> {
                    if (!closed && !pendingChunk.ticketAdded()) {
                        try {
                            level.getChunkSource().addRegionTicket(LC2H_PARITY_TICKET, pos, 2, pos, true);
                            pendingChunk.markTicketAdded();
                        } catch (Throwable t) {
                            pendingChunk.markTicketAcquireFailed();
                        }
                    }
                });
            }

            // Queue the private vanilla graph mutation on the server thread,
            // but never wait for it there.  Calling this method directly from
            // a worker corrupts DistanceManager, while the public wrapper can
            // enter Minecraft's managed blocking path on the server thread.
            CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future = new CompletableFuture<>();
            pendingChunk.markRequestQueued(future);
            level.getServer().execute(() -> {
                if (closed) {
                    future.cancel(false);
                    return;
                }
                try {
                    CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> vanillaFuture =
                        cache.lc2h$getChunkFutureMainThread(pos.x, pos.z, ChunkStatus.FULL, true);
                    vanillaFuture.whenComplete((result, failure) -> {
                        if (failure != null) {
                            future.completeExceptionally(failure);
                        } else {
                            future.complete(result);
                        }
                    });
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            pendingChunk.markFailed("request_error:" + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
    }

    private String timeoutLine(long now, long ageMs, long idleMs) {
        ArrayList<String> details = new ArrayList<>();
        int shown = 0;
        for (PendingChunk pendingChunk : pending.values()) {
            if (shown++ >= 6) {
                break;
            }
            details.add(pendingChunk.pos().x + "," + pendingChunk.pos().z
                + "@waitMs=" + (now - pendingChunk.requestedAtMs())
                + "/ticket=" + pendingChunk.ticketState()
                + "/future=" + pendingChunk.futureState()
                + "/status=" + pendingChunk.loadedStatus());
        }
        return "prime timeout " + label
            + " submitted=" + submitted.size()
            + " completed=" + completed
            + " failed=" + failed
            + " timedOut=" + timedOut
            + " pending=" + pending.size()
            + " observedExtensions=" + observedExtensions
            + " tickets=" + ticketSummary()
            + " futures=" + futureSummary()
            + " statuses=" + loadedStatusSummary()
            + " timeline=" + timelineSummary()
            + " ageMs=" + ageMs
            + " idleMs=" + idleMs
            + " idleTimeoutMs=" + PRIME_IDLE_TIMEOUT_MS
            + " hardTimeoutMs=" + PRIME_HARD_TIMEOUT_MS
            + " thread=" + Thread.currentThread().getName()
            + " details=" + details
            + " failureDetails=" + failureSummary();
    }

    private void appendObservedChunks() {
        if (knownChunks.size() >= OBSERVED_TOTAL_LIMIT) {
            return;
        }
        int remainingCapacity = OBSERVED_TOTAL_LIMIT - knownChunks.size();
        List<ChunkPos> observed = WorldParityObservedChunkTracker.drain(
            level.dimension(),
            Math.min(OBSERVED_APPEND_PER_TICK, remainingCapacity),
            knownChunks
        );
        if (observed.isEmpty()) {
            return;
        }
        ArrayList<String> sample = new ArrayList<>(Math.min(6, observed.size()));
        int appended = 0;
        for (ChunkPos pos : observed) {
            if (pos == null) {
                continue;
            }
            long chunkKey = pos.toLong();
            if (!knownChunks.add(chunkKey)) {
                continue;
            }
            queue.add(pos);
            observedExtensions++;
            appended++;
            lastProgressAtMs = System.currentTimeMillis();
            if (sample.size() < 6) {
                sample.add(pos.x + "," + pos.z);
            }
        }
        if (appended > 0) {
            emit("prime observed-extension " + label
                + " appended=" + appended
                + " queueSize=" + queue.size()
                + " knownChunks=" + knownChunks.size()
                + " sample=" + sample);
        }
    }

    private String ticketSummary() {
        int ticketed = 0;
        int released = 0;
        int acquireFailed = 0;
        for (PendingChunk chunk : allChunks.values()) {
            if (chunk.ticketAdded()) {
                ticketed++;
            }
            if (chunk.ticketReleased()) {
                released++;
            }
            if (chunk.ticketAcquireFailed()) {
                acquireFailed++;
            }
        }
        return "requested=" + submitted.size()
            + "/held=" + ticketed
            + "/released=" + released
            + "/acquireFail=" + acquireFailed;
    }

    private String futureSummary() {
        int ready = 0;
        int queued = 0;
        int visible = 0;
        int unloaded = 0;
        int pendingCount = 0;
        int hardFail = 0;
        for (PendingChunk chunk : allChunks.values()) {
            switch (chunk.futureKind()) {
                case READY -> ready++;
                case QUEUED -> queued++;
                case VISIBLE -> visible++;
                case UNLOADED -> unloaded++;
                case PENDING -> pendingCount++;
                case HARD_FAILED -> hardFail++;
            }
        }
        return "ready=" + ready
            + "/queued=" + queued
            + "/visible=" + visible
            + "/unloaded=" + unloaded
            + "/pending=" + pendingCount
            + "/hardFail=" + hardFail;
    }

    private String loadedStatusSummary() {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        int missing = 0;
        for (PendingChunk pendingChunk : allChunks.values()) {
            pendingChunk.observeProgress(level);
            if (pendingChunk.loadedStatus().equals("missing")) {
                missing++;
                continue;
            }
            counts.merge(pendingChunk.loadedStatus(), 1, Integer::sum);
        }
        return "missing=" + missing + ", loaded=" + counts;
    }

    private String timelineSummary() {
        long firstTicketAt = Long.MAX_VALUE;
        long lastTicketAt = 0L;
        long firstReleaseAt = Long.MAX_VALUE;
        long lastReleaseAt = 0L;
        long firstReadyAt = Long.MAX_VALUE;
        long lastReadyAt = 0L;
        long firstFailureAt = Long.MAX_VALUE;
        long lastFailureAt = 0L;
        for (PendingChunk chunk : allChunks.values()) {
            if (chunk.ticketAddedAtMs() > 0L) {
                firstTicketAt = Math.min(firstTicketAt, chunk.ticketAddedAtMs());
                lastTicketAt = Math.max(lastTicketAt, chunk.ticketAddedAtMs());
            }
            if (chunk.ticketReleasedAtMs() > 0L) {
                firstReleaseAt = Math.min(firstReleaseAt, chunk.ticketReleasedAtMs());
                lastReleaseAt = Math.max(lastReleaseAt, chunk.ticketReleasedAtMs());
            }
            if (chunk.futureKind() == FutureKind.READY && chunk.futureCompletedAtMs() > 0L) {
                firstReadyAt = Math.min(firstReadyAt, chunk.futureCompletedAtMs());
                lastReadyAt = Math.max(lastReadyAt, chunk.futureCompletedAtMs());
            }
            if ((chunk.futureKind() == FutureKind.VISIBLE || chunk.futureKind() == FutureKind.QUEUED) && chunk.futureCompletedAtMs() > 0L) {
                firstReadyAt = Math.min(firstReadyAt, chunk.futureCompletedAtMs());
                lastReadyAt = Math.max(lastReadyAt, chunk.futureCompletedAtMs());
            }
            if (chunk.futureKind() == FutureKind.HARD_FAILED && chunk.futureCompletedAtMs() > 0L) {
                firstFailureAt = Math.min(firstFailureAt, chunk.futureCompletedAtMs());
                lastFailureAt = Math.max(lastFailureAt, chunk.futureCompletedAtMs());
            }
        }
        return "ticketAdd=" + summarizeRange(firstTicketAt, lastTicketAt)
            + "/ticketRelease=" + summarizeRange(firstReleaseAt, lastReleaseAt)
            + "/futureReady=" + summarizeRange(firstReadyAt, lastReadyAt)
            + "/futureFail=" + summarizeRange(firstFailureAt, lastFailureAt);
    }

    private String failureSummary() {
        ArrayList<String> failures = new ArrayList<>();
        int shown = 0;
        for (PendingChunk chunk : allChunks.values()) {
            if (!chunk.hardFailed()) {
                continue;
            }
            if (shown++ >= 8) {
                failures.add("...+" + Math.max(0, failed - 8));
                break;
            }
            failures.add(chunk.pos().x + "," + chunk.pos().z
                + "@ticket=" + chunk.ticketState()
                + "/future=" + chunk.futureState()
                + "/status=" + chunk.loadedStatus()
                + "/ageMs=" + Math.max(0L, System.currentTimeMillis() - chunk.requestedAtMs()));
        }
        return failures.toString();
    }

    private static String summarizeRange(long firstAt, long lastAt) {
        if (firstAt == Long.MAX_VALUE || lastAt <= 0L) {
            return "<none>";
        }
        return firstAt + ".." + lastAt + "(+" + Math.max(0L, lastAt - firstAt) + "ms)";
    }

    private void emit(String line) {
        logLine.accept(line);
        LC2H.LOGGER.info("[LC2H] {}", line);
    }

    private static long longProperty(String primaryKey, String fallbackKey, long defaultValue) {
        String primary = System.getProperty(primaryKey);
        if (primary != null) {
            try {
                return Long.parseLong(primary);
            } catch (NumberFormatException ignored) {
            }
        }
        return Long.getLong(fallbackKey, defaultValue);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        WorldParityObservedChunkTracker.deactivate(level.dimension());
        for (ChunkPos pos : submitted) {
            try {
                PendingChunk pendingChunk = allChunks.get(pos.toLong());
                if (pendingChunk != null && pendingChunk.ticketAdded()) {
                    level.getChunkSource().removeRegionTicket(LC2H_PARITY_TICKET, pos, 2, pos, true);
                    pendingChunk.markTicketReleased();
                }
            } catch (Throwable ignored) {
            }
        }
        pending.clear();
        releasePrimeRuntime();
    }

    private void releasePrimeRuntime() {
        if (primeRuntimeReleased) {
            return;
        }
        primeRuntimeReleased = true;
        WorldParityObservedChunkTracker.deactivate(level.dimension());
        replaySuppression.close();
        primeGateScope.close();
    }

    private enum FutureKind {
        PENDING,
        QUEUED,
        VISIBLE,
        READY,
        UNLOADED,
        HARD_FAILED
    }

    enum CompletionStatus {
        RUNNING,
        SUCCESS,
        TIMED_OUT,
        FAILED
    }

    private static final class PendingChunk {
        private final ChunkPos pos;
        private final long requestedAtMs;
        private volatile String futureState = "scheduled";
        private volatile long futureCompletedAtMs;
        private volatile String failureReason;
        private volatile FutureKind futureKind = FutureKind.PENDING;
        private volatile long ticketAddedAtMs;
        private volatile long ticketReleasedAtMs;
        private volatile boolean ticketAcquireFailed;
        private volatile boolean ticketAddQueued;
        private volatile long firstVisibleAtMs;
        private volatile long readyObservedAtMs;
        private volatile String loadedStatus = "missing";
        private volatile CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future;
        private volatile long lastFutureRequestAtMs;
        private volatile int futureRequestCount;

        private PendingChunk(ChunkPos pos, long requestedAtMs) {
            this.pos = pos;
            this.requestedAtMs = requestedAtMs;
        }

        private ChunkPos pos() {
            return pos;
        }

        private long requestedAtMs() {
            return requestedAtMs;
        }

        private String futureState() {
            return futureState;
        }

        private long futureCompletedAtMs() {
            return futureCompletedAtMs;
        }

        private FutureKind futureKind() {
            return futureKind;
        }

        private long ticketAddedAtMs() {
            return ticketAddedAtMs;
        }

        private long ticketReleasedAtMs() {
            return ticketReleasedAtMs;
        }

        private boolean isReadyObserved() {
            return readyObservedAtMs > 0L;
        }

        private String loadedStatus() {
            return loadedStatus;
        }

        private boolean hardFailed() {
            return failureReason != null;
        }

        private void markRequestQueued(CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future) {
            this.future = future;
            lastFutureRequestAtMs = System.currentTimeMillis();
            futureRequestCount++;
            futureKind = FutureKind.QUEUED;
            futureState = "queued#" + futureRequestCount;
        }

        private void observeProgress(ServerLevel level) {
            if (futureKind == FutureKind.HARD_FAILED) {
                return;
            }
            CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> currentFuture = future;
            if (currentFuture != null && currentFuture.isDone()) {
                Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> result = currentFuture.getNow(null);
                if (result != null) {
                    if (result.left().isPresent()) {
                        ChunkAccess resolved = result.left().orElse(null);
                        if (resolved != null) {
                            loadedStatus = resolved.getStatus() == null ? "loaded" : String.valueOf(resolved.getStatus());
                            if (firstVisibleAtMs == 0L) {
                                firstVisibleAtMs = System.currentTimeMillis();
                            }
                            if (resolved.getStatus() != null && resolved.getStatus().isOrAfter(ChunkStatus.FULL)) {
                                if (readyObservedAtMs == 0L) {
                                    readyObservedAtMs = System.currentTimeMillis();
                                }
                                futureKind = FutureKind.READY;
                                futureCompletedAtMs = readyObservedAtMs;
                                futureState = "ready@" + readyObservedAtMs;
                            } else {
                                futureKind = FutureKind.VISIBLE;
                                futureCompletedAtMs = firstVisibleAtMs;
                                futureState = "visible@" + firstVisibleAtMs + ":" + loadedStatus;
                            }
                        }
                    } else {
                        futureKind = FutureKind.UNLOADED;
                        futureCompletedAtMs = System.currentTimeMillis();
                        String error = result.right().map(ChunkHolder.ChunkLoadingFailure::toString).orElse("<unknown>");
                        futureState = "unloaded#" + futureRequestCount + ":" + (error == null ? "<unknown>" : error);
                    }
                }
            }
            ChunkAccess loaded = null;
            try {
                loaded = level.getChunkSource().getChunkNow(pos.x, pos.z);
            } catch (Throwable ignored) {
            }
            if (loaded != null && loaded.getStatus() != null) {
                loadedStatus = String.valueOf(loaded.getStatus());
                if (loaded.getStatus().isOrAfter(ChunkStatus.FULL)) {
                    if (readyObservedAtMs == 0L) {
                        readyObservedAtMs = System.currentTimeMillis();
                    }
                    futureKind = FutureKind.READY;
                    futureCompletedAtMs = readyObservedAtMs;
                    futureState = "ready@" + readyObservedAtMs;
                    return;
                }
                if (firstVisibleAtMs == 0L) {
                    firstVisibleAtMs = System.currentTimeMillis();
                }
                futureKind = FutureKind.VISIBLE;
                if (futureCompletedAtMs == 0L) {
                    futureCompletedAtMs = firstVisibleAtMs;
                }
                futureState = "visible@" + firstVisibleAtMs + ":" + loadedStatus;
                return;
            }
            loadedStatus = "missing";
            if (futureKind == FutureKind.PENDING) {
                futureState = "scheduled";
            } else if (futureKind == FutureKind.QUEUED) {
                futureState = "queued";
            } else if (futureKind == FutureKind.VISIBLE) {
                futureKind = FutureKind.UNLOADED;
                futureState = "unloaded";
            }
        }

        private void markTicketAdded() {
            ticketAddedAtMs = System.currentTimeMillis();
        }

        private boolean queueTicketAdd() {
            if (ticketAddedAtMs > 0L || ticketAddQueued) {
                return false;
            }
            ticketAddQueued = true;
            return true;
        }

        private void markTicketReleased() {
            ticketReleasedAtMs = System.currentTimeMillis();
        }

        private boolean ticketAdded() {
            return ticketAddedAtMs > 0L;
        }

        private boolean ticketReleased() {
            return ticketReleasedAtMs > 0L;
        }

        private boolean ticketAcquireFailed() {
            return ticketAcquireFailed;
        }

        private void markTicketAcquireFailed() {
            ticketAcquireFailed = true;
        }

        private String ticketState() {
            if (ticketAcquireFailed) {
                return "acquire_failed";
            }
            if (ticketReleasedAtMs > 0L) {
                return "released@" + ticketReleasedAtMs;
            }
            if (ticketAddedAtMs > 0L) {
                return "held@" + ticketAddedAtMs;
            }
            return "not_requested";
        }

        private boolean shouldRetryFuture() {
            if (hardFailed() || readyObservedAtMs > 0L || ticketReleasedAtMs > 0L) {
                return false;
            }
            if (!"missing".equals(loadedStatus)) {
                return false;
            }
            if (futureKind != FutureKind.UNLOADED && futureKind != FutureKind.QUEUED && futureKind != FutureKind.PENDING) {
                return false;
            }
            long now = System.currentTimeMillis();
            return lastFutureRequestAtMs == 0L || (now - lastFutureRequestAtMs) >= FUTURE_RETRY_INTERVAL_MS;
        }

        private void markFailed(String reason) {
            failureReason = reason == null ? "<unknown>" : reason;
            if (reason != null && reason.startsWith("request_error:")) {
                ticketAcquireFailed = true;
            }
            futureKind = FutureKind.HARD_FAILED;
            futureState = "failed:" + failureReason;
            futureCompletedAtMs = System.currentTimeMillis();
        }
    }

}
