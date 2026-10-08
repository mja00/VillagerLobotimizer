package dev.mja00.villagerLobotomizer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MockBukkit runs every region on one thread, so only the same-region and console paths of
 * {@link LobotomizeCommand#reply} can be exercised here; the cross-region hop is not observable.
 */
class LobotomizeCommandReplyTest extends MockBukkitTestBase {

    private VillagerLobotomizer plugin;
    private WorldMock world;

    @BeforeEach
    void loadPlugin() {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
        world = server.addSimpleWorld("test");
    }

    @Test
    void reportIncludesHeroGiftStatus() {
        // "Nobrain" decides the policy before any block is read; BlockMock#isPassable is unimplemented.
        // The persisted marker makes the spawn tracked lobotomized, the only state with a gift timer.
        NamespacedKey markerKey = new NamespacedKey(plugin, LobotomizeStorage.LOBOTOMIZED_KEY);
        Villager villager = world.spawn(new Location(world, 8, 64, 8), Villager.class, v -> {
            v.customName(Component.text("Nobrain"));
            v.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        });
        if (!plugin.getStorage().getLobotomized().contains(villager)) {
            plugin.getStorage().addVillager(villager);
        }
        assertTrue(plugin.getStorage().getLobotomized().contains(villager), "precondition: tracked lobotomized");

        String report = new LobotomizeCommand(plugin).buildVillagerDetails(villager).stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .collect(Collectors.joining("\n"));

        assertTrue(report.contains("Hero gift: no hero seen since it loaded"), report);
    }

    @Test
    void replyReachesAPlayerInTheCurrentRegion() {
        PlayerMock player = server.addPlayer();

        new LobotomizeCommand(plugin).reply(player, Component.text("hello"));
        server.getScheduler().performTicks(1);

        assertEquals("hello", player.nextMessage());
    }

    @Test
    void replyToTheConsoleIsDirect() {
        assertDoesNotThrow(() -> new LobotomizeCommand(plugin).reply(server.getConsoleSender(), Component.text("hello")));
    }
}
