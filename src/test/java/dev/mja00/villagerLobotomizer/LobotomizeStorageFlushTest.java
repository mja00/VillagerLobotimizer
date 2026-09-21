package dev.mja00.villagerLobotomizer;

import dev.mja00.villagerLobotomizer.storage.LobotomizedMarkerStore;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What survives a shutdown. The reported bug was that nothing did: a nightly reboot woke every
 * villager, so the lag spike came back each morning until check-interval elapsed.
 */
class LobotomizeStorageFlushTest extends MockBukkitTestBase {

    /** Well under check-interval, so the periodic check (which mock blocks cannot evaluate) never runs. */
    private static final int TICKS_FOR_SCHEDULED_WAKE = 5;

    private VillagerLobotomizer plugin;
    private WorldMock world;
    private NamespacedKey markerKey;

    private void load(boolean persist) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        config.set("persist-lobotomized-state", persist);
        plugin = MockBukkit.loadWithConfig(VillagerLobotomizer.class, config);
        world = server.addSimpleWorld("test");
        markerKey = new NamespacedKey(plugin, LobotomizeStorage.LOBOTOMIZED_KEY);
    }

    /** A lobotomized, marked villager, as a running server would have it. */
    private Villager lobotomizedVillager() {
        Villager villager = world.spawn(new Location(world, 0, 64, 0), Villager.class);
        plugin.getStorage().removeVillager(villager);
        villager.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        plugin.getStorage().addVillager(villager);
        return villager;
    }

    private boolean hasMarker(Villager villager) {
        return villager.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    @Test
    void shutdownPreservesNoAiAndMarker() {
        load(true);
        Villager villager = lobotomizedVillager();
        assertFalse(villager.isAware(), "precondition: the villager is lobotomized");

        plugin.getStorage().flush(LobotomizeStorage.FlushMode.SHUTDOWN);

        assertFalse(villager.isAware(), "a restart must not wake lobotomized villagers");
        assertTrue(hasMarker(villager), "and the marker must survive so it is re-tracked on load");
    }

    @Test
    void shutdownKeepsTheTrackingRow() throws SQLException {
        load(true);
        LobotomizedMarkerStore store = plugin.getMarkerStore();
        lobotomizedVillager();

        plugin.getStorage().flush(LobotomizeStorage.FlushMode.SHUTDOWN);
        store.drainNow();

        assertEquals(1, store.loadAll().size(), "the row must survive so uninstall can still find it");
    }

    @Test
    void shutdownWakesAndClearsWhenPersistenceIsDisabled() {
        load(false);
        Villager villager = world.spawn(new Location(world, 0, 64, 0), Villager.class);
        plugin.getStorage().removeVillager(villager);
        villager.setAware(false);
        villager.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        plugin.getStorage().addVillager(villager);

        plugin.getStorage().flush(LobotomizeStorage.FlushMode.SHUTDOWN);

        assertTrue(villager.isAware(), "with persistence off there is nothing to restore from, so wake");
        assertFalse(hasMarker(villager), "and leave no marker behind");
    }

    @Test
    void shutdownWakesVillagersWhenTheStoreDiedMidSession() {
        load(true);
        Villager villager = lobotomizedVillager();

        // As the store looks after repeated write failures. Preserving state now would strand
        // villagers with a marker but no row, which the uninstall sweep could never find.
        plugin.getMarkerStore().close();
        plugin.getStorage().flush(LobotomizeStorage.FlushMode.SHUTDOWN);

        assertTrue(villager.isAware(), "an unusable store must fall back to waking villagers");
        assertFalse(hasMarker(villager), "and leave no marker it cannot track");
    }

    @Test
    void reloadStillWakesAndClears() {
        load(true);
        Villager villager = lobotomizedVillager();

        plugin.getStorage().flush(LobotomizeStorage.FlushMode.RELOAD);
        server.getScheduler().performTicks(TICKS_FOR_SCHEDULED_WAKE);

        assertTrue(villager.isAware(), "a reload rescans immediately, so it must hand back a clean slate");
        assertFalse(hasMarker(villager), "and clear the marker");
    }

    @Test
    void reloadDropsTheTrackingRow() throws SQLException {
        load(true);
        LobotomizedMarkerStore store = plugin.getMarkerStore();
        lobotomizedVillager();

        plugin.getStorage().flush(LobotomizeStorage.FlushMode.RELOAD);
        server.getScheduler().performTicks(TICKS_FOR_SCHEDULED_WAKE);
        store.drainNow();

        assertTrue(store.loadAll().isEmpty(), "clearing the marker on reload should drop the row too");
    }

    @Test
    void quiesceForUninstallLeavesVillagersUntouched() {
        load(true);
        Villager villager = lobotomizedVillager();

        // The sweep owns all entity work, so quiescing must not pre-empt it.
        assertEquals(1, plugin.getStorage().quiesceForUninstall().size(), "the tracked villager is handed back");
        assertFalse(villager.isAware(), "quiescing must not wake anything");
        assertTrue(hasMarker(villager), "nor clear any marker");
    }
}
