package org.admany.lc2h.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModList;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.util.server.ServerRescheduler;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.Arrays;
import java.util.function.Consumer;

public class DHCompat {

    private static boolean isLoaded;
    private static boolean apiResolved;
    private static boolean listenerRegistered;

    private static Object worldGenOverrides;
    private static Field delayedWorldProxyField;
    private static Field delayedWrapperFactoryField;
    private static Method registerWorldGeneratorOverride;
    private static Method worldProxyWorldLoaded;
    private static Method worldProxyGetLevels;
    private static Method levelWrapperDimensionName;
    private static Method levelWrapperGetWrappedLevel;
    private static Method fullDataSourceGetWidth;
    private static Method fullDataSourceSetColumn;
    private static Method terrainDataPointCreate;
    private static Method wrapperFactoryGetBlockStateWrapper;
    private static Method wrapperFactoryGetBiomeWrapper;
    private static Method wrapperFactoryGetBiomeWrapperById;
    private static Method wrapperFactoryGetDefaultBlockStateWrapper;
    private static Method wrapperFactoryGetAirBlockStateWrapper;
    private static Field dhApiResultSuccess;
    private static Field dhApiResultMessage;
    private static Object apiDataSourceReturnType;
    private static Class<?> worldGeneratorInterface;

    private static final Map<Object, LevelIntegration> ACTIVE_LEVELS = Collections.synchronizedMap(new IdentityHashMap<>());

    public static void init() {
        isLoaded = ModList.get().isLoaded("distanthorizons");
        if (!isLoaded) {
            return;
        }

        if (!resolveDhApi()) {
            LC2H.LOGGER.warn("Distant Horizons detected but API classes were not found; compatibility disabled");
            return;
        }

        if (!listenerRegistered) {
            MinecraftForge.EVENT_BUS.addListener(DHCompat::handleServerTick);
            listenerRegistered = true;
        }

        LC2H.LOGGER.info("Distant Horizons detected; enabling LOD compatibility bridge");
    }

    public static boolean shouldProcessLOD() {
        return isLoaded;
    }

    private static boolean resolveDhApi() {
        if (apiResolved) {
            return true;
        }

        try {
            Class<?> dhApiClass = Class.forName("com.seibel.distanthorizons.api.DhApi");
            worldGenOverrides = dhApiClass.getField("worldGenOverrides").get(null);

            Class<?> delayedClass = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
            delayedWorldProxyField = delayedClass.getField("worldProxy");
            delayedWrapperFactoryField = delayedClass.getField("wrapperFactory");

            worldGeneratorInterface = Class.forName("com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator");
            Class<?> levelWrapperInterface = Class.forName("com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper");
            Class<?> worldProxyInterface = Class.forName("com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy");
            Class<?> fullDataSourceInterface = Class.forName("com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource");
            Class<?> wrapperFactoryInterface = Class.forName("com.seibel.distanthorizons.api.interfaces.factories.IDhApiWrapperFactory");
            Class<?> blockWrapperInterface = Class.forName("com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper");
            Class<?> biomeWrapperInterface = Class.forName("com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper");

            registerWorldGeneratorOverride = worldGenOverrides.getClass().getMethod("registerWorldGeneratorOverride", levelWrapperInterface, worldGeneratorInterface);
            worldProxyWorldLoaded = worldProxyInterface.getMethod("worldLoaded");
            worldProxyGetLevels = worldProxyInterface.getMethod("getAllLoadedLevelWrappers");
            levelWrapperGetWrappedLevel = levelWrapperInterface.getMethod("getWrappedMcObject");
            levelWrapperDimensionName = levelWrapperInterface.getMethod("getDimensionName");

            fullDataSourceGetWidth = fullDataSourceInterface.getMethod("getWidthInDataColumns");
            fullDataSourceSetColumn = fullDataSourceInterface.getMethod("setApiDataPointColumn", int.class, int.class, List.class);

            Class<?> terrainDataPointClass = Class.forName("com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint");
            terrainDataPointCreate = terrainDataPointClass.getMethod("create", byte.class, int.class, int.class, int.class, int.class, blockWrapperInterface, biomeWrapperInterface);

            wrapperFactoryGetBlockStateWrapper = wrapperFactoryInterface.getMethod("getBlockStateWrapper", Object[].class, levelWrapperInterface);
            wrapperFactoryGetBiomeWrapper = wrapperFactoryInterface.getMethod("getBiomeWrapper", Object[].class, levelWrapperInterface);
            try {
                wrapperFactoryGetBiomeWrapperById = wrapperFactoryInterface.getMethod("getBiomeWrapper", String.class, levelWrapperInterface);
            } catch (NoSuchMethodException ignored) {
                wrapperFactoryGetBiomeWrapperById = null;
            }
            try {
                wrapperFactoryGetDefaultBlockStateWrapper = wrapperFactoryInterface.getMethod("getDefaultBlockStateWrapper", String.class, levelWrapperInterface);
            } catch (NoSuchMethodException ignored) {
                wrapperFactoryGetDefaultBlockStateWrapper = null;
            }
            wrapperFactoryGetAirBlockStateWrapper = wrapperFactoryInterface.getMethod("getAirBlockStateWrapper");

            Class<?> dhApiResultClass = Class.forName("com.seibel.distanthorizons.api.objects.DhApiResult");
            dhApiResultSuccess = dhApiResultClass.getField("success");
            dhApiResultMessage = dhApiResultClass.getField("message");

            Class<?> returnTypeEnum = Class.forName("com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType");
            Object[] constants = returnTypeEnum.getEnumConstants();
            Object match = Arrays.stream(constants)
                    .filter(e -> "API_DATA_SOURCES".equals(e.toString()))
                    .findFirst()
                    .orElse(null);
            apiDataSourceReturnType = match;

            apiResolved = true;
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException | IllegalAccessException e) {
            LC2H.LOGGER.warn("Unable to resolve Distant Horizons API: {}", e.getMessage());
            return false;
        }
    }

    private static void handleServerTick(TickEvent.ServerTickEvent event) {
        if (!apiResolved || event.phase != TickEvent.Phase.END) {
            return;
        }

        try {
            Object worldProxy = delayedWorldProxyField.get(null);
            Object wrapperFactory = delayedWrapperFactoryField.get(null);
            if (worldProxy == null || wrapperFactory == null) {
                return;
            }

            if (!(Boolean) worldProxyWorldLoaded.invoke(worldProxy)) {
                return;
            }

            Iterable<?> wrappers = (Iterable<?>) worldProxyGetLevels.invoke(worldProxy);
            if (wrappers == null) {
                return;
            }

            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object wrapper : wrappers) {
                if (wrapper == null) {
                    continue;
                }
                seen.add(wrapper);
                if (ACTIVE_LEVELS.containsKey(wrapper)) {
                    continue;
                }
                LevelIntegration integration = createIntegration(wrapper, wrapperFactory);
                if (integration != null) {
                    ACTIVE_LEVELS.put(wrapper, integration);
                }
            }

            if (!seen.isEmpty()) {
                ACTIVE_LEVELS.entrySet().removeIf(entry -> {
                    if (!seen.contains(entry.getKey())) {
                        entry.getValue().markUnloaded();
                        return true;
                    }
                    return false;
                });
            }
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Failed to process Distant Horizons tick: {}", t.getMessage());
        }
    }

    private static LevelIntegration createIntegration(Object levelWrapper, Object wrapperFactory) {
        try {
            LevelIntegration integration = new LevelIntegration(levelWrapper, wrapperFactory);
            if (integration.register()) {
                return integration;
            }
        } catch (Throwable t) {
            String identifier = describeWrapper(levelWrapper);
            LC2H.LOGGER.warn("Failed to register Distant Horizons generator for {}: {}", identifier, t.getMessage());
        }
        return null;
    }

    private static String describeWrapper(Object wrapper) {
        try {
            Object dimension = levelWrapperDimensionName.invoke(wrapper);
            return dimension != null ? dimension.toString() : "unknown_dimension";
        } catch (Throwable ignored) {
            return "unknown_dimension";
        }
    }

    private static final class LevelIntegration {
        private final Object levelWrapper;
        private final Object wrapperFactory;
        private final ServerLevel level;
        private final String dimensionName;
        private final Object airWrapper;
        private final Object generatorProxy;
        private final Map<BlockState, Object> blockWrapperCache = new ConcurrentHashMap<>();
        private final Map<Holder<Biome>, Object> biomeWrapperCache = new ConcurrentHashMap<>();
        private final List<Object> reusableColumn = new ArrayList<>(2);
        private final Object defaultBiomeWrapper;
        private final Object defaultSolidWrapper;
        private final ResourceLocation plainsId = ResourceLocation.parse("minecraft:plains");
        private final ResourceLocation stoneId = ResourceLocation.parse("minecraft:stone");

        private LevelIntegration(Object levelWrapper, Object wrapperFactory) throws Exception {
            this.levelWrapper = levelWrapper;
            this.wrapperFactory = wrapperFactory;
            this.level = (ServerLevel) levelWrapperGetWrappedLevel.invoke(levelWrapper);
            this.dimensionName = describeWrapper(levelWrapper);
            this.airWrapper = wrapperFactoryGetAirBlockStateWrapper.invoke(wrapperFactory);
            this.defaultBiomeWrapper = resolveDefaultBiomeWrapper();
            this.defaultSolidWrapper = resolveDefaultBlockWrapper();
            this.generatorProxy = Proxy.newProxyInstance(
                worldGeneratorInterface.getClassLoader(),
                new Class<?>[]{worldGeneratorInterface},
                new GeneratorInvocationHandler(this)
            );
        }

        private boolean register() throws Exception {
            Object result = registerWorldGeneratorOverride.invoke(worldGenOverrides, levelWrapper, generatorProxy);
            if (result == null) {
                LC2H.LOGGER.warn("Distant Horizons API returned null result while registering generator for {}", dimensionName);
                return false;
            }
            boolean success = dhApiResultSuccess.getBoolean(result);
            if (success) {
                LC2H.LOGGER.info("Registered Distant Horizons LOD generator for {}", dimensionName);
                return true;
            }
            String message = (String) dhApiResultMessage.get(result);
            LC2H.LOGGER.warn("Distant Horizons rejected generator for {}: {}", dimensionName, message);
            return false;
        }

        private void markUnloaded() {
            LC2H.LOGGER.info("Distant Horizons level {} unloaded; removing generator bridge", dimensionName);
        }

        private CompletableFuture<Void> generateLod(int chunkPosMinX, int chunkPosMinZ, int lodPosX, int lodPosZ, byte detailLevel,
                                                     Object dataSource, ExecutorService executor, Consumer<Object> consumer) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            Runnable job = () -> runGeneration(chunkPosMinX, chunkPosMinZ, lodPosX, lodPosZ, detailLevel, dataSource, consumer, future);
            if (executor != null) {
                try {
                    executor.execute(job);
                } catch (Throwable t) {
                    job.run();
                }
            } else {
                job.run();
            }
            return future;
        }

        private void runGeneration(int chunkPosMinX, int chunkPosMinZ, int lodPosX, int lodPosZ, byte detailLevel,
                                   Object dataSource, Consumer<Object> consumer, CompletableFuture<Void> resultFuture) {
            try {
                int width = (Integer) fullDataSourceGetWidth.invoke(dataSource);
                int scale = 1 << (detailLevel & 0xFF);
                int baseBlockX = chunkPosMinX << 4;
                int baseBlockZ = chunkPosMinZ << 4;

                BlockState[] blockSamples = new BlockState[width * width];
                @SuppressWarnings("unchecked")
                Holder<Biome>[] biomeSamples = (Holder<Biome>[]) new Holder<?>[width * width];
                int[] heights = new int[width * width];

                CompletableFuture<Void> serverWork = new CompletableFuture<>();
                ServerRescheduler.runOnServer(() -> {
                    try {
                        collectSamples(baseBlockX, baseBlockZ, lodPosX, lodPosZ, scale, width, blockSamples, biomeSamples, heights);
                        serverWork.complete(null);
                    } catch (Throwable t) {
                        serverWork.completeExceptionally(t);
                    }
                });

                serverWork.whenComplete((ok, err) -> {
                    if (err != null) {
                        resultFuture.completeExceptionally(err);
                        return;
                    }
                    try {
                        populateDataSource(detailLevel, dataSource, width, blockSamples, biomeSamples, heights);
                        consumer.accept(dataSource);
                        resultFuture.complete(null);
                    } catch (Throwable t) {
                        resultFuture.completeExceptionally(t);
                        LC2H.LOGGER.debug("Distant Horizons generation failed for {}: {}", dimensionName, t.getMessage());
                    }
                });
            } catch (Throwable t) {
                resultFuture.completeExceptionally(t);
                LC2H.LOGGER.debug("Distant Horizons generation failed for {}: {}", dimensionName, t.getMessage());
            }
        }

        private void collectSamples(int baseBlockX, int baseBlockZ, int lodPosX, int lodPosZ, int scale, int width,
                                    BlockState[] blockSamples, Holder<Biome>[] biomeSamples, int[] heights) {
            if (level == null) {
                throw new IllegalStateException("Server level is not available for Distant Horizons integration");
            }

            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
            int minY = level.getMinBuildHeight();
            int maxY = level.getMaxBuildHeight();

            for (int x = 0; x < width; x++) {
                int worldX = baseBlockX + x * scale + (scale >> 1);
                for (int z = 0; z < width; z++) {
                    int worldZ = baseBlockZ + z * scale + (scale >> 1);
                    int index = x * width + z;

                    int height = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
                    height = Math.max(minY, Math.min(height, maxY));
                    heights[index] = height;

                    int sampleY = Math.max(minY, height - 1);
                    mutable.set(worldX, sampleY, worldZ);
                    blockSamples[index] = level.getBlockState(mutable);
                    biomeSamples[index] = level.getBiome(mutable);
                }
            }
        }

        private void populateDataSource(byte detailLevel, Object dataSource, int width,
                                        BlockState[] blockSamples, Holder<Biome>[] biomeSamples, int[] heights) throws Exception {
            int minY = level.getMinBuildHeight();
            int maxY = level.getMaxBuildHeight();
            int offset = minY < 0 ? -minY : 0;
            int dataMinY = minY + offset;
            int dataMaxY = maxY + offset;
            int safeMinBase = clampY(dataMinY);
            int safeMaxBase = Math.max(safeMinBase, clampY(dataMaxY));

            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    List<Object> working = new ArrayList<>(2);
                    int index = x * width + z;
                    int topY = Math.max(minY, Math.min(heights[index], maxY)) + offset;
                    int safeTop = clampY(topY);
                    if (safeTop < safeMinBase) {
                        safeTop = safeMinBase;
                    } else if (safeTop > safeMaxBase) {
                        safeTop = safeMaxBase;
                    }

                    BlockState state = blockSamples[index];
                    Holder<Biome> biomeHolder = biomeSamples[index];

                    Object biomeWrapper = resolveBiomeWrapper(biomeHolder);
                    Object blockWrapper = resolveBlockWrapper(state);

                    if (biomeWrapper == null) {
                        biomeWrapper = defaultBiomeWrapper;
                    }
                    if (biomeWrapper == null) {
                        continue;
                    }

                    working.clear();

                    int solidBottom = safeMinBase;
                    int solidTop = safeTop;
                    if (solidTop < solidBottom) {
                        int swap = solidTop;
                        solidTop = solidBottom;
                        solidBottom = swap;
                    }

                    if (blockWrapper != null && solidTop > solidBottom) {
                        working.add(terrainDataPointCreate.invoke(null, detailLevel, 0, 0, solidBottom, solidTop, blockWrapper, biomeWrapper));
                    }

                    int airBottom = safeTop;
                    int airTop = safeMaxBase;
                    if (airTop < airBottom) {
                        int swap = airTop;
                        airTop = airBottom;
                        airBottom = swap;
                    }

                    if (airTop > airBottom) {
                        working.add(terrainDataPointCreate.invoke(null, detailLevel, 0, 15, airBottom, airTop, airWrapper, biomeWrapper));
                    }

                    fullDataSourceSetColumn.invoke(dataSource, x, z, working);
                }
            }
        }

        private Object resolveDefaultBiomeWrapper() {
            try {
                if (wrapperFactoryGetBiomeWrapperById != null) {
                    return wrapperFactoryGetBiomeWrapperById.invoke(wrapperFactory, plainsId.toString(), levelWrapper);
                }
                if (level != null) {
                    BlockPos pos = level.getSharedSpawnPos();
                    Holder<Biome> holder = level.getBiome(pos);
                    return wrapperFactoryGetBiomeWrapper.invoke(wrapperFactory, new Object[]{new Object[]{holder}, levelWrapper});
                }
            } catch (Throwable ignored) {
                return null;
            }
            return null;
        }

        private Object resolveDefaultBlockWrapper() {
            try {
                if (wrapperFactoryGetDefaultBlockStateWrapper != null) {
                    return wrapperFactoryGetDefaultBlockStateWrapper.invoke(wrapperFactory, stoneId.toString(), levelWrapper);
                }
                BlockState stoneState = Blocks.STONE.defaultBlockState();
                return wrapperFactoryGetBlockStateWrapper.invoke(wrapperFactory, new Object[]{new Object[]{stoneState}, levelWrapper});
            } catch (Throwable ignored) {
                return null;
            }
        }

        private int clampY(int value) {
            if (value < 0) {
                return 0;
            }
            if (value > 4095) {
                return 4095;
            }
            return value;
        }

        private Object resolveBlockWrapper(BlockState state) {
            if (state == null || state.isAir()) {
                return airWrapper;
            }
            Object wrapper = blockWrapperCache.computeIfAbsent(state, key -> createBlockWrapper(key));
            if (wrapper == null) {
                wrapper = defaultSolidWrapper != null ? defaultSolidWrapper : airWrapper;
            }
            return wrapper;
        }

        private Object createBlockWrapper(BlockState state) {
            try {
                return wrapperFactoryGetBlockStateWrapper.invoke(wrapperFactory, new Object[]{new Object[]{state}, levelWrapper});
            } catch (Throwable directFailure) {
                try {
                ResourceLocation key = state.getBlockHolder().unwrapKey().map(k -> k.location()).orElse(null);
                    if (key != null && wrapperFactoryGetDefaultBlockStateWrapper != null) {
                        return wrapperFactoryGetDefaultBlockStateWrapper.invoke(wrapperFactory, key.toString(), levelWrapper);
                    }
                } catch (Throwable ignored) {
                    // fall through to null
                }
                LC2H.LOGGER.debug("Failed to wrap block state for {}: {}", dimensionName, directFailure.getMessage());
                return null;
            }
        }

        private Object resolveBiomeWrapper(Holder<Biome> holder) {
            if (holder == null) {
                return defaultBiomeWrapper;
            }

            Object wrapper = biomeWrapperCache.computeIfAbsent(holder, key -> createBiomeWrapper(key));
            if (wrapper == null) {
                wrapper = defaultBiomeWrapper;
            }
            return wrapper;
        }

        private Object createBiomeWrapper(Holder<Biome> holder) {
            try {
                return wrapperFactoryGetBiomeWrapper.invoke(wrapperFactory, new Object[]{new Object[]{holder}, levelWrapper});
            } catch (Throwable directFailure) {
                try {
                    ResourceLocation keyLocation = holder.unwrapKey().map(resourceKey -> resourceKey.location()).orElse(null);
                    if (keyLocation != null) {
                        return wrapperFactoryGetBiomeWrapperById.invoke(wrapperFactory, keyLocation.toString(), levelWrapper);
                    }
                } catch (Throwable ignored) {
                    // fall through to null
                }
                LC2H.LOGGER.debug("Failed to wrap biome for {}: {}", dimensionName, directFailure.getMessage());
                return null;
            }
        }
    }

    private static final class GeneratorInvocationHandler implements InvocationHandler {
        private final LevelIntegration integration;
        private final Object identity = new Object();

        private GeneratorInvocationHandler(LevelIntegration integration) {
            this.integration = integration;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();

            switch (name) {
                case "getReturnType":
                    return apiDataSourceReturnType;
                case "runApiValidation":
                    return Boolean.FALSE;
                case "getPriority":
                    return 0;
                case "getSmallestDataDetailLevel":
                    return (byte) 0;
                case "getLargestDataDetailLevel":
                    return (byte) 9;
                case "preGeneratorTaskStart":
                    return null;
                case "generateLod":
                    int chunkPosMinX = (int) args[0];
                    int chunkPosMinZ = (int) args[1];
                    int lodPosX = (int) args[2];
                    int lodPosZ = (int) args[3];
                    byte detailLevel = (byte) args[4];
                    Object dataSource = args[5];
                    ExecutorService executor = (ExecutorService) args[7];
                    @SuppressWarnings("unchecked")
                    Consumer<Object> consumer = (Consumer<Object>) args[8];
                    return integration.generateLod(chunkPosMinX, chunkPosMinZ, lodPosX, lodPosZ, detailLevel, dataSource, executor, consumer);
                default:
                    if (name.equals("toString")) {
                        return "LC2H-DH-WorldGenerator";
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(identity);
                    }
                    if (name.equals("equals")) {
                        return args != null && args.length == 1 && args[0] == proxy;
                    }
                    Object fallback = defaultValueFor(method);
                    LC2H.LOGGER.debug("Unhandled DH generator method {}; returning {}", name, fallback);
                    return fallback;
            }
        }

        private Object defaultValueFor(Method method) {
            Class<?> type = method.getReturnType();
            if (type == Void.TYPE) {
                return null;
            }
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == Boolean.TYPE) {
                return false;
            }
            if (type == Byte.TYPE) {
                return (byte) 0;
            }
            if (type == Short.TYPE) {
                return (short) 0;
            }
            if (type == Integer.TYPE) {
                return 0;
            }
            if (type == Long.TYPE) {
                return 0L;
            }
            if (type == Float.TYPE) {
                return 0.0f;
            }
            if (type == Double.TYPE) {
                return 0.0d;
            }
            if (type == Character.TYPE) {
                return '\0';
            }
            return null;
        }
    }
}
