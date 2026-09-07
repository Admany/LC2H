package org.admany.lc2h.neoforge.compat;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;

/** Small bridge for the common bootstrap code shared with the Forge build. */
public final class FMLJavaModLoadingContext {
    private static final FMLJavaModLoadingContext INSTANCE = new FMLJavaModLoadingContext();

    private FMLJavaModLoadingContext() {
    }

    public static FMLJavaModLoadingContext get() {
        return INSTANCE;
    }

    public IEventBus getModEventBus() {
        return ModLoadingContext.get().getActiveContainer().getEventBus();
    }
}
