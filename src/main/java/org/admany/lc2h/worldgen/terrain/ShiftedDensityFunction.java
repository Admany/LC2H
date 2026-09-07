package org.admany.lc2h.worldgen.terrain;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

public final class ShiftedDensityFunction implements DensityFunction.SimpleFunction {

    @FunctionalInterface
    public interface ColumnShift {

        double shiftAt(int blockX, int blockZ);
    }

    private final DensityFunction delegate;
    private final ColumnShift shift;

    public ShiftedDensityFunction(DensityFunction delegate, ColumnShift shift) {
        this.delegate = delegate;
        this.shift = shift;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        double offset = this.shift.shiftAt(context.blockX(), context.blockZ());
        if (offset <= 0.0D) {
            return this.delegate.compute(context);
        }
        return this.delegate.compute(new DensityFunction.SinglePointContext(
            context.blockX(),
            context.blockY() + (int) Math.round(offset),
            context.blockZ()));
    }

    @Override
    public double minValue() {
        return this.delegate.minValue();
    }

    @Override
    public double maxValue() {
        return this.delegate.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return this.delegate.codec();
    }
}
