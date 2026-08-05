package org.admany.lc2h.runtime;

import java.util.function.IntSupplier;

public final class Lc2hParityRandoms {
    private static final Object LOST_CITIES_GSEED_LOCK = new Object();

    private Lc2hParityRandoms() {
    }

    public static int withLostCitiesSeedLock(IntSupplier supplier) {
        synchronized (LOST_CITIES_GSEED_LOCK) {
            return supplier.getAsInt();
        }
    }
}
