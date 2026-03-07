package org.admany.lc2h.worldgen.lostcities;

import com.mojang.serialization.DataResult;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class DeferredTreeSavedData extends SavedData {

    private static final String DATA_NAME = "lc2h_deferred_trees";
    private static final String TAG_TREES = "trees";
    private static final String TAG_POS = "pos";
    private static final String TAG_FEATURE = "feature";
    private static final String TAG_CONFIG = "config";
    private static final String TAG_BLOCKS = "blocks";
    private static final String TAG_STATE = "state";
    private static final AtomicReference<java.lang.reflect.Field> BUILTIN_BLOCK_REGISTRY_FIELD = new AtomicReference<>();
    private static final AtomicReference<java.lang.reflect.Field> BUILTIN_FEATURE_REGISTRY_FIELD = new AtomicReference<>();

    private record PersistedTree(
        BlockPos pos,
        List<DeferredTreeQueue.CapturedBlock> blocks,
        TreeConfiguration config,
        ResourceLocation featureId
    ) {
    }

    private final List<PersistedTree> trees = new ArrayList<>();

    public static DeferredTreeSavedData getOrCreate(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(DeferredTreeSavedData::load, DeferredTreeSavedData::new, DATA_NAME);
    }

    public static DeferredTreeSavedData load(CompoundTag tag) {
        DeferredTreeSavedData data = new DeferredTreeSavedData();
        if (tag == null || !tag.contains(TAG_TREES, Tag.TAG_LIST)) {
            return data;
        }
        ListTag list = tag.getList(TAG_TREES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.contains(TAG_POS, Tag.TAG_LONG)) {
                continue;
            }
            BlockPos pos = BlockPos.of(entry.getLong(TAG_POS));
            if (entry.contains(TAG_BLOCKS, Tag.TAG_LIST)) {
                ListTag blocksTag = entry.getList(TAG_BLOCKS, Tag.TAG_COMPOUND);
                List<DeferredTreeQueue.CapturedBlock> blocks = new ArrayList<>(blocksTag.size());
                for (int j = 0; j < blocksTag.size(); j++) {
                    CompoundTag blockEntry = blocksTag.getCompound(j);
                    if (!blockEntry.contains(TAG_POS, Tag.TAG_LONG) || !blockEntry.contains(TAG_STATE, Tag.TAG_COMPOUND)) {
                        continue;
                    }
                    BlockState state = readBlockState(blockEntry.getCompound(TAG_STATE));
                    if (state == null) {
                        continue;
                    }
                    blocks.add(new DeferredTreeQueue.CapturedBlock(BlockPos.of(blockEntry.getLong(TAG_POS)), state));
                }
                if (!blocks.isEmpty()) {
                    data.trees.add(new PersistedTree(pos, blocks, null, null));
                }
                continue;
            }
            if (!entry.contains(TAG_FEATURE, Tag.TAG_STRING) || !entry.contains(TAG_CONFIG)) {
                continue;
            }
            ResourceLocation featureId = ResourceLocation.tryParse(entry.getString(TAG_FEATURE));
            if (featureId == null) {
                continue;
            }
            Tag configTag = entry.get(TAG_CONFIG);
            if (configTag == null) {
                continue;
            }
            DataResult<TreeConfiguration> parsed = TreeConfiguration.CODEC.parse(NbtOps.INSTANCE, configTag);
            TreeConfiguration config = parsed.result().orElse(null);
            if (config == null) {
                continue;
            }
            data.trees.add(new PersistedTree(pos, Collections.emptyList(), config, featureId));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (PersistedTree tree : trees) {
            if (tree == null || tree.pos() == null) {
                continue;
            }
            if (tree.blocks() != null && !tree.blocks().isEmpty()) {
                CompoundTag entry = new CompoundTag();
                entry.putLong(TAG_POS, tree.pos().asLong());
                ListTag blockList = new ListTag();
                for (DeferredTreeQueue.CapturedBlock block : tree.blocks()) {
                    if (block == null || block.pos() == null || block.state() == null) {
                        continue;
                    }
                    CompoundTag blockEntry = new CompoundTag();
                    blockEntry.putLong(TAG_POS, block.pos().asLong());
                    blockEntry.put(TAG_STATE, NbtUtils.writeBlockState(block.state()));
                    blockList.add(blockEntry);
                }
                if (!blockList.isEmpty()) {
                    entry.put(TAG_BLOCKS, blockList);
                    list.add(entry);
                }
                continue;
            }
            if (tree.config() == null || tree.featureId() == null) {
                continue;
            }
            DataResult<Tag> encoded = TreeConfiguration.CODEC.encodeStart(NbtOps.INSTANCE, tree.config());
            Tag configTag = encoded.result().orElse(null);
            if (configTag == null) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putLong(TAG_POS, tree.pos().asLong());
            entry.putString(TAG_FEATURE, tree.featureId().toString());
            entry.put(TAG_CONFIG, configTag);
            list.add(entry);
        }
        tag.put(TAG_TREES, list);
        return tag;
    }

    public List<DeferredTreeQueue.PendingTree> toPendingTrees(ResourceKey<Level> dim) {
        if (trees.isEmpty() || dim == null) {
            return Collections.emptyList();
        }
        List<DeferredTreeQueue.PendingTree> out = new ArrayList<>();
        for (PersistedTree tree : trees) {
            if (tree.blocks() != null && !tree.blocks().isEmpty()) {
                out.add(DeferredTreeQueue.PendingTree.captured(tree.pos(), tree.blocks(), dim));
                continue;
            }
            Feature<?> feature = getFeature(tree.featureId());
            if (!(feature instanceof TreeFeature treeFeature)) {
                continue;
            }
            out.add(DeferredTreeQueue.PendingTree.replay(tree.pos(), tree.config(), treeFeature, dim));
        }
        return out;
    }

    public void replaceFromPendingTrees(List<DeferredTreeQueue.PendingTree> pending) {
        trees.clear();
        if (pending == null || pending.isEmpty()) {
            return;
        }
        for (DeferredTreeQueue.PendingTree tree : pending) {
            if (tree == null || tree.pos() == null) {
                continue;
            }
            if (tree.hasCapturedBlocks()) {
                trees.add(new PersistedTree(tree.pos(), tree.blocks(), null, null));
                continue;
            }
            if (tree.config() == null || tree.feature() == null) {
                continue;
            }
            ResourceLocation id = getFeatureId(tree.feature());
            if (id == null) {
                continue;
            }
            trees.add(new PersistedTree(tree.pos(), Collections.emptyList(), tree.config(), id));
        }
    }

    private static BlockState readBlockState(CompoundTag tag) {
        if (tag == null) {
            return null;
        }
        Object lookup = resolveBuiltInBlockLookup();
        if (lookup == null) {
            return null;
        }
        try {
            for (java.lang.reflect.Method method : NbtUtils.class.getMethods()) {
                if (!"readBlockState".equals(method.getName()) || method.getParameterCount() != 2) {
                    continue;
                }
                Object state = method.invoke(null, lookup, tag);
                return state instanceof BlockState blockState ? blockState : null;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Feature<?> getFeature(ResourceLocation id) {
        if (id == null) {
            return null;
        }
        net.minecraft.core.Registry<?> registry = resolveBuiltInFeatureRegistry();
        if (registry == null) {
            return null;
        }
        try {
            Object value = registry.get(id);
            return value instanceof Feature<?> feature ? feature : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ResourceLocation getFeatureId(Feature<?> feature) {
        if (feature == null) {
            return null;
        }
        net.minecraft.core.Registry<?> registry = resolveBuiltInFeatureRegistry();
        if (registry == null) {
            return null;
        }
        try {
            java.lang.reflect.Method method = registry.getClass().getMethod("getKey", Object.class);
            Object key = method.invoke(registry, feature);
            return key instanceof ResourceLocation id ? id : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object resolveBuiltInBlockLookup() {
        java.lang.reflect.Field field = BUILTIN_BLOCK_REGISTRY_FIELD.get();
        if (field == null) {
            field = findBuiltInRegistryField("BLOCK");
            if (field != null) {
                BUILTIN_BLOCK_REGISTRY_FIELD.set(field);
            }
        }
        if (field == null) {
            return null;
        }
        try {
            Object registry = field.get(null);
            if (!(registry instanceof net.minecraft.core.Registry<?> reg)) {
                return null;
            }
            return reg.asLookup();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static net.minecraft.core.Registry<?> resolveBuiltInFeatureRegistry() {
        java.lang.reflect.Field field = BUILTIN_FEATURE_REGISTRY_FIELD.get();
        if (field == null) {
            field = findBuiltInRegistryField("FEATURE");
            if (field != null) {
                BUILTIN_FEATURE_REGISTRY_FIELD.set(field);
            }
        }
        if (field == null) {
            return null;
        }
        try {
            Object registry = field.get(null);
            return registry instanceof net.minecraft.core.Registry<?> reg ? reg : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static java.lang.reflect.Field findBuiltInRegistryField(String name) {
        try {
            Class<?> cls = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            java.lang.reflect.Field field = cls.getField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
