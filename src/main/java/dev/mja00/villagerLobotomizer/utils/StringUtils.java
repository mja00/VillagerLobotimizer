package dev.mja00.villagerLobotomizer.utils;

import java.util.Locale;

public class StringUtils {
    /**
     * Compares two semantic version strings (e.g., "1.2.3").
     *
     * @return -1 if version1 < version2, 0 if equal, 1 if version1 > version2
     */
    public static int compareSemVer(@NotNull String version1, @NotNull String version2) {
        String[] v1Parts = version1.split("\\.");
        String[] v2Parts = version2.split("\\.");
        int length = Math.max(v1Parts.length, v2Parts.length);

        for (int i = 0; i < length; i++) {
            int v1 = i < v1Parts.length ? Integer.parseInt(v1Parts[i]) : 0;
            int v2 = i < v2Parts.length ? Integer.parseInt(v2Parts[i]) : 0;
            if (v1 < v2) {
                return -1;
            }
            if (v1 > v2) {
                return 1;
            }
        }
        return 0;
    }

    /**
     * Converts a legacy sound name (all uppercase with underscores) to the new format (lowercase with dots).
     * Stateless utility for string conversion only.
     */
    public static String convertLegacySoundNameFormat(String soundName) {
        if (soundName != null && soundName.equals(soundName.toUpperCase(Locale.ROOT))) {
            return soundName.toLowerCase(Locale.ROOT).replace('_', '.');
        }
        return soundName;
    }
}
