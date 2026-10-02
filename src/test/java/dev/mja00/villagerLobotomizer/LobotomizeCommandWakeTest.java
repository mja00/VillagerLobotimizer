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

class LobotomizeCommandWakeTest extends MockBukkitTestBase {

    private VillagerLobotomizer plugin;
    private WorldMock world;
    private NamespacedKey markerKey;

    @BeforeEach
    void loadPlugin() {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
        world = server.addSimpleWorld("test");
        markerKey = new NamespacedKey(plugin, LobotomizeStorage.LOBOTOMIZED_KEY);
    }

    @Test
    void wakeRestoresAnUntrackedFrozenVillager() {
        Villager villager = world.spawn(new Location(world, 24, 64, 40), Villager.class);
        // Mirrors a villager left frozen while tracking was paused, e.g. after an incomplete uninstall.
        plugin.getStorage().removeVillager(villager);
        server.getScheduler().performTicks(2);
        villager.setAware(false);
        villager.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);

        new LobotomizeCommand(plugin).wakeVillager(villager);
        server.getScheduler().performTicks(2);

        assertTrue(villager.isAware(), "wake must restore AI even when the villager is untracked");
        assertFalse(villager.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE),
                "wake must clear the marker so the villager is not re-lobotomized");
    }

    @Test
    void wakeKeepsAnAwareVillagerSilencedElsewhere() {
        plugin.getConfig().set("silent-lobotomized-villagers", true);
        Villager villager = world.spawn(new Location(world, 24, 64, 40), Villager.class);
        plugin.getStorage().removeVillager(villager);
        server.getScheduler().performTicks(2);
        villager.setAware(true);
        villager.setSilent(true);

        new LobotomizeCommand(plugin).wakeVillager(villager);
        server.getScheduler().performTicks(2);

        assertTrue(villager.isSilent(), "wake must not unsilence a villager the plugin never froze");
    }
}
