package dev.mja00.villagerLobotomizer.storage;

import dev.mja00.villagerLobotomizer.utils.SentryTaskWrapper;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records which villagers currently carry the persistent lobotomy marker, so
 * {@code /lobotomy uninstall} can find them again even when their chunks are not loaded.
 *
 * <p>The invariant is that a row exists exactly when the plugin has written the PDC marker on that
 * villager. Callers announce marker changes via {@link #markerWritten} and {@link #markerCleared},
 * which only touch memory; the rows are written by {@link #drainNow()} on an async task.
 *
 * <p>Deliberately free of Bukkit types except for {@link #startDrainTask} and the {@link Villager}
 * convenience overload, so the persistence logic is unit-testable without a server.
 */
public final class LobotomizedMarkerStore implements AutoCloseable {

    public static final String DATABASE_FILE_NAME = "state.db";

    /** Consecutive drain failures after which we stop writing rather than retry forever. */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS marked_villagers (
                entity_uuid TEXT    NOT NULL PRIMARY KEY,
                world_uuid  TEXT    NOT NULL,
                chunk_x     INTEGER NOT NULL,
                chunk_z     INTEGER NOT NULL
            ) WITHOUT ROWID
            """;
    private static final String UPSERT_ROW = """
            INSERT INTO marked_villagers (entity_uuid, world_uuid, chunk_x, chunk_z)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(entity_uuid) DO UPDATE SET
                world_uuid = excluded.world_uuid,
                chunk_x = excluded.chunk_x,
                chunk_z = excluded.chunk_z
            """;
    private static final String DELETE_ROW = "DELETE FROM marked_villagers WHERE entity_uuid = ?";
    private static final String SELECT_ALL = "SELECT entity_uuid, world_uuid, chunk_x, chunk_z FROM marked_villagers";

    private final Path databaseFile;
    private final Logger logger;
    private final Object ioLock = new Object();

    /**
     * What the plugin intends the table to contain, keyed by entity UUID. Updated by the announcing
     * thread so it can never disagree with the PDC writes it mirrors. Entries are either committed
     * rows, pending upserts, or pending deletes; committed deletes are removed outright.
     */
    private final Map<UUID, Intent> intents = new ConcurrentHashMap<>();

    private Connection connection;
    private ScheduledTask drainTask;
    private volatile boolean usable;
    private volatile boolean closed;
    private int consecutiveFailures;

    public LobotomizedMarkerStore(@NotNull Path databaseFile, @NotNull Logger logger) {
        this.databaseFile = databaseFile;
        this.logger = logger;
    }

    /**
     * Opens the database, applies the schema and seeds the intent map from existing rows.
     *
     * @return {@code false} if the store is unusable, in which case callers must not write markers
     */
    public boolean open() {
        synchronized (this.ioLock) {
            try {
                Path parent = this.databaseFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                SQLiteDataSource dataSource = new SQLiteDataSource();
                dataSource.setUrl("jdbc:sqlite:" + this.databaseFile.toAbsolutePath());
                this.connection = dataSource.getConnection();
                applyPragmas(this.connection);

                try (Statement statement = this.connection.createStatement()) {
                    statement.executeUpdate(CREATE_TABLE);
                    statement.executeUpdate("PRAGMA user_version = 1");
                }

                for (MarkedVillager row : readAll(this.connection)) {
                    this.intents.put(row.entityId(), Intent.committed(row.worldId(), row.chunkX(), row.chunkZ()));
                }

                this.usable = true;
                return true;
            } catch (SQLException | IOException | RuntimeException e) {
                this.logger.log(Level.SEVERE, "Could not open " + this.databaseFile
                        + "; lobotomized state will not persist this session.", e);
                closeConnectionQuietly();
                this.usable = false;
                return false;
            }
        }
    }

    private void applyPragmas(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Some network and container mounts cannot provide WAL's shared memory, so fall back to
            // the rollback journal instead of failing outright.
            try (ResultSet results = statement.executeQuery("PRAGMA journal_mode = WAL")) {
                String mode = results.next() ? results.getString(1) : null;
                if (mode != null && !"wal".equalsIgnoreCase(mode)) {
                    this.logger.warning("SQLite refused WAL mode on this filesystem (got " + mode
                            + "); continuing with the default journal.");
                }
            }
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.execute("PRAGMA busy_timeout = 3000");
        }
    }

    public boolean isUsable() {
        return this.usable && !this.closed;
    }

    /**
     * Starts the periodic async drain. Separate from {@link #open()} so tests can drain on demand.
     */
    public void startDrainTask(@NotNull Plugin plugin, long periodSeconds) {
        if (!isUsable()) {
            return;
        }
        this.drainTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin,
                SentryTaskWrapper.wrap((task) -> drainNow()), periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    /** Records that the marker now exists on this villager. Never does I/O. */
    public void markerWritten(@NotNull UUID entityId, @NotNull UUID worldId, int chunkX, int chunkZ) {
        if (!isUsable()) {
            return;
        }
        this.intents.compute(entityId, (id, current) -> {
            if (current != null && current.matches(worldId, chunkX, chunkZ) && !current.dirty()) {
                return current;
            }
            return Intent.pendingUpsert(worldId, chunkX, chunkZ);
        });
    }

    /**
     * Records that the marker now exists, deriving the chunk from the villager. Must be called on the
     * thread that owns the entity.
     */
    public void markerWritten(@NotNull Villager villager) {
        markerWritten(villager.getUniqueId(), villager.getWorld().getUID(),
                villager.getLocation().getBlockX() >> 4, villager.getLocation().getBlockZ() >> 4);
    }

    /**
     * Records that the marker is gone. A villager we hold no row for is a no-op, which is what keeps
     * the per-check marker clearing on active villagers from queueing a delete every tick.
     */
    public void markerCleared(@NotNull UUID entityId) {
        if (!isUsable()) {
            return;
        }
        this.intents.compute(entityId, (id, current) -> {
            if (current == null || current.absent()) {
                return current;
            }
            return Intent.pendingDelete();
        });
    }

    /** Rows the store believes exist, from memory. */
    public int getKnownRowCount() {
        return (int) this.intents.values().stream().filter((intent) -> !intent.absent()).count();
    }

    /** Applies every buffered change on the calling thread. Must not be called on a region thread. */
    public void drainNow() {
        if (!isUsable()) {
            return;
        }

        Map<UUID, Intent> batch = new HashMap<>();
        this.intents.forEach((id, intent) -> {
            if (intent.dirty()) {
                batch.put(id, intent);
            }
        });
        if (batch.isEmpty()) {
            return;
        }

        synchronized (this.ioLock) {
            if (this.closed || this.connection == null) {
                return;
            }
            try {
                applyBatch(batch);
                this.consecutiveFailures = 0;
            } catch (SQLException e) {
                this.consecutiveFailures++;
                // Leave the entries dirty so the next drain retries them.
                if (this.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    this.usable = false;
                    this.logger.log(Level.SEVERE, "Giving up on " + this.databaseFile + " after "
                            + this.consecutiveFailures + " failed writes; lobotomized state will not persist.", e);
                } else {
                    this.logger.log(Level.WARNING, "Failed to write lobotomy state; will retry.", e);
                }
                return;
            }
        }

        // Mark clean only where nothing changed underneath us, so a concurrent update stays dirty.
        batch.forEach((id, applied) -> this.intents.compute(id, (key, current) -> {
            if (current != applied) {
                return current;
            }
            return current.absent() ? null : current.asCommitted();
        }));
    }

    private void applyBatch(Map<UUID, Intent> batch) throws SQLException {
        boolean previousAutoCommit = this.connection.getAutoCommit();
        this.connection.setAutoCommit(false);
        try (PreparedStatement upsert = this.connection.prepareStatement(UPSERT_ROW);
             PreparedStatement delete = this.connection.prepareStatement(DELETE_ROW)) {
            for (Map.Entry<UUID, Intent> entry : batch.entrySet()) {
                Intent intent = entry.getValue();
                if (intent.absent()) {
                    delete.setString(1, entry.getKey().toString());
                    delete.addBatch();
                    continue;
                }
                upsert.setString(1, entry.getKey().toString());
                upsert.setString(2, intent.worldId().toString());
                upsert.setInt(3, intent.chunkX());
                upsert.setInt(4, intent.chunkZ());
                upsert.addBatch();
            }
            upsert.executeBatch();
            delete.executeBatch();
            this.connection.commit();
        } catch (SQLException e) {
            rollbackQuietly(e);
            throw e;
        } finally {
            this.connection.setAutoCommit(previousAutoCommit);
        }
    }

    public @NotNull List<MarkedVillager> loadAll() throws SQLException {
        synchronized (this.ioLock) {
            if (this.closed || this.connection == null) {
                return List.of();
            }
            return readAll(this.connection);
        }
    }

    /** Deletes rows immediately, bypassing the buffer. Used by the uninstall sweep. */
    public void deleteNow(@NotNull Collection<UUID> entityIds) throws SQLException {
        if (entityIds.isEmpty()) {
            return;
        }
        synchronized (this.ioLock) {
            if (this.closed || this.connection == null) {
                return;
            }
            boolean previousAutoCommit = this.connection.getAutoCommit();
            this.connection.setAutoCommit(false);
            try (PreparedStatement delete = this.connection.prepareStatement(DELETE_ROW)) {
                for (UUID entityId : entityIds) {
                    delete.setString(1, entityId.toString());
                    delete.addBatch();
                }
                delete.executeBatch();
                this.connection.commit();
            } catch (SQLException e) {
                rollbackQuietly(e);
                throw e;
            } finally {
                this.connection.setAutoCommit(previousAutoCommit);
            }
        }
        entityIds.forEach(this.intents::remove);
    }

    private static @NotNull List<MarkedVillager> readAll(Connection connection) throws SQLException {
        List<MarkedVillager> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                try {
                    rows.add(new MarkedVillager(
                            UUID.fromString(results.getString("entity_uuid")),
                            UUID.fromString(results.getString("world_uuid")),
                            results.getInt("chunk_x"),
                            results.getInt("chunk_z")));
                } catch (IllegalArgumentException e) {
                    // A malformed UUID can only come from external editing; skip rather than abort.
                }
            }
        }
        return rows;
    }

    /** Drains, cancels the drain task and closes the connection. Idempotent. */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        drainNow();
        cancelDrainTask();
        synchronized (this.ioLock) {
            this.closed = true;
            closeConnectionQuietly();
        }
    }

    /**
     * Closes without draining and deletes the database and its journal sidecars. Idempotent, and
     * never reopens: a drain after this must not resurrect the file we just removed.
     */
    public boolean deleteDatabaseFiles() {
        cancelDrainTask();
        synchronized (this.ioLock) {
            this.closed = true;
            this.usable = false;
            closeConnectionQuietly();
            this.intents.clear();

            boolean deleted = true;
            for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
                Path path = this.databaseFile.resolveSibling(this.databaseFile.getFileName() + suffix);
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    this.logger.log(Level.WARNING, "Could not delete " + path, e);
                    deleted = false;
                }
            }
            return deleted;
        }
    }

    private void cancelDrainTask() {
        ScheduledTask task = this.drainTask;
        this.drainTask = null;
        if (task == null) {
            return;
        }
        try {
            task.cancel();
        } catch (Throwable t) {
            // Cancellation failure is harmless; the task body no-ops once closed.
        }
    }

    private void closeConnectionQuietly() {
        Connection open = this.connection;
        this.connection = null;
        if (open == null) {
            return;
        }
        try {
            open.close();
        } catch (SQLException e) {
            this.logger.log(Level.WARNING, "Failed to close " + this.databaseFile, e);
        }
    }

    private void rollbackQuietly(SQLException failure) {
        try {
            this.connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    /** Test hook: how many changes are waiting to be written. */
    int pendingCount() {
        return (int) this.intents.values().stream().filter(Intent::dirty).count();
    }

    private record Intent(UUID worldId, int chunkX, int chunkZ, boolean absent, boolean dirty) {

        static Intent committed(UUID worldId, int chunkX, int chunkZ) {
            return new Intent(worldId, chunkX, chunkZ, false, false);
        }

        static Intent pendingUpsert(UUID worldId, int chunkX, int chunkZ) {
            return new Intent(worldId, chunkX, chunkZ, false, true);
        }

        static Intent pendingDelete() {
            return new Intent(null, 0, 0, true, true);
        }

        boolean matches(UUID otherWorld, int otherChunkX, int otherChunkZ) {
            return !this.absent && this.chunkX == otherChunkX && this.chunkZ == otherChunkZ
                    && otherWorld.equals(this.worldId);
        }

        Intent asCommitted() {
            return committed(this.worldId, this.chunkX, this.chunkZ);
        }
    }
}
