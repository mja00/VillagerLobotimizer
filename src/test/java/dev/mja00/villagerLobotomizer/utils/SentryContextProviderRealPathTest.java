package dev.mja00.villagerLobotomizer.utils;

import dev.mja00.villagerLobotomizer.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Exercises the real (Bukkit-available) path of {@link SentryContextProvider}. The existing
 * {@code SentryContextProviderTest} covers the no-Bukkit fallback; here a mocked server is active,
 * so the provider should return actual values rather than the "unknown" fallback.
 */
class SentryContextProviderRealPathTest extends MockBukkitTestBase {

    @Test
    void serverBrandReflectsRunningServer() {
        String brand = SentryContextProvider.getServerBrand();
        assertNotNull(brand);
        assertNotEquals("unknown", brand, "with a mocked server present, the real brand should resolve");
    }

    @Test
    void minecraftVersionResolvesFromServer() {
        String version = SentryContextProvider.getMinecraftVersion();
        assertNotNull(version);
        assertNotEquals("unknown", version, "minecraft version should resolve from the mocked server");
    }

    @Test
    void bukkitVersionResolvesFromServer() {
        String version = SentryContextProvider.getBukkitVersion();
        assertNotNull(version);
        assertNotEquals("unknown", version, "bukkit/api version should resolve from the mocked server");
    }
}
