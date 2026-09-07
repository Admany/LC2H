package org.admany.lc2h.dev.diagnostics;

import java.util.ArrayList;
import java.util.List;

public final class LifecycleStateInventory {
    private LifecycleStateInventory() {
    }

    public static List<String> summaryLines() {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("LifecycleInventory: owner=FeatureCache key=String value=Object lifecycle=server/runtime invalidation=FeatureCache.clear|shutdown scope=unscoped retainsWorld=false survivesStop=false");
        lines.add("LifecycleInventory: owner=LostCitiesCacheBridge key=bridgeDiskKey(schema+lifecycle+server+raw) value=Serializable lifecycle=server disk invalidation=WorldGenScope.beginServer/endServer scope=seed+server-path+lifecycle retainsWorld=false survivesStop=no-compatible-key");
        lines.add("LifecycleInventory: owner=AsyncBuildingInfoPlanner key=ChunkCoord value=BuildingInfo|sentinel lifecycle=server/runtime invalidation=shutdown|invalidateArea|ttl-prune scope=chunk-only with provider-dependent meaning retainsWorld=false survivesStop=false");
        lines.add("LifecycleInventory: owner=AsyncMultiChunkPlanner key=PlannerKey(scope+dimension+area+x+z) plus ChunkCoord warmup maps lifecycle=server/runtime invalidation=shutdown|integration invalidation scope=WorldGenScope cache scope plus chunk coords retainsWorld=false survivesStop=false");
        lines.add("LifecycleInventory: owner=MultiChunkPlanningCache key=planner-local multichunk keys lifecycle=server/runtime invalidation=clear|ttl scope=multichunk/profile/seed retainsWorld=false survivesStop=false");
        lines.add("LifecycleInventory: owner=MultiChunkBoundaryRegistry key=BoundaryKey(dimension+seed+profile+pair) lifecycle=server/runtime invalidation=clearAll|invalidateArea|ttl scope=dimension+seed+profile retainsWorld=false survivesStop=false");
        lines.add("LifecycleInventory: owner=DeferredTreeQueue key=WorldGenScope.DimensionKey lifecycle=dimension/server invalidation=clearAll|clearDimension|level unload|server stop scope=lifecycle+seed+dimension retainsWorld=false survivesStop=false");
        lines.add("LifecycleInventory: owner=DeferredTreeChunkRetainer key=WorldGenScope.DimensionKey+chunk lifecycle=dimension/server invalidation=clearAll|clearDimension|reconcile scope=lifecycle+seed+dimension retainsWorld=lookup-only survivesStop=false");
        lines.add("LifecycleInventory: owner=ShadowBlockMutationApplier key=ScopedChunk/ScopedTransaction lifecycle=dimension/server invalidation=clearAll|transaction completion scope=lifecycle+seed+dimension retainsWorld=lookup-only survivesStop=false");
        lines.add("LifecycleInventory: owner=ChunkPostProcessor key=ChunkScanKey and ResourceKey<Level> buckets lifecycle=server/runtime invalidation=server stop plus queue drain scope=dimension or chunk; not uniformly lifecycle-scoped retainsWorld=dimension info cache possible survivesStop=expected false after stop");
        lines.add("LifecycleInventory: owner=ChunkRoleProbe and Lost Cities static caches key=coord/profile-derived lifecycle=server/runtime invalidation=resetLostCitiesLifecycleCaches scope=profile+seed implied retainsWorld=false survivesStop=false");
        lines.add("LifecycleInventory: owner=Quantified/parallel queues key=task slices lifecycle=runtime invalidation=planner shutdowns and queue drains scope=input-dependent retainsWorld=false survivesStop=false");
        return lines;
    }
}
