package dev.mja00.villagerLobotomizer.listeners;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import dev.mja00.villagerLobotomizer.VillagerLobotomizer;
import io.papermc.paper.event.block.BlockBreakBlockEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class EntityListener implements Listener {
    private final VillagerLobotomizer plugin;

    public EntityListener(VillagerLobotomizer plugin) {
        this.plugin = plugin;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Villager) {
                    plugin.getStorage().addVillager((Villager)entity);
                }
            }
        }
    }

    @EventHandler
    public final void onLoad(ChunkLoadEvent event) {
        if (this.plugin.isChunkDebugging()) {
            for (Entity entity : event.getChunk().getEntities()) {
                if (entity instanceof Villager) {
                    this.plugin.getLogger().info("[Debug] Caught " + event.getEventName() + " for villager " + entity + " (" + entity.getUniqueId() + "); The villager should have been added to the storage");
                }
            }
        }
    }

    @EventHandler
    public final void onUnload(ChunkUnloadEvent event) {
        if (this.plugin.isChunkDebugging()) {
            for (Entity entity : event.getChunk().getEntities()) {
                if (entity instanceof Villager) {
                    this.plugin.getLogger().info("[Debug] Caught " + event.getEventName() + " for villager " + entity + " (" + entity.getUniqueId() + "); The villager should have been removed from the storage");
                }
            }
        }
    }

    // Realistically only these two events are ever used. The others are for debugging purposes
    @EventHandler
    public final void onAdd(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof Villager) {
            if (this.plugin.isDebugging()) {
                this.plugin.getLogger().info("[Debug] Caught " + event.getEventName() + " for villager " + event.getEntity() + " (" + event.getEntity().getUniqueId() + "); The villager should be added to the storage");
            }

            this.plugin.getStorage().addVillager((Villager)event.getEntity());
        }
    }

    @EventHandler
    public final void onRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof Villager) {
            this.plugin.getStorage().removeVillager((Villager)event.getEntity());
            if (this.plugin.isDebugging()) {
                this.plugin.getLogger().info("[Debug] Caught " + event.getEventName() + " for villager " + event.getEntity() + " (" + event.getEntity().getUniqueId() + "); The villager should have been removed from the storage");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public final void onBlockBreak(BlockBreakEvent event) {
        this.plugin.getStorage().handleBlockChange(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public final void onBlockPlace(BlockPlaceEvent event) {
        this.plugin.getStorage().handleBlockChange(event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public final void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        // Check if the feature is enabled in config
        if (!this.plugin.getConfig().getBoolean("prevent-trading-with-unlobotomized-villagers", false)) {
            return;
        }

        // Only handle villager interactions
        if (!(event.getRightClicked() instanceof Villager villager)) {
            return;
        }

        Player player = event.getPlayer();

        // Check if the villager is tracked by the plugin
        boolean isTrackedActive = this.plugin.getStorage().getActive().contains(villager);
        boolean isTrackedInactive = this.plugin.getStorage().getLobotomized().contains(villager);
        
        // If the villager is not tracked at all, allow trading (default behavior)
        if (!isTrackedActive && !isTrackedInactive) {
            return;
        }
        
        // Block trades only with unlobotomized (active) villagers
        if (isTrackedActive) {
            // Cancel the trading event
            event.setCancelled(true);
            
            // Send a message to the player explaining why the trade was blocked
            Component message = Component.text("You cannot trade with unlobotomized villagers! ")
                    .color(NamedTextColor.RED)
                    .append(Component.text("This villager needs to be lobotomized first.")
                            .color(NamedTextColor.YELLOW));
            player.sendMessage(message);
            
            if (this.plugin.isDebugging()) {
                this.plugin.getLogger().info("[Debug] Blocked trade with unlobotomized villager " + 
                    villager.getUniqueId() + " by player " + player.getName());
            }
        }
    }
}
