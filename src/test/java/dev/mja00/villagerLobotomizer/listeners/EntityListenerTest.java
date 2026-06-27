package dev.mja00.villagerLobotomizer.listeners;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import dev.mja00.villagerLobotomizer.MockBukkitTestBase;
import dev.mja00.villagerLobotomizer.VillagerLobotomizer;
import org.bukkit.Location;
import org.bukkit.entity.Villager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the event wiring in {@link EntityListener}: firing the Paper entity
 * lifecycle events through the registered listener should add/remove villagers from storage.
 */
class EntityListenerTest extends MockBukkitTestBase {

    private VillagerLobotomizer plugin;
    private WorldMock world;

    @BeforeEach
    void loadPlugin() {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
        world = server.addSimpleWorld("test");
    }

    private boolean isTracked(Villager villager) {
        return plugin.getStorage().getActive().contains(villager)
                || plugin.getStorage().getLobotomized().contains(villager);
    }

    @Test
    void addEventTracksVillagerAsActive() {
        Villager villager = world.spawn(new Location(world, 0, 64, 0), Villager.class);

        server.getPluginManager().callEvent(new EntityAddToWorldEvent(villager, world));
        server.getScheduler().performTicks(1);

        assertTrue(plugin.getStorage().getActive().contains(villager),
                "a freshly added villager should be tracked as active");
    }

    @Test
    void removeEventUntracksVillager() {
        Villager villager = world.spawn(new Location(world, 0, 64, 0), Villager.class);
        server.getPluginManager().callEvent(new EntityAddToWorldEvent(villager, world));
        server.getScheduler().performTicks(1);
        assertTrue(isTracked(villager), "precondition: villager is tracked after add");

        server.getPluginManager().callEvent(new EntityRemoveFromWorldEvent(villager, world));

        assertFalse(isTracked(villager), "a removed villager should no longer be tracked");
    }

    @Test
    void constructorScanTracksExistingVillagers() {
        Villager villager = world.spawn(new Location(world, 0, 64, 0), Villager.class);

        // Untrack first so the constructor scan is the only thing that can re-track it; this holds
        // whether or not spawning auto-fired an add event through the already-registered listener.
        plugin.getStorage().removeVillager(villager);
        assertFalse(isTracked(villager), "precondition: villager is untracked before the scan");

        // A fresh listener scans loaded worlds and registers existing villagers
        new EntityListener(plugin);
        server.getScheduler().performTicks(1);

        assertTrue(isTracked(villager), "existing villagers should be picked up by the constructor scan");
    }
}
