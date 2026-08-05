import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small source-file-mode utility for comparing package-owned JFR stacks.
 * Usage: java tools/JfrStackSummary.java recording.jfr [start] [end]
 */
public final class JfrStackSummary {
    private static final String[] OWNED_PREFIXES = {
        "org.admany.lc2h.",
        "mcjty.lostcities."
    };

    private JfrStackSummary() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3) {
            throw new IllegalArgumentException("Expected recording.jfr [startInstant] [endInstant]");
        }
        Instant start = args.length >= 2 ? Instant.parse(args[1]) : Instant.MIN;
        Instant end = args.length >= 3 ? Instant.parse(args[2]) : Instant.MAX;
        Map<String, Long> firstOwned = new HashMap<>();
        Map<String, Long> ownedFrames = new HashMap<>();
        Map<String, Long> leafMethods = new HashMap<>();
        Map<String, Long> stackHeads = new HashMap<>();
        Map<String, Long> threads = new HashMap<>();
        long executionSamples = 0L;
        long ownedSamples = 0L;

        try (RecordingFile recording = new RecordingFile(Path.of(args[0]))) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                if (!"jdk.ExecutionSample".equals(event.getEventType().getName())) {
                    continue;
                }
                Instant timestamp = event.getStartTime();
                if (timestamp.isBefore(start) || timestamp.isAfter(end)) {
                    continue;
                }
                executionSamples++;
                if (event.getStackTrace() == null) {
                    continue;
                }
                List<RecordedFrame> frames = event.getStackTrace().getFrames();
                ArrayList<String> names = new ArrayList<>(frames.size());
                boolean owned = false;
                for (RecordedFrame frame : frames) {
                    String name = methodName(frame.getMethod());
                    names.add(name);
                    if (isOwned(name)) {
                        owned = true;
                    }
                }
                if (!owned) {
                    continue;
                }
                ownedSamples++;
                if (!names.isEmpty()) {
                    increment(leafMethods, names.get(0));
                    increment(stackHeads, String.join(" <- ", names.subList(0, Math.min(8, names.size()))));
                }
                increment(threads, event.getThread("sampledThread").getJavaName());
                boolean foundFirst = false;
                for (String name : names) {
                    if (isOwned(name)) {
                        increment(ownedFrames, name);
                        if (!foundFirst) {
                            increment(firstOwned, name);
                            foundFirst = true;
                        }
                    }
                }
            }
        }

        System.out.println("executionSamples=" + executionSamples + " ownedSamples=" + ownedSamples);
        print("threads", threads, 16);
        print("leafMethods", leafMethods, 30);
        print("stackHeads", stackHeads, 35);
        print("firstOwnedFrame", firstOwned, 40);
        print("allOwnedFrames", ownedFrames, 60);
    }

    private static String methodName(RecordedMethod method) {
        return method.getType().getName() + "." + method.getName();
    }

    private static boolean isOwned(String name) {
        for (String prefix : OWNED_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void increment(Map<String, Long> counts, String key) {
        counts.merge(key, 1L, Long::sum);
    }

    private static void print(String label, Map<String, Long> counts, int limit) {
        System.out.println(label + ':');
        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
            .limit(limit)
            .forEach(entry -> System.out.println(entry.getValue() + "\t" + entry.getKey()));
    }
}
