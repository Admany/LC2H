package org.admany.lc2h.neoforge.compat;

/** Version facade retained for the diagnostics shared with the Forge target. */
public final class ForgeVersion {
    private ForgeVersion() {
    }

    public static String getVersion() {
        String version = System.getProperty("neoforge.version");
        return version == null || version.isBlank() ? "neoforge" : version;
    }
}
