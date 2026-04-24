package org.admany.lc2h.dev.debug;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.config.ProfileSetup;
import mcjty.lostcities.setup.Config;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.data.cache.FeatureCache;
import org.admany.lc2h.util.log.ChatMessenger;
import org.admany.lc2h.worldgen.async.planner.PlannerBatchQueue;
import org.admany.lc2h.worldgen.lostcities.ChunkRoleProbe;
import org.admany.lc2h.worldgen.lostcities.LostCityProfileOverrideManager;

import java.nio.file.Path;
import java.util.List;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public class DebugCommands {
    private static final SuggestionProvider<CommandSourceStack> PROFILE_SUGGESTIONS = (context, builder) -> {
        ensureProfilesInitialized();
        return SharedSuggestionProvider.suggest(LostCityProfileOverrideManager.discoverProfileNames(profileSearchDirs()), builder);
    };

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lc2h")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("cache")
                .executes(context -> {
                    FeatureCache.CacheStats stats = FeatureCache.snapshot();
                    long local = stats.localEntries();
                    Long distributed = stats.quantifiedEntries();
                    long memoryMB = FeatureCache.getMemoryUsageMB();
                    boolean pressure = FeatureCache.isMemoryPressureHigh();

                    Component entries = distributed != null
                        ? Component.translatable("lc2h.dev.cache.entries_total", local + distributed, local, distributed)
                        : Component.translatable("lc2h.dev.cache.entries_local", local);
                    Component pressureLabel = Component.translatable(pressure
                        ? "lc2h.dev.cache.pressure.high"
                        : "lc2h.dev.cache.pressure.normal");
                    Component memoryLine = Component.translatable("lc2h.dev.cache.memory_line", memoryMB, pressureLabel);

                    ChatMessenger.info(context.getSource(), Component.empty().append(entries).append(Component.literal(" | ")).append(memoryLine));
                    return 1;
                }))
            .then(Commands.literal("clearcache")
                .executes(context -> {
                    FeatureCache.CacheStats cleared = FeatureCache.clear();
                    long local = cleared.localEntries();
                    Long distributed = cleared.quantifiedEntries();
                    ChatMessenger.success(context.getSource(), distributed != null
                        ? Component.translatable("lc2h.dev.cache.cleared_with_distributed", local, distributed)
                        : Component.translatable("lc2h.dev.cache.cleared", local));
                    return 1;
                })
                .then(Commands.literal("disk")
                    .executes(context -> {
                        FeatureCache.CacheStats cleared = FeatureCache.clear(true);
                        long local = cleared.localEntries();
                        Long distributed = cleared.quantifiedEntries();
                        ChatMessenger.success(context.getSource(), distributed != null
                            ? Component.translatable("lc2h.dev.cache.cleared_disk_with_distributed", local, distributed)
                            : Component.translatable("lc2h.dev.cache.cleared_disk", local));
                        return 1;
                    })))
            .then(Commands.literal("cleanup")
                .executes(context -> {
                    FeatureCache.triggerMemoryPressureCleanup();
                    ChatMessenger.success(context.getSource(), Component.translatable("lc2h.dev.cache.cleanup_triggered"));
                    return 1;
                }))
            .then(Commands.literal("profile")
                .then(Commands.argument("profile", StringArgumentType.word())
                    .suggests(PROFILE_SUGGESTIONS)
                    .executes(DebugCommands::setProfileOverride))));
    }

    private static int setProfileOverride(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String requestedProfile = StringArgumentType.getString(context, "profile");
        String profileName = resolveProfile(requestedProfile);
        if (profileName == null) {
            ChatMessenger.error(source, "Unknown Lost Cities profile '" + requestedProfile + "'. Put custom profiles in config/lostcities/profiles.");
            return 0;
        }

        ResourceKey<Level> dimension = source.getLevel().dimension();
        LostCityProfileOverrideManager.setOverride(dimension, profileName);
        Config.resetProfileCache();
        clearGenerationCachesForProfileSwitch();

        ChatMessenger.success(source, "Succeeded: Lost Cities profile '" + profileName + "' is now forced for " + dimension.location() + ". Fresh chunks only; existing generated chunks are not overwritten.");
        ChatMessenger.error(source, "Risky: changing profiles mid-world can create seams, terrain mismatches, or broken transitions between old and new chunks. Back up the world first.");
        LC2H.LOGGER.warn("Forced Lost Cities profile '{}' for dimension {} via /lc2h profile. Existing chunks are not rewritten.", profileName, dimension.location());
        return 1;
    }

    private static String resolveProfile(String requestedProfile) {
        if (requestedProfile == null || requestedProfile.isBlank()) {
            return null;
        }
        String profileName = requestedProfile.trim();
        ensureProfilesInitialized();

        if (LostCityProfileOverrideManager.hasKnownProfile(profileName)) {
            return profileName;
        }

        LostCityProfile loaded = LostCityProfileOverrideManager.loadProfileFromDisk(profileName, profileSearchDirs()).orElse(null);
        if (loaded != null) {
            ProfileSetup.STANDARD_PROFILES.put(profileName, loaded);
            return profileName;
        }

        return null;
    }

    private static void ensureProfilesInitialized() {
        if (!ProfileSetup.STANDARD_PROFILES.isEmpty()) {
            return;
        }
        try {
            ProfileSetup.setupProfiles();
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Unable to eagerly initialize Lost Cities profiles for command suggestions: {}", t.getMessage());
        }
    }

    private static List<Path> profileSearchDirs() {
        Path config = FMLPaths.CONFIGDIR.get();
        return List.of(
            config.resolve("lostcities").resolve("profiles"),
            config.resolve("lostcities")
        );
    }

    private static void clearGenerationCachesForProfileSwitch() {
        try {
            FeatureCache.clear();
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Failed to clear LC2H feature cache after profile switch: {}", t.getMessage());
        }
        try {
            ChunkRoleProbe.clear();
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Failed to clear LC2H chunk role cache after profile switch: {}", t.getMessage());
        }
        try {
            PlannerBatchQueue.shutdown();
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Failed to clear LC2H planner batches after profile switch: {}", t.getMessage());
        }
    }
}
