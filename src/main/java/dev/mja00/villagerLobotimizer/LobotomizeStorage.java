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
import java.util.concurrent.ConcurrentHashMap;
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
    private boolean blockChangeDetectionEnabled;
    private boolean tpsBasedDetection;
    private double tpsThreshold;
    private final Map<Chunk, Long> changedChunks = new ConcurrentHashMap<>();
    private int priorityCheckRadius; // Radius to check for villagers around block changes
    private final NamespacedKey statusKey;

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
        this.blockChangeDetectionEnabled = plugin.getConfig().getBoolean("block-change-detection-enabled", true);
        this.tpsBasedDetection = plugin.getConfig().getBoolean("tps-based-detection", false);
        this.tpsThreshold = plugin.getConfig().getDouble("tps-threshold", 18.0);
        this.statusKey = new NamespacedKey(plugin, "lobotomyStatus");
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
        // Check if we already have a stored status
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        Boolean storedStatus = pdc.get(this.statusKey, PersistentDataType.BOOLEAN);

        if (storedStatus != null) {
            // Use stored status
            if (storedStatus) {
                this.activeVillagers.add(villager);
                villager.setAware(true);
            } else {
                this.inactiveVillagers.add(villager);
                villager.setAware(false);
            }
        } else {
            // No stored status, add to active list and determine status later
            this.activeVillagers.add(villager);
        }

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
        if (!villager.isValid() || villager.isDead()) {
            return true; // Remove from current collection
        }

        Location villagerLocation = villager.getLocation().add(0.0F, 0.51, 0.0F);
        // If the chunk is not loaded, keep current status
        if (!villager.getWorld().isChunkLoaded(villagerLocation.getBlockX() >> 4, villagerLocation.getBlockZ() >> 4)) {
            return false; // No change
        }

        boolean shouldBeActive = determineShouldBeActive(villager);

        if (shouldBeActive) {
            if (!active) {
                // Currently inactive but should be active
                villager.setAware(true);
                this.activeVillagers.add(villager);
                saveVillagerStatus(villager, true);
                return true; // Remove from inactive list
            }
            return false; // Already active, no change
        } else {
            // Should be inactive
            this.refreshTrades(villager); // Ensure villagers don't get stale while being lobotomized

            if (active) {
                // Currently active but should be inactive
                villager.setAware(false);
                this.inactiveVillagers.add(villager);
                saveVillagerStatus(villager, false);
                return true; // Remove from active list
            }
            return false; // Already inactive, no change
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
        // Skip if block change detection is disabled
        if (!blockChangeDetectionEnabled) {
            return;
        }

        // Skip if TPS-based detection is enabled and TPS is below threshold
        if (tpsBasedDetection) {
            double currentTps = plugin.getServer().getTPS()[0]; // Get 1 minute TPS
            if (currentTps < tpsThreshold) return;
        }

        Chunk chunk = block.getChunk();
        changedChunks.put(chunk, System.currentTimeMillis());

        // Also mark neighboring chunks if the block is at the edge
        int blockX = block.getX() & 0xF;
        int blockZ = block.getZ() & 0xF;

        World world = block.getWorld();

        // Check adjacent chunks if the block is at the edge
        if (blockX <= 1) {
            Chunk neighbor = world.getChunkAt(chunk.getX() - 1, chunk.getZ());
            changedChunks.put(neighbor, System.currentTimeMillis());
        } else if (blockX >= 14) {
            Chunk neighbor = world.getChunkAt(chunk.getX() + 1, chunk.getZ());
            changedChunks.put(neighbor, System.currentTimeMillis());
        }

        if (blockZ <= 1) {
            Chunk neighbor = world.getChunkAt(chunk.getX(), chunk.getZ() - 1);
            changedChunks.put(neighbor, System.currentTimeMillis());
        } else if (blockZ >= 14) {
            Chunk neighbor = world.getChunkAt(chunk.getX(), chunk.getZ() + 1);
            changedChunks.put(neighbor, System.currentTimeMillis());
        }
    }

    private void processChangedChunks() {
        // Skip processing if block change detection is disabled
        if (!blockChangeDetectionEnabled) {
            changedChunks.clear();
            return;
        }

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
                } else if (activeVillagers.contains(villager)) {
                    if (processVillager(villager, true)) {
                        if (plugin.isDebugging()) {
                            logger.info("[Debug] Deactivated villager due to block change: " + villager.getUniqueId());
                        }
                    }
                }
                // If not in either set, it's not being tracked
            }
        }
    }

    private void saveVillagerStatus(Villager villager, boolean active) {
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        pdc.set(this.statusKey, PersistentDataType.BOOLEAN, active);
    }

    private boolean determineShouldBeActive(Villager villager) {
        Location villagerLocation = villager.getLocation().add(0.0F, 0.51, 0.0F);

        // Check villager name first - this takes priority
        Component customName = villager.customName();
        String villagerName = customName == null ? "" : PlainTextComponentSerializer.plainText().serialize(customName).toLowerCase();

        if (villagerName.contains("nobrain")) {
            return false; // Always inactive
        } else if (villagerName.contains("alwaysbrain")) {
            return true; // Always active
        }

        // Check if in vehicle
        if (this.lobotomizePassengers && villager.getVehicle() instanceof Vehicle) {
            return false; // Should be inactive
        }

        // Check profession condition
        if (this.onlyProfessions && villager.getProfession() == Villager.Profession.NONE) {
            return true; // Should be active
        }

        // Check movement ability
        Material roofType = villager.getWorld().getBlockAt(villagerLocation.getBlockX(), villagerLocation.getBlockY() - 1, villagerLocation.getBlockZ()).getType();
        boolean hasRoof = roofType == Material.HONEY_BLOCK ||
                this.testImpassable(IMPASSABLE_ALL, villager.getWorld().getBlockAt(
                        villagerLocation.getBlockX(), villagerLocation.getBlockY() + 2, villagerLocation.getBlockZ()));

        return this.canMoveCardinally(villager.getWorld(),
                villagerLocation.getBlockX(),
                villagerLocation.getBlockY(),
                villagerLocation.getBlockZ(),
                hasRoof);
    }

    public boolean isBlockChangeDetectionEnabled() {
        return blockChangeDetectionEnabled;
    }

    public boolean isTpsBasedDetectionEnabled() {
        return tpsBasedDetection;
    }

    public double getTpsThreshold() {
        return tpsThreshold;
    }
}