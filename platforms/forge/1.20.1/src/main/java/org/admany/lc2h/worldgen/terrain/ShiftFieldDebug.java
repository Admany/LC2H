package org.admany.lc2h.worldgen.terrain;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.fml.loading.FMLPaths;
import org.admany.lc2h.util.server.DimensionInfoAccessor;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

public final class ShiftFieldDebug {

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private ShiftFieldDebug() {
    }

    private static CityShiftField.Context context(ServerLevel level) {
        IDimensionInfo provider = DimensionInfoAccessor.getForLevel(level);
        if (provider == null) {
            return null;
        }
        LostCityProfile profile;
        try {
            profile = provider.getProfile();
        } catch (Exception ignored) {
            return null;
        }
        return CityShiftField.context(provider, profile, NaturalHeightSampler.forLevel(level));
    }

    public static List<String> renderMap(ServerLevel level, BlockPos centre, int radiusChunks) {
        List<String> out = new ArrayList<>();
        CityShiftField.Context context = context(level);
        if (context == null) {
            out.add("shift map: no Lost Cities provider for this dimension");
            return out;
        }

        int radius = Math.max(4, Math.min(96, radiusChunks));

        int step = 4;
        int spanBlocks = radius * 2 * 16;
        int size = spanBlocks / step;
        int originX = (centre.getX() >> 4 << 4) - radius * 16;
        int originZ = (centre.getZ() >> 4 << 4) - radius * 16;

        double[][] shift = new double[size][size];
        double maxShift = 0.0D;
        for (int pz = 0; pz < size; pz++) {
            for (int px = 0; px < size; px++) {
                double value = CityShiftField.sample(context,
                    originX + px * step, originZ + pz * step);
                shift[pz][px] = value;
                maxShift = Math.max(maxShift, value);
            }
        }

        BufferedImage shiftImage = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        BufferedImage slopeImage = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        double slopeCap = Math.max(0.05D, context.settings().slopeSteep() * 1.5D);
        double worstGradient = 0.0D;
        for (int pz = 0; pz < size; pz++) {
            for (int px = 0; px < size; px++) {
                shiftImage.setRGB(px, pz, heat(maxShift <= 0.0D ? 0.0D : shift[pz][px] / maxShift));

                double here = shift[pz][px];
                double east = shift[pz][Math.min(size - 1, px + 1)];
                double south = shift[Math.min(size - 1, pz + 1)][px];
                double gradient = Math.hypot(east - here, south - here) / step;
                worstGradient = Math.max(worstGradient, gradient);
                slopeImage.setRGB(px, pz, heat(Math.min(1.0D, gradient / slopeCap)));
            }
        }

        int mid = size / 2;
        for (int d = -3; d <= 3; d++) {
            int a = Math.max(0, Math.min(size - 1, mid + d));
            shiftImage.setRGB(a, mid, 0xFFFFFF);
            shiftImage.setRGB(mid, a, 0xFFFFFF);
            slopeImage.setRGB(a, mid, 0xFFFFFF);
            slopeImage.setRGB(mid, a, 0xFFFFFF);
        }

        String stamp = LocalDateTime.now().format(STAMP);
        Path dir = FMLPaths.GAMEDIR.get().resolve("logs").resolve("lc2h");
        try {
            Files.createDirectories(dir);
            Path shiftPath = dir.resolve("shiftfield-" + stamp + ".png");
            Path slopePath = dir.resolve("shiftslope-" + stamp + ".png");
            ImageIO.write(shiftImage, "png", shiftPath.toFile());
            ImageIO.write(slopeImage, "png", slopePath.toFile());
            out.add("settings: " + context.settings().describe());
            out.add("area: " + (radius * 2) + "x" + (radius * 2) + " chunks around "
                + centre.getX() + "," + centre.getZ() + " at " + step + " blocks/pixel");
            out.add("maxShift in view = " + String.format(Locale.ROOT, "%.1f", maxShift)
                + " blocks, peak field gradient = " + String.format(Locale.ROOT, "%.2f", worstGradient)
                + " (limits " + String.format(Locale.ROOT, "%.2f..%.2f",
                    context.settings().slopeFlat(), context.settings().slopeSteep()) + ")");
            out.add("shift map  (black=0, white=max shift): " + shiftPath);
            out.add("slope map  (black=flat, white=" + String.format(Locale.ROOT, "%.2f", slopeCap)
                + "+ field gradient): " + slopePath);
            out.add("a crease or hard edge in the slope map is a blend artefact; a uniform ring"
                + " around each city is the intended skirt. This is the model - use"
                + " '/lc2h dev blend slope' for the gradient of the terrain that actually got built.");
        } catch (IOException e) {
            out.add("shift map: could not write image - " + e.getMessage());
        }
        return out;
    }

    private static int heat(double t) {
        double v = Math.max(0.0D, Math.min(1.0D, t));
        int r = (int) Math.round(255 * Math.min(1.0D, v * 3.0D));
        int g = (int) Math.round(255 * Math.min(1.0D, Math.max(0.0D, v * 3.0D - 1.0D)));
        int b = (int) Math.round(255 * Math.min(1.0D, Math.max(0.0D, v * 3.0D - 2.0D)));
        return (r << 16) | (g << 8) | b;
    }

    public static List<String> slopeReport(ServerLevel level, BlockPos centre, int radiusChunks) {
        List<String> out = new ArrayList<>();
        int radius = Math.max(1, Math.min(32, radiusChunks));
        int originX = (centre.getX() >> 4 << 4) - radius * 16;
        int originZ = (centre.getZ() >> 4 << 4) - radius * 16;
        int span = radius * 32;

        List<Double> gradients = new ArrayList<>(span * span / 4);
        double worst = 0.0D;
        int worstX = 0;
        int worstZ = 0;
        for (int dz = 0; dz < span; dz += 2) {
            for (int dx = 0; dx < span; dx += 2) {
                int x = originX + dx;
                int z = originZ + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                if (!level.hasChunk((x + 2) >> 4, z >> 4) || !level.hasChunk(x >> 4, (z + 2) >> 4)) {
                    continue;
                }
                int h = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                int hx = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x + 2, z);
                int hz = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z + 2);
                double gradient = Math.hypot(hx - h, hz - h) / 2.0D;
                gradients.add(gradient);
                if (gradient > worst) {
                    worst = gradient;
                    worstX = x;
                    worstZ = z;
                }
            }
        }

        CityShiftField.Context context = context(level);
        if (context != null) {
            out.add("settings: " + context.settings().describe());
        }
        if (gradients.isEmpty()) {
            out.add("slope: no loaded chunks in a " + (radius * 2) + " chunk radius - move there first");
            return out;
        }
        gradients.sort(Double::compareTo);
        out.add("samples=" + gradients.size() + " over " + (radius * 2) + "x" + (radius * 2) + " chunks");
        out.add("slope  median=" + fmt(percentile(gradients, 0.50D))
            + "  p90=" + fmt(percentile(gradients, 0.90D))
            + "  p99=" + fmt(percentile(gradients, 0.99D))
            + "  max=" + fmt(worst));
        out.add("worst column at " + worstX + "," + worstZ);

        double[] buckets = {0.1D, 0.25D, 0.5D, 0.75D, 1.0D, 1.5D, 99D};
        String[] labels = {"<0.10 (6deg)", "<0.25 (14deg)", "<0.50 (27deg)", "<0.75 (37deg)",
            "<1.00 (45deg)", "<1.50 (56deg)", ">=1.50 (cliff)"};
        int[] counts = new int[buckets.length];
        for (double gradient : gradients) {
            for (int i = 0; i < buckets.length; i++) {
                if (gradient < buckets[i]) {
                    counts[i]++;
                    break;
                }
            }
        }
        for (int i = 0; i < counts.length; i++) {
            double share = 100.0D * counts[i] / gradients.size();
            out.add(String.format(Locale.ROOT, "  %-16s %5.1f%% %s",
                labels[i], share, bar(share)));
        }
        if (context != null) {
            out.addAll(reliefReport(level, context, originX, originZ, span));
        }
        return out;
    }

    private static List<String> reliefReport(ServerLevel level,
                                             CityShiftField.Context context,
                                             int originX,
                                             int originZ,
                                             int span) {
        List<String> out = new ArrayList<>();

        int window = 32;
        double[] shiftSum = new double[4];
        double[] reliefSum = new double[4];
        int[] counts = new int[4];

        for (int dz = 0; dz + window < span; dz += window) {
            for (int dx = 0; dx + window < span; dx += window) {
                int baseX = originX + dx;
                int baseZ = originZ + dz;
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                boolean complete = true;
                for (int sz = 0; sz <= window && complete; sz += 8) {
                    for (int sx = 0; sx <= window; sx += 8) {
                        int x = baseX + sx;
                        int z = baseZ + sz;
                        if (!level.hasChunk(x >> 4, z >> 4)) {
                            complete = false;
                            break;
                        }
                        int h = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                        min = Math.min(min, h);
                        max = Math.max(max, h);
                    }
                }
                if (!complete) {
                    continue;
                }
                double shift = CityShiftField.sample(context, baseX + window / 2, baseZ + window / 2);
                int band = shift <= 0.5D ? 0 : shift < 16.0D ? 1 : shift < 48.0D ? 2 : 3;
                shiftSum[band] += shift;
                reliefSum[band] += max - min;
                counts[band]++;
            }
        }

        String[] labels = {"untouched (shift 0)", "skirt toe  (0-16)",
            "skirt mid  (16-48)", "near city  (48+)"};
        out.add("relief in 32-block windows, by how far the field lowered the column:");
        double baseline = counts[0] > 0 ? reliefSum[0] / counts[0] : Double.NaN;
        for (int i = 0; i < labels.length; i++) {
            if (counts[i] == 0) {
                out.add(String.format(Locale.ROOT, "  %-21s no samples", labels[i]));
                continue;
            }
            double relief = reliefSum[i] / counts[i];
            out.add(String.format(Locale.ROOT, "  %-21s relief=%5.1f  avgShift=%5.1f  n=%d %s",
                labels[i], relief, shiftSum[i] / counts[i], counts[i], bar(relief * 2.0D)));
        }
        if (!Double.isNaN(baseline) && counts[2] > 0) {
            double mid = reliefSum[2] / counts[2];
            out.add(String.format(Locale.ROOT,
                "skirt relief is %.0f%% of surrounding untouched terrain "
                    + "(relief strength %.2f; lower this ratio by raising it)",
                100.0D * mid / Math.max(1.0E-6D, baseline), context.settings().reliefStrength()));
        } else {
            out.add("need both shifted and untouched terrain loaded in range for the comparison");
        }
        return out;
    }

    private static double percentile(List<Double> sorted, double q) {
        int index = (int) Math.floor(q * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String bar(double percent) {
        int length = (int) Math.round(percent / 4.0D);
        return "#".repeat(Math.max(0, Math.min(25, length)));
    }
}
