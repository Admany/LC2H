package org.admany.lc2h.compat;

import net.minecraftforge.fml.ModList;
import org.admany.lc2h.logging.LCLogger;
import java.util.concurrent.ExecutorService;

public class C2MECompat {

    private static boolean isLoaded = false;
    private static ExecutorService c2meExecutor = null;

    public static void init() {
        try {
            isLoaded = ModList.get().isLoaded("c2me");
            if (isLoaded) {
                LCLogger.info("C2ME detected - integrating with C2ME threading for better performance");
                try {
                    Class<?> globalExecutorsClass = Class.forName("com.ishland.c2me.base.common.GlobalExecutors");
                    c2meExecutor = (ExecutorService) globalExecutorsClass.getField("executor").get(null);
                    LCLogger.info("Successfully integrated with C2ME executor");
                } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
                    LCLogger.warn("C2ME classes not found or accessible: " + e.getMessage() + ". Ensure Sinytra Connector is installed. Falling back to default executor.");
                    c2meExecutor = java.util.concurrent.ForkJoinPool.commonPool();
                } catch (Exception e) {
                    LCLogger.error("Unexpected error integrating with C2ME: " + e.getMessage(), e);
                    c2meExecutor = java.util.concurrent.ForkJoinPool.commonPool();
                }
            } else {
                c2meExecutor = java.util.concurrent.ForkJoinPool.commonPool();
            }
        } catch (Exception e) {
            LCLogger.error("Error initializing C2ME compatibility: " + e.getMessage(), e);
            isLoaded = false;
            c2meExecutor = java.util.concurrent.ForkJoinPool.commonPool();
        }
    }

    public static boolean isCompatible() {
        return true;
    }

    public static ExecutorService getExecutor() {
        return c2meExecutor != null ? c2meExecutor : java.util.concurrent.ForkJoinPool.commonPool();
    }

    public static boolean isC2MELoaded() {
        return isLoaded;
    }
}