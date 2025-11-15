package dev.mja00.villagerLobotomizer;

import dev.mja00.villagerLobotomizer.utils.StringUtils;
import dev.mja00.villagerLobotomizer.utils.VillagerUtils;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class LobotomizeStorage {
    /**
     * IMPASSABLE_REGULAR is a set of blocks that are occulding light (so most solid blocks)
     */
    private static final EnumSet<Material> IMPASSABLE_REGULAR;
    /**
     * IMPASSABLE_FLOOR is a set of carpets types
     */
    private static final EnumSet<Material> IMPASSABLE_FLOOR;
    /**
     * IMPASSABLE_TALL is a set of blocks that are tall (so walls and fences)
     */
    private static final EnumSet<Material> IMPASSABLE_TALL;
    /**
     * IMPASSABLE_ALL is a set of all the impassable blocks
     */
    private static final EnumSet<Material> IMPASSABLE_ALL;
    /**
     * IMPASSABLE_REGULAR_FLOOR is a set of blocks that are regular and floor
     */
    private static final EnumSet<Material> IMPASSABLE_REGULAR_FLOOR;
    /**
     * IMPASSABLE_REGULAR_TALL is a set of blocks that are regular and tall
     */
    private static final EnumSet<Material> IMPASSABLE_REGULAR_TALL;
    /**
     * PROFESSION_BLOCKS is a set of blocks that are profession blocks
     */
    private static final EnumSet<Material> PROFESSION_BLOCKS;
    private static final EnumSet<Material> DOOR_BLOCKS;

    static {
        IMPASSABLE_REGULAR = EnumSet.of(Material.LAVA);
        IMPASSABLE_FLOOR = EnumSet.noneOf(Material.class);
        IMPASSABLE_TALL = EnumSet.noneOf(Material.class);
        DOOR_BLOCKS = EnumSet.noneOf(Material.class);
        for (Material m : Registry.MATERIAL) {
            if (m.isOccluding()) {
                IMPASSABLE_REGULAR.add(m);
            }

            if (m.name().contains("_CARPET")) {
                IMPASSABLE_FLOOR.add(m);
            }

            if (m.name().contains("_WALL") || m.name().contains("_FENCE")) {
                IMPASSABLE_TALL.add(m);
            }

            if (m.name().contains("_DOOR")) {
                DOOR_BLOCKS.add(m);
            }
        }

        IMPASSABLE_ALL = EnumSet.copyOf(IMPASSABLE_REGULAR);
        IMPASSABLE_REGULAR_FLOOR = EnumSet.copyOf(IMPASSABLE_REGULAR);
        IMPASSABLE_REGULAR_TALL = EnumSet.copyOf(IMPASSABLE_REGULAR);
        IMPASSABLE_ALL.addAll(IMPASSABLE_FLOOR);
        IMPASSABLE_ALL.addAll(IMPASSABLE_TALL);
        IMPASSABLE_REGULAR_FLOOR.addAll(IMPASSABLE_FLOOR);
        IMPASSABLE_REGULAR_TALL.addAll(IMPASSABLE_TALL);

        // Create a list of blocks that are profession blocks
        PROFESSION_BLOCKS = EnumSet.noneOf(Material.class);
        PROFESSION_BLOCKS.add(Material.BLAST_FURNACE);
        PROFESSION_BLOCKS.add(Material.SMOKER);
        PROFESSION_BLOCKS.add(Material.CARTOGRAPHY_TABLE);
        PROFESSION_BLOCKS.add(Material.BREWING_STAND);
        PROFESSION_BLOCKS.add(Material.COMPOSTER);
        PROFESSION_BLOCKS.add(Material.BARREL);
        PROFESSION_BLOCKS.add(Material.FLETCHING_TABLE);
        PROFESSION_BLOCKS.add(Material.CAULDRON);
        PROFESSION_BLOCKS.add(Material.LECTERN);
        PROFESSION_BLOCKS.add(Material.STONECUTTER);
        PROFESSION_BLOCKS.add(Material.LOOM);
        PROFESSION_BLOCKS.add(Material.SMITHING_TABLE);
        PROFESSION_BLOCKS.add(Material.GRINDSTONE);
    }

    private final VillagerLobotomizer plugin;
    private final NamespacedKey key;
    private final NamespacedKey lobotomizedKey;
    private final Set<Villager> activeVillagers = Collections.newSetFromMap(new ConcurrentHashMap<>(128));
    private final Set<Villager> inactiveVillagers = Collections.newSetFromMap(new ConcurrentHashMap<>(128));
    // Used to track what chunks we need to trigger updates for
    private final Map<Chunk, Long> changedChunks = new ConcurrentHashMap<>();
    // Track per-villager scheduled tasks using Paper's native schedulers
    private final Map<UUID, ScheduledTask> villagerTasks = new ConcurrentHashMap<>();
    private final Set<String> exemptNames;
    private final long checkInterval;
    private final long inactiveCheckInterval;
    private final long restockInterval;
    private final long restockRandomRange;
    private final boolean onlyProfessions;
    private final boolean lobotomizePassengers;
    private final boolean checkRoof;
    private final boolean silentLobotomizedVillagers;
    private final boolean persistLobotomizedState;
    private Sound restockSound;
    private Sound levelUpSound;
    private final Logger logger;
    private final Random random = new Random();
    private volatile boolean shuttingDown = false;

    public LobotomizeStorage(VillagerLobotomizer plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.checkInterval = plugin.getConfig().getLong("check-interval");
        this.inactiveCheckInterval = plugin.getConfig().getLong("inactive-check-interval", this.checkInterval);
        this.restockInterval = plugin.getConfig().getLong("restock-interval");
        this.restockRandomRange = plugin.getConfig().getLong("restock-random-range");
        this.onlyProfessions = plugin.getConfig().getBoolean("only-lobotomize-villagers-with-professions");
        this.lobotomizePassengers = plugin.getConfig().getBoolean("always-lobotomize-villagers-in-vehicles");
        this.checkRoof = plugin.getConfig().getBoolean("check-roof");
        this.silentLobotomizedVillagers = plugin.getConfig().getBoolean("silent-lobotomized-villagers");
        this.persistLobotomizedState = plugin.getConfig().getBoolean("persist-lobotomized-state", true);
        String soundName = plugin.getConfig().getString("restock-sound");
        String levelUpSoundName = plugin.getConfig().getString("level-up-sound");

        // Convert legacy sound names if needed
        soundName = convertLegacySoundName(soundName, "restock-sound");
        levelUpSoundName = convertLegacySoundName(levelUpSoundName, "level-up-sound");

        // If either sound starts with "minecraft:" we can remove that part as we handle it
        if (soundName.startsWith("minecraft:")) {
            soundName = soundName.replace("minecraft:", "");
        }
        if (levelUpSoundName.startsWith("minecraft:")) {
            levelUpSoundName = levelUpSoundName.replace("minecraft:", "");
        }

        List<String> configNames = plugin.getConfig().getStringList("always-active-names");
        exemptNames = new HashSet<>();
        exemptNames.addAll(configNames);

        // Empty our door set if the config is set to false
        if (!plugin.getConfig().getBoolean("ignore-villagers-stuck-in-doors")) {
            DOOR_BLOCKS.clear();
        }

        // Get the registry
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
                // If the sound is not found, it will throw an exception
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
        // Use Paper's GlobalRegionScheduler for chunk processing (doesn't access entities directly)
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, (task) -> this.processChunks(), 5L, 5L);
    }

    public @NotNull Set<Villager> getLobotomized() {
        return this.inactiveVillagers;
    }

    public @NotNull Set<Villager> getActive() {
        return this.activeVillagers;
    }

    public final void addVillager(@NotNull Villager villager) {
        // Don't add villagers if shutting down
        if (this.shuttingDown || !this.plugin.isEnabled()) {
            return;
        }

        // Check if this villager was previously lobotomized
        boolean wasLobotomized = false;
        if (this.persistLobotomizedState) {
            PersistentDataContainer pdc = villager.getPersistentDataContainer();
            wasLobotomized = pdc.has(this.lobotomizedKey, PersistentDataType.BYTE);
        }

        if (wasLobotomized) {
            // Immediately lobotomize the villager to prevent lag spike
            villager.setAware(false);
            if (this.silentLobotomizedVillagers) {
                villager.setSilent(true);
            }
            this.inactiveVillagers.add(villager);

            if (this.plugin.isDebugging()) {
                this.logger.info("[Debug] Re-lobotomized villager " + villager + " (" + villager.getUniqueId() + ") on chunk load");
            }
        } else {
            this.activeVillagers.add(villager);

            if (this.plugin.isDebugging()) {
                this.logger.info("[Debug] Tracked villager " + villager + " (" + villager.getUniqueId() + ") as active");
            }
        }

        // Schedule per-villager task using Paper's EntityScheduler
        try {
            ScheduledTask task = villager.getScheduler().runAtFixedRate(this.plugin,
                (scheduledTask) -> this.processVillagerSafely(villager),
                null, // No initial delay
                this.checkInterval,
                this.checkInterval
            );

            this.villagerTasks.put(villager.getUniqueId(), task);
        } catch (IllegalPluginAccessException e) {
            // Plugin disabled during scheduling, remove from whichever set it was added to
            this.activeVillagers.remove(villager);
            this.inactiveVillagers.remove(villager);
        }
    }

    public final void removeVillager(@NotNull Villager villager) {
        boolean removed = false;
        boolean active = false;
        
        // Cancel the per-villager task
        ScheduledTask task = this.villagerTasks.remove(villager.getUniqueId());
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
        
        if (this.activeVillagers.remove(villager)) {
            removed = true;
            active = true;
        }

        if (this.inactiveVillagers.remove(villager)) {
            removed = true;
            // Use Paper's EntityScheduler for thread safety
            villager.getScheduler().run(this.plugin, (scheduledTask) -> {
                villager.setAware(true);
                if (this.silentLobotomizedVillagers) {
                    villager.setSilent(false);
                }
            }, null);
        }

        if (this.plugin.isDebugging()) {
            if (removed) {
                this.logger.info("[Debug] Untracked villager " + villager + " (" + villager.getUniqueId() + "), marked as active = " + active + ", cancelled scheduler");
            } else {
                this.logger.info("[Debug] Attempted to untrack villager " + villager + " (" + villager.getUniqueId() + "), but it was not tracked");
            }
        }
    }

    /**
     * Flushes all villagers from storage and stops their tasks. Attempts to un-lobotomize them if possible.
     */
    public final void flush() {
        // Set shutdown flag to prevent new tasks from being scheduled
        this.shuttingDown = true;
        
        // Cancel all per-villager tasks
        for (ScheduledTask task : this.villagerTasks.values()) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        this.villagerTasks.clear();
        
        // We'll flush all the villagers before shutdown, so if the plugin is removed, they won't have lobotomized villagers forever
        // During shutdown, we need to handle this carefully since we can't schedule new tasks
        List<Villager> toFlush = new ArrayList<>(this.inactiveVillagers);
        this.inactiveVillagers.clear();
        
        for (Villager villager : toFlush) {
            if (this.plugin.isDebugging()) {
                this.logger.info("Un-lobotomizing Villager " + villager.getUniqueId());
            }

            if (plugin.isFolia()) {
                this.logger.info("Some Villagers may remain lobotomized after shutdown. If you remove the plugin they will be lobotomized forever.");
            }
            
            try {
                // During shutdown, try setting directly first since scheduler might not work
                villager.setAware(true);
                if (this.silentLobotomizedVillagers) {
                    villager.setSilent(false);
                }
                // Remove persistent lobotomized marker
                if (this.persistLobotomizedState) {
                    villager.getPersistentDataContainer().remove(this.lobotomizedKey);
                }
            } catch (IllegalStateException e) {
                // If we get a thread violation, try using entity scheduler as last resort
                try {
                    villager.getScheduler().run(this.plugin, (task) -> {
                        villager.setAware(true);
                        if (this.silentLobotomizedVillagers) {
                            villager.setSilent(false);
                        }
                        // Remove persistent lobotomized marker
                        if (this.persistLobotomizedState) {
                            villager.getPersistentDataContainer().remove(this.lobotomizedKey);
                        }
                    }, null);
                } catch (Exception schedulerException) {
                    // If both fail, log warning but don't crash the shutdown
                    this.logger.warning("Failed to un-lobotomize villager " + villager.getUniqueId() + " during shutdown: " + e.getMessage());
                }
            } catch (Exception e) {
                this.logger.warning("Failed to un-lobotomize villager " + villager.getUniqueId() + " during shutdown: " + e.getMessage());
            }
        }
    }
    
    
    /**
     * Thread-safe wrapper for processing villagers using entity scheduling
     */
    private void processVillagerSafely(@NotNull Villager villager) {
        boolean isActive = this.activeVillagers.contains(villager);
        boolean isInactive = this.inactiveVillagers.contains(villager);
        
        // Skip if villager is not tracked
        if (!isActive && !isInactive) {
            // Cancel the task since villager is no longer tracked
            ScheduledTask task = this.villagerTasks.remove(villager.getUniqueId());
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
            return;
        }
        
        // Process the villager and handle state changes
        if (isActive) {
            boolean shouldRemove = this.processVillager(villager, true);
            if (shouldRemove) {
                this.activeVillagers.remove(villager);
                // Don't cancel the task here as it will be reused for inactive state
            }
        } else {
            boolean shouldRemove = this.processVillager(villager, false);
            if (shouldRemove) {
                this.inactiveVillagers.remove(villager);
                // Don't cancel the task here as it will be reused for active state
            }
        }
    }

    private boolean processVillager(@NotNull Villager villager, boolean active) {
        if (!villager.isValid() || villager.isDead()) {
            // Remove from whatever
            return true;
        }

        Location villagerLocation = villager.getLocation().add(0.0F, 0.51, 0.0F);

        // If the chunk is not loaded, and the villager is not active, we want to add them to the active list
        if (!villager.getWorld().isChunkLoaded(villagerLocation.getBlockX() >> 4, villagerLocation.getBlockZ() >> 4)) {
            return false; // Keep current if chunk is unloaded
        }

        boolean shouldBeActive = this.shouldBeActive(villager);

        if (shouldBeActive) {
            if (!active) {
                // Already running on entity thread, safe to modify villager
                villager.setAware(true);
                if (this.silentLobotomizedVillagers) {
                    villager.setSilent(false);
                }
                // Remove persistent lobotomized marker
                if (this.persistLobotomizedState) {
                    villager.getPersistentDataContainer().remove(this.lobotomizedKey);
                }
                this.activeVillagers.add(villager);
                if (this.plugin.isDebugging()) {
                    this.logger.info("[Debug] Villager " + villager + " (" + villager.getUniqueId() + ") is now active");
                }
                return true; // Remove from inactive list
            }
            if (this.plugin.isDebugging() && !this.plugin.isFolia() && this.plugin.getActiveVillagersTeam() != null) {
                this.plugin.getActiveVillagersTeam().addEntity(villager);
                villager.setGlowing(true);
            }
        } else {
            // Refresh any trades as this villager is inactive
            this.refreshTrades(villager);

            if (active) {
                // Already running on entity thread, safe to modify villager
                villager.setAware(false);
                if (this.silentLobotomizedVillagers) {
                    villager.setSilent(true);
                }
                // Set persistent lobotomized marker
                if (this.persistLobotomizedState) {
                    villager.getPersistentDataContainer().set(this.lobotomizedKey, PersistentDataType.BYTE, (byte) 1);
                }
                this.inactiveVillagers.add(villager);
                if (this.plugin.isDebugging()) {
                    this.logger.info("[Debug] Villager " + villager + " (" + villager.getUniqueId() + ") is now inactive");
                }
                return true; // Remove from active
            }
            if (this.plugin.isDebugging() && !this.plugin.isFolia() && this.plugin.getInactiveVillagersTeam() != null) {
                this.plugin.getInactiveVillagersTeam().addEntity(villager);
                villager.setGlowing(true);
            }
        }
        return false; // Don't mutate anything
    }

    private boolean shouldRestock(@NotNull Villager villager) {
        return VillagerUtils.shouldRestock(
                villager,
                new NamespacedKey(this.plugin, "lastRestockGameTime"),
                new NamespacedKey(this.plugin, "lastRestockCheckDayTime")
        );
    }

    private void refreshTrades(@NotNull Villager villager) {
        if (!villager.getWorld().isDayTime()) {
            // It's night, do not refresh trades
            return;
        }


        if (!VillagerUtils.isJobSiteNearby(villager)) {
            return;
        }

        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        long lastRestock = pdc.getOrDefault(this.key, PersistentDataType.LONG, 0L);

        long now = System.currentTimeMillis();
        if (now - lastRestock > (this.restockInterval - (this.restockRandomRange > 0 ? this.random.nextLong(this.restockRandomRange) : 0)) && shouldRestock(villager)) {
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

            if (this.restockSound != null) {
                villager.getWorld().playSound(villager.getLocation(), this.restockSound, SoundCategory.NEUTRAL, 1.0F, 1.0F);
            } else {
                villager.getWorld().playSound(villager.getLocation(), VillagerUtils.PROFESSION_TO_SOUND.get(villager.getProfession()),
                        SoundCategory.NEUTRAL, 1.0F,
                        1.0F);
            }
        }

        // Derek comment - Not sure if we want to split this level up check, to separate it from refresh trades. Might
        // be better to(?)
        // Lets also see if we need to level up the villager
        int currentLevel = villager.getVillagerLevel();

        // If we're max level, then just return early
        if (currentLevel == 5) {
            return;
        }

        int expectedLevel = VillagerUtils.getVillagerLevel(villager);

        if (currentLevel < expectedLevel) {
            // We can just set the villager level to the expected level
            int increaseAmount = Math.max(0, expectedLevel - currentLevel);
            villager.increaseLevel(increaseAmount);
            if (this.levelUpSound != null) {
                villager.getWorld().playSound(villager.getLocation(), this.levelUpSound, SoundCategory.NEUTRAL, 1.0F, 1.0F);
            }
            PotionEffect regenEffect = new PotionEffect(PotionEffectType.REGENERATION, 200, 0, false);
            villager.addPotionEffect(regenEffect);

            // Write a log message if we're debugging
            if (this.plugin.isDebugging()) {
                this.plugin.getLogger().info("Villager " + villager.getUniqueId() + " was leveled up to level " + expectedLevel + " from level " + currentLevel);
            }
        }
    }

    private boolean canMoveThrough(World w, int x, int y, int z, boolean roof) {
        boolean isChunkLoaded = w.isChunkLoaded(x >> 4, z >> 4);
        if (!isChunkLoaded) {
            return false;
        }
        Block blockAtHead = w.getBlockAt(x, y + 1, z);
        Block blockAtFeet = w.getBlockAt(x, y, z);
        Block blockUnderFeet = w.getBlockAt(x, y - 1, z);

        // First check if the block at the head is solid, if so, then we can't move from here
        boolean isHeadImpassable = this.testImpassable(IMPASSABLE_REGULAR_FLOOR, blockAtHead, false);
        // Next check if the block at the feet is just regular (villagers can walk on carpets)
        boolean isFeetImpassable = this.testImpassable(IMPASSABLE_REGULAR, blockAtFeet, false);
        // Next check if the block under the feet is regular or tall
        boolean isUnderFeetImpassable = this.testImpassable(IMPASSABLE_TALL, blockUnderFeet, true);
        return !isHeadImpassable && !isFeetImpassable && (!roof || !isUnderFeetImpassable);
    }

    /**
     * Tests if a block is impassable, based on the set provided.
     * If onlyTallBlocks is true, it will only check for tall blocks.
     * If false, it will check for all blocks in the set.
     *
     * @param set            The set of materials to check against
     * @param b              The block to test
     * @param onlyTallBlocks If true, only checks tall blocks
     * @return true if the block is impassable, false otherwise
     */
    private boolean testImpassable(@NotNull EnumSet<Material> set, @NotNull Block b, boolean onlyTallBlocks) {
        Material type = b.getType();

        // Skip any extra checks here
        if (set.contains(type)) {
            return true;
        }

        // If we're only checking tall blocks and we reach this part, return false early
        if (onlyTallBlocks) {
            return false;
        }

        boolean isCarpet = type.name().contains("_CARPET");
        boolean isBed = type.name().contains("_BED");
        boolean isWater = type == Material.WATER;
        BlockData blockData = b.getBlockData();
        boolean isCrop = blockData instanceof Ageable;
        boolean isABypassBlock = (isCrop || isBed || isCarpet || DOOR_BLOCKS.contains(type));
        boolean isNonSolid = !type.isSolid() && this.plugin.getConfig().getBoolean("ignore-non-solid-blocks") && !PROFESSION_BLOCKS.contains(type);
        // A block is impassable if:
        // 1. It isn't water and it's not passable
        // 2. AND it's not a bypass block (the early return ensures if it's in our testing set, it's impassable)
        // 3. AND it's in the set
        return (!isWater && !b.isPassable() && !isABypassBlock && !isNonSolid) || set.contains(type);
    }

    private boolean canMoveCardinally(World w, int x, int y, int z, boolean roof) {
        // Essentially check x + 1, x - 1, z + 1, z - 1, and return the or of the results
        // Means as long as there's 1 walkable path it should not be lobotomized
        Boolean xPlusOne = this.canMoveThrough(w, x + 1, y, z, roof);
        Boolean xMinusOne = this.canMoveThrough(w, x - 1, y, z, roof);
        Boolean zPlusOne = this.canMoveThrough(w, x, y, z + 1, roof);
        Boolean zMinusOne = this.canMoveThrough(w, x, y, z - 1, roof);

        return xPlusOne || xMinusOne || zPlusOne || zMinusOne;
    }


    public void handleBlockChange(Block block) {
        if (this.plugin.isDisableChunkVillagerUpdate()) {
            return;
        }
        // Create a list of chunks we'll add to
        List<Chunk> chunksToProcess = new ArrayList<>();
        // Get our chunk
        Chunk chunk = block.getChunk();
        // Put our chunk in the list
        chunksToProcess.add(chunk);

        // We also want to do neighbors if needed
        int blockInChunkX = block.getX() & 0xF;
        int blockInChunkZ = block.getZ() & 0xF;

        World world = block.getWorld();

        // If <= 1 or >= 14 then add the -/+ chunk respectively
        if (blockInChunkX <= 1) {
            Chunk neighbor = world.getChunkAt(chunk.getX() - 1, chunk.getZ());
            chunksToProcess.add(neighbor);
        } else if (blockInChunkX >= 14) {
            Chunk neighbor = world.getChunkAt(chunk.getX() + 1, chunk.getZ());
            chunksToProcess.add(neighbor);
        }

        // Same for Z
        if (blockInChunkZ <= 1) {
            Chunk neighbor = world.getChunkAt(chunk.getX(), chunk.getZ() - 1);
            chunksToProcess.add(neighbor);
        } else if (blockInChunkZ >= 14) {
            Chunk neighbor = world.getChunkAt(chunk.getX(), chunk.getZ() + 1);
            chunksToProcess.add(neighbor);
        }
        for (Chunk c : chunksToProcess) {
            changedChunks.put(c, System.currentTimeMillis());
        }
    }

    private void processChunks() {
        long now = System.currentTimeMillis();
        changedChunks.entrySet().removeIf(entry -> {
            Chunk chunk = entry.getKey();
            long lastChange = entry.getValue();

            // If the chunk hasn't changed in a while, remove it
            if (now - lastChange > 3000) {
                return true;
            }

            // Are we unloaded?
            if (!chunk.isLoaded()) {
                return true;
            }

            // Log a debug
            if (this.plugin.isChunkDebugging()) {
                this.logger.info("[Debug] Processing chunk " + chunk.getX() + ", " + chunk.getZ() + " for villagers");
            }

            // Schedule villager processing for this chunk on the appropriate region
            if (chunk.isLoaded()) {
                this.scheduleChunkVillagerProcessing(chunk);
            }

            // Keep tracked
            return false;
        });
    }
    
    /**
     * Schedules villager processing for a chunk using region-appropriate scheduling
     */
    private void scheduleChunkVillagerProcessing(Chunk chunk) {
        if (!chunk.isLoaded()) {
            return;
        }

        Entity[] entities = chunk.getEntities();
        for (Entity entity : entities) {
            if (entity instanceof Villager villager) {
                // Check if this villager is tracked by us
                if (inactiveVillagers.contains(villager) || activeVillagers.contains(villager)) {
                    // Schedule processing on the villager's region thread using Paper's EntityScheduler
                    try {
                        villager.getScheduler().run(this.plugin, (scheduledTask) -> {
                            boolean isActive = this.activeVillagers.contains(villager);
                            if (this.processVillager(villager, isActive)) {
                                if (this.plugin.isDebugging()) {
                                    this.logger.info("[Debug] Processed villager " + villager + " (" + villager.getUniqueId() + ") in chunk " + chunk.getX() + ", " + chunk.getZ());
                                }
                            }
                        }, null);
                    } catch (IllegalPluginAccessException e) {
                        // Plugin disabled, ignore
                    }
                }
            }
        }
    }


    /**
     * Determines if a villager should be considered "active" based on name, vehicle, profession, and movement.
     *
     * @param villager The villager to check.
     * @return true if the villager should be active, false otherwise.
     */
    private boolean shouldBeActive(Villager villager) {
        Location villagerLoc = villager.getLocation().add(0.0F, 0.51, 0.0F);

        // Check name
        Component customName = villager.customName();
        String villagerName = customName == null ? "" : PlainTextComponentSerializer.plainText().serialize(customName).toLowerCase();

        if (villagerName.contains("nobrain")) {
            return false;
        } else if (exemptNames.contains(villagerName)) {
            return true;
        }

        // Villagers who are lobotomized will just sink

        // Are we currently swimming?
        if (villager.isSwimming()) {
            return true; // Let them keep swimming
        }

        // Is our feet in water? (No idea if #isSwimming() works when lobotomized)
        Block feetBlock = villager.getWorld().getBlockAt(villagerLoc.getBlockX(), villagerLoc.getBlockY(), villagerLoc.getBlockZ());
        Block headBlock = villager.getWorld().getBlockAt(villagerLoc.getBlockX(), villagerLoc.getBlockY() + 1, villagerLoc.getBlockZ());
        if (feetBlock.getType() == Material.WATER || headBlock.getType() == Material.WATER) {
            // If the feet or head block is water, we can consider the villager active
            return true;
        }

        // Is the villager currently sleeping? (A sleeping villager doesn't do anything really anyways)
        if (villager.isSleeping()) {
            return true;
        }

        // Check vehicle
        if (this.lobotomizePassengers && villager.getVehicle() instanceof Vehicle) {
            return false;
        }

        // Check profession
        if (this.onlyProfessions && villager.getProfession() == Villager.Profession.NONE) {
            return true;
        }

        // Check movement
        Material floorBlockMaterial = villager.getWorld().getBlockAt(villagerLoc.getBlockX(), villagerLoc.getBlockY() - 1, villagerLoc.getBlockZ()).getType();
        Block villagerRoof = villager.getWorld().getBlockAt(villagerLoc.getBlockX(), villagerLoc.getBlockY() + 2, villagerLoc.getBlockZ());

        if (this.checkRoof && villagerRoof.getType() == Material.AIR) {
            return true;
        }

        boolean hasRoof = floorBlockMaterial == Material.HONEY_BLOCK || this.testImpassable(IMPASSABLE_ALL, villagerRoof, false);

        return this.canMoveCardinally(villager.getWorld(), villagerLoc.getBlockX(), villagerLoc.getBlockY(), villagerLoc.getBlockZ(), hasRoof);
    }

    private String convertLegacySoundName(String soundName, String configKey) {
        String converted = StringUtils.convertLegacySoundNameFormat(soundName);
        if (!converted.equals(soundName)) {
            this.logger.info("Found legacy sound name in config, converting to new format and saving config.");
            this.plugin.getConfig().set(configKey, converted);
            this.plugin.saveConfig();
        }
        return converted;
    }

    // Removed ActivatorTask and DeactivatorTask classes - replaced with per-villager entity scheduling
}
