package org.admany.lc2h.client;

import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;
import org.admany.lc2h.dev.benchmark.BenchmarkManager;
import org.admany.lc2h.client.gui.Lc2hConfigScreen;

public final class LC2HClient {
    private LC2HClient() {
    }

    @SuppressWarnings("removal")
    public static void init() {
        BenchmarkManager.initClient();
        ModLoadingContext.get().registerExtensionPoint(
            ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new Lc2hConfigScreen(parent))
        );
    }
}
