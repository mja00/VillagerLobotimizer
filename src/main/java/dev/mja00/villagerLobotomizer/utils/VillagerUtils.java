package dev.mja00.villagerLobotomizer.utils;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataContainer;

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

        PROFESSION_TO_SOUND = Collections.unmodifiableMap(soundMap);
        PROFESSION_TO_STATION = Collections.unmodifiableMap(map);
    }

    /**
     * Collects all workstation materials from the profession-to-station mapping, excluding {@link Material#AIR}.
     * Data is derived from {@link #PROFESSION_TO_STATION} to ensure consistency with the canonical profession map.
     *
     * @return an EnumSet of all workstation materials
     */
    public static java.util.EnumSet<Material> professionStationMaterials() {
        java.util.EnumSet<Material> stations = java.util.EnumSet.noneOf(Material.class);
        for (Material station : PROFESSION_TO_STATION.values()) {
            if (station != Material.AIR) {
                stations.add(station);
            }
        }
        return stations;
    }

    /**
     * Determines if a workstation block for the villager's profession exists within a 3×3×3 cube centered on the villager's location.
     *
     * @param villager the villager to check
     * @return `true` if a matching workstation block is found, `false` otherwise
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
     * Determines if a villager is allowed to restock.
     *
     * @param villager the villager to check
     * @param maxRestocksPerDay the configured daily restock cap (matches the vanilla default of 2)
     * @return true if the villager has fewer than {@code maxRestocksPerDay} restocks today, false otherwise
     */
    public static boolean allowedToRestock(Villager villager, int maxRestocksPerDay) {
        int numberOfRestocksToday = villager.getRestocksToday();
        return numberOfRestocksToday < maxRestocksPerDay;
    }

    /**
     * Determines whether a villager should restock, resetting the daily counter when a new day begins.
     *
     * @param villager the villager to check
     * @param lastRestockCheckDayTimeKey the persistent data key for tracking the last full-time check
     * @param maxRestocksPerDay the configured daily restock cap (matches the vanilla default of 2)
     * @return {@code true} if the villager is allowed to restock today and has recipes requiring restocking,
     *         {@code false} otherwise
     */
    public static boolean shouldRestock(Villager villager, NamespacedKey lastRestockCheckDayTimeKey, int maxRestocksPerDay) {
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        long lastRestockCheckDayTime = pdc.getOrDefault(lastRestockCheckDayTimeKey, org.bukkit.persistence.PersistentDataType.LONG, 0L);
        long fullTime = villager.getWorld().getFullTime();

        // Check for new day using Full Time (absolute ticks) to avoid wrapping issues
        if (lastRestockCheckDayTime > 0L) {
            long lastDay = lastRestockCheckDayTime / 24000L;
            long currentDay = fullTime / 24000L;
            if (currentDay > lastDay) {
                villager.setRestocksToday(0);
            }
        }

        pdc.set(lastRestockCheckDayTimeKey, org.bukkit.persistence.PersistentDataType.LONG, fullTime);

        return allowedToRestock(villager, maxRestocksPerDay) && needsToRestock(villager);
    }
}
