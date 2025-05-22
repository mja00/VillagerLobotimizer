package dev.mja00.villagerLobotomizer.utils;

import org.bukkit.Material;
import org.bukkit.entity.Villager;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VillagerUtils {
    public static final Map<Villager.Profession, Material> PROFESSION_TO_STATION;

    static {
        Map<Villager.Profession, Material> map = new ConcurrentHashMap<>();

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
        map.put(Villager.Profession.NITWIT, null);
        map.put(Villager.Profession.NONE, null);

        // One time registered map
        PROFESSION_TO_STATION = Collections.unmodifiableMap(map);
    }
}
