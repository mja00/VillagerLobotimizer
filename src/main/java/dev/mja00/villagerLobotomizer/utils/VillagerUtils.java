package dev.mja00.villagerLobotomizer.utils;

import org.bukkit.*;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.util.BoundingBox;

import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

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
     * This checks in a 2 block height box, for a total of 3x2x3 box
     * @param villager Villager entity the check is centered around
     * @return
     */
    public static boolean isJobSiteNearby (Villager villager) {
        Material jobSite = PROFESSION_TO_STATION.get(villager.getProfession());

        if (jobSite == Material.AIR) return false;

        Location location = villager.getLocation();
        int[] yOffsets = {0, 1}; // feet and body levels
        for (int yOffset : yOffsets) {
            int checkY = location.getBlockY() + yOffset;
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && z == 0) continue;

                    int checkX = location.getBlockX() + x;
                    int checkZ = location.getBlockZ() + z;

                    if (villager.getWorld().getBlockAt(checkX, checkY, checkZ).getType() == jobSite) {
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
    public static boolean allowedToRestock(Villager villager, NamespacedKey lastRestockGameTimeKey) {
        int numberOfRestocksToday = villager.getRestocksToday();
        Long lastRestockGameTime = villager.getPersistentDataContainer().getOrDefault(lastRestockGameTimeKey, org.bukkit.persistence.PersistentDataType.LONG, 0L);
        return numberOfRestocksToday == 0 || numberOfRestocksToday > 2 && villager.getWorld().getGameTime() > lastRestockGameTime + 2400L;
    }

    /**
     * Determines if a villager should restock, updating persistent data as needed.
     */
    public static boolean shouldRestock(Villager villager, NamespacedKey lastRestockGameTimeKey, NamespacedKey lastRestockCheckDayTimeKey) {
        org.bukkit.persistence.PersistentDataContainer pdc = villager.getPersistentDataContainer();
        long lastRestockGameTime = pdc.getOrDefault(lastRestockGameTimeKey, org.bukkit.persistence.PersistentDataType.LONG, 0L);
        long lastRestockCheckDayTime = pdc.getOrDefault(lastRestockCheckDayTimeKey, org.bukkit.persistence.PersistentDataType.LONG, 0L);
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
        pdc.set(lastRestockGameTimeKey, org.bukkit.persistence.PersistentDataType.LONG, lastRestockGameTime);
        pdc.set(lastRestockCheckDayTimeKey, org.bukkit.persistence.PersistentDataType.LONG, lastRestockCheckDayTime);
        return allowedToRestock(villager, lastRestockGameTimeKey) && needsToRestock(villager);
    }
}
