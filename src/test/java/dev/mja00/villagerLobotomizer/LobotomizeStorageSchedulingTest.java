package dev.mja00.villagerLobotomizer;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.VillagerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-villager task scheduling and the chunk-update path. Villagers here carry a recording scheduler
 * because MockBukkit's task cancel is unimplemented (any reschedule would skip the test) and its
 * scheduler never returns null. "nobrain" and always-active names decide state without geometry.
 */
class LobotomizeStorageSchedulingTest extends MockBukkitTestBase {

    private static final long CHECK_INTERVAL = 20L;
    private static final long INACTIVE_CHECK_INTERVAL = 1000L;
    private static final String ALWAYS_ACTIVE_NAME = "keepawake";

    private VillagerLobotomizer plugin;
    private WorldMock world;
    private NamespacedKey markerKey;

    @BeforeEach
    void loadPlugin() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        config.set("check-interval", CHECK_INTERVAL);
        config.set("inactive-check-interval", INACTIVE_CHECK_INTERVAL);
        config.set("always-active-names", List.of(ALWAYS_ACTIVE_NAME));
        plugin = MockBukkit.loadWithConfig(VillagerLobotomizer.class, config);
        world = server.addSimpleWorld("test");
        world.loadChunk(0, 0);
        markerKey = new NamespacedKey(plugin, LobotomizeStorage.LOBOTOMIZED_KEY);
    }

    private TestVillager spawnVillager(boolean schedulerRetired) {
        TestVillager villager = new TestVillager(server, schedulerRetired);
        villager.setLocation(new Location(world, 8, 64, 8));
        server.registerEntity(villager);
        // Registration may already have tracked it through the listener; start each test untracked.
        plugin.getStorage().removeVillager(villager);
        return villager;
    }

    private void markLobotomized(Villager villager) {
        villager.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
    }

    private void triggerChunkUpdate(Villager villager) {
        plugin.getStorage().handleBlockChange(villager.getLocation().getBlock());
        server.getScheduler().performTicks(10);
    }

    @Test
    void villagerWhoseSchedulerIsRetiredIsNotLeftTracked() {
        TestVillager villager = spawnVillager(true);

        assertDoesNotThrow(() -> plugin.getStorage().addVillager(villager),
                "a removed entity's null task must not escape and abort a bulk add loop");
        assertFalse(plugin.getStorage().getActive().contains(villager),
                "a villager with no task must not stay in the active set");
        assertFalse(plugin.getStorage().getLobotomized().contains(villager),
                "or in the inactive set");
    }

    @Test
    void chunkUpdateTransitionMovesTaskToNewStateInterval() {
        TestVillager villager = spawnVillager(false);
        markLobotomized(villager);
        plugin.getStorage().addVillager(villager);
        assertEquals(INACTIVE_CHECK_INTERVAL, villager.scheduler.lastPeriod(),
                "precondition: a marked villager is scheduled at the inactive interval");

        villager.customName(Component.text(ALWAYS_ACTIVE_NAME));
        triggerChunkUpdate(villager);

        assertTrue(plugin.getStorage().getActive().contains(villager),
                "precondition: the chunk update woke the villager");
        assertEquals(CHECK_INTERVAL, villager.scheduler.lastPeriod(),
                "a chunk-triggered wake must reschedule at check-interval, not keep the inactive one");
    }

    @Test
    void restockUpdatesDemandBeforeResettingUses() {
        TestVillager villager = spawnVillager(false);
        villager.setProfession(Villager.Profession.LIBRARIAN);
        villager.customName(Component.text("nobrain"));
        MerchantRecipe recipe = new MerchantRecipe(new ItemStack(Material.EMERALD), 12, 12, true);
        villager.setRecipes(List.of(recipe));
        world.getBlockAt(9, 64, 8).setType(Material.LECTERN);
        world.setTime(1000L);
        // A counter reset just before this restock, so only the restock itself can move the key forward.
        NamespacedKey gameTimeKey = new NamespacedKey(plugin, "lastRestockGameTime");
        long seededReset = world.getGameTime();
        villager.getPersistentDataContainer().set(gameTimeKey, PersistentDataType.LONG, seededReset);
        markLobotomized(villager);
        plugin.getStorage().addVillager(villager);

        triggerChunkUpdate(villager);

        assertEquals(List.of(12), villager.usesSeenByUpdateDemand,
                "demand must be computed from the uses traded since the last restock");
        assertEquals(0, villager.getRecipes().get(0).getUses(), "and the restock must still reset uses");
        assertTrue(villager.getPersistentDataContainer().get(gameTimeKey, PersistentDataType.LONG) > seededReset,
                "the frozen-time reset window must restart from this restock, as vanilla's does");
    }

    private static final class TestVillager extends VillagerMock {
        final RecordingScheduler scheduler;
        final List<Integer> usesSeenByUpdateDemand = new ArrayList<>();

        TestVillager(ServerMock server, boolean schedulerRetired) {
            super(server, UUID.randomUUID());
            this.scheduler = new RecordingScheduler(schedulerRetired);
        }

        @Override
        public @NotNull EntityScheduler getScheduler() {
            return this.scheduler;
        }

        @Override
        public void updateDemand() {
            int uses = 0;
            for (MerchantRecipe recipe : getRecipes()) {
                uses += recipe.getUses();
            }
            this.usesSeenByUpdateDemand.add(uses);
        }
    }

    /** Records requested periods; a retired scheduler returns null like Paper's for a removed entity. */
    private static final class RecordingScheduler implements EntityScheduler {
        private final boolean retired;
        private final List<Long> periods = new ArrayList<>();

        RecordingScheduler(boolean retired) {
            this.retired = retired;
        }

        long lastPeriod() {
            return this.periods.getLast();
        }

        private ScheduledTask task(Plugin plugin) {
            return this.retired ? null : new StubTask(plugin);
        }

        @Override
        public boolean execute(@NotNull Plugin plugin, @NotNull Runnable run, Runnable retired, long delay) {
            return !this.retired;
        }

        @Override
        public ScheduledTask run(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, Runnable retired) {
            return task(plugin);
        }

        @Override
        public ScheduledTask runDelayed(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, Runnable retired, long delay) {
            return task(plugin);
        }

        @Override
        public ScheduledTask runAtFixedRate(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, Runnable retired,
                                            long initialDelay, long period) {
            this.periods.add(period);
            return task(plugin);
        }
    }

    private static final class StubTask implements ScheduledTask {
        private final Plugin plugin;
        private boolean cancelled;

        StubTask(Plugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public @NotNull Plugin getOwningPlugin() {
            return this.plugin;
        }

        @Override
        public boolean isRepeatingTask() {
            return true;
        }

        @Override
        public @NotNull CancelledState cancel() {
            this.cancelled = true;
            return CancelledState.CANCELLED_BY_CALLER;
        }

        @Override
        public @NotNull ExecutionState getExecutionState() {
            return this.cancelled ? ExecutionState.CANCELLED : ExecutionState.IDLE;
        }
    }
}
