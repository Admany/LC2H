package org.admany.lc2h.dev.diagnostics;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.admany.lc2h.LC2H;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class BlockEntityTickWatch {

    private static final AtomicBoolean ENABLED = new AtomicBoolean(false);
    private static final AtomicLong LAST_LOG_NS = new AtomicLong(0L);
    private static final long LOG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(5);
    private static final boolean CREATE_PRESENT = ModList.get().isLoaded("create");
    private static final String CREATE_WHEEL_BE = "create:crushing_wheel";
    private static final String CREATE_WHEEL_CONTROLLER_BE = "create:crushing_wheel_controller";

    private static final AtomicBoolean TICKER_REFLECTION_LOGGED = new AtomicBoolean(false);

    private static java.util.List<TickingBlockEntity> tryFindTickerList(Level level) {
        if (level == null) {
            return null;
        }
        try {
            Class<?> c = level.getClass();
            while (c != null) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (!java.util.List.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        Object o = f.get(level);
                        if (!(o instanceof java.util.List<?> raw)) {
                            continue;
                        }
                        if (raw.isEmpty()) {
                            continue;
                        }
                        Object first = null;
                        try {
                            first = raw.get(0);
                        } catch (Throwable ignored) {
                        }
                        if (first instanceof TickingBlockEntity) {
                            @SuppressWarnings("unchecked")
                            java.util.List<TickingBlockEntity> cast = (java.util.List<TickingBlockEntity>) raw;
                            return cast;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Double tryReadWheelSpeed(BlockEntity be) {
        if (be == null) {
            return null;
        }
        try {
            java.lang.reflect.Method m = be.getClass().getMethod("getSpeed");
            Object v = m.invoke(be);
            if (v instanceof Number n) {
                return n.doubleValue();
            }
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Field f = be.getClass().getDeclaredField("speed");
            f.setAccessible(true);
            Object v = f.get(be);
            if (v instanceof Number n) {
                return n.doubleValue();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private BlockEntityTickWatch() {}

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static void setEnabled(boolean enabled) {
        ENABLED.set(enabled);
    }

    public static void recordTick(BlockEntity blockEntity) {
        if (!ENABLED.get() || blockEntity == null || !CREATE_PRESENT) {
            return;
        }
        if (!(blockEntity.getLevel() instanceof ServerLevel level)) {
            return;
        }

        ResourceLocation key = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(blockEntity.getType());
        String id = key == null ? null : key.toString();
        if (id == null || !(CREATE_WHEEL_BE.equals(id) || CREATE_WHEEL_CONTROLLER_BE.equals(id))) {
            return;
        }

        long now = System.nanoTime();
        long last = LAST_LOG_NS.get();
        if (now - last < LOG_INTERVAL_NS) {
            return;
        }
        LAST_LOG_NS.set(now);

        BlockPos pos = blockEntity.getBlockPos();
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        long tick = -1L;
        try {
            tick = level.getServer().getTickCount();
        } catch (Throwable ignored) {
        }

        LC2H.LOGGER.info("[LC2H] Create BE tick: type={} pos={} dim={} chunk=({}, {}) tick={}",
            id, pos, level.dimension().location(), chunkX, chunkZ, tick);
    }

    public static void recordTicker(Level level, TickingBlockEntity ticker) {
        if (!ENABLED.get() || ticker == null || !CREATE_PRESENT) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        String type = ticker.getType();
        if (type == null || !(CREATE_WHEEL_BE.equals(type) || CREATE_WHEEL_CONTROLLER_BE.equals(type))) {
            return;
        }

        long now = System.nanoTime();
        long last = LAST_LOG_NS.get();
        if (now - last < LOG_INTERVAL_NS) {
            return;
        }
        LAST_LOG_NS.set(now);

        BlockPos pos = ticker.getPos();
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        long tick = -1L;
        try {
            tick = serverLevel.getServer().getTickCount();
        } catch (Throwable ignored) {
        }

        LC2H.LOGGER.info("[LC2H] Create BE tick: type={} pos={} dim={} chunk=({}, {}) tick={}",
            type, pos, serverLevel.dimension().location(), chunkX, chunkZ, tick);
    }

    public static void debugTickerList(Level level, java.util.List<TickingBlockEntity> tickers) {
        if (!ENABLED.get() || !CREATE_PRESENT || level == null || tickers == null) {
            return;
        }
        long now = System.nanoTime();
        long last = LAST_LOG_NS.get();
        if (now - last < LOG_INTERVAL_NS) {
            return;
        }
        LAST_LOG_NS.set(now);

        int total = tickers.size();
        int wheels = 0;
        int controllers = 0;
        for (TickingBlockEntity ticker : tickers) {
            if (ticker == null) continue;
            String type = ticker.getType();
            if (CREATE_WHEEL_BE.equals(type)) {
                wheels++;
            } else if (CREATE_WHEEL_CONTROLLER_BE.equals(type)) {
                controllers++;
            }
        }
        LC2H.LOGGER.info("[LC2H] BE ticker list: dim={} total={} wheels={} controllers={}",
            level.dimension().location(), total, wheels, controllers);
    }


    public static void pollNearby(MinecraftServer server) {
        if (!ENABLED.get() || !CREATE_PRESENT || server == null) {
            return;
        }

        long now = System.nanoTime();
        long last = LAST_LOG_NS.get();
        if (now - last < LOG_INTERVAL_NS) {
            return;
        }
        LAST_LOG_NS.set(now);

        try {
            var playerList = server.getPlayerList();
            if (playerList == null || playerList.getPlayers().isEmpty()) {
                return;
            }
            ServerPlayer player = playerList.getPlayers().get(0);
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (level == null) {
                return;
            }

            ChunkPos pos = player.chunkPosition();
            net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null) {
                LC2H.LOGGER.info("[LC2H] Create BE scan: dim={} chunk=({}, {}) loadedNow=false", level.dimension().location(), pos.x, pos.z);
                return;
            }

            int wheels = 0;
            int controllers = 0;
            int total = 0;
            String wheelClassSample = null;
            Double wheelSpeedSample = null;
            BlockPos wheelPosSample = null;
            boolean controllerTypeRegistered = false;
            String middleBlockSample = null;
            String middleBeSample = null;
            try {
                total = chunk.getBlockEntities().size();
            } catch (Throwable ignored) {
            }

            try {
                controllerTypeRegistered = ForgeRegistries.BLOCK_ENTITY_TYPES.containsKey(new ResourceLocation("create", "crushing_wheel_controller"));
            } catch (Throwable ignored) {
            }

            try {
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be == null) continue;
                    ResourceLocation key = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType());
                    String id = key == null ? null : key.toString();
                    if (CREATE_WHEEL_BE.equals(id)) {
                        wheels++;
                        if (wheelClassSample == null) {
                            wheelClassSample = be.getClass().getName();
                            wheelSpeedSample = tryReadWheelSpeed(be);
                            wheelPosSample = be.getBlockPos();
                        }
                    } else if (CREATE_WHEEL_CONTROLLER_BE.equals(id)) {
                        controllers++;
                    }
                }
            } catch (Throwable ignored) {
            }

            if (wheelPosSample != null) {
                try {
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockPos otherWheel = wheelPosSample.relative(dir, 2);
                        BlockState otherState = level.getBlockState(otherWheel);
                        ResourceLocation otherKey = otherState == null ? null : ForgeRegistries.BLOCKS.getKey(otherState.getBlock());
                        if (otherKey == null || !"create:crushing_wheel".equals(otherKey.toString())) {
                            continue;
                        }

                        BlockPos middle = wheelPosSample.relative(dir, 1);
                        BlockState middleState = level.getBlockState(middle);
                        ResourceLocation middleKey = middleState == null ? null : ForgeRegistries.BLOCKS.getKey(middleState.getBlock());
                        middleBlockSample = middleKey == null ? null : middleKey.toString();

                        BlockEntity middleBe = level.getBlockEntity(middle);
                        if (middleBe != null) {
                            ResourceLocation beKey = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(middleBe.getType());
                            String typeId = beKey == null ? "<unknown>" : beKey.toString();
                            middleBeSample = typeId + " (" + middleBe.getClass().getName() + ")";
                        } else {
                            middleBeSample = null;
                        }
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }

            boolean shouldTickBlocks = false;
            try {
                shouldTickBlocks = level.shouldTickBlocksAt(player.blockPosition());
            } catch (Throwable ignored) {
            }

            int tickerTotal = -1;
            int tickerWheels = -1;
            int tickerControllers = -1;
            try {
                java.util.List<TickingBlockEntity> tickers = tryFindTickerList(level);
                if (tickers != null) {
                    tickerTotal = tickers.size();
                    int w = 0;
                    int ctr = 0;
                    for (TickingBlockEntity t : tickers) {
                        if (t == null) continue;
                        String type = t.getType();
                        if (CREATE_WHEEL_BE.equals(type)) {
                            w++;
                        } else if (CREATE_WHEEL_CONTROLLER_BE.equals(type)) {
                            ctr++;
                        }
                    }
                    tickerWheels = w;
                    tickerControllers = ctr;
                } else {
                    if (TICKER_REFLECTION_LOGGED.compareAndSet(false, true)) {
                        LC2H.LOGGER.warn("[LC2H] Could not locate Level ticker list via reflection; ticker diagnostics unavailable");
                    }
                }
            } catch (Throwable t) {
                if (TICKER_REFLECTION_LOGGED.compareAndSet(false, true)) {
                    LC2H.LOGGER.warn("[LC2H] Failed reflecting Level ticker list: {}", t.getMessage());
                }
            }

            LC2H.LOGGER.info("[LC2H] Create scan: dim={} chunk=({}, {}) shouldTickBlocks={} wheelsBE={} controllersBE={} totalBE={} tickerTotal={} tickerWheels={} tickerControllers={} wheelClass={} wheelSpeed={} controllerTypeRegistered={} middleBlock={} middleBE={}",
                level.dimension().location(), pos.x, pos.z,
                shouldTickBlocks, wheels, controllers, total,
                tickerTotal, tickerWheels, tickerControllers,
                wheelClassSample, wheelSpeedSample,
                controllerTypeRegistered, middleBlockSample, middleBeSample);
        } catch (Throwable ignored) {
        }
    }
}
