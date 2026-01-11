package org.admany.lc2h.logging.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.admany.lc2h.data.cache.CacheBudgetManager;
import org.admany.lc2h.data.cache.CombinedCacheBudgetManager;
import org.admany.lc2h.data.cache.LostCitiesCacheBridge;
import org.admany.lc2h.data.cache.LostCitiesCacheBudgetManager;
import org.admany.lc2h.logging.LCLogger;

import java.io.*;

public class ConfigManager {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static ConfigManager.Config CONFIG;

    public static boolean ENABLE_ASYNC_DOUBLE_BLOCK_BATCHER = true;
    public static boolean ENABLE_LOSTCITIES_GENERATION_LOCK = true;
    public static boolean ENABLE_LOSTCITIES_PART_SLICE_COMPAT = true;
    public static boolean CITY_BLEND_ENABLED = true;
    public static int CITY_BLEND_WIDTH = 36;
    public static double CITY_BLEND_SOFTNESS = 1.4;
    public static boolean ENABLE_CACHE_STATS_LOGGING = true;
    public static boolean ENABLE_FLOATING_VEGETATION_REMOVAL = true;
    public static boolean ENABLE_EXPLOSION_DEBRIS = false;
    public static boolean HIDE_EXPERIMENTAL_WARNING = true;
    public static boolean ENABLE_DEBUG_LOGGING = false;
    public static String UI_ACCENT_COLOR = "3A86FF";
    public static int UI_ACCENT_COLOR_RGB = 0x3A86FF;
    public static int CACHE_MAX_MB = 384;
    public static long CACHE_MAX_BYTES = 10L * 1024L * 1024L;
    public static int LOSTCITIES_CACHE_MAX_MB = 384;
    public static int CACHE_COMBINED_MAX_MB = 512;
    public static boolean CACHE_ENFORCE_COMBINED_MAX = true;
    public static boolean CACHE_SPLIT_EQUAL = false;
    public static int LOSTCITIES_CACHE_TTL_MINUTES = 20;
    public static int LOSTCITIES_CACHE_DISK_TTL_HOURS = 6;

    // City edge tree handling
    public static boolean CITY_BLEND_CLEAR_TREES = true;

    public static boolean isDedicatedServerEnv() {
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) return true;
        try {
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server != null && !server.isSingleplayer()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static class Config {
        public boolean enableAsyncDoubleBlockBatcher = true;
        public boolean enableLostCitiesGenerationLock = true;
        public boolean enableLostCitiesPartSliceCompat = true;
        public boolean enableCacheStatsLogging = true;
        public boolean enableFloatingVegetationRemoval = true;
        public boolean enableExplosionDebris = false;
        public boolean hideExperimentalWarning = true;
        public boolean enableDebugLogging = false;
        public String uiAccentColor = "3A86FF";
        public int cacheMaxMB = 384;
        public int cacheLostCitiesMaxMB = 384;
        public int cacheCombinedMaxMB = 512;
        public boolean cacheEnforceCombinedMax = true;
        public boolean cacheSplitEqual = false;
        public int cacheLostCitiesTtlMinutes = 20;
        public int cacheLostCitiesDiskTtlHours = 6;

        // City edge blending
        public boolean cityBlendEnabled = false;
        public int cityBlendWidth = 36;
        public double cityBlendSoftness = 1.4;

        // City edge tree handling
        public boolean cityBlendClearTrees = true;
    }

    public static Config loadOrCreateConfig() {
        String path = "config/lc2h/lc2h_config.json";
        boolean physIsServer = FMLEnvironment.dist == Dist.DEDICATED_SERVER;
        boolean detectedServer = isDedicatedServerEnv();
        if (!detectedServer) {
            LCLogger.info("[LC2H] [Config] Not running in a detected dedicated server environment; loading/creating config for local/integrated server.");
        }

        if (!physIsServer && detectedServer) {
            LCLogger.warn("[LC2H] [Env] ⚠ Detected dedicated server runtime but physical Dist is {}. Proceeding in server mode.", FMLEnvironment.dist);
        }

        try {
            File configFile = new File(path);
            Config userConfig = null;
            if (configFile.exists()) {
                try (FileReader reader = new FileReader(configFile)) {
                    JsonReader jsonReader = new JsonReader(reader);
                    jsonReader.setLenient(true);
                    userConfig = GSON.fromJson(jsonReader, Config.class);
                    if (userConfig == null) {
                        LCLogger.warn("[LC2H] [Config] ⚠ Config file was empty or invalid, creating new one");
                        userConfig = new Config();
                    }
                } catch (Exception e) {
                    LCLogger.error("[LC2H] [Config] ❌ Failed to parse config file, backing up and creating new one: " + e.getMessage());
                    backupConfigFile(path);
                    userConfig = new Config();
                }
            } else {
                LCLogger.info("[LC2H] [Config] ⓘ No config file found, creating default one");
                userConfig = new Config();
            }

            Config defaultConfig = new Config();
            Config merged = defaultConfig;
            try {
                for (java.lang.reflect.Field field : Config.class.getFields()) {
                    Object userVal = field.get(userConfig);
                    Object defVal = field.get(defaultConfig);
                    if (userVal != null && !userVal.equals(defVal)) {
                        field.set(merged, userVal);
                    }
                }
            } catch (Exception e) {
                LCLogger.error("[LC2H] [Config] ❌ Failed to merge config fields: " + e.getMessage());
            }

            writePrettyJsonConfig(merged);

            return merged;
        } catch (Exception e) {
            LCLogger.error("[LC2H] [Config] ❌ Failed to load/create config, using defaults: " + e.getMessage());
            return new Config();
        }
    }

    public static void writePrettyJsonConfig(Config merged) {
        String path = "config/lc2h/lc2h_config.json";
        File configFile = new File(path);
        File parentDir = configFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        java.util.Map<String, String> comments = new java.util.LinkedHashMap<>();
        // General Settings
        comments.put("enableAsyncDoubleBlockBatcher", "Enable async batching for double blocks");
        comments.put("enableLostCitiesGenerationLock", "Recommended: serialize nearby Lost Cities chunk-gen to avoid bugged/duplicated chunks (may reduce max throughput)");
        comments.put("enableLostCitiesPartSliceCompat", "Recommended: prevent crashes from broken/invalid Lost Cities building parts (safe bounds checks)");
        comments.put("enableCacheStatsLogging", "Enable cache stats logging");
        comments.put("enableFloatingVegetationRemoval", "Enable removal of floating vegetation after terrain generation");
        comments.put("enableExplosionDebris", "Enable Lost Cities explosion debris spill into adjacent chunks (can add rubble around streets)");
        comments.put("hideExperimentalWarning", "Hide the experimental features warning screen");
        comments.put("enableDebugLogging", "Enable debug logging for memory management and warmup operations");
        comments.put("uiAccentColor", "UI accent color in hex (example: 3A86FF)");
        comments.put("cacheMaxMB", "Max LC2H cache budget (MB).");
        comments.put("cacheLostCitiesMaxMB", "Max Lost Cities cache budget (MB).");
        comments.put("cacheCombinedMaxMB", "Max combined LC2H + Lost Cities cache budget (MB).");
        comments.put("cacheEnforceCombinedMax", "If true, enforce combined max by evicting from the largest cache.");
        comments.put("cacheSplitEqual", "If true, split the combined max evenly between LC2H and Lost Cities.");
        comments.put("cacheLostCitiesTtlMinutes", "Lost Cities RAM cache TTL (minutes).");
        comments.put("cacheLostCitiesDiskTtlHours", "Lost Cities disk cache TTL (hours).");

        // City edge blending
        comments.put("cityBlendEnabled", "Enable smooth blending of city edges into surrounding terrain");
        comments.put("cityBlendWidth", "Blend width in blocks around city borders");
        comments.put("cityBlendSoftness", "Blend softness (higher = softer falloff)");
        comments.put("cityBlendClearTrees", "Prevent trees from generating near city borders (within the blend distance)");

        try (PrintWriter w = new PrintWriter(new FileWriter(path))) {
            w.println("/*");
            w.println("==============================================");
            w.println("|             LC2H - Configuration           |");
            w.println("|             Author: Admany                 |");
            w.println("|             All Rights Reserved            |");
            w.println("==============================================");
            w.println("|  This config file auto-updates itself if   |");
            w.println("|  there are config changes. You do NOT need |");
            w.println("|  to delete it for new features or updates! |");
            w.println("==============================================");
            w.println("*/");
            w.println();
            w.println("{");
            w.println("  \"_comment\": \"Edit this file to configure LC2H. For documentation, visit the mod's wiki.\",");

            java.util.LinkedHashMap<String, String[]> groups = new java.util.LinkedHashMap<>();
            groups.put("General Settings", new String[]{
                "enableAsyncDoubleBlockBatcher",
                "enableLostCitiesGenerationLock",
                "enableLostCitiesPartSliceCompat",
                "enableCacheStatsLogging",
                "enableFloatingVegetationRemoval",
                "enableExplosionDebris",
                "hideExperimentalWarning",
                "enableDebugLogging",
            });
            groups.put("Caching", new String[]{
                "cacheCombinedMaxMB",
                "cacheEnforceCombinedMax",
                "cacheSplitEqual",
                "cacheMaxMB",
                "cacheLostCitiesMaxMB",
                "cacheLostCitiesTtlMinutes",
                "cacheLostCitiesDiskTtlHours",
            });
            groups.put("Interface", new String[]{
                "uiAccentColor"
            });
            groups.put("City Edge", new String[]{
                "cityBlendEnabled",
                "cityBlendWidth",
                "cityBlendSoftness",
                "cityBlendClearTrees"
            });

            java.util.List<String> outputLines = new java.util.ArrayList<>();
            java.util.List<String> jsonFieldLines = new java.util.ArrayList<>();
            for (String group : groups.keySet()) {
                outputLines.add("");
                outputLines.add(String.format("  // === %s ===", group));
                for (String name : groups.get(group)) {
                    Object value = merged.getClass().getField(name).get(merged);
                    String comment = comments.get(name);
                    if (comment != null) {
                        outputLines.add(String.format("  // %s", comment));
                    }
                    String valueStr;
                    if (value instanceof String) {
                        valueStr = String.format("\"%s\"", ((String)value).replace("\"", "\\\""));
                    } else {
                        valueStr = String.valueOf(value);
                    }
                    String jsonLine = String.format("  \"%s\": %s", name, valueStr);
                    outputLines.add(jsonLine);
                    jsonFieldLines.add(jsonLine);
                }
            }
            int jsonFieldIdx = 0;
            int jsonFieldCount = jsonFieldLines.size();
            for (String line : outputLines) {
                if (jsonFieldLines.contains(line)) {
                    jsonFieldIdx++;
                    if (jsonFieldIdx < jsonFieldCount) {
                        w.println(line + ",");
                    } else {
                        w.println(line);
                    }
                } else {
                    w.println(line);
                }
            }
            w.println("}");
        } catch (Exception e) {
            e.printStackTrace();
        }
        try (FileReader reader = new FileReader(path)) {
            Config testConfig = GSON.fromJson(reader, Config.class);
            if (testConfig == null) throw new IOException("Config file is invalid after writing");
        } catch (Exception e) {
            System.err.println("[LC2H] Config file was invalid after writing. Backing up and regenerating a valid config.");
            backupConfigFile(path);
            try {
                writePrettyJsonConfig(new Config());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    public static void backupConfigFile(String path) {
        File configFile = new File(path);
        if (configFile.exists()) {
            File backupFile = new File(path + ".bak");
            try (InputStream in = new FileInputStream(configFile); OutputStream out = new FileOutputStream(backupFile)) {
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    public static void initializeGlobals() {
        CONFIG = loadOrCreateConfig();
        ENABLE_ASYNC_DOUBLE_BLOCK_BATCHER = CONFIG.enableAsyncDoubleBlockBatcher;
        ENABLE_LOSTCITIES_GENERATION_LOCK = CONFIG.enableLostCitiesGenerationLock;
        ENABLE_LOSTCITIES_PART_SLICE_COMPAT = CONFIG.enableLostCitiesPartSliceCompat;
        ENABLE_CACHE_STATS_LOGGING = CONFIG.enableCacheStatsLogging;
        ENABLE_FLOATING_VEGETATION_REMOVAL = CONFIG.enableFloatingVegetationRemoval;
        ENABLE_EXPLOSION_DEBRIS = CONFIG.enableExplosionDebris;
        HIDE_EXPERIMENTAL_WARNING = CONFIG.hideExperimentalWarning;
        ENABLE_DEBUG_LOGGING = CONFIG.enableDebugLogging;
        UI_ACCENT_COLOR = CONFIG.uiAccentColor != null ? CONFIG.uiAccentColor : UI_ACCENT_COLOR;
        UI_ACCENT_COLOR_RGB = parseHexColor(UI_ACCENT_COLOR, 0x3A86FF);
        CACHE_COMBINED_MAX_MB = Math.max(16, CONFIG.cacheCombinedMaxMB);
        CACHE_ENFORCE_COMBINED_MAX = CONFIG.cacheEnforceCombinedMax;
        CACHE_SPLIT_EQUAL = CONFIG.cacheSplitEqual;
        int desiredLc2hMax = Math.max(8, CONFIG.cacheMaxMB);
        int desiredLostCitiesMax = Math.max(8, CONFIG.cacheLostCitiesMaxMB);
        if (CACHE_SPLIT_EQUAL) {
            int half = Math.max(8, CACHE_COMBINED_MAX_MB / 2);
            desiredLc2hMax = half;
            desiredLostCitiesMax = half;
        }
        CACHE_MAX_MB = desiredLc2hMax;
        CACHE_MAX_BYTES = CACHE_MAX_MB * 1024L * 1024L;
        CacheBudgetManager.applyMaxBytes(CACHE_MAX_BYTES);
        LOSTCITIES_CACHE_MAX_MB = desiredLostCitiesMax;
        LostCitiesCacheBudgetManager.applyMaxBytes(LOSTCITIES_CACHE_MAX_MB * 1024L * 1024L);
        LOSTCITIES_CACHE_TTL_MINUTES = Math.max(1, CONFIG.cacheLostCitiesTtlMinutes);
        LOSTCITIES_CACHE_DISK_TTL_HOURS = Math.max(1, CONFIG.cacheLostCitiesDiskTtlHours);
        LostCitiesCacheBudgetManager.applyTtlMinutes(LOSTCITIES_CACHE_TTL_MINUTES);
        LostCitiesCacheBridge.applyDiskTtlHours(LOSTCITIES_CACHE_DISK_TTL_HOURS);
        CombinedCacheBudgetManager.apply(CACHE_ENFORCE_COMBINED_MAX, CACHE_COMBINED_MAX_MB * 1024L * 1024L);
        CITY_BLEND_ENABLED = CONFIG.cityBlendEnabled;
        CITY_BLEND_WIDTH = CONFIG.cityBlendWidth;
        CITY_BLEND_SOFTNESS = CONFIG.cityBlendSoftness;
        CITY_BLEND_CLEAR_TREES = CONFIG.cityBlendClearTrees;
    }

    private static int parseHexColor(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return fallback;
        }
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.startsWith("0x") || value.startsWith("0X")) {
            value = value.substring(2);
        }
        if (value.length() == 3) {
            char r = value.charAt(0);
            char g = value.charAt(1);
            char b = value.charAt(2);
            value = "" + r + r + g + g + b + b;
        }
        if (value.length() != 6) {
            return fallback;
        }
        try {
            return Integer.parseInt(value, 16) & 0xFFFFFF;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
