package org.admany.lc2h.util.diag;

import org.admany.lc2h.LC2H;

public class PerformanceMonitor {

    private static long startTime;
    private static int tasksCompleted = 0;

    public static void startTask(String taskName) {
        startTime = System.currentTimeMillis();
        LC2H.LOGGER.debug("Started task: " + taskName);
    }

    public static void endTask(String taskName) {
        long duration = System.currentTimeMillis() - startTime;
        tasksCompleted++;
        LC2H.LOGGER.info("Completed task: " + taskName + " in " + duration + "ms. Total tasks: " + tasksCompleted);
    }

    public static void reset() {
        tasksCompleted = 0;
    }
}