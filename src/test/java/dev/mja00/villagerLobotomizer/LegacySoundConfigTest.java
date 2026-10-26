package dev.mja00.villagerLobotomizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Legacy enum-style sound names in config are rewritten on load, so a bad rewrite permanently loses the user's value.
 */
class LegacySoundConfigTest extends MockBukkitTestBase {

    private VillagerLobotomizer plugin;

    @BeforeEach
    void loadPlugin() {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
    }

    private void reloadWith(String restockSound, String levelUpSound) {
        plugin.getConfig().set("restock-sound", restockSound);
        plugin.getConfig().set("level-up-sound", levelUpSound);
        plugin.saveConfig();
        plugin.reloadPluginState();
        plugin.reloadConfig();
    }

    @Test
    void multiWordLegacyNameIsSavedAsTheRealRegistryKey() {
        reloadWith("ENTITY_VILLAGER_WORK_LIBRARIAN", "BLOCK_NOTE_BLOCK_PLING");

        assertEquals("entity.villager.work_librarian", plugin.getConfig().getString("restock-sound"));
        assertEquals("block.note_block.pling", plugin.getConfig().getString("level-up-sound"));
    }

    @Test
    void unresolvableLegacyNameIsLeftInConfig() {
        reloadWith("NOT_A_REAL_SOUND", "ALSO_NOT_A_SOUND");

        assertEquals("NOT_A_REAL_SOUND", plugin.getConfig().getString("restock-sound"));
        assertEquals("ALSO_NOT_A_SOUND", plugin.getConfig().getString("level-up-sound"));
    }
    @Test
    void resourcePackLevelUpKeySurvivesReloadWithoutVanillaRegistryLookup() throws Exception {
        reloadWith("", "enthusia_theme:ui.reward");
        var field = LobotomizeStorage.class.getDeclaredField("customLevelUpSound");
        field.setAccessible(true);
        var storageField = VillagerLobotomizer.class.getDeclaredField("storage");
        storageField.setAccessible(true);
        assertEquals("enthusia_theme:ui.reward", field.get(storageField.get(plugin)));
        assertEquals("enthusia_theme:ui.reward", plugin.getConfig().getString("level-up-sound"));
    }
    @Test
    void customLevelUpPlaybackKeepsPositionCategoryAndNeutralMix() {
        reloadWith("", "enthusia_theme:ui.reward");
        var calls = new java.util.ArrayList<Object[]>();
        var world = (org.bukkit.World) java.lang.reflect.Proxy.newProxyInstance(
            org.bukkit.World.class.getClassLoader(), new Class<?>[]{org.bukkit.World.class}, (proxy, method, args) -> {
                if (method.getName().equals("playSound")) { calls.add(args); return null; }
                throw new UnsupportedOperationException(method.getName());
            });
        var location = new org.bukkit.Location(world, 1, 2, 3);
        var villager = (org.bukkit.entity.Villager) java.lang.reflect.Proxy.newProxyInstance(
            org.bukkit.entity.Villager.class.getClassLoader(), new Class<?>[]{org.bukkit.entity.Villager.class}, (proxy, method, args) -> {
                if (method.getName().equals("getWorld")) return world;
                if (method.getName().equals("getLocation")) return location;
                throw new UnsupportedOperationException(method.getName());
            });
        plugin.getStorage().playLevelUpSound(villager);
        assertEquals(1, calls.size());
        org.junit.jupiter.api.Assertions.assertSame(location, calls.getFirst()[0]);
        assertEquals("enthusia_theme:ui.reward", calls.getFirst()[1]);
        assertEquals(org.bukkit.SoundCategory.NEUTRAL, calls.getFirst()[2]);
        assertEquals(1.0f, calls.getFirst()[3]);
        assertEquals(1.0f, calls.getFirst()[4]);
    }
}
