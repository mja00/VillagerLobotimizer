package dev.mja00.villagerLobotomizer;

import dev.mja00.villagerLobotomizer.storage.LobotomizedMarkerStore;
import dev.mja00.villagerLobotomizer.storage.MarkedVillager;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule the uninstall sweep depends on: a row exists exactly when the plugin has written the PDC
 * marker on that villager.
 */
class LobotomizeStorageMarkerTest extends MockBukkitTestBase {

    private VillagerLobotomizer plugin;
    private WorldMock world;
    private NamespacedKey markerKey;
    private LobotomizedMarkerStore store;

    @BeforeEach
    void loadPlugin() {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
        world = server.addSimpleWorld("test");
        markerKey = new NamespacedKey(plugin, LobotomizeStorage.LOBOTOMIZED_KEY);
        store = plugin.getMarkerStore();
    }

    private Villager untrackedVillager() {
        Villager villager = world.spawn(new Location(world, 24, 64, 40), Villager.class);
        plugin.getStorage().removeVillager(villager);
        return villager;
    }

    @Test
    void honouringAnExistingMarkerRecordsARow() throws SQLException {
        Villager villager = untrackedVillager();
        villager.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);

        // An upgraded install has markers on disk but no rows yet, so tracking one must record it.
        plugin.getStorage().addVillager(villager);
        store.drainNow();

        assertEquals(List.of(new MarkedVillager(villager.getUniqueId(), world.getUID(), 1, 2)),
                store.loadAll(), "the row should record where the villager was found");
    }

    @Test
    void clearingTheMarkerDeletesTheRow() throws SQLException {
        Villager villager = untrackedVillager();
        villager.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        plugin.getStorage().addVillager(villager);
        store.drainNow();
        assertEquals(1, store.loadAll().size(), "precondition: the villager has a row");

        // This is the path /lobotomy wake takes.
        plugin.getStorage().clearLobotomizedMarker(villager);
        store.drainNow();

        assertTrue(store.loadAll().isEmpty(), "clearing the marker should drop the row");
    }

    @Test
    void clearingAnUnmarkedVillagerQueuesNoWrite() {
        Villager villager = untrackedVillager();

        // Active villagers get their marker cleared on every check, so this must stay free.
        plugin.getStorage().clearLobotomizedMarker(villager);

        assertEquals(0, store.getKnownRowCount(), "an unmarked villager should never gain a row");
    }
}
