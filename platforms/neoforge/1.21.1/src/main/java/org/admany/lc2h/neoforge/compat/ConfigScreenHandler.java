package org.admany.lc2h.neoforge.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import java.util.function.BiFunction;

/** Adapter for the NeoForge config-screen extension point. */
public final class ConfigScreenHandler {
    private ConfigScreenHandler() {
    }

    public static final class ConfigScreenFactory implements IConfigScreenFactory {
        private final BiFunction<Minecraft, Screen, Screen> factory;

        public ConfigScreenFactory(BiFunction<Minecraft, Screen, Screen> factory) {
            this.factory = factory;
        }

        @Override
        public Screen createScreen(ModContainer container, Screen parent) {
            return factory.apply(Minecraft.getInstance(), parent);
        }
    }
}
