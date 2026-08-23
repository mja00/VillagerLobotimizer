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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final long STALL_TIMEOUT_MILLIS = 30_000L;

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
    private final Map<UUID, MarkedVillager> rowsById = new HashMap<>();
    private final Deque<ChunkTarget> queue = new ArrayDeque<>();

    private Stage stage = Stage.RESTORING_LOADED;
    private ScheduledTask pumpTask;
    private long phaseADeadline;
    private long lastProgressAt;
    private int totalChunks;
    private int lastSweptSeen;
    private long lastChangeAt;
    private boolean secondPassDone;
    private int unresolvedCount;

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
        buildTargets(readRows());
        this.stage = Stage.SWEEPING_ROWS;
    }

    private @NotNull List<MarkedVillager> readRows() {
        try {
            return this.store.loadAll();
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not read the marker store; "
                    + "villagers in unloaded chunks were not restored.", e);
            return List.of();
        }
    }

    /** Groups remaining rows by chunk, so a whole trading hall costs one chunk load. */
    private void buildTargets(@NotNull List<MarkedVillager> rows) {
        Map<Long, ChunkTarget> byChunk = new HashMap<>();
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
            byChunk.computeIfAbsent(Chunk.getChunkKey(row.chunkX(), row.chunkZ()),
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

    private void requestChunk(@NotNull ChunkTarget target) {
        try {
            this.chunkAccessor.withChunk(target.world, target.chunkX, target.chunkZ, (chunk) -> {
                try {
                    if (chunk == null) {
                        // generate=false and nothing on disk. Leave these outstanding: the second pass
                        // may still find them in a neighbouring chunk.
                        return;
                    }
                    if (Bukkit.isOwnedByCurrentRegion(target.world, target.chunkX, target.chunkZ)) {
                        sweepChunk(target, chunk);
                        return;
                    }
                    // Folia's thread confinement for this callback is undocumented, so hand off to the
                    // scheduler that is documented to own the chunk rather than assume.
                    Bukkit.getRegionScheduler().execute(this.plugin, target.world, target.chunkX, target.chunkZ, () -> {
                        if (!target.world.isChunkLoaded(target.chunkX, target.chunkZ)) {
                            requeue(target);
                            return;
                        }
                        sweepChunk(target, target.world.getChunkAt(target.chunkX, target.chunkZ));
                    });
                } finally {
                    this.inFlight.decrementAndGet();
                }
            });
        } catch (Exception e) {
            this.inFlight.decrementAndGet();
            this.plugin.getLogger().log(Level.WARNING, "Could not load chunk "
                    + target.chunkX + "," + target.chunkZ + " in " + target.world.getName(), e);
        }
    }

    private void sweepChunk(@NotNull ChunkTarget target, @NotNull Chunk chunk) {
        if (!chunk.isEntitiesLoaded()) {
            // getEntities() returns an empty array until the entity sections load, which would look
            // like "no villagers here" and silently under-clean.
            requeue(target);
            return;
        }

        Set<UUID> wanted = new HashSet<>(target.villagerIds);
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Villager villager && wanted.remove(villager.getUniqueId())) {
                restore(villager);
            }
        }
        this.chunksSwept.incrementAndGet();
        this.lastChangeAt = System.currentTimeMillis();
    }

    private void requeue(@NotNull ChunkTarget target) {
        if (++target.attempts >= MAX_ATTEMPTS) {
            // Out of retries; whatever is still outstanding is reported at the end.
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(this.plugin, () -> this.queue.add(target));
    }

    /** Retries the 3x3 neighbourhood for villagers that were not in their recorded chunk. */
    private void buildSecondPassTargets() {
        Set<UUID> stillMissing = outstanding();
        if (stillMissing.isEmpty()) {
            return;
        }

        Map<Long, ChunkTarget> byChunk = new HashMap<>();
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
                    byChunk.computeIfAbsent(Chunk.getChunkKey(chunkX, chunkZ),
                            (key) -> new ChunkTarget(world, chunkX, chunkZ)).villagerIds.add(entityId);
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
        if (System.currentTimeMillis() - this.lastChangeAt > STALL_TIMEOUT_MILLIS) {
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
        // Whatever is left could not be found: dead, converted, or its region file is gone. Drop the
        // rows so a re-run does not chase them forever, but report how many.
        Set<UUID> unresolvedIds = outstanding();
        this.unresolvedCount = unresolvedIds.size();
        this.cleared.addAll(unresolvedIds);

        try {
            this.store.deleteNow(this.cleared);
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.WARNING, "Could not delete restored villagers from the marker store.", e);
        }
        this.store.drainNow();

        int outstanding = this.skippedIds.size() + this.unresolvedCount;
        boolean clean = completedNormally && outstanding == 0;

        report(Component.text("Restored ").append(Component.text(this.restored.get()).color(NamedTextColor.GREEN))
                .append(Component.text(" villager(s) across " + this.chunksSwept.get() + " chunk(s).")));

        if (clean) {
            this.store.deleteDatabaseFiles();
            report(Component.text("Uninstall complete. It is safe to delete the plugin jar now; "
                    + "restart the server to finish removing it.").color(NamedTextColor.GREEN));
        } else {
            if (this.skippedIds.size() > 0) {
                report(Component.text(this.skippedIds.size() + " villager(s) are in worlds that are not loaded.")
                        .color(NamedTextColor.YELLOW));
            }
            if (this.unresolvedCount > 0) {
                report(Component.text(this.unresolvedCount + " villager(s) could not be found and were dropped.")
                        .color(NamedTextColor.YELLOW));
            }
            report(Component.text("Uninstall incomplete, so the state file was kept. No villagers are "
                    + "being tracked until the server restarts: run '/lobotomy uninstall confirm' again to "
                    + "finish, or restart to resume normal operation.").color(NamedTextColor.YELLOW));
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

    private static final class ChunkTarget {
        private final World world;
        private final int chunkX;
        private final int chunkZ;
        private final List<UUID> villagerIds = new ArrayList<>();
        private int attempts;

        private ChunkTarget(World world, int chunkX, int chunkZ) {
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }
}
