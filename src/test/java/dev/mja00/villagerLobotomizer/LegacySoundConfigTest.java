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
}
