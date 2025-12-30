package org.admany.lc2h.core;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.compat.C2MECompat;
import org.admany.lc2h.compat.DHCompat;
import org.admany.lc2h.compat.GeneralCompat;

@Mod.EventBusSubscriber(modid = LC2H.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModSetup {

    @SubscribeEvent
    public static void commonSetup(FMLCommonSetupEvent event) {
        LC2H.LOGGER.info("Setting up LC2H core systems");

        C2MECompat.init();
        DHCompat.init();
        GeneralCompat.checkConflicts();

        MinecraftForge.EVENT_BUS.register(new WorldGenHandler());

        LC2H.LOGGER.info("LC2H setup complete");
    }
}
