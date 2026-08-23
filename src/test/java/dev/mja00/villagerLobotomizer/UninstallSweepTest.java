package dev.mja00.villagerLobotomizer;

import dev.mja00.villagerLobotomizer.storage.LobotomizedMarkerStore;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the uninstall sweep. Chunk loading is injected because MockBukkit throws from
 * {@code getChunkAtAsync}; the accessor here hands back already-loaded chunks and {@code null}
 * otherwise, which is what an ungenerated chunk looks like to the sweep.
 *
 * <p>Tests that skip {@code start()} exercise the row-driven phase in isolation: {@code start()} is
 * what restores villagers already in memory, and it would otherwise reach every villager in a mock
 * world before the rows are ever read.
 */
class UninstallSweepTest extends MockBukkitTestBase {

    private static final int MAX_PUMPS = 50;

    private VillagerLobotomizer plugin;
    private WorldMock world;
    private NamespacedKey markerKey;
    /** Held directly: a clean sweep clears the plugin's reference once it has deleted the file. */
    private LobotomizedMarkerStore store;

    private final UninstallSweep.ChunkAccessor loadedChunksOnly = (world, chunkX, chunkZ, callback) ->
            callback.accept(world.isChunkLoaded(chunkX, chunkZ) ? world.getChunkAt(chunkX, chunkZ) : null);

    @BeforeEach
    void loadPlugin() {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
        world = server.addSimpleWorld("test");
        world.loadChunk(0, 0);
        markerKey = new NamespacedKey(plugin, LobotomizeStorage.LOBOTOMIZED_KEY);
        store = plugin.getMarkerStore();
    }

    /** A villager carrying the marker and tracked as lobotomized, as a restart would leave it. */
    private Villager markedVillager() {
        Villager villager = world.spawn(new Location(world, 8, 64, 8), Villager.class);
        plugin.getStorage().removeVillager(villager);
        villager.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        plugin.getStorage().addVillager(villager);
        return villager;
    }

    private UninstallSweep newSweep() {
        return new UninstallSweep(plugin, store, null, loadedChunksOnly);
    }

    private void pumpUntilFinished(UninstallSweep sweep) {
        for (int i = 0; i < MAX_PUMPS && !sweep.isFinished(); i++) {
            sweep.pumpForTesting();
        }
        assertTrue(sweep.isFinished(), "sweep should reach a terminal state");
    }

    @Test
    void restoresLoadedVillagerAndDropsItsRow() throws SQLException {
        Villager villager = markedVillager();
        store.drainNow();
        assertEquals(1, store.loadAll().size(), "precondition: the villager has a row");
        assertFalse(villager.isAware(), "precondition: the villager is lobotomized");

        UninstallSweep sweep = newSweep();
        sweep.start();
        server.getScheduler().performTicks(20);

        assertTrue(villager.isAware(), "the sweep should restore AI");
        assertFalse(villager.getPersistentDataContainer().has(markerKey), "and remove the marker");
        assertTrue(store.loadAll().isEmpty(), "and drop the row");
        assertEquals(1, sweep.getRestoredCount());
    }

    @Test
    void rowDrivenPhaseRestoresVillagerInItsRecordedChunk() throws SQLException {
        Villager villager = markedVillager();
        store.drainNow();

        // No start(), so nothing has looked at loaded villagers: the row is the only way in.
        UninstallSweep sweep = newSweep();
        pumpUntilFinished(sweep);

        assertTrue(villager.isAware(), "the row-driven phase should restore AI");
        assertFalse(villager.getPersistentDataContainer().has(markerKey), "and remove the marker");
        assertTrue(store.loadAll().isEmpty(), "and drop the row");
        assertEquals(1, sweep.getRestoredCount());
    }

    @Test
    void rowForVillagerThatNoLongerExistsIsDroppedAndCounted() throws SQLException {
        store.markerWritten(UUID.randomUUID(), world.getUID(), 0, 0);
        store.drainNow();

        UninstallSweep sweep = newSweep();
        pumpUntilFinished(sweep);

        assertEquals(1, sweep.getUnresolvedCount(), "a row with no entity should be counted unresolved");
        assertTrue(store.loadAll().isEmpty(), "and its row dropped, so it cannot be retried forever");
    }

    @Test
    void rowInUnloadedWorldIsSkippedAndItsRowKept() throws SQLException {
        store.markerWritten(UUID.randomUUID(), UUID.randomUUID(), 0, 0);
        store.drainNow();

        UninstallSweep sweep = newSweep();
        pumpUntilFinished(sweep);

        assertEquals(1, sweep.getSkippedCount(), "a world that is not loaded should be skipped, not dropped");
        assertEquals(1, store.loadAll().size(), "its row must survive so the command can finish later");
        assertEquals(0, sweep.getUnresolvedCount());
    }

    @Test
    void rowForUngeneratedChunkIsDroppedAndCounted() throws SQLException {
        // Nothing has loaded this chunk, so the accessor reports it as absent.
        store.markerWritten(UUID.randomUUID(), world.getUID(), 40, 40);
        store.drainNow();

        UninstallSweep sweep = newSweep();
        pumpUntilFinished(sweep);

        assertEquals(1, sweep.getUnresolvedCount(), "an absent chunk means there is nothing to restore");
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    void staleChunkCoordinatesAreFoundInTheSecondPass() throws SQLException {
        Villager villager = markedVillager();
        store.drainNow();
        // Pretend the villager was pushed across a chunk border since we last recorded it.
        world.loadChunk(1, 0);
        store.markerWritten(villager.getUniqueId(), world.getUID(), 1, 0);
        store.drainNow();

        UninstallSweep sweep = newSweep();
        pumpUntilFinished(sweep);

        assertTrue(villager.isAware(), "the 3x3 second pass should still find it");
        assertEquals(1, sweep.getRestoredCount());
        assertEquals(0, sweep.getUnresolvedCount());
    }

    @Test
    void cleanSweepDeletesTheStateFileAndDisablesThePlugin() {
        markedVillager();
        store.drainNow();

        UninstallSweep sweep = newSweep();
        sweep.start();
        server.getScheduler().performTicks(20);

        assertFalse(plugin.getDataFolder().toPath()
                        .resolve(LobotomizedMarkerStore.DATABASE_FILE_NAME).toFile().exists(),
                "a clean sweep should delete the state file");
        assertFalse(plugin.isEnabled(), "and disable the plugin");
    }

    @Test
    void incompleteSweepKeepsTheStateFileAndLeavesThePluginEnabled() {
        store.markerWritten(UUID.randomUUID(), UUID.randomUUID(), 0, 0);
        store.drainNow();

        UninstallSweep sweep = newSweep();
        pumpUntilFinished(sweep);
        server.getScheduler().performTicks(5);

        assertTrue(plugin.getDataFolder().toPath()
                        .resolve(LobotomizedMarkerStore.DATABASE_FILE_NAME).toFile().exists(),
                "an incomplete sweep must keep the only record of what is left");
        assertTrue(plugin.isEnabled(), "and stay enabled so the command can be re-run");
    }

    @Test
    void secondUninstallIsRefusedWhileOneIsRunning() {
        assertTrue(plugin.startUninstall(server.getConsoleSender()), "the first uninstall should start");
        assertFalse(plugin.startUninstall(server.getConsoleSender()), "a concurrent uninstall should be refused");
    }

    @Test
    void reloadIsRefusedWhileUninstalling() {
        assertTrue(plugin.startUninstall(server.getConsoleSender()));

        assertEquals(-1, plugin.reloadPluginState(),
                "a reload would re-lobotomize villagers the sweep is clearing");
    }
}
