package org.admany.lc2h.dev.debug;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeQueue;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeEventHandler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

final class DeferredTreeSettlePriming implements AutoCloseable {
    private static final TicketType<ChunkPos> LC2H_PARITY_TICKET =
        TicketType.create("lc2h_world_parity", Comparator.comparingLong(ChunkPos::toLong));

    private final ServerLevel level;
    private final String labelPrefix;
    private final int submitPerTick;
    private final int discoverPerWave;
    private final int totalChunkLimit;
    private final Consumer<String> logLine;
    private final LinkedHashSet<Long> interestChunks = new LinkedHashSet<>();
    private final LinkedHashSet<Long> activeRequested = new LinkedHashSet<>();
    private final LinkedHashSet<Long> retained = new LinkedHashSet<>();
    private final ArrayList<ChunkPrimingSession> retainedSessions = new ArrayList<>();
    private final LinkedHashSet<Long> uniqueRequested = new LinkedHashSet<>();
    private ChunkPrimingSession activeSession;
    private int wave;
    private int uniqueChunksRequested;
    private int reacquiredChunks;
    private boolean limitReached;
    private boolean closed;

    DeferredTreeSettlePriming(ServerLevel level,
                              String labelPrefix,
                              int submitPerTick,
                              int discoverPerWave,
                              int totalChunkLimit,
                              Collection<ChunkPos> seedChunks,
                              Consumer<String> logLine) {
        this.level = level;
        this.labelPrefix = labelPrefix;
        this.submitPerTick = Math.max(1, submitPerTick);
        this.discoverPerWave = Math.max(1, discoverPerWave);
        this.totalChunkLimit = Math.max(this.submitPerTick, totalChunkLimit);
        this.logLine = logLine;
        if (seedChunks != null) {
            for (ChunkPos seedChunk : seedChunks) {
                if (seedChunk != null) {
                    interestChunks.add(seedChunk.toLong());
                }
            }
        }
    }

    void step() {
        if (closed || level == null) {
            return;
        }
        if (activeSession != null) {
            try (DeferredTreeEventHandler.ReplaySuppression ignored = DeferredTreeEventHandler.suppressChunkLoadReplay()) {
                boolean done = activeSession.step();
                if (done) {
                    if (activeSession.completionStatus() == ChunkPrimingSession.CompletionStatus.SUCCESS) {
                        retainCompletedSession(activeSession);
                    } else {
                        activeSession.close();
                    }
                    activeRequested.clear();
                    activeSession = null;
                }
            }
        }
        if (activeSession != null) {
            return;
        }

        List<ChunkPos> discovered = DeferredTreeQueue.pendingUnloadedTouchedChunks(
            level,
            Math.max(discoverPerWave * 4, discoverPerWave),
            discoveryExclude(),
            interestChunks
        );
        if (discovered.isEmpty()) {
            return;
        }
        ArrayList<ChunkPos> missing = new ArrayList<>(Math.min(discoverPerWave, discovered.size()));
        ArrayList<String> sample = new ArrayList<>(Math.min(6, missing.size()));
        for (ChunkPos pos : discovered) {
            if (pos == null) {
                continue;
            }
            long chunkKey = pos.toLong();
            boolean seenBefore = uniqueRequested.contains(chunkKey);
            if (!seenBefore && uniqueRequested.size() >= totalChunkLimit) {
                limitReached = true;
                continue;
            }
            if (seenBefore) {
                reacquiredChunks++;
            } else {
                uniqueRequested.add(chunkKey);
                uniqueChunksRequested++;
            }
            activeRequested.add(chunkKey);
            missing.add(pos);
            if (sample.size() < 6) {
                sample.add(pos.x + "," + pos.z);
            }
            if (missing.size() >= discoverPerWave) {
                break;
            }
        }
        if (missing.isEmpty()) {
            if (limitReached) {
                logLine.accept("settle extension limit-reached label=" + labelPrefix
                    + " uniqueRequested=" + uniqueRequested.size()
                    + " retained=" + retained.size()
                    + " limit=" + totalChunkLimit);
            }
            return;
        }
        wave++;
        String label = labelPrefix + " settleExtensionWave=" + wave;
        logLine.accept("settle extension start label=" + label
            + " chunks=" + missing.size()
            + " requestedTotal=" + uniqueRequested.size()
            + " reacquired=" + reacquiredChunks
            + " limit=" + totalChunkLimit
            + " sample=" + sample);
        activeSession = new ChunkPrimingSession(level, label, missing, submitPerTick, logLine, false, false);
    }

    String diagnostics() {
        long retainedUnloaded = countRetainedUnloaded();
        return "waves=" + wave
            + ", requested=" + uniqueChunksRequested
            + ", uniqueRequested=" + uniqueRequested.size()
            + ", retained=" + retained.size()
            + ", retainedUnloaded=" + retainedUnloaded
            + ", interest=" + interestChunks.size()
            + ", reacquired=" + reacquiredChunks
            + ", activeRequested=" + activeRequested.size()
            + ", active=" + (activeSession != null)
            + ", limitReached=" + limitReached;
    }

    boolean hasActiveSession() {
        return activeSession != null;
    }

    int relevantPendingTrees() {
        return DeferredTreeQueue.pendingCount(level, interestChunks);
    }

    int relevantReadyTrees() {
        return DeferredTreeQueue.readyCount(level, interestChunks);
    }

    List<String> relevantPendingDetails(int limit) {
        return DeferredTreeQueue.pendingDetails(level, limit, interestChunks);
    }

    Set<Long> interestChunkKeys() {
        return Collections.unmodifiableSet(interestChunks);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (activeSession != null) {
            activeSession.close();
            activeSession = null;
        }
        for (ChunkPrimingSession retainedSession : retainedSessions) {
            if (retainedSession == null) {
                continue;
            }
            retainedSession.close();
        }
        retainedSessions.clear();
        for (Long chunkKey : retained) {
            if (chunkKey == null) {
                continue;
            }
            try {
                ChunkPos pos = new ChunkPos(chunkKey);
                level.getChunkSource().removeRegionTicket(LC2H_PARITY_TICKET, pos, 2, pos, true);
            } catch (Throwable ignored) {
            }
        }
        activeRequested.clear();
        retained.clear();
        interestChunks.clear();
        uniqueRequested.clear();
    }

    private LinkedHashSet<Long> discoveryExclude() {
        LinkedHashSet<Long> exclude = new LinkedHashSet<>(retained);
        exclude.addAll(activeRequested);
        return exclude;
    }

    private void retainCompletedSession(ChunkPrimingSession session) {
        if (session == null) {
            return;
        }
        retainedSessions.add(session);
        List<ChunkPos> chunks = session.requestedChunks();
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (ChunkPos pos : chunks) {
            if (pos == null) {
                continue;
            }
            long chunkKey = pos.toLong();
            retained.add(chunkKey);
            interestChunks.add(chunkKey);
        }
    }

    private long countRetainedUnloaded() {
        long unloaded = 0L;
        for (Long chunkKey : retained) {
            if (chunkKey == null) {
                continue;
            }
            ChunkPos pos = new ChunkPos(chunkKey);
            if (level.getChunkSource().getChunkNow(pos.x, pos.z) == null) {
                unloaded++;
            }
        }
        return unloaded;
    }
}
