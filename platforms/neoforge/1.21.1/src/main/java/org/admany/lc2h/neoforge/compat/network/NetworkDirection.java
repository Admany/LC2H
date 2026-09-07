package org.admany.lc2h.neoforge.compat.network;

import net.neoforged.fml.LogicalSide;

/** Direction values retained for the shared debug packet declarations. */
public enum NetworkDirection {
    PLAY_TO_CLIENT(LogicalSide.CLIENT),
    PLAY_TO_SERVER(LogicalSide.SERVER);

    private final LogicalSide receptionSide;

    NetworkDirection(LogicalSide receptionSide) {
        this.receptionSide = receptionSide;
    }

    public LogicalSide getReceptionSide() {
        return receptionSide;
    }
}
