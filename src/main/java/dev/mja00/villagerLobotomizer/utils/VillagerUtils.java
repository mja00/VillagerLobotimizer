package dev.mja00.villagerLobotomizer.utils;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Villager;

import java.util.Collections;
import java.util.Map;
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
}
