package dev.mja00.villagerLobotomizer.listeners;

import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.MerchantInventory;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;

import dev.mja00.villagerLobotomizer.VillagerLobotomizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

public class EntityListener implements Listener {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String DEFAULT_TRADE_MESSAGE = "<red>You cannot trade with unlobotomized villagers!</red> <yellow>This villager needs to be lobotomized first.</yellow>";
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
                    this.plugin.getLogger().log(Level.INFO, "[Debug] Caught {0} for villager {1} ({2}); The villager should have been added to the storage", new Object[]{event.getEventName(), entity, entity.getUniqueId()});
                }
            }
        }
    }

    @EventHandler
    public final void onUnload(ChunkUnloadEvent event) {
        if (this.plugin.isChunkDebugging()) {
            for (Entity entity : event.getChunk().getEntities()) {
                if (entity instanceof Villager) {
                    this.plugin.getLogger().log(Level.INFO, "[Debug] Caught {0} for villager {1} ({2}); The villager should have been removed from the storage", new Object[]{event.getEventName(), entity, entity.getUniqueId()});
                }
            }
        }
    }

    // Realistically only these two events are ever used. The others are for debugging purposes
    @EventHandler
    public final void onAdd(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof Villager) {
            if (this.plugin.isDebugging()) {
                this.plugin.getLogger().log(Level.INFO, "[Debug] Caught {0} for villager {1} ({2}); The villager should be added to the storage", new Object[]{event.getEventName(), event.getEntity(), event.getEntity().getUniqueId()});
            }

            this.plugin.getStorage().addVillager((Villager)event.getEntity());
        }
    }

    @EventHandler
    public final void onRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof Villager) {
            this.plugin.getStorage().removeVillager((Villager)event.getEntity());
            if (this.plugin.isDebugging()) {
                this.plugin.getLogger().log(Level.INFO, "[Debug] Caught {0} for villager {1} ({2}); The villager should have been removed from the storage", new Object[]{event.getEventName(), event.getEntity(), event.getEntity().getUniqueId()});
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

    /**
     * Prevents players from trading with unlobotomized villagers if the prevention feature is enabled.
     *
     * Sends the player a configured message when trading is blocked, falling back to a default
     * message if the configuration message fails to parse as MiniMessage.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public final void onInventoryOpen(InventoryOpenEvent event) {
        if (!this.plugin.getConfig().getBoolean("prevent-trading-with-unlobotomized-villagers", false)) {
            return;
        }

        if (!(event.getInventory() instanceof MerchantInventory merchantInventory)) {
            return;
        }

        // Only handle villager merchants; ignore wandering traders and virtual merchants
        if (!(merchantInventory.getMerchant() instanceof Villager villager)) {
            return;
        }

        // active set == tracked, unlobotomized villagers
        if (!this.plugin.getStorage().getActive().contains(villager)) {
            return;
        }

        event.setCancelled(true);

        Player player = (Player) event.getPlayer();

        String messageString = this.plugin.getConfig().getString("unlobotomized-villager-trade-message", DEFAULT_TRADE_MESSAGE);
        if (messageString == null) {
            messageString = DEFAULT_TRADE_MESSAGE;
        }
        Component message;
        try {
            message = MINI_MESSAGE.deserialize(messageString);
        } catch (Exception e) {
            // Fallback to default message if parsing fails
            this.plugin.getLogger().log(Level.WARNING, "Failed to parse MiniMessage for unlobotomized-villager-trade-message: {0}", e.getMessage());
            message = MINI_MESSAGE.deserialize(DEFAULT_TRADE_MESSAGE);
        }
        player.sendMessage(message);

        if (this.plugin.isDebugging()) {
            this.plugin.getLogger().log(Level.INFO, "[Debug] Blocked trade with unlobotomized villager {0} by player {1}", new Object[]{villager.getUniqueId(), player.getName()});
        }
    }
}
