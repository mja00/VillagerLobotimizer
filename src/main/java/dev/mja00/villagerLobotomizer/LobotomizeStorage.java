package dev.mja00.villagerLobotomizer;

import dev.mja00.villagerLobotomizer.utils.VillagerUtils;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
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
import org.bukkit.util.BoundingBox;
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
    private final VillagerLobotomizer plugin;
    private final NamespacedKey key;
    private final NamespacedKey chunkKey;
    private final Set<Villager> activeVillagers = Collections.newSetFromMap(new ConcurrentHashMap<>(128));
    private final Set<Villager> inactiveVillagers = Collections.newSetFromMap(new ConcurrentHashMap<>(128));
    private Set<String> exemptNames;
    private long checkInterval;
    private long inactiveCheckInterval;
    private long restockInterval;
    private long restockRandomRange;
    private boolean onlyProfessions;
    private boolean lobotomizePassengers;
    private boolean checkRoof;
    private Sound restockSound;
    private Sound levelUpSound;
    private Logger logger;
    private Random random = new Random();
    // Used to track what chunks we need to trigger updates for
    private final Map<Chunk, Long> changedChunks = new ConcurrentHashMap<>();

    static {
        IMPASSABLE_REGULAR = EnumSet.of(Material.LAVA);
        IMPASSABLE_FLOOR = EnumSet.noneOf(Material.class);
        IMPASSABLE_TALL = EnumSet.noneOf(Material.class);
        DOOR_BLOCKS = EnumSet.noneOf(Material.class);
        for(Material m : Material.values()) {
            if (m.isOccluding()) {
                if (m.name().contains("_CARPET")) {
                    System.out.println("Adding " + m.toString() + " to IMPASSABLE_REGULAR");
                }
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
        String soundName = plugin.getConfig().getString("restock-sound");
        String levelUpSoundName = plugin.getConfig().getString("level-up-sound");
        
        // Convert legacy sound names if needed
        soundName = convertLegacySoundName(soundName, "restock-sound");
        levelUpSoundName = convertLegacySoundName(levelUpSoundName, "level-up-sound");
        
        // If either sound starts with "minecraft:" we can remove that part as we handle it
        if (soundName != null && soundName.startsWith("minecraft:")) {
            soundName = soundName.replace("minecraft:", "");
        }
        if (levelUpSoundName != null && levelUpSoundName.startsWith("minecraft:")) {
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
            if (soundName != null && !soundName.isEmpty()) {
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
            if (levelUpSoundName != null && !levelUpSoundName.isEmpty()) {
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
        this.chunkKey = new NamespacedKey(plugin, "reloadProfessions");
        Bukkit.getScheduler().runTaskTimer(plugin, new DeactivatorTask(), this.checkInterval, this.checkInterval);
        Bukkit.getScheduler().runTaskTimer(plugin, new ActivatorTask(), this.inactiveCheckInterval, this.inactiveCheckInterval);
        Bukkit.getScheduler().runTaskTimer(plugin, this::processChunks, 5L, 5L);
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
            if (this.plugin.isDebugging()) {
                this.logger.info("Un-lobotomizing Villager " + villager.getUniqueId());
            }
            villager.setAware(true);
            return true;
        });
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
                villager.setAware(true);
                this.activeVillagers.add(villager);
                if (this.plugin.isDebugging()) {
                    this.logger.info("[Debug] Villager " + villager + " (" + villager.getUniqueId() + ") is now active");
                }
                return true; // Remove from inactive list
            }
            if (this.plugin.isDebugging() && this.plugin.getActiveVillagersTeam() != null) {
                this.plugin.getActiveVillagersTeam().addEntity(villager);
                villager.setGlowing(true);
            }
            return false; // Already active
        } else {
            // Refresh any trades as this villager is inactive
            this.refreshTrades(villager);

            if (active) {
                villager.setAware(false);
                this.inactiveVillagers.add(villager);
                if (this.plugin.isDebugging()) {
                    this.logger.info("[Debug] Villager " + villager + " (" + villager.getUniqueId() + ") is now inactive");
                }
                return true; // Remove from active
            }
            if (this.plugin.isDebugging() && this.plugin.getInactiveVillagersTeam() != null) {
                this.plugin.getInactiveVillagersTeam().addEntity(villager);
                villager.setGlowing(true);
            }
            return false;
        }
    }

    private boolean allowedToRestock(@NotNull Villager villager) {
        int numberOfRestocksToday = villager.getRestocksToday();
        Long lastRestockGameTime = villager.getPersistentDataContainer().getOrDefault(new NamespacedKey(this.plugin, "lastRestockGameTime"), PersistentDataType.LONG, 0L);
        return numberOfRestocksToday == 0 || numberOfRestocksToday > 2 && villager.getWorld().getGameTime() > lastRestockGameTime + 2400L;
    }

    private boolean shouldRestock(@NotNull Villager villager) {
        // Get their last restock game time from their PDC, or 0
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        long lastRestockGameTime = pdc.getOrDefault(new NamespacedKey(this.plugin, "lastRestockGameTime"), PersistentDataType.LONG, 0L);
        long lastRestockCheckDayTime = pdc.getOrDefault(new NamespacedKey(this.plugin, "lastRestockCheckDayTime"), PersistentDataType.LONG, 0L);
        long gameTime = villager.getWorld().getGameTime();
        boolean gameTimeOverRestockTime = gameTime > lastRestockGameTime;
        long dayTime = villager.getWorld().getTime();
        if (lastRestockCheckDayTime > 0L) {
            long time = lastRestockCheckDayTime / 24000L;
            long dayTimeOverRestockTime = dayTime / 24000L;
            gameTimeOverRestockTime |= dayTimeOverRestockTime > time;
        }
        lastRestockCheckDayTime = dayTime;
        if (gameTimeOverRestockTime) {
            lastRestockGameTime = gameTime;
            villager.setRestocksToday(0);
        }

        // Store our PDC values
        pdc.set(new NamespacedKey(this.plugin, "lastRestockGameTime"), PersistentDataType.LONG, lastRestockGameTime);
        pdc.set(new NamespacedKey(this.plugin, "lastRestockCheckDayTime"), PersistentDataType.LONG, lastRestockCheckDayTime);

        return allowedToRestock(villager) && VillagerUtils.needsToRestock(villager);
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
     * @param set The set of materials to check against
     * @param b The block to test
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
        // Create a list of chunks we'll add to
        List<Chunk> chunksToProcess = new ArrayList<>();
        // Get our chunk
        Chunk chunk = block.getChunk();
        // Put our chunk in the list
        chunksToProcess.add(chunk);

        // Check if our block is a profession block
//        boolean isProfessionBlock = false;
//        for (Material material : PROFESSION_BLOCKS) {
//            if (block.getType() == material) {
//                isProfessionBlock = true;
//                break;
//            }
//        }
//        if (isProfessionBlock) {
//            logger.info("[Debug] Block " + block.getType() + " is a profession block, so we need to process the chunk");
//        }

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
//            if (isProfessionBlock) {
//                PersistentDataContainer pdc = c.getPersistentDataContainer();
//                pdc.set(this.chunkKey, PersistentDataType.BOOLEAN, true);
//            }
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

            // And now process the rest
            for (Map.Entry<Chunk, Long> iEntry : new HashMap<>(changedChunks).entrySet()) {
                Chunk iChunk = iEntry.getKey();
                if (iChunk.isLoaded()) {
                    processVillagersInChunk(iChunk);
                }
            }

            // Keep tracked
            return false;
        });
    }

    private void processVillagersInChunk(Chunk chunk) {
        if (!chunk.isLoaded()) {
            return;
        }

        Entity[] entities = chunk.getEntities();
        for (Entity entity : entities) {
            if (entity instanceof Villager villager) {
               if (inactiveVillagers.contains(villager)) {
                   if (processVillager(villager, false)) {
                       if (plugin.isDebugging()) {
                           logger.info("[Debug] Processed villager " + villager + " (" + villager.getUniqueId() + ") in chunk " + chunk.getX() + ", " + chunk.getZ());
                       }
                   }
               } else if (activeVillagers.contains(villager)) {
                     if (processVillager(villager, true)) {
                          if (plugin.isDebugging()) {
                            logger.info("[Debug] Processed villager " + villager + " (" + villager.getUniqueId() + ") in chunk " + chunk.getX() + ", " + chunk.getZ());
                          }
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

    public final class ActivatorTask implements Runnable {
        public ActivatorTask() {
        }

        public void run() {
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
        public DeactivatorTask() {
        }

        public void run() {
            Set<Villager> toRemove = new HashSet<>();
            for (Villager villager : activeVillagers) {
                if (processVillager(villager, true)) {
                    toRemove.add(villager);
                }
            }
            activeVillagers.removeAll(toRemove);
        }
    }

    private String convertLegacySoundName(String soundName, String configKey) {
        if (soundName != null && soundName.equals(soundName.toUpperCase(Locale.ROOT))) {
            this.logger.info("Found legacy sound name in config, converting to new format and saving config.");
            soundName = soundName.toLowerCase(Locale.ROOT).replace('_', '.');
            // Write this back out into the config
            this.plugin.getConfig().set(configKey, soundName);
            this.plugin.saveConfig();
        }
        return soundName;
    }
}
