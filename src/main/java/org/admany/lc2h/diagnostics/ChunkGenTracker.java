package org.admany.lc2h.diagnostics;

import mcjty.lostcities.api.ILostCityBuilding;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import org.admany.lc2h.mixin.accessor.BuildingInfoAccessor;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class ChunkGenTracker {

    private static final int MAX_EVENTS = Integer.getInteger("lc2h.chunkinfo.events", 6);
    private static final long MAX_AGE_MS = Long.getLong("lc2h.chunkinfo.maxAgeMs", 30L * 60L * 1000L);
    private static final ConcurrentHashMap<ChunkCoord, ChunkGenTrace> TRACE = new ConcurrentHashMap<>();
    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private ChunkGenTracker() {
    }

    public static void recordGenerateStart(ChunkCoord coord) {
        record(coord, "generate-start", null);
    }

    public static void recordGenerateEnd(ChunkCoord coord) {
        record(coord, "generate-end", null);
    }

    public static void recordGenerateSkip(ChunkCoord coord, String reason) {
        record(coord, "generate-skip", reason);
    }

    public static void recordBuildingInfo(BuildingInfo info) {
        if (info == null) {
            return;
        }
        ChunkCoord coord = null;
        String detail = null;
        try {
            BuildingInfoAccessor accessor = (BuildingInfoAccessor) (Object) info;
            coord = accessor.lc2h$getCoord();
            ILostCityBuilding buildingType = accessor.lc2h$getBuildingType();
            LostCityProfile profile = accessor.lc2h$getProfile();
            int floors = accessor.lc2h$getFloors();
            int cellars = accessor.lc2h$getCellars();
            int cityLevel = accessor.lc2h$getCityLevel();
            String buildingName = safeName(buildingType);
            String profileName = safeProfileName(profile);
            detail = "building=" + buildingName
                + " floors=" + floors
                + " cellars=" + cellars
                + " cityLevel=" + cityLevel
                + " profile=" + profileName;
        } catch (Throwable ignored) {
        }
        if (coord != null) {
            record(coord, "building-info", detail);
        }
    }

    public static String buildReport(ChunkCoord coord) {
        if (coord == null) {
            return "LC2H chunkinfo: invalid coord";
        }
        ChunkGenSnapshot snapshot = snapshot(coord);
        if (snapshot == null) {
            return "LC2H chunkinfo: no data for " + coord;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("LC2H chunkinfo for ").append(coord).append('\n');
        sb.append("generate-start=").append(snapshot.generateStartCount)
            .append(" generate-end=").append(snapshot.generateEndCount)
            .append(" generate-skip=").append(snapshot.generateSkipCount)
            .append(" building-info=").append(snapshot.buildingInfoCount)
            .append('\n');
        if (snapshot.lastBuildingDetail != null) {
            sb.append("last building-info: ").append(snapshot.lastBuildingDetail).append('\n');
        }
        if (snapshot.events.isEmpty()) {
            sb.append("events: none");
            return sb.toString();
        }
        sb.append("events (latest ").append(snapshot.events.size()).append("):");
        long now = System.currentTimeMillis();
        for (ChunkGenEvent event : snapshot.events) {
            long ageSec = Math.max(0L, (now - event.timeMs()) / 1000L);
            sb.append('\n')
                .append("- [").append(TIME_FORMAT.format(Instant.ofEpochMilli(event.timeMs()))).append(", ")
                .append(ageSec).append("s ago] ")
                .append(event.event());
            if (event.thread() != null && !event.thread().isBlank()) {
                sb.append(" thread=").append(event.thread());
            }
            if (event.detail() != null && !event.detail().isBlank()) {
                sb.append(" ").append(event.detail());
            }
        }
        return sb.toString();
    }

    private static void record(ChunkCoord coord, String event, String detail) {
        if (coord == null || event == null) {
            return;
        }
        long now = System.currentTimeMillis();
        ChunkGenTrace trace = TRACE.computeIfAbsent(coord, k -> new ChunkGenTrace());
        synchronized (trace) {
            trace.lastUpdateMs = now;
            trace.addEvent(new ChunkGenEvent(now, event, Thread.currentThread().getName(), detail));
        }
        pruneIfExpired(coord, trace, now);
    }

    private static void pruneIfExpired(ChunkCoord coord, ChunkGenTrace trace, long now) {
        if (trace == null || coord == null) {
            return;
        }
        boolean expired;
        synchronized (trace) {
            expired = trace.lastUpdateMs > 0 && (now - trace.lastUpdateMs) > MAX_AGE_MS;
        }
        if (expired) {
            TRACE.remove(coord, trace);
        }
    }

    private static ChunkGenSnapshot snapshot(ChunkCoord coord) {
        ChunkGenTrace trace = TRACE.get(coord);
        if (trace == null) {
            return null;
        }
        synchronized (trace) {
            if (trace.lastUpdateMs > 0 && (System.currentTimeMillis() - trace.lastUpdateMs) > MAX_AGE_MS) {
                TRACE.remove(coord, trace);
                return null;
            }
            return trace.snapshot();
        }
    }

    private static String safeName(ILostCityBuilding buildingType) {
        if (buildingType == null) {
            return "<none>";
        }
        try {
            Method m = buildingType.getClass().getMethod("getName");
            Object v = m.invoke(buildingType);
            if (v != null) {
                return String.valueOf(v);
            }
        } catch (Throwable ignored) {
        }
        return String.valueOf(buildingType);
    }

    private static String safeProfileName(LostCityProfile profile) {
        if (profile == null) {
            return "<none>";
        }
        try {
            return profile.getName();
        } catch (Throwable t) {
            return "<error>";
        }
    }

    private static final class ChunkGenTrace {
        private final ArrayDeque<ChunkGenEvent> events = new ArrayDeque<>();
        private int generateStartCount;
        private int generateEndCount;
        private int generateSkipCount;
        private int buildingInfoCount;
        private String lastBuildingDetail;
        private long lastUpdateMs;

        private void addEvent(ChunkGenEvent event) {
            if ("generate-start".equals(event.event())) {
                generateStartCount++;
            } else if ("generate-end".equals(event.event())) {
                generateEndCount++;
            } else if ("generate-skip".equals(event.event())) {
                generateSkipCount++;
            } else if ("building-info".equals(event.event())) {
                buildingInfoCount++;
                lastBuildingDetail = event.detail();
            }
            events.addFirst(event);
            while (events.size() > MAX_EVENTS) {
                events.removeLast();
            }
        }

        private ChunkGenSnapshot snapshot() {
            List<ChunkGenEvent> copy = new ArrayList<>(events);
            return new ChunkGenSnapshot(
                generateStartCount,
                generateEndCount,
                generateSkipCount,
                buildingInfoCount,
                lastBuildingDetail,
                copy
            );
        }
    }

    private record ChunkGenEvent(long timeMs, String event, String thread, String detail) {
    }

    private record ChunkGenSnapshot(int generateStartCount,
                                    int generateEndCount,
                                    int generateSkipCount,
                                    int buildingInfoCount,
                                    String lastBuildingDetail,
                                    List<ChunkGenEvent> events) {
    }
}
