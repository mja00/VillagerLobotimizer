package dev.mja00.villagerLobotomizer.utils;

import org.bukkit.Bukkit;

public class SentryContextProvider {

    /**
     * Detect server brand (Paper, Purpur, Folia, etc.)
     */
    public static String getServerBrand() {
        try {
            String brand = Bukkit.getServer().getName();
            return brand != null ? brand : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Retrieves the server version.
     *
     * @return the server version, or "unknown" if unavailable
     */
    public static String getServerVersion() {
        try {
            String version = Bukkit.getServer().getVersion();
            return version != null ? version : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Retrieves the Minecraft version.
     *
     * @return the Minecraft version, or {@code "unknown"} if unavailable
     */
    public static String getMinecraftVersion() {
        try {
            String version = Bukkit.getMinecraftVersion();
            return version != null ? version : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Retrieves the Bukkit/Paper API version.
     *
     * @return the Bukkit/Paper API version, or "unknown" if unavailable
     */
    public static String getBukkitVersion() {
        try {
            String version = Bukkit.getBukkitVersion();
            return version != null ? version : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Retrieves the Java version.
     *
     * @return the Java version string, or {@code "unknown"} if not available
     */
    public static String getJavaVersion() {
        String version = System.getProperty("java.version");
        return version != null ? version : "unknown";
    }
}
