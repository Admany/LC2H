package org.admany.lc2h.data;

import java.util.concurrent.ConcurrentHashMap;


public class PrecomputedData {
    public final NoiseData noiseData;
    public final BuildingData buildingData;
    public final CacheData cacheData;

    public PrecomputedData(NoiseData noiseData, BuildingData buildingData, CacheData cacheData) {
        this.noiseData = noiseData;
        this.buildingData = buildingData;
        this.cacheData = cacheData;
    }

    public static final double[][] EMPTY_NOISE = new double[0][0];

    public static class NoiseData {
        public final double[][] densityValues;
        public final double[][] aquiferData;
        public final int preliminarySurface;

        public NoiseData(double[][] densityValues, double[][] aquiferData, int preliminarySurface) {
            this.densityValues = densityValues;
            this.aquiferData = aquiferData;
            this.preliminarySurface = preliminarySurface;
        }
    }

    public static class BuildingData {
        public final int cityLevel;
        public final boolean isCity;
        public final String cityStyle;
        public final HeightData heightData;

        public BuildingData(int cityLevel, boolean isCity, String cityStyle, HeightData heightData) {
            this.cityLevel = cityLevel;
            this.isCity = isCity;
            this.cityStyle = cityStyle;
            this.heightData = heightData;
        }
    }

    public static class HeightData {
        public final int minHeight;
        public final int maxHeight;
        public final int avgHeight;

        public HeightData(int minHeight, int maxHeight, int avgHeight) {
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            this.avgHeight = avgHeight;
        }
    }

    public static class CacheData {
        public final ConcurrentHashMap<String, Object> precomputedValues = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        public <T> T get(String key, Class<T> type) {
            return (T) precomputedValues.get(key);
        }

        public void put(String key, Object value) {
            precomputedValues.put(key, value);
        }
    }
}