package org.admany.lc2h.neoforge.compat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.LogicalSide;

/** Forge-shaped tick events emitted from the NeoForge pre/post tick events. */
public final class TickEvent {
    public enum Phase {
        START,
        END
    }

    private TickEvent() {
    }

    public static final class ServerTickEvent extends Event {
        public final Phase phase;
        public final LogicalSide side = LogicalSide.SERVER;
        private final MinecraftServer server;

        public ServerTickEvent(MinecraftServer server, Phase phase) {
            this.server = server;
            this.phase = phase;
        }

        public MinecraftServer getServer() {
            return server;
        }
    }

    public static final class ClientTickEvent extends Event {
        public final Phase phase;

        public ClientTickEvent(Phase phase) {
            this.phase = phase;
        }
    }

    public static final class LevelTickEvent extends Event {
        public final Phase phase;
        public final Level level;

        public LevelTickEvent(Level level, Phase phase) {
            this.level = level;
            this.phase = phase;
        }

        public Level getLevel() {
            return level;
        }
    }
}
