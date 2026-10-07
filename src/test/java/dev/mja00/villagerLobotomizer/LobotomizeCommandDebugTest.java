package dev.mja00.villagerLobotomizer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Villager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The villagers here are named "nobrain" so the policy decides before reading any block: MockBukkit's
 * BlockMock#isPassable is unimplemented and would turn a geometry-reaching test into a silent skip.
 */
class LobotomizeCommandDebugTest extends MockBukkitTestBase {

    private VillagerLobotomizer plugin;
    private WorldMock world;

    @BeforeEach
    void loadPlugin() {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
        world = server.addSimpleWorld("test");
    }

    private Villager spawnNobrain() {
        Villager villager = world.spawn(new Location(world, 24, 64, 40), Villager.class);
        villager.customName(Component.text("Nobrain"));
        return villager;
    }

    private static String plain(List<Component> lines) {
        return lines.stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .collect(Collectors.joining("\n"));
    }

    @Test
    void reportShowsEveryRuleAndTheDecidingOne() {
        Villager villager = spawnNobrain();
        plugin.getStorage().removeVillager(villager);

        String report = plain(new LobotomizeCommand(plugin).buildVillagerDetails(villager));

        assertTrue(report.contains("Rule result: LOBOTOMIZE"), report);
        assertTrue(report.contains("[LOBOTOMIZE] \"nobrain\" name: name \"nobrain\" contains \"nobrain\""), report);
        assertTrue(report.contains("[SKIP] trapped (movement) check"), report);
        assertTrue(report.contains("Block classification: "), report);
    }

    @Test
    void reportFlagsAnUntrackedVillager() {
        Villager villager = spawnNobrain();
        plugin.getStorage().removeVillager(villager);

        String report = plain(new LobotomizeCommand(plugin).buildVillagerDetails(villager));

        assertTrue(report.contains("Not tracked"), report);
    }

    @Test
    void reportSaysATrackedActiveVillagerWillBeLobotomizedNext() {
        Villager villager = spawnNobrain();
        plugin.getStorage().addVillager(villager);
        assertTrue(plugin.getStorage().getActive().contains(villager), "precondition: tracked active");

        String report = plain(new LobotomizeCommand(plugin).buildVillagerDetails(villager));

        assertTrue(report.contains("Will be lobotomized at its next check"), report);
    }
}
