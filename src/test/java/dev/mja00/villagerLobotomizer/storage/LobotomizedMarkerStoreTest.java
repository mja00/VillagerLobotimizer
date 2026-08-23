package dev.mja00.villagerLobotomizer.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence and buffering behaviour of {@link LobotomizedMarkerStore}. No MockBukkit needed: the
 * store only touches Bukkit for its scheduled drain and the {@code Villager} overload.
 */
class LobotomizedMarkerStoreTest {

    private static final Logger LOGGER = Logger.getLogger(LobotomizedMarkerStoreTest.class.getName());

    @TempDir
    Path dataFolder;

    private LobotomizedMarkerStore openStore() {
        LobotomizedMarkerStore store = new LobotomizedMarkerStore(
                dataFolder.resolve(LobotomizedMarkerStore.DATABASE_FILE_NAME), LOGGER);
        assertTrue(store.open(), "store should open in a temp folder");
        return store;
    }

    @Test
    void roundTripsRowAcrossReopen() throws SQLException {
        UUID entity = UUID.randomUUID();
        UUID world = UUID.randomUUID();

        LobotomizedMarkerStore store = openStore();
        store.markerWritten(entity, world, 3, -7);
        store.drainNow();
        store.close();

        LobotomizedMarkerStore reopened = openStore();
        try {
            assertEquals(List.of(new MarkedVillager(entity, world, 3, -7)), reopened.loadAll(),
                    "a drained row should survive a reopen");
            assertEquals(1, reopened.getKnownRowCount());
        } finally {
            reopened.close();
        }
    }

    @Test
    void clearRemovesRow() throws SQLException {
        UUID entity = UUID.randomUUID();
        LobotomizedMarkerStore store = openStore();
        try {
            store.markerWritten(entity, UUID.randomUUID(), 0, 0);
            store.drainNow();
            assertEquals(1, store.loadAll().size(), "precondition: row was written");

            store.markerCleared(entity);
            store.drainNow();

            assertTrue(store.loadAll().isEmpty(), "clearing the marker should delete the row");
            assertEquals(0, store.getKnownRowCount());
        } finally {
            store.close();
        }
    }

    @Test
    void lastIntentWinsWhenCoalesced() throws SQLException {
        UUID entity = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        LobotomizedMarkerStore store = openStore();
        try {
            store.markerWritten(entity, world, 1, 1);
            store.markerCleared(entity);
            store.markerWritten(entity, world, 9, 9);
            store.drainNow();

            assertEquals(List.of(new MarkedVillager(entity, world, 9, 9)), store.loadAll(),
                    "the newest intent should be the one written");
        } finally {
            store.close();
        }
    }

    @Test
    void repeatedFlipsDoNotGrowPendingBuffer() {
        UUID entity = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        LobotomizedMarkerStore store = openStore();
        try {
            for (int i = 0; i < 1000; i++) {
                store.markerWritten(entity, world, i, i);
                store.markerCleared(entity);
            }

            assertEquals(1, store.pendingCount(), "coalescing by entity should keep the buffer at one entry");
        } finally {
            store.close();
        }
    }

    @Test
    void skipsRedundantWriteForUnchangedChunk() {
        UUID entity = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        LobotomizedMarkerStore store = openStore();
        try {
            store.markerWritten(entity, world, 4, 4);
            store.drainNow();
            assertEquals(0, store.pendingCount(), "precondition: nothing pending after a drain");

            store.markerWritten(entity, world, 4, 4);
            assertEquals(0, store.pendingCount(), "re-writing the same chunk should enqueue nothing");

            store.markerWritten(entity, world, 5, 4);
            assertEquals(1, store.pendingCount(), "a moved villager should enqueue an update");
        } finally {
            store.close();
        }
    }

    @Test
    void clearForUnknownVillagerEnqueuesNothing() {
        LobotomizedMarkerStore store = openStore();
        try {
            // Active villagers have their marker cleared on every check, so this must stay free.
            for (int i = 0; i < 100; i++) {
                store.markerCleared(UUID.randomUUID());
            }

            assertEquals(0, store.pendingCount(), "clearing a villager we hold no row for should be a no-op");
        } finally {
            store.close();
        }
    }

    @Test
    void clearForKnownRowEnqueuesDelete() {
        UUID entity = UUID.randomUUID();
        LobotomizedMarkerStore store = openStore();
        try {
            store.markerWritten(entity, UUID.randomUUID(), 0, 0);
            store.drainNow();

            // The row can outlive its marker if the chunk never saved, so this must still fire.
            store.markerCleared(entity);

            assertEquals(1, store.pendingCount(), "clearing a known row should enqueue a delete");
        } finally {
            store.close();
        }
    }

    @Test
    void openFailureLeavesStoreUnusableWithoutThrowing() throws Exception {
        Path blocker = dataFolder.resolve("blocker");
        Files.writeString(blocker, "not a directory");

        LobotomizedMarkerStore store = new LobotomizedMarkerStore(blocker.resolve("nested/state.db"), LOGGER);

        assertFalse(store.open(), "opening under a regular file should fail");
        assertFalse(store.isUsable());
        store.markerWritten(UUID.randomUUID(), UUID.randomUUID(), 0, 0);
        store.markerCleared(UUID.randomUUID());
        store.drainNow();
        assertEquals(0, store.pendingCount(), "an unusable store should ignore writes rather than buffer them");
        store.close();
    }

    @Test
    void deleteDatabaseFilesRemovesSidecars() {
        LobotomizedMarkerStore store = openStore();
        store.markerWritten(UUID.randomUUID(), UUID.randomUUID(), 0, 0);
        store.drainNow();

        assertTrue(store.deleteDatabaseFiles(), "delete should report success");

        Path database = dataFolder.resolve(LobotomizedMarkerStore.DATABASE_FILE_NAME);
        assertFalse(Files.exists(database), "the database file should be gone");
        assertFalse(Files.exists(dataFolder.resolve(database.getFileName() + "-wal")), "the WAL should be gone");
        assertFalse(Files.exists(dataFolder.resolve(database.getFileName() + "-shm")), "the shm should be gone");
    }

    @Test
    void drainAfterDeleteDoesNotRecreateTheDatabase() {
        LobotomizedMarkerStore store = openStore();
        store.markerWritten(UUID.randomUUID(), UUID.randomUUID(), 0, 0);
        store.deleteDatabaseFiles();

        // onDisable still runs after the uninstall sweep deleted the file.
        store.drainNow();
        store.close();

        assertFalse(Files.exists(dataFolder.resolve(LobotomizedMarkerStore.DATABASE_FILE_NAME)),
                "a drain after deletion must not resurrect the state file");
    }
}
