package dev.mja00.villagerLobotomizer.utils;

import org.bukkit.Bukkit;

public class SentryContextProvider {

    /**
     * Detect server brand (Paper, Purpur, Folia, etc.)
     */
    public static String getServerBrand() {
        return Bukkit.getServer().getName();
    }

    public static String getServerVersion() {
        return Bukkit.getServer().getVersion();
    }

    /**
     * Get Minecraft version
     */
    public static String getMinecraftVersion() {
        return Bukkit.getMinecraftVersion();
    }

    /**
     * Get Bukkit/Paper API version
     */
    public static String getBukkitVersion() {
        return Bukkit.getBukkitVersion();
    }

    /**
     * Get Java version
     */
    public static String getJavaVersion() {
        return System.getProperty("java.version");
    }
}
