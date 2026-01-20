package org.admany.lc2h.util.spawn;

import mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries;
import mcjty.lostcities.worldgen.lost.cityassets.Building;
import mcjty.lostcities.worldgen.lost.cityassets.BuildingPart;
import mcjty.lostcities.worldgen.lost.cityassets.MultiBuilding;
import mcjty.lostcities.worldgen.lost.regassets.data.DataTools;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.CommonLevelAccessor;
import org.admany.lc2h.LC2H;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SpawnAssetIndex {
    private static volatile Set<String> BUILDING_IDS = Set.of();
    private static volatile Set<String> PART_IDS = Set.of();
    private static volatile Set<String> MULTI_BUILDING_IDS = Set.of();
    private static volatile Map<String, String> BUILDING_ALIASES = Map.of();
    private static volatile Map<String, String> PART_ALIASES = Map.of();
    private static volatile Map<String, String> MULTI_BUILDING_ALIASES = Map.of();
    private static final AtomicBoolean LOADED = new AtomicBoolean(false);

    private SpawnAssetIndex() {
    }

    public static void refresh(CommonLevelAccessor level) {
        if (level == null) {
            return;
        }
        try {
            AssetRegistries.load(level);
        } catch (Throwable t) {
            LC2H.LOGGER.warn("[LC2H] Failed to load Lost Cities registries for spawn asset index", t);
        }
        try {
            AssetRegistries.MULTI_BUILDINGS.loadAll(level);
        } catch (Throwable ignored) {
        }

        Set<String> buildings = new HashSet<>();
        Set<String> parts = new HashSet<>();
        Set<String> multibuildings = new HashSet<>();
        Map<String, String> buildingAliases = new ConcurrentHashMap<>();
        Map<String, String> partAliases = new ConcurrentHashMap<>();
        Map<String, String> multiAliases = new ConcurrentHashMap<>();
        Set<String> buildingAmbiguous = new HashSet<>();
        Set<String> partAmbiguous = new HashSet<>();
        Set<String> multiAmbiguous = new HashSet<>();

        try {
            for (Building building : AssetRegistries.BUILDINGS.getIterable()) {
                ResourceLocation id = building.getId();
                if (id != null) {
                    String idString = id.toString();
                    buildings.add(idString);
                    registerAlias(buildingAliases, buildingAmbiguous, idString, idString);
                    registerAlias(buildingAliases, buildingAmbiguous, id.getPath(), idString);
                }
                String name = building.getName();
                if (name != null && !name.isBlank()) {
                    buildings.add(name);
                    registerAlias(buildingAliases, buildingAmbiguous, name, id != null ? id.toString() : name);
                }
            }
        } catch (Throwable t) {
            LC2H.LOGGER.warn("[LC2H] Failed to enumerate Lost Cities building ids", t);
        }

        try {
            for (BuildingPart part : AssetRegistries.PARTS.getIterable()) {
                ResourceLocation id = part.getId();
                if (id != null) {
                    String idString = id.toString();
                    parts.add(idString);
                    registerAlias(partAliases, partAmbiguous, idString, idString);
                    registerAlias(partAliases, partAmbiguous, id.getPath(), idString);
                }
                String name = part.getName();
                if (name != null && !name.isBlank()) {
                    parts.add(name);
                    registerAlias(partAliases, partAmbiguous, name, id != null ? id.toString() : name);
                }
            }
        } catch (Throwable t) {
            LC2H.LOGGER.warn("[LC2H] Failed to enumerate Lost Cities part ids", t);
        }

        try {
            for (MultiBuilding multi : AssetRegistries.MULTI_BUILDINGS.getIterable()) {
                ResourceLocation id = multi.getId();
                if (id != null) {
                    String idString = id.toString();
                    multibuildings.add(idString);
                    registerAlias(multiAliases, multiAmbiguous, idString, idString);
                    registerAlias(multiAliases, multiAmbiguous, id.getPath(), idString);
                }
                String name = multi.getName();
                if (name != null && !name.isBlank()) {
                    multibuildings.add(name);
                    registerAlias(multiAliases, multiAmbiguous, name, id != null ? id.toString() : name);
                }
            }
        } catch (Throwable t) {
            LC2H.LOGGER.warn("[LC2H] Failed to enumerate Lost Cities multibuilding ids", t);
        }

        BUILDING_IDS = Collections.unmodifiableSet(buildings);
        PART_IDS = Collections.unmodifiableSet(parts);
        MULTI_BUILDING_IDS = Collections.unmodifiableSet(multibuildings);
        BUILDING_ALIASES = Collections.unmodifiableMap(buildingAliases);
        PART_ALIASES = Collections.unmodifiableMap(partAliases);
        MULTI_BUILDING_ALIASES = Collections.unmodifiableMap(multiAliases);
        LOADED.set(true);

        LC2H.LOGGER.debug(
            "[LC2H] Loaded Lost Cities assets: buildings={}, parts={}, multibuildings={}",
            BUILDING_IDS.size(),
            PART_IDS.size(),
            MULTI_BUILDING_IDS.size()
        );
        logNamespaceSample("deceasedcraft", BUILDING_IDS, "buildings");
    }

    public static Set<String> getBuildingIds() {
        return BUILDING_IDS;
    }

    public static Set<String> getPartIds() {
        return PART_IDS;
    }

    public static Set<String> getMultiBuildingIds() {
        return MULTI_BUILDING_IDS;
    }

    public static boolean isBuildingKnown(String id) {
        return id != null && BUILDING_IDS.contains(id);
    }

    public static boolean isPartKnown(String id) {
        return id != null && PART_IDS.contains(id);
    }

    public static boolean isMultiBuildingKnown(String id) {
        return id != null && MULTI_BUILDING_IDS.contains(id);
    }

    public static void ensureLoaded(CommonLevelAccessor level) {
        if (LOADED.get()) {
            return;
        }
        refresh(level);
    }

    public static String resolveBuildingId(String id) {
        return resolveAlias(id, BUILDING_IDS, BUILDING_ALIASES);
    }

    public static String resolvePartId(String id) {
        return resolveAlias(id, PART_IDS, PART_ALIASES);
    }

    public static String resolveMultiBuildingId(String id) {
        return resolveAlias(id, MULTI_BUILDING_IDS, MULTI_BUILDING_ALIASES);
    }

    private static void registerAlias(Map<String, String> aliases,
                                      Set<String> ambiguous,
                                      String alias,
                                      String id) {
        if (alias == null || alias.isBlank() || id == null || id.isBlank()) {
            return;
        }
        if (ambiguous.contains(alias)) {
            return;
        }
        String existing = aliases.putIfAbsent(alias, id);
        if (existing != null && !existing.equals(id)) {
            aliases.remove(alias);
            ambiguous.add(alias);
        }
    }

    private static String resolveAlias(String id, Set<String> known, Map<String, String> aliases) {
        if (id == null || id.isBlank()) {
            return id;
        }
        if (known.contains(id)) {
            return id;
        }
        try {
            ResourceLocation canonical = id.contains(":")
                ? ResourceLocation.fromNamespaceAndPath(id.substring(0, id.indexOf(':')), id.substring(id.indexOf(':') + 1))
                : DataTools.fromName(id);
            if (canonical != null) {
                String canonicalId = canonical.toString();
                if (known.contains(canonicalId)) {
                    return canonicalId;
                }
                String canonicalName = DataTools.toName(canonical);
                if (known.contains(canonicalName)) {
                    return canonicalName;
                }
            }
        } catch (Throwable ignored) {
        }
        String fileName = extractFileName(id);
        if (fileName != null) {
            String namespace = extractNamespace(id);
            String byNamespaceFile = findByFileNameAndNamespace(known, namespace, fileName);
            if (byNamespaceFile != null) {
                return byNamespaceFile;
            }
            String stripped = stripRotationSuffix(fileName);
            if (stripped != null && !stripped.equals(fileName)) {
                String byNamespaceStripped = findByFileNameAndNamespace(known, namespace, stripped);
                if (byNamespaceStripped != null) {
                    return byNamespaceStripped;
                }
            }
            String byFileName = findUniqueByFileName(known, fileName);
            if (byFileName != null) {
                return byFileName;
            }
            String byFileNameLower = findUniqueByFileName(known, fileName.toLowerCase());
            if (byFileNameLower != null) {
                return byFileNameLower;
            }
            if (stripped != null && !stripped.equals(fileName)) {
                String byStripped = findUniqueByFileName(known, stripped);
                if (byStripped != null) {
                    return byStripped;
                }
                String byStrippedLower = findUniqueByFileName(known, stripped.toLowerCase());
                if (byStrippedLower != null) {
                    return byStrippedLower;
                }
            }
            String rotationSuffix = extractRotationSuffix(fileName);
            String baseNoLetter = stripTrailingLetter(stripped != null ? stripped : fileName);
            String familyMatch = findFamilyMatch(known, namespace, stripped != null ? stripped : fileName, baseNoLetter, rotationSuffix);
            if (familyMatch != null) {
                return familyMatch;
            }
            String bestPrefix = findBestMatchByPrefix(known, namespace, fileName);
            if (bestPrefix != null) {
                return bestPrefix;
            }
            if (stripped != null && !stripped.equals(fileName)) {
                String bestStripped = findBestMatchByPrefix(known, namespace, stripped);
                if (bestStripped != null) {
                    return bestStripped;
                }
            }
        }
        String resolved = aliases.get(id);
        return resolved != null ? resolved : id;
    }

    public static java.util.List<String> suggestBuildingIds(String rawId, int limit) {
        return suggestIds(rawId, BUILDING_IDS, limit);
    }

    public static java.util.List<String> suggestPartIds(String rawId, int limit) {
        return suggestIds(rawId, PART_IDS, limit);
    }

    public static java.util.List<String> suggestMultiBuildingIds(String rawId, int limit) {
        return suggestIds(rawId, MULTI_BUILDING_IDS, limit);
    }

    public static String suggestBestBuildingId(String rawId) {
        return suggestBestId(rawId, BUILDING_IDS);
    }

    public static String suggestBestPartId(String rawId) {
        return suggestBestId(rawId, PART_IDS);
    }

    public static String suggestBestMultiBuildingId(String rawId) {
        return suggestBestId(rawId, MULTI_BUILDING_IDS);
    }

    private static java.util.List<String> suggestIds(String rawId, Set<String> known, int limit) {
        if (rawId == null || rawId.isBlank() || known.isEmpty()) {
            return java.util.List.of();
        }
        String best = suggestBestId(rawId, known);
        if (best == null) {
            return java.util.List.of();
        }
        return java.util.List.of(best);
    }

    private static String suggestBestId(String rawId, Set<String> known) {
        if (rawId == null || rawId.isBlank() || known.isEmpty()) {
            return null;
        }
        String needle = rawId.toLowerCase();
        String path = needle;
        int colon = needle.indexOf(':');
        if (colon >= 0 && colon + 1 < needle.length()) {
            path = needle.substring(colon + 1);
        }
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < path.length()) {
            path = path.substring(slash + 1);
        }
        String base = stripRotationSuffix(path);
        String baseNoLetter = stripTrailingLetter(base);
        int bestScore = -1;
        String bestId = null;
        for (String id : known) {
            String lower = id.toLowerCase();
            String segment = lastPathSegment(lower);
            if (segment == null) {
                continue;
            }
            int score = 0;
            if (lower.equals(needle)) {
                score = 100;
            } else if (segment.equals(path)) {
                score = 90;
            } else if (base != null && segment.equals(base)) {
                score = 85;
            } else if (baseNoLetter != null && segment.equals(baseNoLetter)) {
                score = 80;
            } else if (baseNoLetter != null && segment.startsWith(baseNoLetter)) {
                score = 70;
            } else if (segment.startsWith(path)) {
                score = 60;
            } else if (segment.contains(path)) {
                score = 50;
            } else if (lower.contains(path)) {
                score = 40;
            }
            if (score <= 0) {
                continue;
            }
            if (score > bestScore) {
                bestScore = score;
                bestId = id;
            } else if (score == bestScore && bestId != null && id.compareTo(bestId) < 0) {
                bestId = id;
            }
        }
        return bestId;
    }

    private static void logNamespaceSample(String namespace, Set<String> known, String label) {
        if (namespace == null || namespace.isBlank() || known.isEmpty()) {
            return;
        }
        java.util.ArrayList<String> sample = new java.util.ArrayList<>();
        for (String id : known) {
            if (id.startsWith(namespace + ":")) {
                sample.add(id);
                if (sample.size() >= 10) {
                    break;
                }
            }
        }
        if (!sample.isEmpty()) {
            LC2H.LOGGER.debug(
                "[LC2H] Sample {} for namespace {}: {}",
                label,
                namespace,
                sample
            );
        }
    }

    private static String extractFileName(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        String path = rawId;
        int colon = path.indexOf(':');
        if (colon >= 0 && colon + 1 < path.length()) {
            path = path.substring(colon + 1);
        }
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < path.length()) {
            path = path.substring(slash + 1);
        }
        return path.isBlank() ? null : path;
    }

    private static String stripRotationSuffix(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.endsWith("_90")) {
            return value.substring(0, value.length() - 3);
        }
        if (value.endsWith("_180")) {
            return value.substring(0, value.length() - 4);
        }
        if (value.endsWith("_270")) {
            return value.substring(0, value.length() - 4);
        }
        return value;
    }

    private static String extractRotationSuffix(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.endsWith("_90")) {
            return "_90";
        }
        if (value.endsWith("_180")) {
            return "_180";
        }
        if (value.endsWith("_270")) {
            return "_270";
        }
        return null;
    }

    private static String stripTrailingLetter(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        int len = value.length();
        char last = value.charAt(len - 1);
        if (last >= 'a' && last <= 'z') {
            return value.substring(0, len - 1);
        }
        if (last >= 'A' && last <= 'Z') {
            return value.substring(0, len - 1);
        }
        return value;
    }

    private static String extractNamespace(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        int colon = rawId.indexOf(':');
        if (colon > 0) {
            return rawId.substring(0, colon);
        }
        return null;
    }

    private static String findUniqueByFileName(Set<String> known, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String match = null;
        for (String id : known) {
            String path = id;
            int colon = path.indexOf(':');
            if (colon >= 0 && colon + 1 < path.length()) {
                path = path.substring(colon + 1);
            }
            int slash = path.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < path.length()) {
                path = path.substring(slash + 1);
            }
            if (fileName.equals(path)) {
                if (match != null && !match.equals(id)) {
                    return null;
                }
                match = id;
            }
        }
        return match;
    }

    private static String findByFileNameAndNamespace(Set<String> known, String namespace, String fileName) {
        if (namespace == null || namespace.isBlank() || fileName == null || fileName.isBlank()) {
            return null;
        }
        String match = null;
        for (String id : known) {
            int colon = id.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String idNamespace = id.substring(0, colon);
            if (!namespace.equals(idNamespace)) {
                continue;
            }
            String path = id.substring(colon + 1);
            int slash = path.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < path.length()) {
                path = path.substring(slash + 1);
            }
            if (fileName.equals(path)) {
                if (match != null && !match.equals(id)) {
                    return null;
                }
                match = id;
            }
        }
        return match;
    }

    private static String findBestMatchByPrefix(Set<String> known, String namespace, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String needle = fileName.toLowerCase();
        int bestScore = -1;
        String bestId = null;
        boolean tie = false;
        for (String id : known) {
            if (namespace != null && !namespace.isBlank()) {
                int colon = id.indexOf(':');
                if (colon <= 0 || !namespace.equals(id.substring(0, colon))) {
                    continue;
                }
            }
            String segment = lastPathSegment(id);
            if (segment == null) {
                continue;
            }
            String hay = segment.toLowerCase();
            int score = prefixScore(needle, hay);
            if (score <= 0) {
                continue;
            }
            if (score > bestScore) {
                bestScore = score;
                bestId = id;
                tie = false;
            } else if (score == bestScore && bestId != null && !bestId.equals(id)) {
                tie = true;
            }
        }
        if (tie || bestScore < 8) {
            return null;
        }
        return bestId;
    }

    private static int prefixScore(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int prefix = 0;
        for (int i = 0; i < max; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                break;
            }
            prefix++;
        }
        if (prefix == 0) {
            return 0;
        }
        int lengthPenalty = Math.abs(a.length() - b.length());
        return (prefix * 2) - lengthPenalty;
    }

    private static String lastPathSegment(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String path = id;
        int colon = path.indexOf(':');
        if (colon >= 0 && colon + 1 < path.length()) {
            path = path.substring(colon + 1);
        }
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < path.length()) {
            path = path.substring(slash + 1);
        }
        return path.isBlank() ? null : path;
    }

    private static String findFamilyMatch(Set<String> known,
                                          String namespace,
                                          String base,
                                          String baseNoLetter,
                                          String rotationSuffix) {
        int bestScore = -1;
        String bestId = null;
        boolean tie = false;
        for (String id : known) {
            if (namespace != null && !namespace.isBlank()) {
                int colon = id.indexOf(':');
                if (colon <= 0 || !namespace.equals(id.substring(0, colon))) {
                    continue;
                }
            }
            String segment = lastPathSegment(id);
            if (segment == null) {
                continue;
            }
            if (rotationSuffix != null && !segment.endsWith(rotationSuffix)) {
                continue;
            }
            String segmentBase = stripRotationSuffix(segment);
            int score = 0;
            if (segmentBase.equals(base)) {
                score = 3;
            } else if (baseNoLetter != null && segmentBase.equals(baseNoLetter)) {
                score = 2;
            } else if (baseNoLetter != null && segmentBase.startsWith(baseNoLetter)) {
                score = 1;
            }
            if (score == 0) {
                continue;
            }
            if (rotationSuffix != null) {
                score += 1;
            }
            if (score > bestScore) {
                bestScore = score;
                bestId = id;
                tie = false;
            } else if (score == bestScore && bestId != null && !bestId.equals(id)) {
                tie = true;
            }
        }
        if (tie) {
            return null;
        }
        return bestId;
    }
}
