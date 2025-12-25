package dev.mja00.villagerLobotomizer.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SentryContextProvider.
 *
 * Note: These tests run in a unit test environment where Bukkit/Paper may not be
 * fully initialized. The methods should gracefully handle this by returning "unknown"
 * instead of null or throwing exceptions.
 */
class SentryContextProviderTest {

    @Test
    void getServerBrandReturnsNonNullAndNonEmpty() {
        // In test environment without Bukkit, should return "unknown"
        // In real environment, should return actual server brand
        String brand = SentryContextProvider.getServerBrand();
        assertNotNull(brand, "Server brand should never be null");
        assertFalse(brand.isEmpty(), "Server brand should not be empty");
    }

    @Test
    void getServerVersionReturnsNonNullAndNonEmpty() {
        String version = SentryContextProvider.getServerVersion();
        assertNotNull(version, "Server version should never be null");
        assertFalse(version.isEmpty(), "Server version should not be empty");
    }

    @Test
    void getMinecraftVersionReturnsNonNullAndNonEmpty() {
        String version = SentryContextProvider.getMinecraftVersion();
        assertNotNull(version, "Minecraft version should never be null");
        assertFalse(version.isEmpty(), "Minecraft version should not be empty");
    }

    @Test
    void getBukkitVersionReturnsNonNullAndNonEmpty() {
        String version = SentryContextProvider.getBukkitVersion();
        assertNotNull(version, "Bukkit version should never be null");
        assertFalse(version.isEmpty(), "Bukkit version should not be empty");
    }

    @Test
    void getJavaVersionReturnsNonNullAndNonEmpty() {
        String version = SentryContextProvider.getJavaVersion();
        assertNotNull(version, "Java version should never be null");
        assertFalse(version.isEmpty(), "Java version should not be empty");
    }

    @Test
    void getJavaVersionReturnsValidFormat() {
        String version = SentryContextProvider.getJavaVersion();
        // Java version should always be available and contain digits (not "unknown")
        assertNotEquals("unknown", version, "Java version should be available in test environment");
        assertTrue(version.matches(".*\\d.*"),
            "Java version should contain digits, got: " + version);
    }

    @Test
    void methodsHandleUninitializedEnvironmentGracefully() {
        // All methods should complete without throwing exceptions
        // even if Bukkit is not initialized (returns "unknown" as fallback)
        assertDoesNotThrow(() -> {
            SentryContextProvider.getServerBrand();
            SentryContextProvider.getServerVersion();
            SentryContextProvider.getMinecraftVersion();
            SentryContextProvider.getBukkitVersion();
            SentryContextProvider.getJavaVersion();
        }, "All methods should handle uninitialized environment gracefully");
    }
}
