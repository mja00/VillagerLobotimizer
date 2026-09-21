package dev.mja00.villagerLobotomizer;

import dev.mja00.villagerLobotomizer.storage.LobotomizedMarkerStore;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.List;
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
        return markedVillagerAt(world);
    }

    private Villager markedVillagerAt(WorldMock target) {
        Villager villager = target.spawn(new Location(target, 8, 64, 8), Villager.class);
        plugin.getStorage().removeVillager(villager);
        villager.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        plugin.getStorage().addVillager(villager);
        return villager;
    }

    private UninstallSweep newSweep() {
        return new UninstallSweep(plugin, store, null, loadedChunksOnly);
    }

    /** Drives the state machine directly, without its scheduled pump. */
    private void pumpUntilFinished(UninstallSweep sweep) {
        for (int i = 0; i < MAX_PUMPS && !sweep.isFinished(); i++) {
            sweep.pumpForTesting();
        }
        assertTrue(sweep.isFinished(), "sweep should reach a terminal state");
    }

    /** Drives a sweep through its own scheduled pump, as the server would. */
    private void runSweep(UninstallSweep sweep) {
        sweep.start();
        server.getScheduler().performTicks(MAX_PUMPS);
        assertTrue(sweep.isFinished(), "sweep should reach a terminal state");
    }

    private boolean stateFileExists() {
        return plugin.getDataFolder().toPath().resolve(LobotomizedMarkerStore.DATABASE_FILE_NAME).toFile().exists();
    }

    @Test
    void restoresLoadedVillagerAndDropsItsRow() throws SQLException {
        Villager villager = markedVillager();
        store.drainNow();
        assertEquals(1, store.loadAll().size(), "precondition: the villager has a row");
        assertFalse(villager.isAware(), "precondition: the villager is lobotomized");

        UninstallSweep sweep = newSweep();
        runSweep(sweep);

        assertTrue(villager.isAware(), "the sweep should restore AI");
        assertFalse(villager.getPersistentDataContainer().has(markerKey), "and remove the marker");
        assertFalse(stateFileExists(), "and, with nothing left, delete the state file");
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
        assertFalse(stateFileExists(), "and, with nothing left, delete the state file");
        assertEquals(1, sweep.getRestoredCount());
    }

    @Test
    void rowForVillagerThatNoLongerExistsIsDroppedAndCounted() throws SQLException {
        store.markerWritten(UUID.randomUUID(), world.getUID(), 0, 0);
        store.drainNow();

        UninstallSweep sweep = newSweep();
        pumpUntilFinished(sweep);

        assertEquals(1, sweep.getUnresolvedCount(), "a row with no entity should be counted unresolved");
        assertFalse(stateFileExists(), "a stale row leaves nothing behind, so the uninstall completes");
        server.getScheduler().performTicks(5);
        assertFalse(plugin.isEnabled(), "and the plugin is disabled rather than asking for a pointless re-run");
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
        assertFalse(stateFileExists(), "and nothing is left to keep the state file for");
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
        runSweep(sweep);

        assertFalse(stateFileExists(), "a clean sweep should delete the state file");
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

    @Test
    void rowsInSeveralWorldsAreEachSweptInTheirOwnWorld() throws SQLException {
        // Same chunk coordinates in three worlds. If targets are keyed by chunk coordinates alone
        // the rows merge, one world's villager is swept against another world's chunk, and it ends
        // up dropped as "missing" while it is still alive and frozen.
        WorldMock other1 = server.addSimpleWorld("other1");
        WorldMock other2 = server.addSimpleWorld("other2");
        other1.loadChunk(0, 0);
        other2.loadChunk(0, 0);
        List<Villager> villagers = List.of(
                markedVillagerAt(world), markedVillagerAt(other1), markedVillagerAt(other2));
        store.drainNow();

        UninstallSweep sweep = newSweep();
        pumpUntilFinished(sweep);

        for (Villager villager : villagers) {
            assertTrue(villager.isAware(), "each villager must be restored from its own world's chunk");
        }
        assertEquals(3, sweep.getRestoredCount());
        assertEquals(0, sweep.getUnresolvedCount(), "no living villager may be dropped as missing");
        assertFalse(stateFileExists(), "and, with nothing left, delete the state file");
    }

    @Test
    void stalledSweepKeepsRowsItNeverVisited() throws SQLException {
        store.markerWritten(UUID.randomUUID(), world.getUID(), 0, 0);
        store.drainNow();
        assertEquals(1, store.loadAll().size(), "precondition: one row to lose");

        // The accessor takes the request and never answers, like a chunk load that stalls. Nothing
        // was confirmed absent, so the rows must survive or the advertised re-run has nothing to retry.
        UninstallSweep sweep = new UninstallSweep(plugin, store, null, (w, x, z, callback) -> { });
        sweep.setStallTimeoutForTesting(0L);
        pumpUntilFinished(sweep);

        assertEquals(1, store.loadAll().size(), "an aborted sweep must keep rows it never confirmed absent");
        assertEquals(0, sweep.getUnresolvedCount(), "nothing was confirmed absent, so nothing is dropped");
        assertTrue(plugin.isEnabled(), "an incomplete sweep keeps the plugin enabled so it can be re-run");
        assertTrue(stateFileExists(), "and keeps the state file");
    }

    @Test
    void villagerWhoseOwnChunkFailsToLoadIsNotDroppedBecauseItsNeighboursWereSearched() throws SQLException {
        Villager villager = markedVillager();
        store.drainNow();

        // The recorded chunk can never be searched; the 3x3 pass still resolves its eight neighbours
        // (all absent here). Those misses must not add up to "confirmed gone" for a villager whose
        // own chunk was never looked at.
        UninstallSweep sweep = new UninstallSweep(plugin, store, null, (w, x, z, callback) -> {
            if (x == 0 && z == 0) {
                throw new IllegalStateException("simulated chunk load failure");
            }
            loadedChunksOnly.withChunk(w, x, z, callback);
        });
        pumpUntilFinished(sweep);

        assertFalse(villager.isAware(), "precondition: the villager was never reached");
        assertEquals(0, sweep.getUnresolvedCount(), "an unsearched chunk cannot confirm absence");
        assertEquals(1, store.loadAll().size(), "the row must survive for a re-run");
        assertTrue(stateFileExists());
    }

    @Test
    void villagerWhoseEntitiesNeverLoadIsNotDroppedAfterRetriesRunOut() throws SQLException {
        Villager villager = markedVillager();
        store.drainNow();

        // The chunk loads but its entity sections never do, so every attempt requeues until the
        // retry budget is spent. That is a transient load delay, not evidence the villager is gone.
        UninstallSweep sweep = new UninstallSweep(plugin, store, null, (w, x, z, callback) -> {
            if (!w.isChunkLoaded(x, z)) {
                callback.accept(null);
                return;
            }
            Chunk real = w.getChunkAt(x, z);
            callback.accept((Chunk) Proxy.newProxyInstance(Chunk.class.getClassLoader(), new Class<?>[]{Chunk.class},
                    (proxy, method, args) -> method.getName().equals("isEntitiesLoaded") ? false : method.invoke(real, args)));
        });
        pumpUntilFinished(sweep);

        assertFalse(villager.isAware(), "precondition: the villager was never reached");
        assertEquals(0, sweep.getUnresolvedCount(), "retry exhaustion is not proof of absence");
        assertEquals(1, store.loadAll().size(), "the row must survive for a re-run");
        assertTrue(plugin.isEnabled(), "and the plugin stays enabled so it can be re-run");
    }
}
