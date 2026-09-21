package dev.mja00.villagerLobotomizer;

import dev.mja00.villagerLobotomizer.storage.LobotomizedMarkerStore;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uninstall with {@code persist-lobotomized-state: false}. The state file is not opened at enable
 * in that mode, but it can still hold rows from an earlier session — and those rows are the only
 * record of villagers that are frozen on disk in unloaded chunks. The sweep must open and read the
 * file rather than degrade into "no rows", which would report a clean uninstall while deleting it.
 */
class UninstallSweepWithoutPersistenceTest extends MockBukkitTestBase {

    private static final int TICKS_TO_FINISH = 50;

    @Test
    void sweepReadsRowsLeftByAnEarlierPersistEnabledSession() throws Exception {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        config.set("persist-lobotomized-state", false);
        VillagerLobotomizer plugin = MockBukkit.loadWithConfig(VillagerLobotomizer.class, config);
        assertNull(plugin.getMarkerStore(), "precondition: persistence off leaves the store unopened");

        Path stateFile = plugin.getDataFolder().toPath()
                .resolve(LobotomizedMarkerStore.DATABASE_FILE_NAME);
        LobotomizedMarkerStore seeder = new LobotomizedMarkerStore(stateFile, plugin.getLogger());
        assertTrue(seeder.open());
        // A world that is not loaded: unreachable from memory, only the row can find this villager.
        seeder.markerWritten(UUID.randomUUID(), UUID.randomUUID(), 0, 0);
        seeder.drainNow();
        seeder.close();
        assertTrue(Files.exists(stateFile), "precondition: rows from the earlier session exist");

        assertTrue(plugin.startUninstall(server.getConsoleSender()),
                "the uninstall must start even with persistence off");
        server.getScheduler().performTicks(TICKS_TO_FINISH);

        LobotomizedMarkerStore store = plugin.getMarkerStore();
        assertNotNull(store, "the sweep must open the state file instead of assuming it is empty");
        assertEquals(1, store.loadAll().size(),
                "an unread row must be seen and kept, never silently deleted");
        assertTrue(Files.exists(stateFile), "the state file must survive an unclean sweep");
        assertTrue(plugin.isEnabled(), "an unclean sweep keeps the plugin enabled for a re-run");
    }
}
