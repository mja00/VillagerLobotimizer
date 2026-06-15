package dev.mja00.villagerLobotomizer.policy;

import dev.mja00.villagerLobotomizer.utils.VillagerUtils;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.block.data.Ageable;

import java.util.EnumSet;

/**
 * Holds the precomputed Material sets the activity policy tests membership against.
 */
public record BlockClassifier(
        EnumSet<Material> impassableRegular,
        EnumSet<Material> impassableTall,
        EnumSet<Material> impassableAll,
        EnumSet<Material> cropBlocks,
        EnumSet<Material> doorBlocks,
        EnumSet<Material> professionBlocks) {

    public static BlockClassifier fromServerRegistry() {
        EnumSet<Material> impassableRegular = EnumSet.of(Material.LAVA);
        EnumSet<Material> impassableFloor = EnumSet.noneOf(Material.class);
        EnumSet<Material> impassableTall = EnumSet.noneOf(Material.class);
        EnumSet<Material> doorBlocks = EnumSet.noneOf(Material.class);
        EnumSet<Material> cropBlocks = EnumSet.noneOf(Material.class);

        for (Material m : Registry.MATERIAL) {
            if (m.isOccluding()) {
                impassableRegular.add(m);
            }
            if (m.name().contains("_CARPET")) {
                impassableFloor.add(m);
            }
            if (m.name().contains("_WALL") || m.name().contains("_FENCE")) {
                impassableTall.add(m);
            }
            if (m.name().contains("_DOOR")) {
                doorBlocks.add(m);
            }
            // Identify crops once; createBlockData() can throw for non-standard materials.
            if (m.isBlock()) {
                try {
                    if (m.createBlockData() instanceof Ageable) {
                        cropBlocks.add(m);
                    }
                } catch (Exception ignored) {
                    // Material cannot produce block data; not a crop.
                }
            }
        }

        EnumSet<Material> impassableAll = EnumSet.copyOf(impassableRegular);
        impassableAll.addAll(impassableFloor);
        impassableAll.addAll(impassableTall);

        EnumSet<Material> professionBlocks = VillagerUtils.professionStationMaterials();

        return new BlockClassifier(impassableRegular, impassableTall,
                impassableAll, cropBlocks, doorBlocks, professionBlocks);
    }
}
