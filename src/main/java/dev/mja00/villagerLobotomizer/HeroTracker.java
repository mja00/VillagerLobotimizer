package dev.mja00.villagerLobotomizer;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import dev.mja00.villagerLobotomizer.policy.HeroGiftPolicy;
import dev.mja00.villagerLobotomizer.utils.SentryTaskWrapper;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * Tracks online players with Hero of the Village and runs a scan on each one's own thread every
 * {@link HeroGiftPolicy#SCAN_INTERVAL_TICKS}, matching how often a vanilla villager re-checks which
 * players it can see. Nothing runs while no hero is online.
 */
public class HeroTracker implements Listener {

    private final Plugin plugin;
    private final Consumer<Player> scan;
    private final Set<UUID> heroes = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ScheduledTask> scanTasks = new ConcurrentHashMap<>();

    /**
     * @param scan run on the hero's thread for each scan; the hero is known to have the effect
     */
    public HeroTracker(@NotNull Plugin plugin, @NotNull Consumer<Player> scan) {
        this.plugin = plugin;
        this.scan = scan;
    }

    /** Picks up heroes already online, e.g. after a plugin reload. */
    public void scanOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            // A player's effects belong to its region thread on Folia.
            player.getScheduler().run(this.plugin, SentryTaskWrapper.wrap(task -> refresh(player)), null);
        }
    }

    /** A live view; iterate it rather than copying. */
    public @NotNull Set<UUID> candidates() {
        return Collections.unmodifiableSet(this.heroes);
    }

    void refresh(@NotNull Player player) {
        if (player.isOnline() && player.hasPotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE)) {
            track(player);
        } else {
            untrack(player.getUniqueId());
        }
    }

    private void track(@NotNull Player player) {
        UUID id = player.getUniqueId();
        this.heroes.add(id);
        this.scanTasks.computeIfAbsent(id, ignored -> {
            try {
                return player.getScheduler().runAtFixedRate(this.plugin,
                        SentryTaskWrapper.wrap(task -> runScan(player, task)),
                        () -> this.scanTasks.remove(id),
                        HeroGiftPolicy.SCAN_INTERVAL_TICKS, HeroGiftPolicy.SCAN_INTERVAL_TICKS);
            } catch (IllegalPluginAccessException e) {
                // Plugin disabling: there is nothing left to scan for.
                return null;
            }
        });
    }

    private void runScan(@NotNull Player player, @NotNull ScheduledTask task) {
        UUID id = player.getUniqueId();
        if (this.scanTasks.get(id) != task) {
            // Superseded or untracked; a cancel that failed must not leave a second scan running.
            safeCancel(task);
            return;
        }
        // The effect can end without an event reaching us (e.g. during a reload), so re-check it here.
        if (!player.isOnline() || !player.hasPotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE)) {
            untrack(id);
            return;
        }
        this.scan.accept(player);
    }

    private void untrack(@NotNull UUID id) {
        this.heroes.remove(id);
        safeCancel(this.scanTasks.remove(id));
    }

    private static void safeCancel(ScheduledTask task) {
        if (task == null) {
            return;
        }
        try {
            task.cancel();
        } catch (RuntimeException ignored) {
            // MockBukkit leaves cancel unimplemented; runScan's ownership check stops the task instead.
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || event.getModifiedType() != PotionEffectType.HERO_OF_THE_VILLAGE) {
            return;
        }
        if (event.getNewEffect() != null) {
            track(player);
        } else {
            untrack(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        untrack(event.getPlayer().getUniqueId());
    }
}
