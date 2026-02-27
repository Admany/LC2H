package org.admany.lc2h.worldgen.seams;

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
import org.admany.lc2h.mixin.accessor.minecraft.WorldGenRegionAccessor;
import org.admany.lc2h.util.chunk.ChunkPostProcessor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public final class SeamOwnershipJournal {

    private static final AtomicBoolean OVERFLOW_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean APPLY_FAIL_LOGGED = new AtomicBoolean(false);

    private static final ConcurrentHashMap<SeamChunkKey, ConcurrentHashMap<Long, SeamWriteIntent>> JOURNAL =
        new ConcurrentHashMap<>();

    private record GenerationContext(WorldGenRegion region, ResourceLocation dimension, int centerChunkX, int centerChunkZ) {
    }

    private static final ThreadLocal<GenerationContext> CONTEXT = new ThreadLocal<>();

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
            ResourceLocation dim = level.dimension().location();
            net.minecraft.world.level.ChunkPos center = region.getCenter();
            CONTEXT.set(new GenerationContext(region, dim, center.x, center.z));
        } catch (Throwable ignored) {
            CONTEXT.remove();
        }
    }

    public static void endLostCityPass(WorldGenRegion region) {
        if (!enabled() || region == null) {
            CONTEXT.remove();
            return;
        }
        try {
            applyForCenterChunk(region);
            applyForRegionWindow(region);
        } catch (Throwable ignored) {
        } finally {
            CONTEXT.remove();
        }
    }

    public static boolean deferCrossChunkWrite(WorldGenRegion region, BlockPos pos, net.minecraft.world.level.block.state.BlockState state, int flags) {
        if (!enabled() || region == null || pos == null || state == null) {
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
        if (!(region instanceof WorldGenRegionAccessor accessor)) {
            return;
        }
        net.minecraft.world.level.ChunkPos first = accessor.lc2h$getFirstPos();
        net.minecraft.world.level.ChunkPos last = accessor.lc2h$getLastPos();
        if (first == null || last == null) {
            return;
        }
        int minX = Math.min(first.x, last.x);
        int maxX = Math.max(first.x, last.x);
        int minZ = Math.min(first.z, last.z);
        int maxZ = Math.max(first.z, last.z);
        ResourceLocation dim = level.dimension().location();

        for (SeamChunkKey key : JOURNAL.keySet()) {
            if (key == null || key.dimension() == null || !key.dimension().equals(dim)) {
                continue;
            }
            if (key.chunkX() < minX || key.chunkX() > maxX || key.chunkZ() < minZ || key.chunkZ() > maxZ) {
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
            if (ChunkPostProcessor.isTracked(state.getBlock())) {
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
            try {
                if (level.setBlock(pos, intent.state(), intent.flags())) {
                    chunkIntents.remove(entry.getKey(), intent);
                }
            } catch (Throwable ignored) {
            }
        }
        if (chunkIntents.isEmpty()) {
            JOURNAL.remove(key, chunkIntents);
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
        CONTEXT.remove();
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
}
