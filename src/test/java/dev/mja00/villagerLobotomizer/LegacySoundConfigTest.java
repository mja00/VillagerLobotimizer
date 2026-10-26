package dev.mja00.villagerLobotomizer;

import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Villager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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
    void resourcePackKeysAreKeptInConfigAndPlayedVerbatim() {
        reloadWith("my_pack:ui.restock", "my_pack:ui.reward");

        assertEquals("my_pack:ui.restock", plugin.getConfig().getString("restock-sound"));
        assertEquals("my_pack:ui.reward", plugin.getConfig().getString("level-up-sound"));
        assertPlaysOnce("my_pack:ui.restock", villager -> plugin.getStorage().playRestockSound(villager));
        assertPlaysOnce("my_pack:ui.reward", villager -> plugin.getStorage().playLevelUpSound(villager));
    }

    private static void assertPlaysOnce(String sound, Consumer<Villager> play) {
        List<Object[]> calls = new ArrayList<>();
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> {
            if (method.getName().equals("playSound")) {
                calls.add(args);
                return null;
            }
            throw new UnsupportedOperationException(method.getName());
        });
        Location location = new Location(world, 1, 2, 3);
        Villager villager = (Villager) Proxy.newProxyInstance(Villager.class.getClassLoader(), new Class<?>[]{Villager.class}, (proxy, method, args) -> {
            if (method.getName().equals("getWorld")) {
                return world;
            }
            if (method.getName().equals("getLocation")) {
                return location;
            }
            throw new UnsupportedOperationException(method.getName());
        });

        play.accept(villager);

        assertEquals(1, calls.size());
        assertSame(location, calls.getFirst()[0]);
        assertEquals(sound, calls.getFirst()[1]);
        assertEquals(SoundCategory.NEUTRAL, calls.getFirst()[2]);
        assertEquals(1.0f, calls.getFirst()[3]);
        assertEquals(1.0f, calls.getFirst()[4]);
    }
}
