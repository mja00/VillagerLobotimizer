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

    public static String getServerVersion() {
        try {
            String version = Bukkit.getServer().getVersion();
            return version != null ? version : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Get Minecraft version
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
     * Get Bukkit/Paper API version
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
     * Get Java version
     */
    public static String getJavaVersion() {
        String version = System.getProperty("java.version");
        return version != null ? version : "unknown";
    }
}
