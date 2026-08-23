package dev.mja00.villagerLobotomizer;

import org.bukkit.Location;
import org.bukkit.entity.Villager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A villager tracked as active but left without AI must be woken. Before this repair the wake only
 * ran on an inactive-to-active transition, so a villager that loaded asleep without a valid marker
 * stayed frozen forever and, with trade prevention on, became untradeable.
 *
 * <p>Only the {@code addVillager} half is covered here. The periodic-check half cannot be tested
 * under MockBukkit: any path reaching the activity policy calls {@code Block#isPassable}, which
 * {@code BlockMock} does not implement, and the resulting exception is reported as a skipped test
 * rather than a failure. Verify that half on a real server.
 */
class ProcessVillagerRepairTest extends MockBukkitTestBase {

    private VillagerLobotomizer plugin;
    private WorldMock world;

    @BeforeEach
    void loadPlugin() {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
        world = server.addSimpleWorld("test");
    }

    @Test
    void addingSleepingVillagerWithoutMarkerWakesItImmediately() {
        Villager villager = world.spawn(new Location(world, 0, 64, 0), Villager.class);
        // Spawning may already have fired an add event through the registered listener.
        plugin.getStorage().removeVillager(villager);
        villager.setAware(false);

        plugin.getStorage().addVillager(villager);

        assertTrue(villager.isAware(),
                "a villager loaded asleep with no marker should be woken as it is tracked");
        assertTrue(plugin.getStorage().getActive().contains(villager),
                "and it should be tracked as active");
    }
}
