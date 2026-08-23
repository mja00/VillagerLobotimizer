package dev.mja00.villagerLobotomizer;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobotomizeStorageTest extends MockBukkitTestBase {

    private VillagerLobotomizer plugin;
    private WorldMock world;
    private NamespacedKey lobotomizedKey;

    @BeforeEach
    void loadPlugin() {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
        world = server.addSimpleWorld("test");
        lobotomizedKey = new NamespacedKey(plugin, "isLobotomized");
    }

    @Test
    void shutdownPreservesLobotomizedStateByDefault() {
        Villager villager = trackedLobotomizedVillager();

        assertFalse(plugin.getConfig().getBoolean("uninstall"),
                "uninstall cleanup should be disabled by default");

        server.getPluginManager().disablePlugin(plugin);

        assertFalse(villager.isAware(), "normal shutdown should preserve disabled villager AI");
        assertTrue(hasLobotomizedMarker(villager),
                "normal shutdown should preserve the persistent lobotomy marker");
    }

    @Test
    void uninstallCleanupWakesVillagerAndRemovesMarker() {
        Villager villager = trackedLobotomizedVillager();
        plugin.getConfig().set("uninstall", true);

        server.getPluginManager().disablePlugin(plugin);

        assertTrue(villager.isAware(), "uninstall cleanup should restore villager AI");
        assertFalse(hasLobotomizedMarker(villager),
                "uninstall cleanup should remove the persistent lobotomy marker");
    }

    private Villager trackedLobotomizedVillager() {
        Villager villager = world.spawn(new Location(world, 0, 64, 0), Villager.class);
        plugin.getStorage().addVillager(villager);
        villager.setAware(false);
        villager.getPersistentDataContainer().set(lobotomizedKey, PersistentDataType.BYTE, (byte) 1);
        return villager;
    }

    private boolean hasLobotomizedMarker(Villager villager) {
        return villager.getPersistentDataContainer().has(lobotomizedKey, PersistentDataType.BYTE);
    }
}
