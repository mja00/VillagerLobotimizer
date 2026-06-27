package dev.mja00.villagerLobotomizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import dev.mja00.villagerLobotomizer.policy.BlockClassifier;
import dev.mja00.villagerLobotomizer.policy.BlockGrid;
import dev.mja00.villagerLobotomizer.policy.BlockSnapshot;
import dev.mja00.villagerLobotomizer.policy.VillagerActivityPolicy;
import dev.mja00.villagerLobotomizer.policy.VillagerState;
import dev.mja00.villagerLobotomizer.utils.SentryTaskWrapper;
import dev.mja00.villagerLobotomizer.utils.StringUtils;
import dev.mja00.villagerLobotomizer.utils.VillagerUtils;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Core villager tracking state machine. Owns the active/inactive set split, the per-villager
 * scheduler map, the trade-refresh path, the chunk-processing pipeline, and the watchdog
 * that reconciles any state drift.
 *
 * <h2>Threading contract</h2>
 *
 * On Folia, the global region thread owns no entity. Direct calls into the Bukkit API
 * ({@code setAware}, {@code setSilent}, persistent data container writes) from a non-entity
 * thread will throw {@link IllegalStateException}. To keep the public API safe to call from
 * any thread, methods that read or mutate a {@link Villager} dispatch through
 * {@link org.bukkit.entity.Entity#getScheduler()} so the body runs on the villager's owning
 * thread. The set mutations ({@link #setActive}, {@link #setInactive},
 * {@link #addVillager}, {@link #removeVillager}, {@link #reEvaluateVillager}) acquire the
 * {@code stateLock} to keep the active/inactive sets atomic and disjoint.
 *
 * <h2>Shutdown</h2>
 *
 * {@link #flush(boolean)} cancels all scheduled tasks and snapshots the tracked villagers.
 * On reload ({@code reloading=true}) each villager is dispatched to its entity scheduler to
 * wake up; the new storage instance starts empty. On shutdown ({@code reloading=false}) each
 * villager is dispatched to its entity scheduler to <strong>re-evaluate</strong>: villagers
 * still trapped stay lobotomized (and the persistent marker is honored), villagers whose
 * environment opened up since the last check are woken. If the entity scheduler does not run
 * before the JVM exits (a possibility on Folia), the persistent lobotomized marker, when
 * enabled, re-lobotomizes the villager on next chunk load.
 */
public class LobotomizeStorage {
    private final VillagerLobotomizer plugin;
    private final NamespacedKey key;
    private final NamespacedKey lobotomizedKey;
    private final NamespacedKey lastRestockCheckDayTimeKey;
    private final Set<Villager> activeVillagers = Collections.newSetFromMap(new ConcurrentHashMap<>(128));
    private final Set<Villager> inactiveVillagers = Collections.newSetFromMap(new ConcurrentHashMap<>(128));
    private final Map<Chunk, Long> changedChunks = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> villagerTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> villagerTaskIntervals = new ConcurrentHashMap<>();
    private final Set<String> exemptNames;
    private final long checkInterval;
    private final long inactiveCheckInterval;
    private final long restockInterval;
    private final long restockRandomRange;
    private final int maxRestocksPerDay;
    private final boolean onlyProfessions;
    private final boolean onlyWithExperience;
    private final boolean alwaysLobotomizeVillagersInVehicles;
    private final boolean checkRoof;
    private final boolean silentLobotomizedVillagers;
    private final boolean persistLobotomizedState;
    private final boolean ignoreStuckInDoors;
    private final VillagerActivityPolicy activityPolicy;
    private Sound restockSound;
    private Sound levelUpSound;
    private final Logger logger;
    private final Random random = new Random();
    private volatile boolean shuttingDown = false;
    private final ScheduledTask chunkProcessingTask;
    private ScheduledTask watchdogTask;
    private static final long WATCHDOG_INTERVAL_TICKS = 1200L;
    /**
     * 0.01 above the villager's floor block, to land on the feet block rather than the floor
     * block itself when the villager is standing on a partial-block workstation (brewing stand,
     * lectern, etc.). The Y offset is applied via {@code Location.add(0, FEET_BLOCK_Y_OFFSET, 0)}
     * before computing the block coordinates passed into the activity policy.
     */
    private static final double FEET_BLOCK_Y_OFFSET = 0.51;
    // Guards compound mutations of activeVillagers/inactiveVillagers so a villager
    // is never observable in both sets simultaneously.
    private final Object stateLock = new Object();

    /**
     * Initializes the villager lobotomization storage system.
     *
     * @param plugin the VillagerLobotomizer plugin instance providing configuration
     */
    public LobotomizeStorage(VillagerLobotomizer plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        // check-interval/inactive-check-interval are scheduler periods (ticks). Lower bound is 1, or
        // runAtFixedRate throws IllegalArgumentException for every tracked villager. Upper bound is
        // 600 (30s at 20 TPS) — the longest reasonable tick-based interval before considering a
        // cooldown-based design instead. restock-interval and restock-random-range are wall-clock
        // milliseconds and must not be negative.
        this.checkInterval = validateClampedInterval("check-interval", plugin.getConfig().getLong("check-interval"), 1L, 600L);
        this.inactiveCheckInterval = validateClampedInterval("inactive-check-interval", plugin.getConfig().getLong("inactive-check-interval", this.checkInterval), 1L, 600L);
        this.restockInterval = validateInterval("restock-interval", plugin.getConfig().getLong("restock-interval"), 0L, 540000L);
        this.restockRandomRange = validateInterval("restock-random-range", plugin.getConfig().getLong("restock-random-range"), 0L, 0L);
        // max-restocks-per-day is a small count (vanilla default 2). Negative values are nonsense
        // and would prevent restocking entirely by misdirection; clamp to the default.
        this.maxRestocksPerDay = (int) validateClampedInterval("max-restocks-per-day", plugin.getConfig().getInt("max-restocks-per-day", 2), 0L, 2L);
        this.onlyProfessions = plugin.getConfig().getBoolean("only-lobotomize-villagers-with-professions");
        this.onlyWithExperience = plugin.getConfig().getBoolean("only-lobotomize-villagers-with-experience");
        this.alwaysLobotomizeVillagersInVehicles = plugin.getConfig().getBoolean("always-lobotomize-villagers-in-vehicles");
        this.checkRoof = plugin.getConfig().getBoolean("check-roof");
        this.silentLobotomizedVillagers = plugin.getConfig().getBoolean("silent-lobotomized-villagers");
        this.persistLobotomizedState = plugin.getConfig().getBoolean("persist-lobotomized-state", true);
        String soundName = plugin.getConfig().getString("restock-sound", "");
        String levelUpSoundName = plugin.getConfig().getString("level-up-sound", "");

        soundName = convertLegacySoundName(soundName, "restock-sound");
        levelUpSoundName = convertLegacySoundName(levelUpSoundName, "level-up-sound");

        if (soundName == null) {
            soundName = "";
        }
        if (levelUpSoundName == null) {
            levelUpSoundName = "";
        }

        // We add the minecraft: namespace ourselves when resolving, so strip it here
        if (!soundName.isEmpty() && soundName.startsWith("minecraft:")) {
            soundName = soundName.replace("minecraft:", "");
        }
        if (!levelUpSoundName.isEmpty() && levelUpSoundName.startsWith("minecraft:")) {
            levelUpSoundName = levelUpSoundName.replace("minecraft:", "");
        }

        List<String> configNames = plugin.getConfig().getStringList("always-active-names");
        exemptNames = configNames.stream()
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        // Whether door blocks should count as a bypass (passable) block. Stored per-instance so it
        // is re-read on /lobotomy reload, then passed into the activity policy below.
        this.ignoreStuckInDoors = plugin.getConfig().getBoolean("ignore-villagers-stuck-in-doors");

        this.activityPolicy = new VillagerActivityPolicy(
                this.alwaysLobotomizeVillagersInVehicles,
                this.onlyProfessions,
                this.onlyWithExperience,
                this.checkRoof,
                this.ignoreStuckInDoors,
                plugin.getConfig().getBoolean("ignore-non-solid-blocks"),
                this.exemptNames,
                BlockClassifier.fromServerRegistry());

        Registry<@NotNull Sound> soundRegistry = RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT);

        try {
            if (!soundName.isEmpty()) {
                NamespacedKey key = new NamespacedKey(NamespacedKey.MINECRAFT, soundName);
                this.restockSound = soundRegistry.getOrThrow(key);
            } else {
                this.restockSound = null;
            }
        } catch (IllegalArgumentException | NoSuchElementException var4) {
            plugin.getLogger().warning("Unknown sound name \"" + soundName + "\"");
        } catch (Exception badError) {
            plugin.getLogger().warning("Unknown error while trying to get sound name \"" + soundName + "\". Villagers won't have any sounds.");
            plugin.getLogger().warning(badError.toString());
        }


        try {
            if (!levelUpSoundName.isEmpty()) {
                NamespacedKey key = new NamespacedKey(NamespacedKey.MINECRAFT, levelUpSoundName);
                this.levelUpSound = soundRegistry.getOrThrow(key);
            } else {
                this.levelUpSound = null;
            }
        } catch (IllegalArgumentException | NoSuchElementException var5) {
            plugin.getLogger().warning("Unknown sound name \"" + levelUpSoundName + "\"");
        } catch (Exception badError) {
            plugin.getLogger().warning("Unknown error while trying to get sound name \"" + levelUpSoundName + "\". Villagers won't have any sounds.");
            plugin.getLogger().warning(badError.toString());
        }

        this.key = new NamespacedKey(plugin, "lastRestock");
        this.lobotomizedKey = new NamespacedKey(plugin, "isLobotomized");
        this.lastRestockCheckDayTimeKey = new NamespacedKey(plugin, "lastRestockCheckDayTime");
        // Use Paper's GlobalRegionScheduler for chunk processing. It never touches entities directly;
        // per-chunk entity access is dispatched to the owning region via getRegionScheduler() (see
        // scheduleChunkVillagerProcessing), keeping this Folia thread-ownership safe.
        this.chunkProcessingTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin,
                SentryTaskWrapper.wrap((task) -> this.processChunks()),
                5L,
                5L
        );
        this.watchdogTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin,
                SentryTaskWrapper.wrap((task) -> this.runWatchdog()),
                WATCHDOG_INTERVAL_TICKS,
                WATCHDOG_INTERVAL_TICKS
        );
    }

    public @NotNull Set<Villager> getLobotomized() {
        return this.inactiveVillagers;
    }

    public @NotNull Set<Villager> getActive() {
        return this.activeVillagers;
    }

    // Order is remove-then-add so the brief intermediate state is "in neither set"
    // rather than "in both" — readers seeing a momentary gap simply skip a tick.
    private void setActive(@NotNull Villager v) {
        synchronized (this.stateLock) {
            this.inactiveVillagers.remove(v);
            this.activeVillagers.add(v);
        }
    }

    private void setInactive(@NotNull Villager v) {
        synchronized (this.stateLock) {
            this.activeVillagers.remove(v);
            this.inactiveVillagers.add(v);
        }
    }

    /**
     * Removes a villager from all tracking sets.
     *
     * @param v the villager to remove
     * @return {@code true} if the villager was being tracked, {@code false} otherwise
     */
    private boolean untrack(@NotNull Villager v) {
        synchronized (this.stateLock) {
            boolean wasActive = this.activeVillagers.remove(v);
            boolean wasInactive = this.inactiveVillagers.remove(v);
            return wasActive || wasInactive;
        }
    }

    /**
     * Registers a villager for active/inactive state tracking with periodic monitoring.
     *
     * If persistent lobotomized state is configured, checks whether the villager
     * carries a lobotomized marker; if so, initializes it as inactive, otherwise
     * as active. Schedules a periodic task to monitor and potentially transition
     * the villager between states.
     */
    public final void addVillager(@NotNull Villager villager) {
        if (this.shuttingDown || !this.plugin.isEnabled()) {
            return;
        }

        // Read the persistent lobotomized marker on the calling thread. PDC reads are safe
        // across threads; the marker is a hint that the villager should be tracked as
        // already-lobotomized. The entity-mutating portion (setAware/setSilent/PDC writes)
        // and the set-membership update are dispatched to the villager's owning thread via
        // villager.getScheduler() so the public API is Folia-safe by construction regardless
        // of caller.
        boolean wasLobotomized = false;
        if (this.persistLobotomizedState) {
            PersistentDataContainer pdc = villager.getPersistentDataContainer();
            wasLobotomized = pdc.has(this.lobotomizedKey, PersistentDataType.BYTE);
        }

        if (wasLobotomized) {
            villager.getScheduler().run(this.plugin,
                    SentryTaskWrapper.wrap(t -> reAddAsLobotomized(villager)), null);
        } else {
            villager.getScheduler().run(this.plugin,
                    SentryTaskWrapper.wrap(t -> reAddAsActive(villager)), null);
        }
    }

    /**
     * Re-tracks a villager that was already lobotomized (PDC marker present). Runs on the
     * villager's owning thread via {@code villager.getScheduler()}. Called from
     * {@link #addVillager}.
     */
    private void reAddAsLobotomized(@NotNull Villager villager) {
        // Re-lobotomize the villager to prevent lag spike on chunk load.
        villager.setAware(false);
        if (this.silentLobotomizedVillagers) {
            villager.setSilent(true);
        }
        setInactive(villager);

        if (this.plugin.isDebugging()) {
            this.logger.info("[Debug] Re-lobotomized villager " + villager + " (" + villager.getUniqueId() + ") on chunk load");
        }
        this.scheduleVillagerTask(villager, this.inactiveCheckInterval);
    }

    /**
     * Re-tracks a new villager (no PDC marker). Runs on the villager's owning thread via
     * {@code villager.getScheduler()}. Called from {@link #addVillager}.
     */
    private void reAddAsActive(@NotNull Villager villager) {
        setActive(villager);

        if (this.plugin.isDebugging()) {
            this.logger.info("[Debug] Tracked villager " + villager + " (" + villager.getUniqueId() + ") as active");
        }
        this.scheduleVillagerTask(villager, this.checkInterval);
    }

    /**
     * Removes a villager from tracking and reactivates it if it was previously lobotomized.
     *
     * Cancels the villager's scheduled processing task and removes it from the tracking sets.
     * If the villager was inactive, restores awareness and optionally sound.
     */
    public final void removeVillager(@NotNull Villager villager) {
        boolean wasInactive;
        boolean wasActive;
        // Cancel the per-villager task and drop set membership atomically (paired with
        // scheduleVillagerTask) so a concurrent (re)schedule can't leave a live orphan task.
        synchronized (this.stateLock) {
            ScheduledTask task = this.villagerTasks.remove(villager.getUniqueId());
            this.villagerTaskIntervals.remove(villager.getUniqueId());
            this.safeCancel(task);
            wasActive = this.activeVillagers.remove(villager);
            wasInactive = this.inactiveVillagers.remove(villager);
        }
        boolean removed = wasActive || wasInactive;

        if (wasInactive) {
            // Use Paper's EntityScheduler for thread safety
            villager.getScheduler().run(this.plugin, SentryTaskWrapper.wrap((scheduledTask) -> {
                villager.setAware(true);
                if (this.silentLobotomizedVillagers) {
                    villager.setSilent(false);
                }
            }), null);
        }

        if (this.plugin.isDebugging()) {
            if (removed) {
                this.logger.info("[Debug] Untracked villager " + villager + " (" + villager.getUniqueId() + "), marked as active = " + wasActive + ", cancelled scheduler");
            } else {
                this.logger.info("[Debug] Attempted to untrack villager " + villager + " (" + villager.getUniqueId() + "), but it was not tracked");
            }
        }
    }

    /**
     * Clears the persistent lobotomized marker from a villager.
     *
     * @param villager the villager to clear the marker from
     */
    public void clearLobotomizedMarker(@NotNull Villager villager) {
        // Clear unconditionally, even when persist-lobotomized-state is off: a marker written under a
        // previously-enabled config must not survive to re-lobotomize a woken villager if the feature
        // is toggled back on. has() keeps this cheap when there is nothing to remove.
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        if (pdc.has(this.lobotomizedKey, PersistentDataType.BYTE)) {
            pdc.remove(this.lobotomizedKey);
            if (this.plugin.isDebugging()) {
                this.logger.info("[Debug] Removed persistent lobotomized marker from " + villager.getUniqueId());
            }
        }
    }

    /**
     * Flushes all tracked villagers from storage, stops their processing tasks, and attempts to un-lobotomize them during shutdown.
     */
    public final void flush() {
        flush(false);
    }

    /**
     * Cancels a scheduled task, swallowing any scheduler error. Cancellation failure is harmless:
     * shuttingDown and the per-villager guards already neuter task bodies, so a surviving task no-ops.
     */
    private void safeCancel(ScheduledTask task) {
        if (task == null) {
            return;
        }
        try {
            task.cancel();
        } catch (Throwable t) {
            if (this.plugin.isDebugging()) {
                this.logger.fine("Failed to cancel scheduled task: " + t.getMessage());
            }
        }
    }

    /**
     * Stops tracking all villagers, cancels their scheduled tasks, and restores their activity state.
     *
     * @param reloading {@code true} when the plugin remains enabled (such as during a config reload).
     *                  Wake operations are dispatched via each villager's {@code EntityScheduler},
     *                  ensuring safe cross-region (Folia) execution. {@code false} during plugin shutdown,
     *                  where a per-villager re-evaluation is dispatched via the entity scheduler: each
     *                  villager's current environment is re-checked and the villager is woken or re-
     *                  lobotomized accordingly.
     */
    public final void flush(boolean reloading) {
        // Prevent new tasks from being scheduled past this point
        this.shuttingDown = true;

        this.safeCancel(this.chunkProcessingTask);
        this.safeCancel(this.watchdogTask);

        // Take a snapshot of the union under stateLock — covers any villager stuck in both sets.
        // Cancel and clear per-villager tasks under the same lock that scheduleVillagerTask holds,
        // so a concurrent schedule can't install an orphan task after we clear (it re-checks
        // shuttingDown there).
        // We deliberately do NOT clear activeVillagers/inactiveVillagers on shutdown: the
        // re-evaluation tasks below read those sets to determine the villager's current state.
        // Clearing would force the re-eval to either assume a default (and mis-classify villagers
        // that are still trapped) or accept an extra parameter to remember the prior state.
        List<Villager> toFlush;
        synchronized (this.stateLock) {
            for (ScheduledTask task : this.villagerTasks.values()) {
                this.safeCancel(task);
            }
            this.villagerTasks.clear();
            this.villagerTaskIntervals.clear();

            toFlush = new ArrayList<>(this.inactiveVillagers.size() + this.activeVillagers.size());
            toFlush.addAll(this.inactiveVillagers);
            for (Villager v : this.activeVillagers) {
                if (!this.inactiveVillagers.contains(v)) toFlush.add(v);
            }
            if (reloading) {
                // On reload, the new storage instance will start with empty sets; clear the old
                // sets defensively so any leftover reference to the old storage sees a clean state.
                this.inactiveVillagers.clear();
                this.activeVillagers.clear();
            }
        }

        if (!reloading && !toFlush.isEmpty()) {
            this.logger.info("Shutdown: re-evaluating " + toFlush.size()
                    + " tracked villager(s) on their owning threads. Persistent lobotomized markers will be honored.");
        }

        for (Villager villager : toFlush) {
            if (this.plugin.isDebugging()) {
                this.logger.info((reloading ? "Waking" : "Re-evaluating") + " villager " + villager.getUniqueId());
            }

            if (reloading) {
                // Plugin stays enabled: the entity scheduler will run, so this works cross-region
                // without throwing a thread-ownership exception per villager.
                try {
                    villager.getScheduler().run(this.plugin, SentryTaskWrapper.wrap((task) -> wakeVillager(villager)), null);
                } catch (Exception e) {
                    this.logger.log(java.util.logging.Level.WARNING, "Failed to schedule un-lobotomize for villager " + villager.getUniqueId() + " during reload: " + e.getMessage(), e);
                }
                continue;
            }

            // Shutdown: dispatch a re-evaluation on the villager's owning thread. The re-eval
            // checks the current environment and either wakes or re-lobotomizes accordingly.
            try {
                villager.getScheduler().run(this.plugin,
                        SentryTaskWrapper.wrap((task) -> reEvaluateVillager(villager)), null);
            } catch (Exception e) {
                // If we can't even schedule the re-eval (e.g., the entity scheduler is unavailable
                // mid-shutdown), fall back to a direct wake so the villager is not left lobotomized
                // indefinitely. The persistent marker will still re-lobotomize it on next chunk load
                // if the environment actually warrants it.
                this.logger.log(java.util.logging.Level.WARNING, "Failed to schedule re-eval for villager " + villager.getUniqueId() + " during shutdown, falling back to wake: " + e.getMessage(), e);
                try {
                    wakeVillager(villager);
                } catch (Exception inner) {
                    this.logger.log(java.util.logging.Level.WARNING, "Failed to wake villager " + villager.getUniqueId() + " during shutdown fallback: " + inner.getMessage(), inner);
                }
            }
        }
    }

    /**
     * Restores a villager to its un-lobotomized vanilla state. Must be called on the thread/region
     * that owns the entity (call directly only when already on it, otherwise via its EntityScheduler).
     */
    private void wakeVillager(@NotNull Villager villager) {
        villager.setAware(true);
        if (this.silentLobotomizedVillagers) {
            villager.setSilent(false);
        }
        clearLobotomizedMarker(villager);
    }

    /**
     * Re-evaluates a tracked villager at shutdown and applies the policy result: wake if the
     * environment has opened up, re-lobotomize if the villager is still trapped. Runs on the
     * villager's owning thread via {@code villager.getScheduler()} (dispatched from
     * {@link #flush(boolean)}). Reads the villager's current set membership under the state
     * lock and writes the new membership, so a transition between active and inactive is
     * atomic with respect to other set readers.
     */
    private void reEvaluateVillager(@NotNull Villager villager) {
        if (!villager.isValid() || villager.isDead()) {
            return;
        }

        Location villagerLocation = villager.getLocation().add(0.0F, (float) FEET_BLOCK_Y_OFFSET, 0.0F);
        int blockX = villagerLocation.getBlockX();
        int blockY = villagerLocation.getBlockY();
        int blockZ = villagerLocation.getBlockZ();

        // Chunk already unloaded: nothing to evaluate against. The persistent marker (when enabled)
        // will re-lobotomize on next chunk load; otherwise the villager just stays as it is.
        if (!villager.getWorld().isChunkLoaded(blockX >> 4, blockZ >> 4)) {
            return;
        }

        boolean shouldBeActive = this.shouldBeActive(villager, blockX, blockY, blockZ);
        boolean isActive = this.activeVillagers.contains(villager);
        boolean isInactive = this.inactiveVillagers.contains(villager);

        // Defensive: if the villager is no longer tracked (shouldn't happen for a flushed
        // snapshot), keep its current state.
        if (!isActive && !isInactive) {
            return;
        }

        if (shouldBeActive) {
            if (isInactive) {
                // Wake up: the environment opened up between the last check and shutdown.
                villager.setAware(true);
                if (this.silentLobotomizedVillagers) {
                    villager.setSilent(false);
                }
                clearLobotomizedMarker(villager);
                setActive(villager);
            } else {
                // Already active; just clear any stale marker.
                clearLobotomizedMarker(villager);
            }
        } else if (isActive) {
            // Still trapped: re-lobotomize.
            villager.setAware(false);
            if (this.silentLobotomizedVillagers) {
                villager.setSilent(true);
            }
            if (this.persistLobotomizedState) {
                villager.getPersistentDataContainer().set(this.lobotomizedKey, PersistentDataType.BYTE, (byte) 1);
            }
            setInactive(villager);
        }
        // If already inactive and still trapped, do nothing: leave the persistent marker as-is.
    }


    /**
     * Safely processes and potentially transitions a villager between active and inactive states.
     *
     * Validates the villager, checks its membership in the tracked sets, and evaluates whether
     * it should toggle state. If a state transition occurs, reschedules the villager's periodic
     * task with the appropriate check interval.
     */
    private void processVillagerSafely(@NotNull Villager villager) {
        // After flush()/reload swap, an old storage instance can still have in-flight callbacks on
        // other region threads. Bail out promptly so we don't mutate the old, orphaned sets or
        // reschedule tasks via the dead instance while the new instance is taking over.
        if (this.shuttingDown) {
            return;
        }
        try {
            if (!villager.isValid() || villager.isDead()) {
                ScheduledTask task = this.villagerTasks.remove(villager.getUniqueId());
                this.villagerTaskIntervals.remove(villager.getUniqueId());
                this.safeCancel(task);
                untrack(villager);
                return;
            }

            boolean isActive = this.activeVillagers.contains(villager);
            boolean isInactive = this.inactiveVillagers.contains(villager);

            // Skip if villager is not tracked
            if (!isActive && !isInactive) {
                // Cancel the task since villager is no longer tracked
                ScheduledTask task = this.villagerTasks.remove(villager.getUniqueId());
                this.villagerTaskIntervals.remove(villager.getUniqueId());
                if (task != null && !task.isCancelled()) {
                    task.cancel();
                }
                return;
            }

            if (isActive && isInactive) {
                this.logger.info("[Watchdog] processVillagerSafely saw villager "
                        + villager.getUniqueId() + " in both sets; reconciling.");
                reconcile(villager);
                return;
            }

            boolean transitioned = this.processVillager(villager, isActive);
            if (transitioned) {
                long nextInterval = isActive ? this.inactiveCheckInterval : this.checkInterval;
                this.rescheduleVillagerTask(villager, nextInterval);
            }
        } catch (IllegalStateException e) {
            if (this.plugin.isDebugging()) {
                this.logger.warning("Skipping villager processing for " + villager.getUniqueId() + " due to state change: " + e.getMessage());
            }
        }
    }

    /**
     * Evaluates whether a villager should be active based on configured policy and performs state transitions as necessary.
     *
     * @param active whether the villager is currently in the active state
     * @return true if the villager transitioned states or is invalid or dead; false if it remained in its current state or the chunk was unloaded
     */
    private boolean processVillager(@NotNull Villager villager, boolean active) {
        if (!villager.isValid() || villager.isDead()) {
            return true;
        }

        Location villagerLocation = villager.getLocation().add(0.0F, (float) FEET_BLOCK_Y_OFFSET, 0.0F);
        int blockX = villagerLocation.getBlockX();
        int blockY = villagerLocation.getBlockY();
        int blockZ = villagerLocation.getBlockZ();

        // Chunk unloaded: can't evaluate blocks, keep current state
        if (!villager.getWorld().isChunkLoaded(blockX >> 4, blockZ >> 4)) {
            return false; // Keep current if chunk is unloaded
        }

        // Reuse the coordinates already computed above instead of cloning the location again.
        boolean shouldBeActive = this.shouldBeActive(villager, blockX, blockY, blockZ);

        if (shouldBeActive) {
            // Clear any stale marker whenever the villager should be active, not just on transition,
            // so a marker left by a prior persist-enabled run can't re-lobotomize it after a config flip.
            clearLobotomizedMarker(villager);
            if (!active) {
                // Already running on entity thread, safe to modify villager
                villager.setAware(true);
                if (this.silentLobotomizedVillagers) {
                    villager.setSilent(false);
                }
                setActive(villager);
                if (this.plugin.isDebugging()) {
                    this.logger.info("[Debug] Villager " + villager + " (" + villager.getUniqueId() + ") is now active");
                }
                return true; // Transitioned: caller may want to reschedule at new interval
            }
            if (this.plugin.isDebugging() && !this.plugin.isFolia() && this.plugin.getActiveVillagersTeam() != null) {
                this.plugin.getActiveVillagersTeam().addEntity(villager);
                villager.setGlowing(true);
            }
        } else {
            // Inactive villagers still need their trades refreshed
            this.refreshTrades(villager);

            if (active) {
                // Already running on entity thread, safe to modify villager
                villager.setAware(false);
                if (this.silentLobotomizedVillagers) {
                    villager.setSilent(true);
                }
                if (this.persistLobotomizedState) {
                    villager.getPersistentDataContainer().set(this.lobotomizedKey, PersistentDataType.BYTE, (byte) 1);
                    if (this.plugin.isDebugging()) {
                        this.logger.info("[Debug] Set persistent lobotomized marker for " + villager.getUniqueId());
                    }
                }
                setInactive(villager);
                if (this.plugin.isDebugging()) {
                    this.logger.info("[Debug] Villager " + villager + " (" + villager.getUniqueId() + ") is now inactive");
                }
                return true; // Transitioned: caller may want to reschedule at new interval
            }
            // Re-assert lobotomized state if the villager drifted back to aware while still tracked
            // inactive (such as a stale wake callback from an old storage instance after a reload).
            if (villager.isAware()) {
                villager.setAware(false);
                if (this.silentLobotomizedVillagers) {
                    villager.setSilent(true);
                }
                if (this.persistLobotomizedState) {
                    villager.getPersistentDataContainer().set(this.lobotomizedKey, PersistentDataType.BYTE, (byte) 1);
                }
            }
            if (this.plugin.isDebugging() && !this.plugin.isFolia() && this.plugin.getInactiveVillagersTeam() != null) {
                this.plugin.getInactiveVillagersTeam().addEntity(villager);
                villager.setGlowing(true);
            }
        }
        return false; // Don't mutate anything
    }

    private void runWatchdog() {
        if (this.shuttingDown) return;
        // Snapshot under stateLock so the weakly-consistent iterator can't pair a stale
        // active-set yield with a fresh inactive-set contains and report a false dupe.
        List<Villager> dupes;
        synchronized (this.stateLock) {
            dupes = new ArrayList<>();
            for (Villager v : this.activeVillagers) {
                if (this.inactiveVillagers.contains(v)) {
                    dupes.add(v);
                }
            }
        }
        if (dupes.isEmpty()) return;
        this.logger.warning("[Watchdog] Found " + dupes.size()
                + " villager(s) in both active and inactive sets; reconciling.");
        for (Villager v : dupes) {
            reconcile(v);
        }
    }

    /**
     * Corrects a villager found to be in an inconsistent active/inactive state.
     *
     * Determines the correct state based on the villager's environment and updates its membership,
     * awareness, and any persistent state markers accordingly.
     *
     * @param v the villager to reconcile
     */
    private void reconcile(@NotNull Villager v) {
        try {
            v.getScheduler().run(this.plugin, SentryTaskWrapper.wrap((t) -> {
                if (!v.isValid() || v.isDead()) {
                    untrack(v);
                    return;
                }
                boolean shouldBeActive = this.shouldBeActive(v);
                if (shouldBeActive) {
                    setActive(v);
                    v.setAware(true);
                    if (this.silentLobotomizedVillagers) {
                        v.setSilent(false);
                    }
                    if (this.persistLobotomizedState) {
                        v.getPersistentDataContainer().remove(this.lobotomizedKey);
                    }
                } else {
                    setInactive(v);
                    v.setAware(false);
                    if (this.silentLobotomizedVillagers) {
                        v.setSilent(true);
                    }
                    if (this.persistLobotomizedState) {
                        v.getPersistentDataContainer().set(
                                this.lobotomizedKey, PersistentDataType.BYTE, (byte) 1);
                    }
                }
                this.logger.info("[Watchdog] Reconciled villager " + v.getUniqueId()
                        + " to " + (shouldBeActive ? "active" : "inactive"));
            }), null);
        } catch (IllegalPluginAccessException e) {
            // plugin disabling
        }
    }

    /**
     * Determines if a villager should have its trades restocked.
     *
     * @return {@code true} if the villager should restock, {@code false} otherwise
     */
    private boolean shouldRestock(@NotNull Villager villager) {
        return VillagerUtils.shouldRestock(villager, this.lastRestockCheckDayTimeKey, this.maxRestocksPerDay);
    }

    /**
     * Restocks the villager's trades and increases their level based on time and experience.
     *
     * Restock resets all merchant recipe uses when the configured interval has passed and restock
     * conditions are met. Leveling increases the villager's level to match accumulated experience.
     * Both operations require daytime (in the overworld) and a nearby job site.
     */
    private void refreshTrades(@NotNull Villager villager) {
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        long lastRestock = pdc.getOrDefault(this.key, PersistentDataType.LONG, 0L);

        long now = System.currentTimeMillis();
        long timeSinceLastRestock = now - lastRestock;
        // Bound the random offset by restockInterval so effectiveInterval can't go negative when
        // restock-random-range is misconfigured larger than restock-interval, which would make
        // intervalPassed always true until the daily restock cap is hit.
        long randomBound = Math.min(this.restockRandomRange, this.restockInterval);
        long randomOffset = randomBound > 0 ? this.random.nextLong(randomBound) : 0L;
        long effectiveInterval = this.restockInterval - randomOffset;
        boolean intervalPassed = timeSinceLastRestock > effectiveInterval;

        int currentLevel = villager.getVillagerLevel();
        int expectedLevel = VillagerUtils.getVillagerLevel(villager);
        boolean needsLevelUp = currentLevel < expectedLevel;

        // Cheap gate first: if neither a restock nor a level-up could happen this tick, skip the
        // daytime check and the 27-block job-site scan entirely. restock-interval is much larger
        // than check-interval, so the interval gate is false on most checks for most villagers.
        if (!intervalPassed && !needsLevelUp) {
            return;
        }

        // Both restocking and leveling require it to be day (in the overworld) with a job site nearby.
        if (villager.getWorld().getEnvironment() == World.Environment.NORMAL && !villager.getWorld().isDayTime()) {
            if (this.plugin.isDebugging()) {
                this.logger.info("[Debug] Skipping trade refresh for " + villager.getUniqueId() + " - it's night time in overworld");
            }
            return;
        }

        if (!VillagerUtils.isJobSiteNearby(villager)) {
            if (this.plugin.isDebugging()) {
                this.logger.info("[Debug] Skipping trade refresh for " + villager.getUniqueId() + " - no job site nearby");
            }
            return;
        }

        if (this.plugin.isDebugging()) {
            this.logger.info("[Debug] Trade refresh check for " + villager.getUniqueId() +
                    ": timeSinceLastRestock=" + timeSinceLastRestock + "ms, effectiveInterval=" + effectiveInterval + "ms, restocksToday=" + villager.getRestocksToday());
        }

        if (intervalPassed && shouldRestock(villager)) {
            lastRestock = now;
            pdc.set(this.key, PersistentDataType.LONG, lastRestock);
            List<MerchantRecipe> recipes = new ArrayList<>(villager.getRecipes());

            for (MerchantRecipe recipe : recipes) {
                recipe.setUses(0);
            }

            villager.setRecipes(recipes);
            villager.setRestocksToday(villager.getRestocksToday() + 1);
            // Tell the villager to update pricing of their trades
            villager.updateDemand();
            
            if (this.plugin.isDebugging()) {
                this.logger.info("[Debug] Villager " + villager.getUniqueId() + " restocked! restocksToday now: " + villager.getRestocksToday());
            }

            if (this.restockSound != null) {
                villager.getWorld().playSound(villager.getLocation(), this.restockSound, SoundCategory.NEUTRAL, 1.0F, 1.0F);
            } else {
                villager.getWorld().playSound(villager.getLocation(), VillagerUtils.PROFESSION_TO_SOUND.get(villager.getProfession()),
                        SoundCategory.NEUTRAL, 1.0F,
                        1.0F);
            }
        } else if (this.plugin.isDebugging()) {
            this.logger.info("[Debug] Trade refresh NOT performed for " + villager.getUniqueId() +
                    ": intervalPassed=" + intervalPassed + " (needed " + effectiveInterval + "ms, had " + timeSinceLastRestock + "ms)" +
                    (intervalPassed ? ", shouldRestock=false" : ""));
        }

        // Level the villager up to match its accumulated experience. Gated on day + job site above
        // (mirrors restock); max villager level is 5, which getVillagerLevel already caps.
        if (needsLevelUp) {
            int increaseAmount = Math.max(0, expectedLevel - currentLevel);
            villager.increaseLevel(increaseAmount);
            if (this.levelUpSound != null) {
                villager.getWorld().playSound(villager.getLocation(), this.levelUpSound, SoundCategory.NEUTRAL, 1.0F, 1.0F);
            }
            PotionEffect regenEffect = new PotionEffect(PotionEffectType.REGENERATION, 200, 0, false);
            villager.addPotionEffect(regenEffect);

            if (this.plugin.isDebugging()) {
                this.plugin.getLogger().info("Villager " + villager.getUniqueId() + " was leveled up to level " + expectedLevel + " from level " + currentLevel);
            }
        }
    }

    /**
     * Records that a block has changed, marking its chunk and edge-adjacent neighbors for deferred villager processing.
     */
    public void handleBlockChange(Block block) {
        if (this.plugin.isDisableChunkVillagerUpdate()) {
            return;
        }
        // Mark the block's own chunk, plus any edge-adjacent neighbor, without allocating a list
        // per event (block break/place fires constantly on a busy server).
        Chunk chunk = block.getChunk();
        long stamp = System.currentTimeMillis();
        changedChunks.put(chunk, stamp);

        int blockInChunkX = block.getX() & 0xF;
        int blockInChunkZ = block.getZ() & 0xF;

        World world = block.getWorld();

        // If <= 1 or >= 14 then add the -/+ chunk respectively without loading them
        if (blockInChunkX <= 1 && world.isChunkLoaded(chunk.getX() - 1, chunk.getZ())) {
            changedChunks.put(world.getChunkAt(chunk.getX() - 1, chunk.getZ()), stamp);
        } else if (blockInChunkX >= 14 && world.isChunkLoaded(chunk.getX() + 1, chunk.getZ())) {
            changedChunks.put(world.getChunkAt(chunk.getX() + 1, chunk.getZ()), stamp);
        }

        // Same for Z
        if (blockInChunkZ <= 1 && world.isChunkLoaded(chunk.getX(), chunk.getZ() - 1)) {
            changedChunks.put(world.getChunkAt(chunk.getX(), chunk.getZ() - 1), stamp);
        } else if (blockInChunkZ >= 14 && world.isChunkLoaded(chunk.getX(), chunk.getZ() + 1)) {
            changedChunks.put(world.getChunkAt(chunk.getX(), chunk.getZ() + 1), stamp);
        }
    }

    /**
     * Schedules villager activity evaluation for recently modified chunks.
     * Chunks are removed from tracking if they have not been modified within the past 3 seconds
     * or are no longer loaded.
     */
    private void processChunks() {
        long now = System.currentTimeMillis();
        changedChunks.entrySet().removeIf(entry -> {
            Chunk chunk = entry.getKey();
            long lastChange = entry.getValue();

            // Drop chunks idle past the update threshold (3 seconds)
            if (now - lastChange > 3000) {
                return true;
            }

            if (!chunk.isLoaded()) {
                return true;
            }

            if (this.plugin.isChunkDebugging()) {
                this.logger.info("[Debug] Processing chunk " + chunk.getX() + ", " + chunk.getZ() + " for villagers");
            }

            if (chunk.isLoaded()) {
                this.scheduleChunkVillagerProcessing(chunk);
            }

            return false;
        });
    }
    
    /**
     * Schedules villager processing for a chunk using region-appropriate scheduling.
     *
     * <p>Entity enumeration ({@link Chunk#getEntities()}) and per-entity access must happen on the
     * region thread that owns the chunk. This method is invoked from {@link #processChunks()} on the
     * global region scheduler, which on Folia does <strong>not</strong> own arbitrary chunks, so
     * touching entities here directly would throw a thread-ownership violation. We therefore dispatch
     * the whole body onto the owning region via {@link io.papermc.paper.threadedregions.scheduler.RegionScheduler}
     * (which simply runs on the main thread on non-Folia Paper).
     */
    private void scheduleChunkVillagerProcessing(Chunk chunk) {
        if (!chunk.isLoaded()) {
            return;
        }

        final World world = chunk.getWorld();
        final int cx = chunk.getX();
        final int cz = chunk.getZ();

        try {
            Bukkit.getRegionScheduler().run(this.plugin, world, cx, cz, SentryTaskWrapper.wrap((scheduledTask) -> {
                // Re-check liveness: the region task runs a tick or more after we were scheduled.
                if (!chunk.isLoaded()) {
                    return;
                }
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof Villager villager) {
                        if (this.inactiveVillagers.contains(villager) || this.activeVillagers.contains(villager)) {
                            boolean isActive = this.activeVillagers.contains(villager);
                            if (this.processVillager(villager, isActive)) {
                                if (this.plugin.isDebugging()) {
                                    this.logger.info("[Debug] Processed villager " + villager + " (" + villager.getUniqueId() + ") in chunk " + cx + ", " + cz);
                                }
                            }
                        }
                    }
                }
            }));
        } catch (IllegalPluginAccessException e) {
            // Plugin disabled, ignore
            if (this.plugin.isDebugging()) {
                this.logger.fine("Skipped chunk villager processing; plugin disabled or stopping.");
            }
        }
    }


    /**
     * Determines if a villager should be considered active.
     *
     * @return {@code true} if the villager should be active, {@code false} otherwise.
     */
    private boolean shouldBeActive(Villager villager) {
        Location loc = villager.getLocation().add(0.0F, (float) FEET_BLOCK_Y_OFFSET, 0.0F);
        return shouldBeActive(villager, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /**
     * Determines whether a villager should be active at the given block location.
     *
     * @return {@code true} if the villager should be active at the given location, {@code false} otherwise
     */
    private boolean shouldBeActive(Villager villager, int blockX, int blockY, int blockZ) {
        return this.activityPolicy.shouldBeActive(
                villagerStateOf(villager, blockX, blockY, blockZ),
                gridOf(villager.getWorld()));
    }

    /**
     * Builds a state representation of a villager at the given block coordinates.
     *
     * @return a VillagerState containing the villager's name, swimming and sleeping status,
     *         vehicle presence, profession, experience, and block coordinates
     */
    private VillagerState villagerStateOf(Villager villager, int blockX, int blockY, int blockZ) {
        Component customName = villager.customName();
        String name = customName == null
                ? ""
                : PlainTextComponentSerializer.plainText().serialize(customName).toLowerCase();
        return new VillagerState(
                name,
                villager.isSwimming(),
                villager.isSleeping(),
                villager.getVehicle() instanceof Vehicle,
                villager.getProfession() == Villager.Profession.NONE,
                villager.getVillagerExperience(),
                blockX, blockY, blockZ);
    }

    /**
     * Adapts a live {@link World} to a {@link BlockGrid}. Returns {@code null} for coordinates in an
     * unloaded chunk, which the policy treats as "cannot move there" — this reproduces the original
     * per-column {@code isChunkLoaded} short-circuit for cardinal neighbours. The villager's own
     * column (feet/head/floor/roof) is assumed loaded by callers ({@code processVillager} guards the
     * villager's chunk before evaluating; {@code reconcile} only runs on valid in-world villagers),
     * so the policy's null-tolerance for the centre column is never exercised in practice. A future
     * caller that evaluates a villager whose own chunk may be unloaded must revisit that assumption.
     * <p>
     * A single {@code shouldBeActive} evaluation queries up to ~16 blocks, the bulk of them in the
     * villager's own chunk (only cardinal neighbours near a chunk edge cross into another chunk). The
     * adapter caches the most-recently-resolved {@link Chunk} so those queries collapse to one
     * chunk-map lookup instead of one per block. The returned grid is stateful and not thread-safe,
     * but each evaluation builds a fresh one and drives it synchronously on the owning region thread,
     * where the cached chunk cannot unload mid-evaluation.
     */
    private static BlockGrid gridOf(World world) {
        return new BlockGrid() {
            private Chunk cachedChunk;
            private int cachedChunkX;
            private int cachedChunkZ;

            /**
             * Gets a block snapshot at the specified world coordinates, caching chunk lookups for efficiency.
             *
             * @return a snapshot of the block at the coordinates, or null if the chunk is not loaded
             */
            @Override
            public BlockSnapshot at(int x, int y, int z) {
                // Clamp y between the world's minimum and maximum height
                int clampedY = Math.clamp(y, world.getMinHeight(), world.getMaxHeight() - 1);
                int chunkX = x >> 4;
                int chunkZ = z >> 4;
                Chunk chunk = this.cachedChunk;
                if (chunk == null || chunkX != this.cachedChunkX || chunkZ != this.cachedChunkZ) {
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        return null;
                    }
                    chunk = world.getChunkAt(chunkX, chunkZ);
                    this.cachedChunk = chunk;
                    this.cachedChunkX = chunkX;
                    this.cachedChunkZ = chunkZ;
                }
                Block b = chunk.getBlock(x & 0xF, clampedY, z & 0xF);
                Material type = b.getType();
                return new BlockSnapshot(type, b.isPassable(), type.isSolid());
            }
        };
    }

    /**
     * Converts a legacy sound name format and updates the config if a conversion is needed.
     *
     * @param soundName the sound name to convert
     * @param configKey the configuration key to update if conversion occurs
     * @return the converted sound name, or an empty string if the conversion fails
     */
    private String convertLegacySoundName(String soundName, String configKey) {
        String converted = StringUtils.convertLegacySoundNameFormat(soundName);
        if (converted == null) {
            return "";
        }
        if (!converted.equals(soundName)) {
            this.logger.info("Found legacy sound name in config, converting to new format and saving config.");
            this.plugin.getConfig().set(configKey, converted);
            this.plugin.saveConfig();
        }
        return converted;
    }

    /**
     * Validates a config interval, clamping out-of-range values to a safe fallback and warning.
     *
     * @param configKey the config key (for the warning message)
     * @param value     the configured value
     * @param min       the minimum acceptable value (inclusive)
     * @param fallback  the value to use when {@code value < min}
     * @return {@code value} if it is at least {@code min}, otherwise {@code fallback}
     */
    private long validateInterval(String configKey, long value, long min, long fallback) {
        if (value < min) {
            this.logger.warning("Config value '" + configKey + "' must be >= " + min + " (got " + value
                    + "); falling back to " + fallback + ".");
            return fallback;
        }
        return value;
    }

    /**
     * Validates a config interval, clamping out-of-range values to the nearest bound and warning.
     *
     * @param configKey the config key (for the warning message)
     * @param value     the configured value
     * @param min       the minimum acceptable value (inclusive)
     * @param max       the maximum acceptable value (inclusive)
     * @return {@code value} if it is in {@code [min, max]}, otherwise the nearest bound
     */
    private long validateClampedInterval(String configKey, long value, long min, long max) {
        if (value < min) {
            this.logger.warning("Config value '" + configKey + "' must be >= " + min + " (got " + value
                    + "); clamping to " + min + ".");
            return min;
        }
        if (value > max) {
            this.logger.warning("Config value '" + configKey + "' must be <= " + max + " (got " + value
                    + "); clamping to " + max + ".");
            return max;
        }
        return value;
    }

    /**
     * Schedules periodic processing of a villager at the specified interval,
     * replacing any existing task.
     *
     * Does nothing if the plugin is disabled or shutting down. If the villager
     * is no longer tracked, the scheduling is skipped. If a plugin access
     * exception occurs, the villager is untracked.
     *
     * @param villager the villager to schedule
     * @param interval the scheduling period in ticks
     */
    private void scheduleVillagerTask(@NotNull Villager villager, long interval) {
        if (this.shuttingDown || !this.plugin.isEnabled()) {
            return;
        }

        UUID id = villager.getUniqueId();
        // Manage the task and set membership atomically (paired with removeVillager) so a concurrent
        // removal can't interleave between cancelling the old task and installing the new one, which
        // would leak a live task for a villager that is no longer tracked.
        synchronized (this.stateLock) {
            // Re-check under the lock: flush() flips shuttingDown and clears tasks here too, so this
            // closes the window where a schedule slipping past the pre-lock check installs an orphan.
            if (this.shuttingDown) {
                return;
            }
            // Don't install a task for a villager that was removed concurrently.
            if (!this.activeVillagers.contains(villager) && !this.inactiveVillagers.contains(villager)) {
                return;
            }

            ScheduledTask existing = this.villagerTasks.remove(id);
            if (existing != null && !existing.isCancelled()) {
                existing.cancel();
            }

            try {
                ScheduledTask task = villager.getScheduler().runAtFixedRate(
                        this.plugin,
                        SentryTaskWrapper.wrap((scheduledTask) -> this.processVillagerSafely(villager)),
                        null,
                        interval,
                        interval
                );
                this.villagerTasks.put(id, task);
                this.villagerTaskIntervals.put(id, interval);
            } catch (IllegalPluginAccessException e) {
                untrack(villager);
                this.villagerTaskIntervals.remove(id);
            }
        }
    }

    /**
     * Reschedule a villager only when the interval changes.
     */
    private void rescheduleVillagerTask(@NotNull Villager villager, long newInterval) {
        Long currentInterval = this.villagerTaskIntervals.get(villager.getUniqueId());
        if (currentInterval != null && currentInterval == newInterval) {
            return;
        }
        this.scheduleVillagerTask(villager, newInterval);
    }
}
