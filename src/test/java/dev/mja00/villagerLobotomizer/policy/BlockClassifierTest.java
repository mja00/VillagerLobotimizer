package dev.mja00.villagerLobotomizer.policy;

import dev.mja00.villagerLobotomizer.MockBukkitTestBase;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockClassifierTest extends MockBukkitTestBase {

    @Test
    void impassableTallHoldsOnlyRealWallsFencesAndGates() {
        BlockClassifier classifier = BlockClassifier.fromServerRegistry();

        assertTrue(classifier.impassableTall().contains(Material.COBBLESTONE_WALL));
        assertTrue(classifier.impassableTall().contains(Material.OAK_FENCE));
        assertTrue(classifier.impassableTall().contains(Material.OAK_FENCE_GATE));

        // Wall-mounted decorations have no collision, so they must not block movement or count as a roof.
        for (Material decoration : new Material[] {Material.SOUL_WALL_TORCH, Material.OAK_WALL_SIGN,
                Material.WHITE_WALL_BANNER, Material.SKELETON_WALL_SKULL, Material.TUBE_CORAL_WALL_FAN}) {
            assertFalse(classifier.impassableTall().contains(decoration), decoration.name());
            assertFalse(classifier.impassableAll().contains(decoration), decoration.name());
        }
    }
}
