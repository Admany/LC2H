package org.admany.lc2h.neoforge.compat.network;

import net.minecraft.server.level.ServerPlayer;

import java.util.function.Supplier;

/** Target marker retained for the optional Forge debug packet path. */
public final class PacketDistributor {
    public static final PlayerTarget PLAYER = new PlayerTarget();

    private PacketDistributor() {
    }

    public static final class PlayerTarget {
        public Target with(Supplier<ServerPlayer> player) {
            return new Target(player);
        }
    }

    public static final class Target {
        private final Supplier<ServerPlayer> player;

        private Target(Supplier<ServerPlayer> player) {
            this.player = player;
        }

        public ServerPlayer player() {
            return player == null ? null : player.get();
        }
    }
}
