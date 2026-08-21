package org.admany.lc2h.worldgen.lostcities;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

import java.util.Map;

public final class MidgardTreeCaptureHooks {

    // Explicit harness-only trace. It is off in normal clients/servers and
    // gives compatibility tests a reason code without hot-path logging.
    private static final boolean TEST_TRACE = Boolean.getBoolean("lc2h.shadowtest.traceMidgard");
    private static final ThreadLocal<String> TEST_TRACE_RESULT = new ThreadLocal<>();

    private MidgardTreeCaptureHooks() {
    }

    public static boolean shouldRejectFeaturePlacement(FeaturePlaceContext<?> context) {
        return decisionForFeaturePlacement(context) == TreeCapturePolicy.Decision.REJECT;
    }

    public static TreeCapturePolicy.Decision decisionForFeaturePlacement(FeaturePlaceContext<?> context) {
        if (context == null || context.level() == null || context.origin() == null) {
            return TreeCapturePolicy.Decision.PASS_THROUGH;
        }
        return TreeCapturePolicy.decideAt(context.level().getLevel(), context.origin());
    }

    public static boolean beginFeaturePlacement(FeaturePlaceContext<?> context, String hookId) {
        return beginFeaturePlacement(context, hookId, decisionForFeaturePlacement(context));
    }

    public static boolean beginFeaturePlacement(FeaturePlaceContext<?> context,
                                                String hookId,
                                                TreeCapturePolicy.Decision decision) {
        TreeCompatTracker.markHookObserved(hookId);
        if (context == null) {
            trace("context-null");
            return false;
        }
        if (DeferredTreeCaptureContext.isCapturing()) {
            trace("already-capturing");
            return false;
        }
        if (DeferredTreeQueue.isReplaying()) {
            trace("replay-active");
            return false;
        }
        if (decision != TreeCapturePolicy.Decision.CAPTURE) {
            trace("policy=" + decision.name().toLowerCase());
            return false;
        }
        WorldGenLevel level = context.level();
        ServerLevel serverLevel = level == null ? null : level.getLevel();
        BlockPos origin = context.origin();
        DeferredTreeCaptureContext.begin(
            origin,
            serverLevel.dimension(),
            serverLevel.getSeed(),
            DeferredTreeCaptureContext.CaptureSource.MIDGARD_STRUCTURE);
        trace("capture-started origin=" + origin + " level=" + serverLevel.dimension().location());
        return true;
    }

    public static void finishFeaturePlacement(boolean started, Boolean placed) {
        if (!started) {
            return;
        }
        DeferredTreeCaptureContext.CapturedTree captured = DeferredTreeCaptureContext.finish();
        if (captured == null || !Boolean.TRUE.equals(placed) || captured.blocks().isEmpty()) {
            trace("capture-discarded placed=" + placed + " blocks=" + (captured == null ? 0 : captured.blocks().size()));
            return;
        }
        TreeCompatTracker.recordCapture(captured.source(), captured.blocks().size());
        DeferredTreeQueue.enqueue(
            captured.dim(),
            DeferredTreeQueue.PendingTree.captured(
                captured.origin(),
                captured.blocks(),
                captured.dim(),
                captured.seed(),
                captured.source()));
        trace("capture-enqueued blocks=" + captured.blocks().size());
    }

    public static void resetTestTrace() {
        if (TEST_TRACE) {
            TEST_TRACE_RESULT.remove();
        }
    }

    public static String testTrace() {
        return TEST_TRACE ? TEST_TRACE_RESULT.get() : "trace-disabled";
    }

    private static void trace(String value) {
        if (TEST_TRACE) {
            TEST_TRACE_RESULT.set(value);
        }
    }

    public static void observeStructurePostPass(Map<BlockPos, BlockState> trunkPositions, WorldGenLevel level) {
        TreeCompatTracker.recordMidgardTrunkPostpass();
        // This post-pass also runs for ordinary Midgard trees outside LC2H's capture
        // policy.  It is an observation point, not proof that a deferred replay is
        // required. Capture is owned by the outer feature boundary above.
    }
}
