package dev.mja00.villagerLobotimizer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.logging.Logger;

public class LobotomizeStorage {
    private static final EnumSet<Material> IMPASSABLE_REGULAR;
    private static final EnumSet<Material> IMPASSABLE_FLOOR;
    private static final EnumSet<Material> IMPASSABLE_TALL;
    private static final EnumSet<Material> IMPASSABLE_ALL;
    private static final EnumSet<Material> IMPASSABLE_REGULAR_FLOOR;
    private static final EnumSet<Material> IMPASSABLE_REGULAR_TALL;
    private final VillagerLobotimizer plugin;
    private final NamespacedKey key;
    private final Set<Villager> activeVillagers = Collections.newSetFromMap(new ConcurrentHashMap<>(128));
    private final Set<Villager> inactiveVillagers = Collections.newSetFromMap(new ConcurrentHashMap<>(128));
    private long checkInterval;
    private long inactiveCheckInterval;
    private long restockInterval;
    private boolean onlyProfessions;
    private boolean lobotomizePassengers;
    private Sound restockSound;
    private Logger logger;
    private final Map<Chunk, Long> changedChunks = new ConcurrentHashMap<>();
    private int priorityCheckRadius; // Radius to check for villagers around block changes

    static {
        IMPASSABLE_REGULAR = EnumSet.of(Material.LAVA);
        IMPASSABLE_FLOOR = EnumSet.noneOf(Material.class);
        IMPASSABLE_TALL = EnumSet.noneOf(Material.class);

        for(Material m : Material.values()) {
            if (m.isOccluding()) {
                IMPASSABLE_REGULAR.add(m);
            }

            if (m.name().contains("_CARPET")) {
                IMPASSABLE_FLOOR.add(m);
            }

            if (m.name().contains("_WALL") || m.name().contains("_FENCE")) {
                IMPASSABLE_TALL.add(m);
            }
        }

        IMPASSABLE_ALL = EnumSet.copyOf(IMPASSABLE_REGULAR);
        IMPASSABLE_REGULAR_FLOOR = EnumSet.copyOf(IMPASSABLE_REGULAR);
        IMPASSABLE_REGULAR_TALL = EnumSet.copyOf(IMPASSABLE_REGULAR);
        IMPASSABLE_ALL.addAll(IMPASSABLE_FLOOR);
        IMPASSABLE_ALL.addAll(IMPASSABLE_TALL);
        IMPASSABLE_REGULAR_FLOOR.addAll(IMPASSABLE_FLOOR);
        IMPASSABLE_REGULAR_TALL.addAll(IMPASSABLE_TALL);
    }

    public LobotomizeStorage(VillagerLobotimizer plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.checkInterval = plugin.getConfig().getLong("check-interval");
        this.inactiveCheckInterval = plugin.getConfig().getLong("inactive-check-interval", this.checkInterval);
        this.restockInterval = plugin.getConfig().getLong("restock-interval");
        this.onlyProfessions = plugin.getConfig().getBoolean("only-lobotomize-villagers-with-professions");
        this.lobotomizePassengers = plugin.getConfig().getBoolean("always-lobotomize-villagers-in-vehicles");
        this.priorityCheckRadius = plugin.getConfig().getInt("priority-check-radius", 3);
        String soundName = plugin.getConfig().getString("restock-sound");

        try {
            this.restockSound = soundName != null && !soundName.isEmpty() ? Sound.valueOf(soundName) : null;
        } catch (IllegalArgumentException var4) {
            plugin.getLogger().warning("Unknown sound name \"" + soundName + "\"");
        }

        this.key = new NamespacedKey(plugin, "lastRestock");
        Bukkit.getScheduler().runTaskTimer(plugin, new DeactivatorTask(), this.checkInterval, this.checkInterval);
        Bukkit.getScheduler().runTaskTimer(plugin, new ActivatorTask(), this.inactiveCheckInterval, this.inactiveCheckInterval);
        Bukkit.getScheduler().runTaskTimer(plugin, this::processChangedChunks, 5L, 5L);
    }

    public @NotNull Set<Villager> getLobotomized() {
        return this.inactiveVillagers;
    }

    public @NotNull Set<Villager> getActive() {
        return this.activeVillagers;
    }

    public final void addVillager(@NotNull Villager villager) {
        this.activeVillagers.add(villager);
        if (this.plugin.isDebugging()) {
            this.logger.info("[Debug] Tracked villager " + villager + " (" + villager.getUniqueId() + ")");
        }
    }

    public final void removeVillager(@NotNull Villager villager) {
        boolean removed = false;
        boolean active = false;
        if (this.activeVillagers.remove(villager)) {
            removed = true;
            active = true;
        }

        if (this.inactiveVillagers.remove(villager)) {
            removed = true;
            villager.setAware(true);
        }

        if (this.plugin.isDebugging()) {
            if (removed) {
                this.logger.info("[Debug] Untracked villager " + villager + " (" + villager.getUniqueId() + "), marked as active = " + active);
            } else {
                this.logger.info("[Debug] Attempted to untrack villager " + villager + " (" + villager.getUniqueId() + "), but it was not tracked");
            }
        }
    }

    public final void flush() {
        // We'll flush all the villagers before shutdown, so if the plugin is removed, they won't have lobotomized villagers forever
        this.inactiveVillagers.removeIf((villager) -> {
            this.logger.info("Un-lobotomizing Villager " + villager.getUniqueId());
            villager.setAware(true);
            return true;
        });
    }

    private boolean processVillager(@NotNull Villager villager, boolean active) {
        Location villagerLocation = villager.getLocation().add(0.0F, 0.51, 0.0F);
        // If the chunk is not loaded, and the villager is not active, we want to add them to the active list
        if (!villager.getWorld().isChunkLoaded(villagerLocation.getBlockX() >> 4, villagerLocation.getBlockZ() >> 4)) {
            if (!active) {
                this.activeVillagers.add(villager);
            }

            return !active;
        } else if (villager.isValid() && !villager.isDead()) {
            // We want to check the name of the villager. As we want a nametag with "nobrain" to lobotomize the villager always and a tag with "alwaysbrain" to not lobotomize the villager
            Component customName = villager.customName();
            String villagerName = customName == null ? "" : PlainTextComponentSerializer.plainText().serialize(customName).toLowerCase();
            // Handle the villagers name. Note: All villagers are added to the active list on server start, even if they were previously lobotomized (so their awareness may be false)
            if (villagerName.contains("nobrain")) {
                if (active) {
                    villager.setAware(false);
                    this.inactiveVillagers.add(villager);
                    return true;
                }
                return false;
            } else if (villagerName.contains("alwaysbrain")) {
                if (!active) {
                    villager.setAware(true);
                    this.activeVillagers.add(villager);
                    return true;
                }
                return false;
            }
            Material roofType = villager.getWorld().getBlockAt(villagerLocation.getBlockX(), villagerLocation.getBlockY() - 1, villagerLocation.getBlockZ()).getType();
            boolean hasRoof = roofType == Material.HONEY_BLOCK || this.testImpassable(IMPASSABLE_ALL, villager.getWorld().getBlockAt(villagerLocation.getBlockX(), villagerLocation.getBlockY() + 2, villagerLocation.getBlockZ()));
            // Check if: 
            // 1. Either lobotomize passengers is disabled OR the villager is not riding a vehicle AND
            // 2. Either only professions is enabled and villager has no profession OR the villager can move in any cardinal directions
            // 3. The villager is set to always have a brain
            if ((!this.lobotomizePassengers || !(villager.getVehicle() instanceof Vehicle)) && (this.onlyProfessions && villager.getProfession() == Villager.Profession.NONE || this.canMoveCardinally(villager.getWorld(), villagerLocation.getBlockX(), villagerLocation.getBlockY(), villagerLocation.getBlockZ(), hasRoof))) {
                if (active) {
                    return false;
                } else {
                    villager.setAware(true);
                    this.activeVillagers.add(villager);
                    return true;
                }
            } else {
                this.refreshTrades(villager); // Ensure villagers don't get stale while being lobotomized
                if (!active) {
                    return false;
                } else {
                    villager.setAware(false);
                    this.inactiveVillagers.add(villager);
                    return true;
                }
            }
        } else {
            if (this.plugin.isDebugging()) {
                this.logger.info("[Debug] Untracked villager " + villager + " (" + villager.getUniqueId() + "), because it was either dead, invalid, or in an unloaded chunk");
            }

            return true;
        }
    }

    private void refreshTrades(@NotNull Villager villager) {
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        Long lastRestock = (Long)pdc.get(this.key, PersistentDataType.LONG);
        if (lastRestock == null) {
            lastRestock = 0L;
        }

        long now = System.currentTimeMillis();
        if (now - lastRestock > this.restockInterval) {
            lastRestock = now;
            pdc.set(this.key, PersistentDataType.LONG, lastRestock);
            List<MerchantRecipe> recipes = new ArrayList<>(villager.getRecipes());

            for (MerchantRecipe recipe : recipes) {
                recipe.setUses(0);
            }

            villager.setRecipes(recipes);
            if (this.restockSound != null) {
                villager.getWorld().playSound(villager.getLocation(), this.restockSound, SoundCategory.NEUTRAL, 1.0F, 1.0F);
            }
        }
    }

    private boolean canMoveThrough(World w, int x, int y, int z, boolean roof) {
        return w.isChunkLoaded(x >> 4, z >> 4) &&
                !this.testImpassable(IMPASSABLE_REGULAR_FLOOR, w.getBlockAt(x, y + 1, z)) &&
                !this.testImpassable(IMPASSABLE_TALL, w.getBlockAt(x, y, z)) &&
                (!roof || !this.testImpassable(IMPASSABLE_REGULAR_TALL, w.getBlockAt(x, y - 1, z)));
    }

    private boolean testImpassable(@NotNull EnumSet<Material> set, @NotNull Block b) {
        Material type = b.getType();
        return set.contains(type) || type != Material.WATER && !b.isPassable();
    }

    private boolean canMoveCardinally(World w, int x, int y, int z, boolean roof) {
        // Essentially check x + 1, x - 1, z + 1, z - 1, and return the or of the results
        Boolean xPlusOne = this.canMoveThrough(w, x + 1, y, z, roof);
        Boolean xMinusOne = this.canMoveThrough(w, x - 1, y, z, roof);
        Boolean zPlusOne = this.canMoveThrough(w, x, y, z + 1, roof);
        Boolean zMinusOne = this.canMoveThrough(w, x, y, z - 1, roof);

        return xPlusOne || xMinusOne || zPlusOne || zMinusOne;
    }

    public final class ActivatorTask implements Runnable {
        public void run() {
            // Create a copy to avoid concurrent modification
            Set<Villager> toRemove = new HashSet<>();
            for (Villager villager : inactiveVillagers) {
                if (processVillager(villager, false)) {
                    toRemove.add(villager);
                }
            }
            inactiveVillagers.removeAll(toRemove);
        }
    }

    public final class DeactivatorTask implements Runnable {
        public void run() {
            // Create a copy to avoid concurrent modification
            Set<Villager> toRemove = new HashSet<>();
            for (Villager villager : activeVillagers) {
                if (processVillager(villager, true)) {
                    toRemove.add(villager);
                }
            }
            activeVillagers.removeAll(toRemove);
        }
    }

    public void handleBlockChange(Block block) {
        Chunk chunk = block.getChunk();
        changedChunks.put(chunk, System.currentTimeMillis());

        if (plugin.isDebugging()) {
            logger.info("[Debug] Tracking chunk change at " + chunk.getX() + "," + chunk.getZ());
        }
    }

    private void processChangedChunks() {
        long now = System.currentTimeMillis();
        // Clean up old entries
        changedChunks.entrySet().removeIf(entry -> {
            Chunk chunk = entry.getKey();
            long changeTime = entry.getValue();

            // If change is older than 30 seconds, remove it
            if (now - changeTime > 30000) {
                return true;
            }

            // Skip unloaded chunks
            if (!chunk.isLoaded()) {
                return true;
            }

            if (plugin.isDebugging()) {
                logger.info("[Debug] Processing changed chunk: " + chunk.getX() + "," + chunk.getZ());
            }

            for (Map.Entry<Chunk, Long> innerEntry : new HashMap<>(changedChunks).entrySet()) {
                Chunk innerChunk = innerEntry.getKey();
                if (innerChunk.isLoaded()) {
                    processVillagersInChunk(innerChunk);
                }
            }

            return false; // Keep tracking recent changes
        });
    }

    private void processVillagersInChunk(Chunk chunk) {
        if (!chunk.isLoaded()) return;

        // Get all entities in the chunk
        Entity[] entities = chunk.getEntities();
        for (Entity entity : entities) {
            if (entity instanceof Villager) {
                Villager villager = (Villager) entity;

                // First check inactive villagers for better responsiveness
                if (inactiveVillagers.contains(villager)) {
                    if (processVillager(villager, false)) {
                        if (plugin.isDebugging()) {
                            logger.info("[Debug] Reactivated villager due to block change: " + villager.getUniqueId());
                        }
                    }
                }
            }
        }
    }
}
