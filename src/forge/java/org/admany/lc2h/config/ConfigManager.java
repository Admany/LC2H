package org.admany.lc2h.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.admany.lc2h.data.cache.CacheBudgetManager;
import org.admany.lc2h.data.cache.CombinedCacheBudgetManager;
import org.admany.lc2h.data.cache.LostCitiesCacheBridge;
import org.admany.lc2h.data.cache.LostCitiesCacheBudgetManager;
import org.admany.lc2h.log.LCLogger;
import org.admany.lc2h.worldgen.lostcities.LostCitiesStreetModePolicy;
import org.admany.lc2h.worldgen.terrain.CityShiftField;
import org.admany.lc2h.util.ResourceLocations;

import java.io.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public class ConfigManager {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static ConfigManager.Config CONFIG;

    public static final String DEFAULT_GLOW_LICHEN_ID = "minecraft:glow_lichen";
    public static final String IMMERSIVE_WEATHERING_FROST_ID = "immersive_weathering:frost";

    public static boolean ENABLE_ASYNC_DOUBLE_BLOCK_BATCHER = true;
    public static boolean ENABLE_AUTOMATIC_CHUNK_SCANS = false;
    public static boolean REJECT_STRUCTURES_IN_CITY_CHUNKS = false;
    public static int CITY_STRUCTURE_REJECTION_BUFFER_CHUNKS = 0;
    public static boolean CITY_VERTICAL_TERRAIN_CLEARANCE = false;
    public static boolean ENABLE_LOSTCITIES_GENERATION_LOCK = true;
    public static boolean ENABLE_LOSTCITIES_PART_SLICE_COMPAT = true;
    public static String LOSTCITIES_STREET_GENERATION_MODE = "LEGACY";
    /* Experimental terrain shaping is opt-in.  Enabling it on a migrated or
     * freshly-created profile changes native Lost Cities terrain before the
     * player has a chance to inspect the A/B result. */
    public static boolean CITY_BLEND_ENABLED = false;
    public static int CITY_BLEND_WIDTH = 36;
    public static double CITY_BLEND_SOFTNESS = 1.4;
    public static boolean ENABLE_CACHE_STATS_LOGGING = true;
    public static boolean ENABLE_FLOATING_VEGETATION_REMOVAL = true;
    public static boolean ENABLE_EXPLOSION_DEBRIS = false;
    public static boolean HIDE_EXPERIMENTAL_WARNING = true;
    public static boolean ENABLE_DEBUG_LOGGING = false;
    public static String UI_ACCENT_COLOR = "3A86FF";
    public static int UI_ACCENT_COLOR_RGB = 0x3A86FF;
    public static String UI_LOCALE = "en_us";
    public static int CACHE_MAX_MB = 384;
    public static long CACHE_MAX_BYTES = 10L * 1024L * 1024L;
    public static int LOSTCITIES_CACHE_MAX_MB = 128;
    public static int CACHE_COMBINED_MAX_MB = 256;
    public static boolean CACHE_ENFORCE_COMBINED_MAX = true;
    public static boolean CACHE_SPLIT_EQUAL = false;
    public static int LOSTCITIES_CACHE_TTL_MINUTES = 10;
    public static int LOSTCITIES_CACHE_DISK_TTL_HOURS = 2;

    // Trees crossing an LC edge are captured and replayed safely. This stays on.
    public static boolean CITY_BLEND_CLEAR_TREES = true;
    public static boolean CITY_BLEND_TREE_SEAM_FIX = true;
    public static int CITY_BLEND_TREE_SEAM_BUFFER = 3;
    public static float TREE_SEAM_RADIUS_MULTIPLIER = 1.0f;
    public static boolean SEAM_OWNERSHIP_ENABLED = false;
    public static int SEAM_OWNERSHIP_MAX_INTENTS_PER_CHUNK = 8192;
    public static long SEAM_OWNERSHIP_INTENT_TTL_MS = 10L * 60L * 1000L;
    public static int HIGHWAY_SUPPORT_MAX_DEPTH = 192;

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
        public boolean enableAutomaticChunkScans = false;
        public boolean rejectStructuresInCityChunks = false;
        public int cityStructureRejectionBufferChunks = 0;
    // Kept so old config files still migrate. Native LC terrain correction owns hills now.
        public boolean cityVerticalTerrainClearance = false;
        public boolean enableLostCitiesGenerationLock = true;
        public boolean enableLostCitiesPartSliceCompat = true;
        public String lostCitiesStreetGenerationMode = "LEGACY";
        public boolean enableCacheStatsLogging = true;
        public boolean enableFloatingVegetationRemoval = true;
        /**
         * Additional block ids treated as attachment vegetation by the
         * post-generation cleanup.  Keep this data-driven so modded blocks
         * do not require another LC2H release just to become eligible.
         */
        public java.util.List<String> floatingVegetationAdditionalBlocks =
            new java.util.ArrayList<>(java.util.List.of(
                DEFAULT_GLOW_LICHEN_ID));
        public boolean enableExplosionDebris = false;
        public boolean hideExperimentalWarning = true;
        public boolean enableDebugLogging = false;
        public String uiAccentColor = "3A86FF";
        public String uiLocale = "en_us";
        public int cacheMaxMB = 384;
        public int cacheLostCitiesMaxMB = 128;
        public int cacheCombinedMaxMB = 256;
        public boolean cacheEnforceCombinedMax = true;
        public boolean cacheSplitEqual = false;
        public int cacheLostCitiesTtlMinutes = 10;
        public int cacheLostCitiesDiskTtlHours = 2;

        // City edge blending
        public boolean cityBlendEnabled = false;
        public int cityBlendWidth = 36;
        public double cityBlendSoftness = 1.4;

        // City edge tree handling
        public boolean cityBlendClearTrees = true;
        public boolean cityBlendTreeSeamFix = true;
        public int cityBlendTreeSeamBuffer = 3;
        public float treeSeamRadiusMultiplier = 1.0f;
        public boolean seamOwnershipEnabled = false;
        public int seamOwnershipMaxIntentsPerChunk = 8192;
        public long seamOwnershipIntentTtlMs = 10L * 60L * 1000L;
        public int highwaySupportMaxDepth = 192;
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
            JsonObject userFields = new JsonObject();
            if (configFile.exists()) {
                try (FileReader reader = new FileReader(configFile)) {
                    JsonReader jsonReader = new JsonReader(reader);
                    jsonReader.setLenient(true);
                    var parsed = JsonParser.parseReader(jsonReader);
                    if (parsed != null && parsed.isJsonObject()) {
                        userFields = parsed.getAsJsonObject();
                        userConfig = GSON.fromJson(parsed, Config.class);
                    }
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

            Config merged = new Config();
            try {
                for (java.lang.reflect.Field field : Config.class.getFields()) {
            // Gson fills missing primitives with false or zero. Merge only fields that
            // are present so new true-by-default options survive config migration.
                    if (userFields.has(field.getName())) {
                        field.set(merged, field.get(userConfig));
                    }
                }
            } catch (Exception e) {
                LCLogger.error("[LC2H] [Config] ❌ Failed to merge config fields: " + e.getMessage());
            }

            // Drop obsolete opt-outs before writing the normalized config. The old
            // vertical clear deleted terrain blindly and the tree flags caused rejects.
            merged.cityVerticalTerrainClearance = false;
            merged.cityBlendClearTrees = true;
            merged.cityBlendTreeSeamFix = true;
            // The street planner is now a live LC2H setting. Normalize old or
            // hand-edited values while migrating the JSON file.
            merged.lostCitiesStreetGenerationMode = LostCitiesStreetModePolicy.normalizeValue(merged.lostCitiesStreetGenerationMode);
            merged.floatingVegetationAdditionalBlocks = normalizeFloatingVegetationBlocks(
                merged.floatingVegetationAdditionalBlocks);
            writePrettyJsonConfig(merged);

            return merged;
        } catch (Exception e) {
            LCLogger.error("[LC2H] [Config] ❌ Failed to load/create config, using defaults: " + e.getMessage());
            return new Config();
        }
    }

    /**
     * Built-in attachment vegetation is always eligible for the general
     * floating cleanup. Immersive Weathering's frost is only advertised as a
     * default when that block is actually registered by the current pack.
     */
    public static List<String> defaultFloatingVegetationBlocks() {
        LinkedHashSet<String> defaults = new LinkedHashSet<>();
        defaults.add(DEFAULT_GLOW_LICHEN_ID);
        try {
            ResourceLocation frost = ResourceLocations.tryParse(IMMERSIVE_WEATHERING_FROST_ID);
            if (frost != null && ForgeRegistries.BLOCKS.containsKey(frost)) {
                defaults.add(IMMERSIVE_WEATHERING_FROST_ID);
            }
        } catch (Throwable ignored) {
            // Registry access is not guaranteed during very early config
            // loading; the runtime predicate retries it after registration.
        }
        return List.copyOf(defaults);
    }

    /**
     * Normalizes a JSON/UI list and merges mandatory built-in defaults. Empty
     * tokens and malformed registry ids are ignored so a typo never makes the
     * cleanup scan throw or match an unrelated block.
     */
    public static List<String> normalizeFloatingVegetationBlocks(Collection<?> configured) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>(defaultFloatingVegetationBlocks());
        if (configured != null) {
            for (Object raw : configured) {
                if (raw == null) {
                    continue;
                }
                String value = raw.toString().trim().toLowerCase(Locale.ROOT);
                if (value.isEmpty()) {
                    continue;
                }
                // Config JSON stores one id per item, while the screen accepts
                // comma/newline separated input for easy editing.
                for (String token : value.split("[,\\r\\n]+")) {
                    String id = token.trim();
                    if (!id.isEmpty() && ResourceLocations.tryParse(id) != null) {
                        normalized.add(id);
                    }
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    public static List<String> parseFloatingVegetationText(String text) {
        return normalizeFloatingVegetationBlocks(text == null ? List.of() : List.of(text));
    }

    public static String floatingVegetationText(Collection<?> configured) {
        return String.join(", ", normalizeFloatingVegetationBlocks(configured));
    }

    /**
     * Registry-backed defaults are refreshed after Forge has fired registry
     * events. This matters because the mod constructor loads config before a
     * third-party block such as Immersive Weathering's frost is registered.
     */
    public static void refreshFloatingVegetationDefaults() {
        if (CONFIG == null) {
            return;
        }
        List<String> normalized = normalizeFloatingVegetationBlocks(CONFIG.floatingVegetationAdditionalBlocks);
        if (!normalized.equals(CONFIG.floatingVegetationAdditionalBlocks)) {
            CONFIG.floatingVegetationAdditionalBlocks = normalized;
            writePrettyJsonConfig(CONFIG);
            LCLogger.info("[LC2H] [Config] Floating vegetation defaults refreshed: {}", normalized);
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
        comments.put("enableAutomaticChunkScans", "Scan every loaded chunk for legacy floating/double-block cleanup. Expensive in large modpacks and disabled by default; use /lc2h cleanup chunk for targeted repair.");
        comments.put("rejectStructuresInCityChunks", "Legacy broad structure toggle. LC2H always keeps the complete-start collision guard active so vanilla and modded structures cannot be cut by city boundaries.");
        comments.put("cityStructureRejectionBufferChunks", "Extra non-city chunk ring kept clear around cities when rejecting structures (0-4).");
        comments.put("cityVerticalTerrainClearance", "Retired compatibility field. LC2H always preserves native Lost Cities terrain shaping.");
        comments.put("enableLostCitiesGenerationLock", "Recommended: serialize nearby Lost Cities chunk-gen to avoid bugged/duplicated chunks (may reduce max throughput)");
        comments.put("enableLostCitiesPartSliceCompat", "Recommended: prevent crashes from broken/invalid Lost Cities building parts (safe bounds checks)");
        comments.put("lostCitiesStreetGenerationMode", "Lost Cities street planner. LEGACY is the LC2H-safe default; HIERARCHICAL_GRID_V1 is the Lost Cities 7.5.x grid. Applies to chunks planned after saving, without a restart.");
        comments.put("enableCacheStatsLogging", "Enable cache stats logging");
        comments.put("enableFloatingVegetationRemoval", "Enable removal of floating vegetation after terrain generation");
        comments.put("floatingVegetationAdditionalBlocks", "Additional block ids treated as attachment vegetation by the general floating cleanup. Glow lichen is built in; Immersive Weathering frost is added when registered.");
        comments.put("enableExplosionDebris", "Enable Lost Cities explosion debris spill into adjacent chunks (can add rubble around streets)");
        comments.put("hideExperimentalWarning", "Hide the experimental features warning screen");
        comments.put("enableDebugLogging", "Enable debug logging for memory management and warmup operations");
        comments.put("uiAccentColor", "UI accent color in hex (example: 3A86FF)");
        comments.put("uiLocale", "Preferred LC2H config screen language (example: en_us, lt_lt).");
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
        comments.put("cityBlendClearTrees", "Always active: protect city boundaries from unsafe tree placement.");
        comments.put("cityBlendTreeSeamFix", "Always active: capture and replay trees that cross Lost Cities seams.");
        comments.put("cityBlendTreeSeamBuffer", "Buffer (blocks) from a chunk edge to block seam-crossing trees");
        comments.put("treeSeamRadiusMultiplier", "Multiplier for auto-detected tree spread at seams (raise for giant tree modpacks)");
        comments.put("seamOwnershipEnabled", "Experimental: defer cross-chunk Lost Cities writes through a seam journal. Disabled by default because it changes native generation order.");
        comments.put("seamOwnershipMaxIntentsPerChunk", "Maximum deferred seam write intents per target chunk");
        comments.put("seamOwnershipIntentTtlMs", "How long deferred seam write intents are kept before expiring (milliseconds)");
        comments.put("highwaySupportMaxDepth", "Maximum downward support depth for Lost Cities highway pillars (higher reaches seabed in deep oceans)");

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
                "enableAutomaticChunkScans",
                "rejectStructuresInCityChunks",
                "cityStructureRejectionBufferChunks",
                "cityVerticalTerrainClearance",
                "enableLostCitiesGenerationLock",
                "enableLostCitiesPartSliceCompat",
                "lostCitiesStreetGenerationMode",
                "enableCacheStatsLogging",
                "enableFloatingVegetationRemoval",
                "floatingVegetationAdditionalBlocks",
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
                "uiAccentColor",
                "uiLocale"
            });
            groups.put("City Edge", new String[]{
                "cityBlendEnabled",
                "cityBlendWidth",
                "cityBlendSoftness",
                "cityBlendClearTrees",
                "cityBlendTreeSeamFix",
                "cityBlendTreeSeamBuffer",
                "treeSeamRadiusMultiplier",
                "seamOwnershipEnabled",
                "seamOwnershipMaxIntentsPerChunk",
                "seamOwnershipIntentTtlMs",
                "highwaySupportMaxDepth"
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
                    } else if (value instanceof java.util.Collection<?>) {
                        // Keep list-valued settings valid JSON instead of relying on
                        // Collection#toString(), which omits the required string quotes.
                        valueStr = GSON.toJson(value);
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
        ENABLE_AUTOMATIC_CHUNK_SCANS = CONFIG.enableAutomaticChunkScans;
        REJECT_STRUCTURES_IN_CITY_CHUNKS = CONFIG.rejectStructuresInCityChunks;
        CITY_STRUCTURE_REJECTION_BUFFER_CHUNKS = Math.max(0, Math.min(4, CONFIG.cityStructureRejectionBufferChunks));
        // Do not clear terrain above the city floor. Lost Cities owns the height-aware
        // hill and highway pass. Keep this field only for migration.
        CITY_VERTICAL_TERRAIN_CLEARANCE = false;
        ENABLE_LOSTCITIES_GENERATION_LOCK = CONFIG.enableLostCitiesGenerationLock;
        ENABLE_LOSTCITIES_PART_SLICE_COMPAT = CONFIG.enableLostCitiesPartSliceCompat;
        LOSTCITIES_STREET_GENERATION_MODE = LostCitiesStreetModePolicy.normalizeValue(CONFIG.lostCitiesStreetGenerationMode);
        CONFIG.lostCitiesStreetGenerationMode = LOSTCITIES_STREET_GENERATION_MODE;
        LostCitiesStreetModePolicy.setConfiguredMode(LOSTCITIES_STREET_GENERATION_MODE);
        LCLogger.info("[LC2H] [Config] Lost Cities street planner set to {} (live; applies to subsequently planned chunks)",
            LOSTCITIES_STREET_GENERATION_MODE);
        ENABLE_CACHE_STATS_LOGGING = CONFIG.enableCacheStatsLogging;
        ENABLE_FLOATING_VEGETATION_REMOVAL = CONFIG.enableFloatingVegetationRemoval;
        ENABLE_EXPLOSION_DEBRIS = CONFIG.enableExplosionDebris;
        HIDE_EXPERIMENTAL_WARNING = CONFIG.hideExperimentalWarning;
        ENABLE_DEBUG_LOGGING = CONFIG.enableDebugLogging;
        UI_ACCENT_COLOR = CONFIG.uiAccentColor != null ? CONFIG.uiAccentColor : UI_ACCENT_COLOR;
        UI_LOCALE = (CONFIG.uiLocale != null && !CONFIG.uiLocale.isBlank()) ? CONFIG.uiLocale : UI_LOCALE;
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
        // Keep the cached terrain field in sync with the live config. This is
        // also what makes Apply & Save take effect without a process restart.
        CityShiftField.ShiftSettings.withEnabled(CITY_BLEND_ENABLED);
        CityShiftField.withBlendShape(CITY_BLEND_WIDTH, CITY_BLEND_SOFTNESS);
        CITY_BLEND_CLEAR_TREES = true;
        CITY_BLEND_TREE_SEAM_FIX = true;
        CITY_BLEND_TREE_SEAM_BUFFER = Math.max(1, CONFIG.cityBlendTreeSeamBuffer);
        TREE_SEAM_RADIUS_MULTIPLIER = (float) Math.max(0.5D, Math.min(3.0D, CONFIG.treeSeamRadiusMultiplier));
        SEAM_OWNERSHIP_ENABLED = CONFIG.seamOwnershipEnabled;
        SEAM_OWNERSHIP_MAX_INTENTS_PER_CHUNK = Math.max(256, CONFIG.seamOwnershipMaxIntentsPerChunk);
        SEAM_OWNERSHIP_INTENT_TTL_MS = Math.max(30_000L, CONFIG.seamOwnershipIntentTtlMs);
        HIGHWAY_SUPPORT_MAX_DEPTH = Math.max(40, Math.min(384, CONFIG.highwaySupportMaxDepth));
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
