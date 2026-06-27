package dev.mja00.villagerLobotomizer;

import dev.mja00.villagerLobotomizer.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State-machine tests for {@link LobotomizeStorage}. Locks in the {@code stateLock} invariants
 * added in commit {@code a9dd0d1} (the #70 atomicity fix) so a future refactor of the
 * compound mutations cannot regress them silently.
 */
class LobotomizeStorageTest extends MockBukkitTestBase {

    // Built the same way as the production key in LobotomizeStorage's constructor to guarantee
    // namespace alignment under any test runtime (MockBukkit, Paper, etc.).
    private NamespacedKey lobotomizedKey() {
        return new NamespacedKey(plugin, "isLobotomized");
    }

    private VillagerLobotomizer plugin;
    private WorldMock world;

    @BeforeEach
    void loadPlugin() {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
        world = server.addSimpleWorld("test");
    }

    private Villager spawnVillager() {
        return world.spawn(new Location(world, 0, 64, 0), Villager.class);
    }

    private void markAsLobotomized(Villager villager) {
        villager.getPersistentDataContainer().set(lobotomizedKey(), PersistentDataType.BYTE, (byte) 1);
    }

    @Test
    void addVillager_tracks_new_villager_as_active() {
        Villager v = spawnVillager();
        plugin.getStorage().addVillager(v);
        server.getScheduler().performTicks(1);

        assertTrue(plugin.getStorage().getActive().contains(v),
                "a freshly added villager (no PDC marker) should be tracked as active");
        assertFalse(plugin.getStorage().getLobotomized().contains(v),
                "a freshly added villager (no PDC marker) should not be in the lobotomized set");
    }

    @Test
    void addVillager_with_pdc_marker_tracks_as_lobotomized() {
        Villager v = spawnVillager();
        markAsLobotomized(v);
        plugin.getStorage().addVillager(v);
        server.getScheduler().performTicks(1);

        assertTrue(plugin.getStorage().getLobotomized().contains(v),
                "a villager with the persistent lobotomized marker should be tracked as lobotomized");
        assertFalse(plugin.getStorage().getActive().contains(v),
                "a villager with the persistent lobotomized marker should not be in the active set");
    }

    @Test
    void addVillager_then_removeVillager_leaves_villager_in_neither_set() {
        Villager v = spawnVillager();
        plugin.getStorage().addVillager(v);
        server.getScheduler().performTicks(1);
        assertTrue(plugin.getStorage().getActive().contains(v), "precondition: v is tracked as active");

        plugin.getStorage().removeVillager(v);

        assertFalse(plugin.getStorage().getActive().contains(v),
                "removed villager should not be in the active set");
        assertFalse(plugin.getStorage().getLobotomized().contains(v),
                "removed villager should not be in the lobotomized set");
    }

    @Test
    void addVillager_is_idempotent_for_active_villager() {
        Villager v = spawnVillager();
        plugin.getStorage().addVillager(v);
        plugin.getStorage().addVillager(v);
        server.getScheduler().performTicks(2);

        assertEquals(1, plugin.getStorage().getActive().size(),
                "calling addVillager twice should not double-schedule the villager");
    }

    @Test
    void addVillager_is_idempotent_for_lobotomized_villager() {
        Villager v = spawnVillager();
        markAsLobotomized(v);
        plugin.getStorage().addVillager(v);
        plugin.getStorage().addVillager(v);
        server.getScheduler().performTicks(2);

        assertEquals(1, plugin.getStorage().getLobotomized().size(),
                "calling addVillager twice should not double-schedule the lobotomized villager");
    }

    @Test
    void addVillager_keeps_active_and_lobotomized_sets_disjoint() {
        Villager v1 = spawnVillager();
        Villager v2 = spawnVillager();
        markAsLobotomized(v2);

        plugin.getStorage().addVillager(v1);
        plugin.getStorage().addVillager(v2);
        server.getScheduler().performTicks(2);

        assertEquals(1, plugin.getStorage().getActive().size(),
                "exactly one villager should be in the active set");
        assertEquals(1, plugin.getStorage().getLobotomized().size(),
                "exactly one villager should be in the lobotomized set");
        assertTrue(plugin.getStorage().getActive().contains(v1));
        assertTrue(plugin.getStorage().getLobotomized().contains(v2));
        assertFalse(plugin.getStorage().getActive().contains(v2),
                "a lobotomized villager should never be in the active set");
        assertFalse(plugin.getStorage().getLobotomized().contains(v1),
                "an active villager should never be in the lobotomized set");
    }

    /**
     * Defense-in-depth test: the lobotomized path's entity mutation (setAware(false), setSilent(true))
     * must be dispatched through {@code villager.getScheduler()} so the public API is Folia-safe
     * regardless of caller. With the refactored addVillager, the mutation is scheduled, not
     * synchronous: the villager stays aware immediately after addVillager returns, and only
     * becomes unaware after the scheduled task runs.
     */
    @Test
    void addVillager_defers_lobotomize_mutation_to_entity_scheduler() {
        Villager v = spawnVillager();
        markAsLobotomized(v);
        assertTrue(v.isAware(), "precondition: a freshly spawned villager is aware");

        plugin.getStorage().addVillager(v);

        assertTrue(v.isAware(),
                "setAware(false) should be deferred to the entity scheduler, not called synchronously");

        server.getScheduler().performTicks(1);

        assertFalse(v.isAware(),
                "the scheduled entity-scheduler task should have applied setAware(false)");
    }

    @Test
    void flushFalse_dispatches_reeval_that_wakes_villager_in_open_space() {
        // Start with a lobotomized villager in open space: this is the only state the
        // re-eval can transition out of. If the re-eval never runs, the villager stays
        // lobotomized; the assertion below proves the scheduled tick fired.
        Villager v = spawnVillager();
        // The re-eval calls isChunkLoaded before evaluating the policy; in MockBukkit the
        // spawned-villager chunk isn't auto-marked as loaded, so we touch the chunk first.
        v.getWorld().getChunkAt(v.getLocation().getBlockX() >> 4, v.getLocation().getBlockZ() >> 4);
        markAsLobotomized(v);
        plugin.getStorage().addVillager(v);
        server.getScheduler().performTicks(1);
        assertTrue(plugin.getStorage().getLobotomized().contains(v),
                "precondition: v starts shutdown tracked as lobotomized");

        plugin.getStorage().flush(false);
        server.getScheduler().performTicks(2);

        assertTrue(plugin.getStorage().getActive().contains(v),
                "the re-eval should wake a lobotomized villager in open space");
        assertFalse(plugin.getStorage().getLobotomized().contains(v),
                "a woken villager should not be in the lobotomized set");
    }
}
