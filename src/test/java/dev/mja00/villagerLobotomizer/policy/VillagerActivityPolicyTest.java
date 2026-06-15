package dev.mja00.villagerLobotomizer.policy;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerActivityPolicyTest {

    private static final BlockSnapshot AIR = new BlockSnapshot(Material.AIR, true, false);
    private static final BlockSnapshot STONE = new BlockSnapshot(Material.STONE, false, true);
    private static final BlockSnapshot WATER = new BlockSnapshot(Material.WATER, true, false);
    private static final BlockSnapshot CARPET = new BlockSnapshot(Material.WHITE_CARPET, false, true);
    private static final BlockSnapshot HONEY = new BlockSnapshot(Material.HONEY_BLOCK, false, true);
    private static final BlockSnapshot DOOR = new BlockSnapshot(Material.OAK_DOOR, false, true);
    private static final BlockSnapshot FENCE = new BlockSnapshot(Material.OAK_FENCE, false, true);

    /** Minimal hand-built classifier — we control exactly which materials are "impassable". */
    private static BlockClassifier classifier() {
        return new BlockClassifier(
                EnumSet.of(Material.STONE),                                    // impassableRegular
                EnumSet.of(Material.OAK_FENCE),                                // impassableTall
                EnumSet.of(Material.STONE, Material.WHITE_CARPET, Material.OAK_FENCE), // impassableAll
                EnumSet.of(Material.WHEAT),                                    // cropBlocks
                EnumSet.of(Material.OAK_DOOR),                                 // doorBlocks
                EnumSet.of(Material.LECTERN));                                 // professionBlocks
    }

    /** A mutable grid of snapshots; any coordinate not set defaults to AIR. */
    private static final class TestGrid implements BlockGrid {
        private final Map<Long, BlockSnapshot> blocks = new HashMap<>();
        private final Set<Long> unloadedColumns = new java.util.HashSet<>();

        private static long key(int x, int y, int z) {
            return (((long) x) & 0x3FFFFFF) | ((((long) z) & 0x3FFFFFF) << 26) | ((((long) y) & 0xFFF) << 52);
        }

        private static long col(int x, int z) {
            return (((long) x) & 0x3FFFFFF) | ((((long) z) & 0x3FFFFFF) << 26);
        }

        TestGrid set(int x, int y, int z, BlockSnapshot snapshot) {
            blocks.put(key(x, y, z), snapshot);
            return this;
        }

        TestGrid unload(int x, int z) {
            unloadedColumns.add(col(x, z));
            return this;
        }

        @Override
        public BlockSnapshot at(int x, int y, int z) {
            if (unloadedColumns.contains(col(x, z))) {
                return null;
            }
            return blocks.getOrDefault(key(x, y, z), AIR);
        }
    }

    private VillagerActivityPolicy policy(boolean lobotomizePassengers, boolean onlyProfessions,
                                          boolean onlyWithExperience, boolean checkRoof,
                                          boolean ignoreStuckInDoors, boolean ignoreNonSolidBlocks,
                                          Set<String> exemptNames) {
        return new VillagerActivityPolicy(lobotomizePassengers, onlyProfessions, onlyWithExperience,
                checkRoof, ignoreStuckInDoors, ignoreNonSolidBlocks, exemptNames, classifier());
    }

    private VillagerActivityPolicy defaultPolicy() {
        return policy(false, false, false, false, false, false, Set.of());
    }

    private static VillagerState villager(String name, int x, int y, int z) {
        return new VillagerState(name, false, false, false, false, 10, x, y, z);
    }

    /** Surround the four cardinal neighbours of (x,z) at feet level with STONE (a sealed box). */
    private static TestGrid sealedBox(int x, int y, int z) {
        return new TestGrid()
                .set(x + 1, y, z, STONE)
                .set(x - 1, y, z, STONE)
                .set(x, y, z + 1, STONE)
                .set(x, y, z - 1, STONE);
    }

    @Test
    void openSpaceIsActive() {
        // All neighbours default to AIR -> walkable -> active.
        assertTrue(defaultPolicy().shouldBeActive(villager("", 0, 64, 0), new TestGrid()));
    }

    @Test
    void sealedBoxIsInactive() {
        assertFalse(defaultPolicy().shouldBeActive(villager("", 0, 64, 0), sealedBox(0, 64, 0)));
    }

    @Test
    void nobrainNameForcesInactiveEvenInOpenSpace() {
        assertFalse(defaultPolicy().shouldBeActive(villager("mr nobrain", 0, 64, 0), new TestGrid()));
    }

    @Test
    void nobrainNameTakesPrecedenceOverExemptName() {
        // "nobrain" is checked before the exempt-name list, so a name matching both -> inactive.
        VillagerActivityPolicy p = policy(false, false, false, false, false, false, Set.of("nobrain keepme"));
        assertFalse(p.shouldBeActive(villager("nobrain keepme", 0, 64, 0), new TestGrid()));
    }

    @Test
    void exemptNameForcesActiveEvenInSealedBox() {
        VillagerActivityPolicy p = policy(false, false, false, false, false, false, Set.of("keepme"));
        assertTrue(p.shouldBeActive(villager("keepme", 0, 64, 0), sealedBox(0, 64, 0)));
    }

    @Test
    void swimmingForcesActiveInSealedBox() {
        VillagerState v = new VillagerState("", true, false, false, false, 10, 0, 64, 0);
        assertTrue(defaultPolicy().shouldBeActive(v, sealedBox(0, 64, 0)));
    }

    @Test
    void waterAtFeetForcesActiveInSealedBox() {
        TestGrid grid = sealedBox(0, 64, 0).set(0, 64, 0, WATER);
        assertTrue(defaultPolicy().shouldBeActive(villager("", 0, 64, 0), grid));
    }

    @Test
    void sleepingForcesActiveInSealedBox() {
        VillagerState v = new VillagerState("", false, true, false, false, 10, 0, 64, 0);
        assertTrue(defaultPolicy().shouldBeActive(v, sealedBox(0, 64, 0)));
    }

    @Test
    void vehicleForcesInactiveOnlyWhenConfigured() {
        VillagerState inVehicle = new VillagerState("", false, false, true, false, 10, 0, 64, 0);
        // open space, but lobotomizePassengers + in a vehicle -> inactive
        assertFalse(policy(true, false, false, false, false, false, Set.of())
                .shouldBeActive(inVehicle, new TestGrid()));
        // same state, feature off -> falls through to movement -> active
        assertTrue(defaultPolicy().shouldBeActive(inVehicle, new TestGrid()));
    }

    @Test
    void onlyProfessionsKeepsUnemployedActiveInSealedBox() {
        VillagerState none = new VillagerState("", false, false, false, true, 10, 0, 64, 0);
        assertTrue(policy(false, true, false, false, false, false, Set.of())
                .shouldBeActive(none, sealedBox(0, 64, 0)));
    }

    @Test
    void onlyWithExperienceKeepsZeroExpActiveInSealedBox() {
        VillagerState zeroExp = new VillagerState("", false, false, false, false, 0, 0, 64, 0);
        assertTrue(policy(false, false, true, false, false, false, Set.of())
                .shouldBeActive(zeroExp, sealedBox(0, 64, 0)));
    }

    @Test
    void carpetAsFloorOfNeighbourIsWalkable() {
        // One neighbour has a carpet at feet level; carpets are a bypass -> walkable -> active.
        TestGrid grid = sealedBox(0, 64, 0).set(1, 64, 0, CARPET);
        assertTrue(defaultPolicy().shouldBeActive(villager("", 0, 64, 0), grid));
    }

    @Test
    void doorNeighbourIsBypassOnlyWhenIgnoreStuckInDoors() {
        // Box the villager in, but put a door on one neighbour's feet.
        TestGrid grid = sealedBox(0, 64, 0).set(1, 64, 0, DOOR);
        // doors not ignored -> door blocks (a door snapshot is not passable) -> still inactive
        assertFalse(defaultPolicy().shouldBeActive(villager("", 0, 64, 0), grid));
        // doors ignored -> door is a bypass -> walkable -> active
        assertTrue(policy(false, false, false, false, true, false, Set.of())
                .shouldBeActive(villager("", 0, 64, 0), grid));
    }

    @Test
    void checkRoofWithAirAboveForcesActive() {
        // Sealed at feet level, but roof (y+2) is AIR and checkRoof is on -> active.
        assertTrue(policy(false, false, false, true, false, false, Set.of())
                .shouldBeActive(villager("", 0, 64, 0), sealedBox(0, 64, 0)));
    }

    @Test
    void honeyBlockFloorActsAsRoofAndBlocksMovementOverTallBlocks() {
        // A honey block under the villager sets hasRoof=true, which makes canMoveThrough also
        // require the block UNDER each neighbour's feet to be passable. With fences (tall/impassable)
        // under every neighbour, movement is blocked -> inactive.
        TestGrid roofed = new TestGrid()
                .set(0, 63, 0, HONEY)        // villager's floor -> hasRoof = true
                .set(1, 63, 0, FENCE)
                .set(-1, 63, 0, FENCE)
                .set(0, 63, 1, FENCE)
                .set(0, 63, -1, FENCE);
        assertFalse(defaultPolicy().shouldBeActive(villager("", 0, 64, 0), roofed));

        // Control: no honey floor (and open roof) -> hasRoof = false -> under-feet ignored -> active.
        TestGrid open = new TestGrid()
                .set(1, 63, 0, FENCE)
                .set(-1, 63, 0, FENCE)
                .set(0, 63, 1, FENCE)
                .set(0, 63, -1, FENCE);
        assertTrue(defaultPolicy().shouldBeActive(villager("", 0, 64, 0), open));
    }

    @Test
    void unloadedNeighbourCountsAsNotMovable() {
        // Box on three sides; the open side's chunk is unloaded -> not movable -> inactive.
        TestGrid grid = new TestGrid()
                .set(-1, 64, 0, STONE)
                .set(0, 64, 1, STONE)
                .set(0, 64, -1, STONE)
                .unload(1, 0);
        assertFalse(defaultPolicy().shouldBeActive(villager("", 0, 64, 0), grid));
    }

    @Test
    void headHeightCarpetDoesNotBlockMovement() {
        // A carpet at head height is passable (a bypass block) and must NOT trap the villager.
        TestGrid grid = new TestGrid()
                .set(1, 65, 0, CARPET)
                .set(-1, 65, 0, CARPET)
                .set(0, 65, 1, CARPET)
                .set(0, 65, -1, CARPET);
        assertTrue(defaultPolicy().shouldBeActive(villager("", 0, 64, 0), grid));
    }

    @Test
    void headHeightSolidBlockBlocksMovement() {
        // Mirror of headHeightCarpetDoesNotBlockMovement: a SOLID block at head height on every
        // neighbour must still block movement, confirming the head check rejects solids (i.e. the
        // carpet fix narrowed the check to carpets only and did not over-broaden it).
        TestGrid grid = new TestGrid()
                .set(1, 65, 0, STONE)
                .set(-1, 65, 0, STONE)
                .set(0, 65, 1, STONE)
                .set(0, 65, -1, STONE);
        assertFalse(defaultPolicy().shouldBeActive(villager("", 0, 64, 0), grid));
    }
}
