package dev.mja00.villagerLobotomizer.listeners;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import dev.mja00.villagerLobotomizer.LobotomizeStorage;
import dev.mja00.villagerLobotomizer.MockBukkitTestBase;
import dev.mja00.villagerLobotomizer.VillagerLobotomizer;
import dev.mja00.villagerLobotomizer.storage.LobotomizedMarkerStore;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertTrue(plugin.getStorage().getActive().contains(villager),
                "a freshly added villager should be tracked as active");
    }

    @Test
    void removeEventUntracksVillager() {
        Villager villager = world.spawn(new Location(world, 0, 64, 0), Villager.class);
        server.getPluginManager().callEvent(new EntityAddToWorldEvent(villager, world));
        assertTrue(isTracked(villager), "precondition: villager is tracked after add");

        server.getPluginManager().callEvent(new EntityRemoveFromWorldEvent(villager, world));

        assertFalse(isTracked(villager), "a removed villager should no longer be tracked");
    }

    /** A villager whose marker is on disk and whose row the store has already written. */
    private Villager markedVillager() {
        Villager villager = world.spawn(new Location(world, 0, 64, 0), Villager.class);
        plugin.getStorage().removeVillager(villager);
        villager.getPersistentDataContainer()
                .set(new NamespacedKey(plugin, LobotomizeStorage.LOBOTOMIZED_KEY), PersistentDataType.BYTE, (byte) 1);
        plugin.getStorage().addVillager(villager);
        store().drainNow();
        return villager;
    }

    private LobotomizedMarkerStore store() {
        return plugin.getMarkerStore();
    }

    private int rowCount() throws Exception {
        store().drainNow();
        return store().loadAll().size();
    }

    @Test
    void deathDropsTheTrackingRow() throws Exception {
        Villager villager = markedVillager();
        assertEquals(1, rowCount(), "precondition: the villager has a row");

        server.getPluginManager().callEvent(new EntityRemoveEvent(villager, EntityRemoveEvent.Cause.DEATH));

        assertEquals(0, rowCount(), "a dead villager's row should be dropped");
    }

    @Test
    void chunkUnloadKeepsTheTrackingRow() throws Exception {
        Villager villager = markedVillager();

        server.getPluginManager().callEvent(new EntityRemoveEvent(villager, EntityRemoveEvent.Cause.UNLOAD));

        assertEquals(1, rowCount(),
                "an unloaded villager keeps its marker on disk, so its row must survive too");
    }

    @Test
    void transformationDropsTheTrackingRow() throws Exception {
        Villager villager = markedVillager();

        // Villager to zombie villager: the entity is replaced, so the marker goes with it.
        server.getPluginManager().callEvent(new EntityRemoveEvent(villager, EntityRemoveEvent.Cause.TRANSFORMATION));

        assertEquals(0, rowCount(), "a converted villager's row should be dropped");
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

        assertTrue(isTracked(villager), "existing villagers should be picked up by the constructor scan");
    }
}
