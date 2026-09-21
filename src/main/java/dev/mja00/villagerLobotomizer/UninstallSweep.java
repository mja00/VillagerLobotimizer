package dev.mja00.villagerLobotomizer;

import dev.mja00.villagerLobotomizer.storage.LobotomizedMarkerStore;
import dev.mja00.villagerLobotomizer.storage.MarkedVillager;
import dev.mja00.villagerLobotomizer.utils.SentryTaskWrapper;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Undoes everything the plugin has done: restores AI and strips the persistent marker from every
 * villager it ever lobotomized, including those in chunks that are not loaded, then disables the
 * plugin so the jar can be deleted.
 *
 * <p>Runs as a small state machine driven from the global region scheduler. Villagers currently in
 * memory are handled first, then the remaining rows in the marker store drive targeted chunk loads.
 */
public final class UninstallSweep {

    /**
     * Brings a chunk into memory and hands it to the callback on a thread that owns it. Injectable
     * because MockBukkit does not implement asynchronous chunk loading.
     */
    @FunctionalInterface
    public interface ChunkAccessor {
        void withChunk(World world, int chunkX, int chunkZ, Consumer<@Nullable Chunk> callback);
    }

    /** Roughly one player's view-distance stream, so the sweep never looks like a load storm. */
    private static final int MAX_IN_FLIGHT = 8;
    private static final int MAX_ATTEMPTS = 3;
    private static final long PROGRESS_EVERY_TICKS = 100L;
    private static final long PHASE_A_TIMEOUT_MILLIS = 10_000L;
    private static final long DEFAULT_STALL_TIMEOUT_MILLIS = 30_000L;

    private enum Stage { RESTORING_LOADED, SWEEPING_ROWS, FINISHED }

    private final VillagerLobotomizer plugin;
    private final LobotomizedMarkerStore store;
    private final NamespacedKey lobotomizedKey;
    private final boolean silent;
    private final UUID requesterId;
    private final ChunkAccessor chunkAccessor;

    private final AtomicInteger dispatched = new AtomicInteger();
    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger restored = new AtomicInteger();
    private final AtomicInteger chunksSwept = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();

    private final Set<UUID> cleared = ConcurrentHashMap.newKeySet();
    /** Rows in worlds that are not loaded right now. Kept, never dropped. */
    private final Set<UUID> skippedIds = ConcurrentHashMap.newKeySet();
    /**
     * Villagers a second-pass chunk was swept for without finding them. Not on its own proof of
     * absence: every one of the 3x3 neighbourhood chunks must have been searched.
     */
    private final Set<UUID> confirmedAbsent = ConcurrentHashMap.newKeySet();
    /**
     * Villagers with a second-pass chunk that was never searched (retries exhausted, load failed).
     * Their neighbourhood was not fully covered, so their rows are kept for a re-run.
     */
    private final Set<UUID> searchIncomplete = ConcurrentHashMap.newKeySet();
    private final Map<UUID, MarkedVillager> rowsById = new HashMap<>();
    /** Concurrent so a region thread can requeue while still holding its in-flight slot. */
    private final Deque<ChunkTarget> queue = new ConcurrentLinkedDeque<>();

    private Stage stage = Stage.RESTORING_LOADED;
    private ScheduledTask pumpTask;
    private long phaseADeadline;
    private long lastProgressAt;
    private int totalChunks;
    private int lastSweptSeen;
    private long lastChangeAt;
    private boolean secondPassDone;
    private int unresolvedCount;
    private long stallTimeoutMillis = DEFAULT_STALL_TIMEOUT_MILLIS;

    UninstallSweep(@NotNull VillagerLobotomizer plugin, @NotNull LobotomizedMarkerStore store,
                   @Nullable UUID requesterId, @NotNull ChunkAccessor chunkAccessor) {
        this.plugin = plugin;
        this.store = store;
        this.lobotomizedKey = new NamespacedKey(plugin, LobotomizeStorage.LOBOTOMIZED_KEY);
        this.silent = plugin.getConfig().getBoolean("silent-lobotomized-villagers");
        this.requesterId = requesterId;
        this.chunkAccessor = chunkAccessor;
    }

    static @NotNull ChunkAccessor paperChunkAccessor() {
        // The consumer overload is always invoked by the chunk system on a thread that owns the chunk;
        // the CompletableFuture form can complete inline on the calling thread instead.
        // generate=false so an ungenerated chunk yields null rather than creating terrain.
        return (world, chunkX, chunkZ, callback) -> world.getChunkAtAsync(chunkX, chunkZ, false, callback);
    }

    /** Quiesces the plugin, restores everything in memory, then starts the row-driven sweep. */
    void start() {
        List<Villager> tracked = this.plugin.getStorage().quiesceForUninstall();
        report(Component.text("Restoring villagers, this may take a while on a large world.")
                .color(NamedTextColor.YELLOW));

        restoreLoadedVillagers(tracked);

        long now = System.currentTimeMillis();
        this.phaseADeadline = now + PHASE_A_TIMEOUT_MILLIS;
        this.lastChangeAt = now;
        this.lastProgressAt = now;
        this.pumpTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                this.plugin, SentryTaskWrapper.wrap((task) -> pump()), 1L, 1L);
    }

    /**
     * Restores every villager currently in memory. Also scans loaded worlds, so a villager whose row
     * was never written is still cleaned up.
     */
    private void restoreLoadedVillagers(@NotNull List<Villager> tracked) {
        Set<UUID> seen = new HashSet<>();
        List<Villager> candidates = new ArrayList<>(tracked);
        for (World world : Bukkit.getWorlds()) {
            candidates.addAll(world.getEntitiesByClass(Villager.class));
        }

        for (Villager villager : candidates) {
            if (!seen.add(villager.getUniqueId())) {
                continue;
            }
            this.dispatched.incrementAndGet();
            try {
                ScheduledTask scheduled = villager.getScheduler().run(this.plugin, SentryTaskWrapper.wrap((task) -> {
                    try {
                        restore(villager);
                    } finally {
                        this.completed.incrementAndGet();
                    }
                }), this.completed::incrementAndGet);
                if (scheduled == null) {
                    // Entity already gone, so neither callback will fire; do not wait on it.
                    this.completed.incrementAndGet();
                }
            } catch (Exception e) {
                this.completed.incrementAndGet();
                this.plugin.getLogger().log(Level.WARNING,
                        "Could not restore villager " + villager.getUniqueId(), e);
            }
        }
    }

    /**
     * The one idempotent unit of work, always on the thread that owns the entity. The row is dropped
     * last: doing it before the marker is actually gone would leave a marker nothing records.
     */
    private void restore(@NotNull Villager villager) {
        villager.setAware(true);
        if (this.silent) {
            villager.setSilent(false);
        }
        villager.getPersistentDataContainer().remove(this.lobotomizedKey);
        this.cleared.add(villager.getUniqueId());
        this.store.markerCleared(villager.getUniqueId());
        this.restored.incrementAndGet();
    }

    private void pump() {
        switch (this.stage) {
            case RESTORING_LOADED -> pumpRestoringLoaded();
            case SWEEPING_ROWS -> pumpSweepingRows();
            case FINISHED -> { }
        }
    }

    private void pumpRestoringLoaded() {
        boolean done = this.completed.get() >= this.dispatched.get();
        if (!done && System.currentTimeMillis() < this.phaseADeadline) {
            return;
        }

        // Anything still outstanding is picked up by the row sweep; restore() is idempotent.
        // Reading the table blocks briefly, which is fine here: it is one small local query during an
        // operation the admin asked for that is about to force-load chunks anyway.
        this.store.drainNow();
        List<MarkedVillager> rows = readRows();
        if (rows == null) {
            // A failed read must abort: continuing as if there were no rows would end in a "clean"
            // report over a state file whose contents were never seen.
            report(Component.text("Could not read the state file; rows for villagers not already "
                    + "restored were kept.").color(NamedTextColor.YELLOW));
            finish(false);
            return;
        }
        buildTargets(rows);
        this.lastChangeAt = System.currentTimeMillis();
        this.stage = Stage.SWEEPING_ROWS;
    }

    /** @return the rows, or {@code null} if the state file could not be read */
    private @Nullable List<MarkedVillager> readRows() {
        try {
            return this.store.loadAll();
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not read the marker store; "
                    + "villagers in unloaded chunks were not restored.", e);
            return null;
        }
    }

    /** Groups remaining rows by world and chunk, so a whole trading hall costs one chunk load. */
    private void buildTargets(@NotNull List<MarkedVillager> rows) {
        Map<ChunkKey, ChunkTarget> byChunk = new HashMap<>();
        for (MarkedVillager row : rows) {
            if (this.cleared.contains(row.entityId())) {
                continue;
            }
            World world = Bukkit.getWorld(row.worldId());
            if (world == null) {
                // Not a missing world, just one that is not loaded right now. Keep the row so the
                // command can finish the job once it is.
                this.skippedIds.add(row.entityId());
                continue;
            }
            this.rowsById.put(row.entityId(), row);
            // The world is part of the key: chunk coordinates are only unique within one world, and
            // merging rows from two worlds would sweep one of them against the wrong world's chunk.
            byChunk.computeIfAbsent(new ChunkKey(world.getUID(), row.chunkX(), row.chunkZ()),
                    (key) -> new ChunkTarget(world, row.chunkX(), row.chunkZ())).villagerIds.add(row.entityId());
        }

        this.queue.addAll(byChunk.values());
        this.totalChunks = this.queue.size();
        if (this.totalChunks > 0) {
            report(Component.text("Loading " + this.totalChunks + " chunk(s) to reach villagers that are not in memory."));
        }
    }

    private void pumpSweepingRows() {
        while (this.inFlight.get() < MAX_IN_FLIGHT) {
            ChunkTarget target = this.queue.poll();
            if (target == null) {
                break;
            }
            this.inFlight.incrementAndGet();
            requestChunk(target);
        }

        reportProgress();

        if (!this.queue.isEmpty() || this.inFlight.get() > 0) {
            checkForStall();
            return;
        }
        if (!this.secondPassDone) {
            this.secondPassDone = true;
            buildSecondPassTargets();
            if (!this.queue.isEmpty()) {
                return;
            }
        }
        finish(true);
    }

    /**
     * The in-flight slot is released only once the target has settled (swept, absent, requeued or
     * abandoned). Releasing it earlier lets the pump see an empty queue with nothing in flight and
     * finish while a hand-off or requeue is still pending, which is how a living villager gets
     * confirmed absent by its neighbours before its own chunk was ever searched.
     */
    private void requestChunk(@NotNull ChunkTarget target) {
        try {
            this.chunkAccessor.withChunk(target.world, target.chunkX, target.chunkZ, (chunk) -> {
                if (chunk == null) {
                    // generate=false and nothing on disk, so nothing can be here. Only the second pass
                    // may treat that as absence: a first-pass miss may just be a stale recorded chunk.
                    if (target.secondPass) {
                        this.confirmedAbsent.addAll(target.villagerIds);
                    }
                    this.inFlight.decrementAndGet();
                    return;
                }
                if (Bukkit.isOwnedByCurrentRegion(target.world, target.chunkX, target.chunkZ)) {
                    settle(target, chunk);
                    return;
                }
                // Folia's thread confinement for this callback is undocumented, so hand off to the
                // scheduler that is documented to own the chunk rather than assume.
                try {
                    Bukkit.getRegionScheduler().execute(this.plugin, target.world, target.chunkX, target.chunkZ,
                            () -> settle(target, target.world.isChunkLoaded(target.chunkX, target.chunkZ)
                                    ? target.world.getChunkAt(target.chunkX, target.chunkZ) : null));
                } catch (Exception e) {
                    abandon(target, e);
                }
            });
        } catch (Exception e) {
            abandon(target, e);
        }
    }

    /** Sweeps or requeues on the owning thread, then releases the in-flight slot. */
    private void settle(@NotNull ChunkTarget target, @Nullable Chunk chunk) {
        try {
            if (chunk == null || !chunk.isEntitiesLoaded()) {
                // Unloaded again, or getEntities() would return an empty array until the entity
                // sections load, which would look like "no villagers here" and silently under-clean.
                requeue(target);
                return;
            }
            sweepChunk(target, chunk);
        } finally {
            this.inFlight.decrementAndGet();
        }
    }

    private void sweepChunk(@NotNull ChunkTarget target, @NotNull Chunk chunk) {
        Set<UUID> wanted = new HashSet<>(target.villagerIds);
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Villager villager && wanted.remove(villager.getUniqueId())) {
                restore(villager);
            }
        }
        if (target.secondPass) {
            this.confirmedAbsent.addAll(wanted);
        }
        this.chunksSwept.incrementAndGet();
        this.lastChangeAt = System.currentTimeMillis();
    }

    private void requeue(@NotNull ChunkTarget target) {
        if (++target.attempts >= MAX_ATTEMPTS) {
            giveUp(target);
            return;
        }
        this.queue.add(target);
    }

    private void abandon(@NotNull ChunkTarget target, @NotNull Exception cause) {
        this.plugin.getLogger().log(Level.WARNING, "Could not load chunk "
                + target.chunkX + "," + target.chunkZ + " in " + target.world.getName(), cause);
        giveUp(target);
        this.inFlight.decrementAndGet();
    }

    /** A chunk that was never searched: its second-pass villagers cannot be confirmed absent. */
    private void giveUp(@NotNull ChunkTarget target) {
        if (target.secondPass) {
            this.searchIncomplete.addAll(target.villagerIds);
        }
    }

    /** Retries the 3x3 neighbourhood for villagers that were not in their recorded chunk. */
    private void buildSecondPassTargets() {
        Set<UUID> stillMissing = outstanding();
        if (stillMissing.isEmpty()) {
            return;
        }

        Map<ChunkKey, ChunkTarget> byChunk = new HashMap<>();
        for (UUID entityId : stillMissing) {
            MarkedVillager row = this.rowsById.get(entityId);
            if (row == null) {
                continue;
            }
            World world = Bukkit.getWorld(row.worldId());
            if (world == null) {
                continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int chunkX = row.chunkX() + dx;
                    int chunkZ = row.chunkZ() + dz;
                    ChunkTarget target = byChunk.computeIfAbsent(
                            new ChunkKey(world.getUID(), chunkX, chunkZ),
                            (key) -> new ChunkTarget(world, chunkX, chunkZ, true));
                    target.villagerIds.add(entityId);
                }
            }
        }
        this.queue.addAll(byChunk.values());
        this.totalChunks += this.queue.size();
    }

    /** Rows we meant to visit that are still neither restored nor skipped. */
    private @NotNull Set<UUID> outstanding() {
        Set<UUID> remaining = new HashSet<>(this.rowsById.keySet());
        remaining.removeAll(this.cleared);
        return remaining;
    }

    private void checkForStall() {
        int swept = this.chunksSwept.get();
        if (swept != this.lastSweptSeen) {
            this.lastSweptSeen = swept;
            this.lastChangeAt = System.currentTimeMillis();
            return;
        }
        if (System.currentTimeMillis() - this.lastChangeAt >= this.stallTimeoutMillis) {
            this.plugin.getLogger().warning("Uninstall stalled waiting on chunk loads; stopping here.");
            finish(false);
        }
    }

    private void reportProgress() {
        long now = System.currentTimeMillis();
        if (now - this.lastProgressAt < PROGRESS_EVERY_TICKS * 50L) {
            return;
        }
        this.lastProgressAt = now;
        if (this.totalChunks == 0) {
            return;
        }
        report(Component.text("Restored " + this.restored.get() + " villager(s), "
                + this.chunksSwept.get() + "/" + this.totalChunks + " chunks."));
    }

    /**
     * Deletes only the rows actually cleared. The state file is kept whenever anything was skipped or
     * unresolved, so re-running the command can finish rather than leaving villagers with no record.
     */
    private void finish(boolean completedNormally) {
        this.stage = Stage.FINISHED;
        // Only drop rows for villagers whose whole 3x3 neighbourhood was searched without finding
        // them: the entity is gone (dead, converted, region deleted). Anything less than that — a
        // stalled load, a chunk whose entity sections never arrived, an aborted run — keeps its row
        // so the advertised re-run can retry it. A stale dead row costs one extra chunk visit;
        // deleting a living villager's only record is the unrecoverable error.
        Set<UUID> outstandingIds = outstanding();
        Set<UUID> droppable = new HashSet<>(outstandingIds);
        droppable.retainAll(this.confirmedAbsent);
        droppable.removeAll(this.searchIncomplete);
        if (!completedNormally) {
            droppable.clear();
        }
        Set<UUID> kept = new HashSet<>(outstandingIds);
        kept.removeAll(droppable);
        this.cleared.addAll(droppable);
        this.unresolvedCount = droppable.size();

        try {
            this.store.deleteNow(this.cleared);
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.WARNING, "Could not delete restored villagers from the marker store.", e);
        }
        this.store.drainNow();

        // Dropped rows were fully searched for, so they leave nothing behind; only rows still on
        // disk make the run incomplete.
        boolean clean = completedNormally && this.skippedIds.isEmpty() && kept.isEmpty();

        report(Component.text("Restored ").append(Component.text(String.valueOf(this.restored.get())).color(NamedTextColor.GREEN))
                .append(Component.text(" villager(s) across " + this.chunksSwept.get() + " chunk(s).")));
        if (this.unresolvedCount > 0) {
            report(Component.text(this.unresolvedCount + " villager(s) no longer exist; their stale rows were dropped.")
                    .color(NamedTextColor.YELLOW));
        }

        if (clean) {
            this.store.deleteDatabaseFiles();
            report(Component.text("Uninstall complete. It is safe to delete the plugin jar now; "
                    + "restart the server to finish removing it.").color(NamedTextColor.GREEN));
        } else {
            if (this.skippedIds.size() > 0) {
                report(Component.text(this.skippedIds.size() + " villager(s) are in worlds that are not loaded.")
                        .color(NamedTextColor.YELLOW));
            }
            if (!kept.isEmpty()) {
                report(Component.text(kept.size() + " villager(s) were not reached before the sweep stopped; "
                        + "their rows were kept so a re-run can retry them.").color(NamedTextColor.YELLOW));
            }
            report(Component.text("Uninstall incomplete, so the state file was kept. Villager tracking is "
                    + "paused: run '/lobotomy uninstall confirm' again to finish, or '/lobotomy reload' "
                    + "(or restart) to resume normal operation.").color(NamedTextColor.YELLOW));
        }

        ScheduledTask task = this.pumpTask;
        this.pumpTask = null;
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable t) {
                // Harmless: the pump no-ops once the stage is FINISHED.
            }
        }

        if (!clean) {
            // Leave the plugin enabled so the command is still there to re-run.
            this.plugin.finishUninstall(false);
            return;
        }
        this.plugin.finishUninstall(true);
    }

    private void report(@NotNull Component message) {
        this.plugin.getLogger().info(PlainTextComponentSerializer.plainText().serialize(message));
        if (this.requesterId == null) {
            return;
        }
        Player player = Bukkit.getPlayer(this.requesterId);
        if (player != null) {
            player.sendMessage(message);
        }
    }

    /** Test hook: drives the state machine without the scheduler. */
    void pumpForTesting() {
        pump();
    }

    /** Test hook: a stall cannot otherwise be provoked without waiting out the real timeout. */
    void setStallTimeoutForTesting(long millis) {
        this.stallTimeoutMillis = millis;
    }

    int getRestoredCount() {
        return this.restored.get();
    }

    int getUnresolvedCount() {
        return this.unresolvedCount;
    }

    int getSkippedCount() {
        return this.skippedIds.size();
    }

    boolean isFinished() {
        return this.stage == Stage.FINISHED;
    }

    /** Chunk coordinates plus the world they belong to; coordinates alone are not unique. */
    private record ChunkKey(UUID worldId, int chunkX, int chunkZ) {
    }

    private static final class ChunkTarget {
        private final World world;
        private final int chunkX;
        private final int chunkZ;
        /** True for the 3x3 neighbourhood pass, whose misses may be confirmed as absent. */
        private final boolean secondPass;
        private final List<UUID> villagerIds = new ArrayList<>();
        private int attempts;

        private ChunkTarget(World world, int chunkX, int chunkZ) {
            this(world, chunkX, chunkZ, false);
        }

        private ChunkTarget(World world, int chunkX, int chunkZ, boolean secondPass) {
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.secondPass = secondPass;
        }
    }
}
