package org.admany.lc2h.worldgen.seams;

import mcjty.lostcities.varia.ChunkCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.util.chunk.ChunkPostProcessor;
import org.admany.lc2h.worldgen.apply.ChunkShadowMutationPlan;
import org.admany.lc2h.worldgen.apply.ShadowBlockMutationApplier;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public final class SeamOwnershipJournal {

    private static final AtomicBoolean OVERFLOW_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean APPLY_FAIL_LOGGED = new AtomicBoolean(false);

    private static final ConcurrentHashMap<SeamChunkKey, ConcurrentHashMap<Long, SeamWriteIntent>> JOURNAL =
        new ConcurrentHashMap<>();
    private static final Set<SeamChunkKey> ACTIVE_PASSES = ConcurrentHashMap.newKeySet();

    private record GenerationContext(WorldGenRegion region, ResourceLocation dimension, int centerChunkX, int centerChunkZ) {
    }

    private static final ThreadLocal<GenerationContext> CONTEXT = new ThreadLocal<>();

    private static final ThreadLocal<Boolean> APPLYING_INTENTS = new ThreadLocal<>();

    private SeamOwnershipJournal() {
    }

    private static boolean enabled() {
        return ConfigManager.SEAM_OWNERSHIP_ENABLED;
    }

    private static int maxIntentsPerChunk() {
        return Math.max(256, ConfigManager.SEAM_OWNERSHIP_MAX_INTENTS_PER_CHUNK);
    }

    private static long intentTtlMs() {
        return Math.max(30_000L, ConfigManager.SEAM_OWNERSHIP_INTENT_TTL_MS);
    }

    public static void beginLostCityPass(WorldGenRegion region) {
        beginPass(region, region == null ? null : region.getCenter());
    }

    public static void beginBiomeDecorationPass(WorldGenRegion region, int ownerChunkX, int ownerChunkZ) {
        beginPass(region, new net.minecraft.world.level.ChunkPos(ownerChunkX, ownerChunkZ));
    }

    private static void beginPass(WorldGenRegion region, net.minecraft.world.level.ChunkPos ownerChunk) {
        if (!enabled() || region == null) {
            return;
        }
        CONTEXT.remove();
        try {
            ServerLevel level = resolveServerLevel(region);
            if (level == null) {
                CONTEXT.remove();
                return;
            }
            if (ownerChunk == null) {
                CONTEXT.remove();
                return;
            }
            ResourceLocation dim = level.dimension().location();
            ACTIVE_PASSES.add(new SeamChunkKey(dim, ownerChunk.x, ownerChunk.z));
            CONTEXT.set(new GenerationContext(region, dim, ownerChunk.x, ownerChunk.z));
        } catch (Throwable ignored) {
            CONTEXT.remove();
        }
    }

    public static void endLostCityPass(WorldGenRegion region) {
        endPass(region, region == null ? null : region.getCenter());
    }

    public static void endBiomeDecorationPass(WorldGenRegion region, int ownerChunkX, int ownerChunkZ) {
        endPass(region, new net.minecraft.world.level.ChunkPos(ownerChunkX, ownerChunkZ));
    }

    private static void endPass(WorldGenRegion region, net.minecraft.world.level.ChunkPos ownerChunk) {
        if (!enabled() || region == null) {
            CONTEXT.remove();
            return;
        }
        try {
            boolean wasApplying = Boolean.TRUE.equals(APPLYING_INTENTS.get());
            APPLYING_INTENTS.set(Boolean.TRUE);
            try {
                applyForCenterChunk(region);
                applyForRegionWindow(region);
            } finally {
                if (wasApplying) {
                    APPLYING_INTENTS.set(Boolean.TRUE);
                } else {
                    APPLYING_INTENTS.remove();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                ServerLevel level = resolveServerLevel(region);
                if (level != null && ownerChunk != null) {
                    ACTIVE_PASSES.remove(new SeamChunkKey(level.dimension().location(), ownerChunk.x, ownerChunk.z));
                }
            } catch (Throwable ignored) {
            }
            CONTEXT.remove();
        }
    }

    public static boolean deferCrossChunkWrite(WorldGenRegion region, BlockPos pos, net.minecraft.world.level.block.state.BlockState state, int flags) {
        if (!enabled() || region == null || pos == null || state == null) {
            return false;
        }

        if (Boolean.TRUE.equals(APPLYING_INTENTS.get())) {
            return false;
        }

        GenerationContext ctx = CONTEXT.get();
        if (ctx == null) {
            return false;
        }
        if (ctx.region != region) {
            CONTEXT.remove();
            return false;
        }

        int targetChunkX = pos.getX() >> 4;
        int targetChunkZ = pos.getZ() >> 4;
        if (targetChunkX == ctx.centerChunkX && targetChunkZ == ctx.centerChunkZ) {
            return false;
        }

        SeamChunkKey key = new SeamChunkKey(ctx.dimension, targetChunkX, targetChunkZ);
        ConcurrentHashMap<Long, SeamWriteIntent> chunkIntents = JOURNAL.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
        long packed = pos.asLong();
        chunkIntents.put(packed, new SeamWriteIntent(
            pos.immutable(),
            state,
            flags,
            System.currentTimeMillis(),
            ctx.centerChunkX,
            ctx.centerChunkZ
        ));

        int max = maxIntentsPerChunk();
        if (chunkIntents.size() > max) {
            trimChunkJournal(chunkIntents);
            if (OVERFLOW_LOGGED.compareAndSet(false, true)) {
                LC2H.LOGGER.warn("[LC2H] Seam journal overflow for chunk {}. Old intents are trimmed.", key);
            }
        } else if (chunkIntents.size() < max / 2) {
            OVERFLOW_LOGGED.set(false);
        }
        return true;
    }

    private static void trimChunkJournal(ConcurrentHashMap<Long, SeamWriteIntent> chunkIntents) {
        long now = System.currentTimeMillis();
        long ttl = intentTtlMs();
        int max = maxIntentsPerChunk();
        int trimmed = 0;
        for (Map.Entry<Long, SeamWriteIntent> entry : chunkIntents.entrySet()) {
            SeamWriteIntent intent = entry.getValue();
            if (intent == null) {
                chunkIntents.remove(entry.getKey());
                trimmed++;
                continue;
            }
            if ((now - intent.createdAtMs()) > ttl) {
                chunkIntents.remove(entry.getKey(), intent);
                trimmed++;
                continue;
            }
            if (chunkIntents.size() <= max) {
                break;
            }
            chunkIntents.remove(entry.getKey(), intent);
            trimmed++;
            if (trimmed > 512) {
                break;
            }
        }
    }

    private static void applyForCenterChunk(WorldGenRegion region) {
        ServerLevel level = resolveServerLevel(region);
        if (level == null) {
            return;
        }
        ResourceLocation dim = level.dimension().location();
        net.minecraft.world.level.ChunkPos center = region.getCenter();
        SeamChunkKey key = new SeamChunkKey(dim, center.x, center.z);
        applyForChunk(region, key);
    }

    private static void applyForRegionWindow(WorldGenRegion region) {
        if (region == null) {
            return;
        }
        ServerLevel level = resolveServerLevel(region);
        if (level == null) {
            return;
        }
        ResourceLocation dim = level.dimension().location();

        for (SeamChunkKey key : JOURNAL.keySet()) {
            if (key == null || key.dimension() == null || !key.dimension().equals(dim)) {
                continue;
            }
            if (!region.hasChunk(key.chunkX(), key.chunkZ())) {
                continue;
            }
            applyForChunk(region, key);
        }
    }

    private static ServerLevel resolveServerLevel(WorldGenRegion region) {
        if (region == null) {
            return null;
        }
        try {
            java.lang.reflect.Method method = region.getClass().getMethod("getServerLevel");
            Object value = method.invoke(region);
            if (value instanceof ServerLevel level) {
                return level;
            }
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method method = region.getClass().getMethod("getLevel");
            Object value = method.invoke(region);
            if (value instanceof ServerLevel level) {
                return level;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void applyForChunk(WorldGenRegion region, SeamChunkKey key) {
        ConcurrentHashMap<Long, SeamWriteIntent> chunkIntents = JOURNAL.get(key);
        if (chunkIntents == null || chunkIntents.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long ttl = intentTtlMs();
        for (Map.Entry<Long, SeamWriteIntent> entry : chunkIntents.entrySet()) {
            SeamWriteIntent intent = entry.getValue();
            if (intent == null) {
                chunkIntents.remove(entry.getKey());
                continue;
            }
            if ((now - intent.createdAtMs()) > ttl) {
                chunkIntents.remove(entry.getKey(), intent);
                continue;
            }

            BlockPos pos = intent.pos();
            if ((pos.getX() >> 4) != key.chunkX() || (pos.getZ() >> 4) != key.chunkZ()) {
                chunkIntents.remove(entry.getKey(), intent);
                continue;
            }

            boolean applied = false;
            try {
                applied = region.setBlock(pos, intent.state(), intent.flags(), 512);
            } catch (Throwable t) {
                if (APPLY_FAIL_LOGGED.compareAndSet(false, true)) {
                    LC2H.LOGGER.warn("[LC2H] Seam journal apply failed once: {}", t.toString());
                }
            }
            if (applied) {
                runPostApplyHooks(region, pos, intent.state());
                chunkIntents.remove(entry.getKey(), intent);
            }
        }

        if (chunkIntents.isEmpty()) {
            JOURNAL.remove(key, chunkIntents);
            APPLY_FAIL_LOGGED.set(false);
        }
    }

    private static void runPostApplyHooks(WorldGenRegion region, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (region == null || pos == null || state == null) {
            return;
        }
        try {
            if (ChunkPostProcessor.isFloatingCandidate(state)) {
                ChunkPostProcessor.markForRemovalIfFloating(region, pos);
            }
        } catch (Throwable ignored) {
        }
        try {
            ChunkPostProcessor.markTreePlacement(region, pos, state);
        } catch (Throwable ignored) {
        }
    }

    private static void applyForLoadedChunk(ServerLevel level, LevelChunk chunk) {
        if (level == null || chunk == null) {
            return;
        }
        SeamChunkKey key = new SeamChunkKey(level.dimension().location(), chunk.getPos().x, chunk.getPos().z);
        ConcurrentHashMap<Long, SeamWriteIntent> chunkIntents = JOURNAL.get(key);
        if (chunkIntents == null || chunkIntents.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long ttl = intentTtlMs();
        ArrayList<SeamWriteIntent> ready = new ArrayList<>();
        for (Map.Entry<Long, SeamWriteIntent> entry : chunkIntents.entrySet()) {
            SeamWriteIntent intent = entry.getValue();
            if (intent == null) {
                chunkIntents.remove(entry.getKey());
                continue;
            }
            if ((now - intent.createdAtMs()) > ttl) {
                chunkIntents.remove(entry.getKey(), intent);
                continue;
            }
            BlockPos pos = intent.pos();
            if (!level.isLoaded(pos)) {
                continue;
            }
            if ((pos.getX() >> 4) != key.chunkX() || (pos.getZ() >> 4) != key.chunkZ()) {
                chunkIntents.remove(entry.getKey(), intent);
                continue;
            }
            if (chunkIntents.remove(entry.getKey(), intent)) {
                ready.add(intent);
            }
        }
        enqueueLoadedChunkIntents(level, chunk, ready);
        if (chunkIntents.isEmpty()) {
            JOURNAL.remove(key, chunkIntents);
        }
    }

    private static void enqueueLoadedChunkIntents(ServerLevel level, LevelChunk chunk, ArrayList<SeamWriteIntent> intents) {
        if (level == null || chunk == null || intents == null || intents.isEmpty()) {
            return;
        }
        ChunkCoord targetChunk = new ChunkCoord(level.dimension(), chunk.getPos().x, chunk.getPos().z);
        ChunkShadowMutationPlan.Builder builder = ChunkShadowMutationPlan.builder(level, targetChunk);
        for (SeamWriteIntent intent : intents) {
            if (intent == null || intent.pos() == null || intent.state() == null) {
                continue;
            }
            builder.add(intent.pos(), intent.state(), intent.flags(), false);
        }
        ChunkShadowMutationPlan plan = builder.build();
        if (plan.size() > 0) {
            ShadowBlockMutationApplier.enqueueDeferred(plan);
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!enabled()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        applyForLoadedChunk(level, chunk);
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!enabled()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        SeamChunkKey key = new SeamChunkKey(level.dimension().location(), chunk.getPos().x, chunk.getPos().z);
        ConcurrentHashMap<Long, SeamWriteIntent> intents = JOURNAL.get(key);
        if (intents != null && intents.isEmpty()) {
            JOURNAL.remove(key, intents);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        JOURNAL.clear();
        ACTIVE_PASSES.clear();
        CONTEXT.remove();
        APPLYING_INTENTS.remove();
        OVERFLOW_LOGGED.set(false);
        APPLY_FAIL_LOGGED.set(false);
    }

    public static int getPendingChunkCount() {
        return JOURNAL.size();
    }

    public static int getPendingIntentCount() {
        int total = 0;
        for (ConcurrentHashMap<Long, SeamWriteIntent> map : JOURNAL.values()) {
            if (map != null) {
                total += map.size();
            }
        }
        return total;
    }

    public static boolean hasPendingWrites(ResourceLocation dimension, int chunkX, int chunkZ) {
        if (dimension == null) {
            return false;
        }
        ConcurrentHashMap<Long, SeamWriteIntent> intents = JOURNAL.get(new SeamChunkKey(dimension, chunkX, chunkZ));
        return intents != null && !intents.isEmpty();
    }

    public static void flushLoadedChunk(ServerLevel level, int chunkX, int chunkZ) {
        if (!enabled() || level == null) {
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }
        applyForLoadedChunk(level, chunk);
    }

    public static boolean hasActivePassNearby(ResourceLocation dimension, int chunkX, int chunkZ, int radiusChunks) {
        if (dimension == null) {
            return false;
        }
        int radius = Math.max(0, radiusChunks);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (ACTIVE_PASSES.contains(new SeamChunkKey(dimension, chunkX + dx, chunkZ + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isChunkReadyForTreeReplay(ResourceLocation dimension, int chunkX, int chunkZ) {
        if (dimension == null) {
            return true;
        }
        if (hasPendingWrites(dimension, chunkX, chunkZ)) {
            return false;
        }
        return !hasActivePassNearby(dimension, chunkX, chunkZ, 1);
    }
}
