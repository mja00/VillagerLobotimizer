package dev.mja00.villagerLobotomizer;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobotomizeStorageTest extends MockBukkitTestBase {

    private VillagerLobotomizer plugin;
    private WorldMock world;
    private NamespacedKey lobotomizedKey;

    @BeforeEach
    void loadPlugin() {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
        world = server.addSimpleWorld("test");
        lobotomizedKey = new NamespacedKey(plugin, "isLobotomized");
    }

    @Test
    void shutdownPreservesLobotomizedStateByDefault() {
        Villager villager = trackedLobotomizedVillager();

        assertFalse(plugin.getConfig().getBoolean("uninstall"),
                "uninstall cleanup should be disabled by default");

        server.getPluginManager().disablePlugin(plugin);

        assertFalse(villager.isAware(), "normal shutdown should preserve disabled villager AI");
        assertTrue(hasLobotomizedMarker(villager),
                "normal shutdown should preserve the persistent lobotomy marker");
    }

    @Test
    void uninstallCleanupWakesVillagerAndRemovesMarker() {
        Villager villager = trackedLobotomizedVillager();
        plugin.getConfig().set("uninstall", true);
        plugin.saveConfig();

        server.getPluginManager().disablePlugin(plugin);

        assertTrue(villager.isAware(), "uninstall cleanup should restore villager AI");
        assertFalse(hasLobotomizedMarker(villager),
                "uninstall cleanup should remove the persistent lobotomy marker");
    }

    @Test
    void shutdownReadsUninstallSettingDirectlyFromConfigFile() throws IOException {
        Villager villager = trackedLobotomizedVillager();
        writeConfigValue("uninstall", true);

        assertFalse(plugin.getConfig().getBoolean("uninstall"),
                "precondition: the in-memory config should still contain the stale value");

        server.getPluginManager().disablePlugin(plugin);

        assertTrue(villager.isAware(), "file-backed uninstall cleanup should restore villager AI");
        assertFalse(hasLobotomizedMarker(villager),
                "file-backed uninstall cleanup should remove the persistent marker");
    }

    @Test
    void shutdownWakesMarkerlessVillagerWhenPersistenceIsDisabled() throws IOException {
        writeConfigValue("persist-lobotomized-state", false);
        plugin.reloadPluginState();

        Villager villager = trackedMarkerlessVillager();
        villager.setAware(false);

        server.getPluginManager().disablePlugin(plugin);

        assertTrue(villager.isAware(),
                "shutdown must wake villagers when no marker can restore them on startup");
        assertFalse(hasLobotomizedMarker(villager));
    }

    @Test
    void addingMarkerlessVillagerRepairsStaleNoAiState() {
        Villager villager = trackedMarkerlessVillager();
        plugin.getStorage().removeVillager(villager);
        villager.setAware(false);

        plugin.getStorage().addVillager(villager);

        assertTrue(villager.isAware(), "active villagers must not retain a stale NoAI state");
        assertTrue(plugin.getStorage().getActive().contains(villager));
    }

    @Test
    void chunkUnloadPreparationWakesVillagerButRetainsMarker() {
        Villager villager = trackedLobotomizedVillager();

        plugin.getStorage().prepareVillagerForUnload(villager);

        assertTrue(villager.isAware(), "villager AI should be restored before chunk serialization");
        assertTrue(hasLobotomizedMarker(villager),
                "the marker should remain so a later chunk load can immediately restore NoAI");
        assertTrue(plugin.getStorage().getLobotomized().contains(villager),
                "unload preparation should not race entity-removal tracking");
    }

    @Test
    void removingLobotomizedVillagerRestoresAiImmediately() {
        Villager villager = trackedLobotomizedVillager();

        plugin.getStorage().removeVillager(villager);

        assertTrue(villager.isAware(), "entity removal should not depend on a next-tick callback");
        assertTrue(hasLobotomizedMarker(villager),
                "entity removal should retain the restart marker");
        assertFalse(plugin.getStorage().getLobotomized().contains(villager));
    }

    @Test
    void uninstallInvalidatesMarkerFromPreviouslyUnloadedVillager() {
        Villager villager = trackedLobotomizedVillager();
        String markerGeneration = villager.getPersistentDataContainer().get(
                lobotomizedKey, PersistentDataType.STRING);
        plugin.getStorage().prepareVillagerForUnload(villager);
        plugin.getStorage().removeVillager(villager);
        plugin.getConfig().set("uninstall", true);
        plugin.saveConfig();

        server.getPluginManager().disablePlugin(plugin);

        assertEquals(markerGeneration, villager.getPersistentDataContainer().get(
                lobotomizedKey, PersistentDataType.STRING),
                "precondition: an unloaded entity marker cannot be removed during shutdown");
        assertNotEquals(markerGeneration, plugin.getLobotomyGeneration(),
                "uninstall should rotate the generation so the unloaded marker becomes stale");
        YamlConfiguration state = YamlConfiguration.loadConfiguration(
                new File(plugin.getDataFolder(), "state.yml"));
        assertEquals(plugin.getLobotomyGeneration(), state.getString("lobotomy-generation"),
                "the rotated generation must survive a later reinstall");
    }

    @Test
    void staleGenerationMarkerIsIgnoredOnLaterLoad() {
        Villager villager = trackedMarkerlessVillager();
        plugin.getStorage().removeVillager(villager);
        villager.getPersistentDataContainer().set(
                lobotomizedKey, PersistentDataType.STRING, "invalidated-generation");
        villager.setAware(false);

        plugin.getStorage().addVillager(villager);

        assertTrue(villager.isAware(), "stale uninstall markers must not restore NoAI");
        assertFalse(hasLobotomizedMarker(villager), "stale uninstall markers should be removed on load");
        assertTrue(plugin.getStorage().getActive().contains(villager));
    }

    private Villager trackedLobotomizedVillager() {
        Villager villager = world.spawn(new Location(world, 0, 64, 0), Villager.class);
        plugin.getStorage().removeVillager(villager);
        villager.getPersistentDataContainer().set(
                lobotomizedKey, PersistentDataType.STRING, plugin.getLobotomyGeneration());
        plugin.getStorage().addVillager(villager);
        return villager;
    }

    private Villager trackedMarkerlessVillager() {
        Villager villager = world.spawn(new Location(world, 0, 64, 0), Villager.class);
        plugin.getStorage().addVillager(villager);
        return villager;
    }

    private boolean hasLobotomizedMarker(Villager villager) {
        return villager.getPersistentDataContainer().has(lobotomizedKey, PersistentDataType.STRING)
                || villager.getPersistentDataContainer().has(lobotomizedKey, PersistentDataType.BYTE);
    }

    private void writeConfigValue(String key, Object value) throws IOException {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration diskConfig = YamlConfiguration.loadConfiguration(configFile);
        diskConfig.set(key, value);
        diskConfig.save(configFile);
    }
}
