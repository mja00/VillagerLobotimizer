package dev.mja00.villagerLobotomizer.utils;

import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.util.BoundingBox;

public class VillagerUtils {
    public static final Map<Villager.Profession, Material> PROFESSION_TO_STATION;
    public static final Map<Villager.Profession, Sound> PROFESSION_TO_SOUND;

    static {
        Map<Villager.Profession, Material> map = new ConcurrentHashMap<>();
        Map<Villager.Profession, Sound> soundMap = new ConcurrentHashMap<>();

        soundMap.put(Villager.Profession.ARMORER, Sound.ENTITY_VILLAGER_WORK_ARMORER);
        soundMap.put(Villager.Profession.BUTCHER, Sound.ENTITY_VILLAGER_WORK_BUTCHER);
        soundMap.put(Villager.Profession.CARTOGRAPHER, Sound.ENTITY_VILLAGER_WORK_CARTOGRAPHER);
        soundMap.put(Villager.Profession.CLERIC, Sound.ENTITY_VILLAGER_WORK_CLERIC);
        soundMap.put(Villager.Profession.FARMER, Sound.ENTITY_VILLAGER_WORK_FARMER);
        soundMap.put(Villager.Profession.FISHERMAN, Sound.ENTITY_VILLAGER_WORK_FISHERMAN);
        soundMap.put(Villager.Profession.FLETCHER, Sound.ENTITY_VILLAGER_WORK_FLETCHER);
        soundMap.put(Villager.Profession.LEATHERWORKER, Sound.ENTITY_VILLAGER_WORK_LEATHERWORKER);
        soundMap.put(Villager.Profession.LIBRARIAN, Sound.ENTITY_VILLAGER_WORK_LIBRARIAN);
        soundMap.put(Villager.Profession.MASON, Sound.ENTITY_VILLAGER_WORK_MASON);
        soundMap.put(Villager.Profession.SHEPHERD, Sound.ENTITY_VILLAGER_WORK_SHEPHERD);
        soundMap.put(Villager.Profession.TOOLSMITH, Sound.ENTITY_VILLAGER_WORK_TOOLSMITH);
        soundMap.put(Villager.Profession.WEAPONSMITH, Sound.ENTITY_VILLAGER_WORK_WEAPONSMITH);

        map.put(Villager.Profession.ARMORER, Material.BLAST_FURNACE);
        map.put(Villager.Profession.BUTCHER, Material.SMOKER);
        map.put(Villager.Profession.CARTOGRAPHER, Material.CARTOGRAPHY_TABLE);
        map.put(Villager.Profession.CLERIC, Material.BREWING_STAND);
        map.put(Villager.Profession.FARMER, Material.COMPOSTER);
        map.put(Villager.Profession.FISHERMAN, Material.BARREL);
        map.put(Villager.Profession.FLETCHER, Material.FLETCHING_TABLE);
        map.put(Villager.Profession.LEATHERWORKER, Material.CAULDRON);
        map.put(Villager.Profession.LIBRARIAN, Material.LECTERN);
        map.put(Villager.Profession.MASON, Material.STONECUTTER);
        map.put(Villager.Profession.SHEPHERD, Material.LOOM);
        map.put(Villager.Profession.TOOLSMITH, Material.SMITHING_TABLE);
        map.put(Villager.Profession.WEAPONSMITH, Material.GRINDSTONE);

        // Professions with no workstation
        map.put(Villager.Profession.NITWIT, Material.AIR);
        map.put(Villager.Profession.NONE, Material.AIR);
        soundMap.put(Villager.Profession.NITWIT, Sound.ENTITY_VILLAGER_CELEBRATE);
        soundMap.put(Villager.Profession.NONE, Sound.ENTITY_VILLAGER_CELEBRATE);

        // One time registered map
        PROFESSION_TO_SOUND = Collections.unmodifiableMap(soundMap);
        PROFESSION_TO_STATION = Collections.unmodifiableMap(map);
    }

    /**
     * Check for a job site in a 1 block adjacent radius (including diagonals)
     * This checks in a 3 block height box, for a total of 3x3x3 box
     *
     * @param villager Villager entity the check is centered around
     * @return
     */
    public static boolean isJobSiteNearby(Villager villager) {
        Material jobSite = PROFESSION_TO_STATION.get(villager.getProfession());
        if (jobSite == Material.AIR) {
            return false;
        }

        Location loc = villager.getLocation();
        World world = loc.getWorld();
        int baseX = loc.getBlockX();
        int baseY = loc.getBlockY();
        int baseZ = loc.getBlockZ();

        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (world.getBlockAt(baseX + dx, baseY + dy, baseZ + dz).getType() == jobSite) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns the villager level based on experience.
     * https://minecraft.wiki/w/Trading#Level
     */
    public static int getVillagerLevel(Villager villager) {
        int villagerExperience = villager.getVillagerExperience();
        if (villagerExperience >= 250) {
            return 5;
        }
        if (villagerExperience >= 150) {
            return 4;
        }
        if (villagerExperience >= 70) {
            return 3;
        }
        if (villagerExperience >= 10) {
            return 2;
        }
        return 1;
    }

    /**
     * Checks if a villager needs to restock by looking at their trade usage
     *
     * @param villager The villager to check
     * @return true if any of the villager's trades have been used
     */
    public static boolean needsToRestock(Villager villager) {
        for (MerchantRecipe recipe : villager.getRecipes()) {
            if (recipe.getUses() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds particles around a villager for visual effects
     *
     * @param particle The type of particle to spawn
     * @param villager The villager to spawn particles around
     */
    public static void addParticlesAroundSelf(Particle particle, Villager villager) {
        World world = villager.getWorld();
        double scale = 1.0;
        BoundingBox boundingBox = villager.getBoundingBox();
        Random random = new Random();

        // Spawn 5 particles around the villager
        for (int i = 0; i < 5; i++) {
            double d = random.nextGaussian() * 0.02;
            double d1 = random.nextGaussian() * 0.02;
            double d2 = random.nextGaussian() * 0.02;
            // Get a vertical offset above the villager
            double randomY = villager.getY() + boundingBox.getHeight() * random.nextDouble();
            double xScale = (2.0 * random.nextDouble() - 1.0) * scale;
            double randomX = villager.getX() + boundingBox.getWidthX() * xScale;
            double zScale = (2.0 * random.nextDouble() - 1.0) * scale;
            double randomZ = villager.getZ() + boundingBox.getWidthZ() * zScale;
            // Spawn the particle
            world.spawnParticle(particle, randomX, randomY, randomZ, 1, d, d1, d2, 0.0);
        }
    }

    /**
     * Checks if a villager is allowed to restock based on restocks today and last restock game time.
     */
    public static boolean allowedToRestock(Villager villager, NamespacedKey lastRestockFullTimeKey) {
        int numberOfRestocksToday = villager.getRestocksToday();
        // Allow up to 2 restocks per day (vanilla behavior)
        // The cooldown between restocks is handled by the config's restock-interval (wall-clock based)
        // rather than a hardcoded game-time check, which is problematic when doDaylightCycle is disabled
        return numberOfRestocksToday != 2;
    }

    /**
     * Determines if a villager should restock, updating persistent data as needed.
     */
    public static boolean shouldRestock(Villager villager, NamespacedKey lastRestockGameTimeKey, NamespacedKey lastRestockCheckDayTimeKey) {
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        long lastRestockCheckDayTime = pdc.getOrDefault(lastRestockCheckDayTimeKey, org.bukkit.persistence.PersistentDataType.LONG, 0L);
        long fullTime = villager.getWorld().getFullTime();
        
        // Check for new day using Full Time (absolute ticks) to avoid wrapping issues
        if (lastRestockCheckDayTime > 0L) {
            long lastDay = lastRestockCheckDayTime / 24000L;
            long currentDay = fullTime / 24000L;
            if (currentDay > lastDay) {
                villager.setRestocksToday(0);
                pdc.set(lastRestockGameTimeKey, org.bukkit.persistence.PersistentDataType.LONG, 0L);
            }
        }
        
        // Update the last check time to current full time
        pdc.set(lastRestockCheckDayTimeKey, org.bukkit.persistence.PersistentDataType.LONG, fullTime);

        boolean allowed = allowedToRestock(villager, lastRestockGameTimeKey) && needsToRestock(villager);
        
        if (allowed) {
            // Update last restock time to now, so the cooldown works for the next check
            // Using getFullTime() (persistent) instead of getGameTime() (can reset)
            pdc.set(lastRestockGameTimeKey, org.bukkit.persistence.PersistentDataType.LONG, fullTime);
        }
        
        return allowed;
    }
}
