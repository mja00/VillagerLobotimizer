package dev.mja00.villagerLobotomizer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scoreboard.Scoreboard;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Debug teams make every tracked villager glow for all players, so toggling debug on must honor
 * {@code create-debug-teams} the same way enable and reload do.
 */
class DebugTeamsTest extends MockBukkitTestBase {

    private VillagerLobotomizer load(boolean createDebugTeams) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        config.set("create-debug-teams", createDebugTeams);
        return MockBukkit.loadWithConfig(VillagerLobotomizer.class, config);
    }

    @Test
    void togglingDebugOnSkipsTeamsWhenCreateDebugTeamsIsOff() {
        VillagerLobotomizer plugin = load(false);

        plugin.setDebugging(true);

        assertTrue(plugin.isDebugging());
        assertNull(plugin.getActiveVillagersTeam(), "no glowing team when create-debug-teams is false");
        assertNull(plugin.getInactiveVillagersTeam(), "no glowing team when create-debug-teams is false");
        Scoreboard scoreboard = server.getScoreboardManager().getMainScoreboard();
        assertTrue(scoreboard.getTeams().isEmpty(), "no team may be registered on the main scoreboard");
    }

    @Test
    void togglingDebugOnCreatesTeamsWhenCreateDebugTeamsIsOn() {
        VillagerLobotomizer plugin = load(true);

        plugin.setDebugging(true);

        assertNotNull(plugin.getActiveVillagersTeam());
        assertNotNull(plugin.getInactiveVillagersTeam());
    }
}
