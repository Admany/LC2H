package org.admany.lc2h.compat;

import net.minecraftforge.fml.ModList;
import org.admany.lc2h.LC2H;

public class GeneralCompat {

    public static void checkConflicts() {
        if (ModList.get().isLoaded("someconflictingmod")) {
            LC2H.LOGGER.warn("Detected conflicting mod - LC2H may not work properly");
        }
    }

    public static boolean isSafeToRun() {
        return !ModList.get().isLoaded("incompatiblemod");
    }
}