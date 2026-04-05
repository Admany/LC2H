package org.admany.lc2h.util.spawn;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;


public final class ResolvedAssetFilter {
    public static final ResolvedAssetFilter NONE = new ResolvedAssetFilter(null, null, null);

    private final Set<String> buildingIds;
    private final Set<String> partIds;
    private final Set<String> multiBuildingIds;

    private ResolvedAssetFilter(Set<String> buildingIds, Set<String> partIds, Set<String> multiBuildingIds) {
        this.buildingIds = buildingIds;
        this.partIds = partIds;
        this.multiBuildingIds = multiBuildingIds;
    }

    public static ResolvedAssetFilter resolve(Set<String> rawBuildings,
                                              Set<String> rawParts,
                                              Set<String> rawMultiBuildings) {
        if (isBlankSet(rawBuildings) && isBlankSet(rawParts) && isBlankSet(rawMultiBuildings)) {
            return NONE;
        }
        return new ResolvedAssetFilter(
            resolveVariants(rawBuildings, SpawnAssetIndex.AssetType.BUILDING),
            resolveVariants(rawParts, SpawnAssetIndex.AssetType.PART),
            resolveVariants(rawMultiBuildings, SpawnAssetIndex.AssetType.MULTI_BUILDING)
        );
    }

    public boolean buildingMatches(String candidateId) {
        return matches(candidateId, buildingIds, SpawnAssetIndex.AssetType.BUILDING);
    }

    public boolean partMatches(String candidateId) {
        return matches(candidateId, partIds, SpawnAssetIndex.AssetType.PART);
    }

    public boolean multiBuildingMatches(String candidateId) {
        return matches(candidateId, multiBuildingIds, SpawnAssetIndex.AssetType.MULTI_BUILDING);
    }

    public boolean hasBuildingConstraint() {
        return buildingIds != null;
    }

    public boolean hasPartConstraint() {
        return partIds != null;
    }

    public boolean hasMultiBuildingConstraint() {
        return multiBuildingIds != null;
    }

    public boolean isUnconstrained() {
        return buildingIds == null && partIds == null && multiBuildingIds == null;
    }

    private static boolean matches(String candidateId, Set<String> allowed, SpawnAssetIndex.AssetType type) {
        if (allowed == null) {
            return true;
        }
        if (candidateId == null || candidateId.isBlank()) {
            return false;
        }
        if (allowed.contains(candidateId)) {
            return true;
        }
        for (String variant : SpawnAssetIndex.resolveToVariantSet(candidateId, type)) {
            if (allowed.contains(variant)) {
                return true;
            }
        }
        if (type == SpawnAssetIndex.AssetType.BUILDING) {
            String candidateFamily = toBuildingFamilyId(candidateId);
            if (candidateFamily != null) {
                for (String accepted : allowed) {
                    if (candidateFamily.equals(toBuildingFamilyId(accepted))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String toBuildingFamilyId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String trimmed = id.trim();
        int colon = trimmed.indexOf(':');
        String namespace = colon >= 0 ? trimmed.substring(0, colon) : "";
        String path = colon >= 0 && colon + 1 < trimmed.length() ? trimmed.substring(colon + 1) : trimmed;
        int slash = path.indexOf('/');
        if (slash >= 0) {
            path = path.substring(0, slash);
        }
        path = stripRotationSuffix(path);
        return namespace + ':' + path;
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

    private static Set<String> resolveVariants(Set<String> rawIds, SpawnAssetIndex.AssetType type) {
        if (isBlankSet(rawIds)) {
            return null;
        }
        Set<String> out = new HashSet<>(Math.max(4, rawIds.size() * 4));
        for (String rawId : rawIds) {
            if (rawId == null || rawId.isBlank()) {
                continue;
            }
            out.addAll(SpawnAssetIndex.resolveToVariantSet(rawId, type));
        }
        return Collections.unmodifiableSet(out);
    }

    private static boolean isBlankSet(Set<String> values) {
        return values == null || values.isEmpty();
    }
}
