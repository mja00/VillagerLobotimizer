package dev.mja00.villagerLobotomizer.policy;

import dev.mja00.villagerLobotomizer.policy.ActivityDecision.BlockVerdict;
import dev.mja00.villagerLobotomizer.policy.ActivityDecision.DirectionTrace;
import dev.mja00.villagerLobotomizer.policy.ActivityDecision.Outcome;
import dev.mja00.villagerLobotomizer.policy.ActivityDecision.Rule;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerActivityPolicyTest {

    private static final BlockSnapshot AIR = new BlockSnapshot(Material.AIR, true, false);
    private static final BlockSnapshot STONE = new BlockSnapshot(Material.STONE, false, true);
    private static final BlockSnapshot WATER = new BlockSnapshot(Material.WATER, true, false);
    private static final BlockSnapshot CARPET = new BlockSnapshot(Material.WHITE_CARPET, false, true);
    private static final BlockSnapshot HONEY = new BlockSnapshot(Material.HONEY_BLOCK, false, true);
    private static final BlockSnapshot DOOR = new BlockSnapshot(Material.OAK_DOOR, false, true);
    private static final BlockSnapshot FENCE = new BlockSnapshot(Material.OAK_FENCE, false, true);
    // Non-solid, non-passable, and not a bypass block (sign-like). Walkable only when ignoreNonSolidBlocks is on.
    private static final BlockSnapshot NON_SOLID = new BlockSnapshot(Material.OAK_SIGN, false, false);
    // A profession block that is also non-solid; must stay blocking even with ignoreNonSolidBlocks on.
    private static final BlockSnapshot LECTERN = new BlockSnapshot(Material.LECTERN, false, false);

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
    private static class TestGrid implements BlockGrid {
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
    void nonSolidNeighbourIsWalkableOnlyWhenIgnoreNonSolidBlocks() {
        // Box the villager in, but put a non-solid, non-passable, non-bypass block on one neighbour's feet.
        TestGrid grid = sealedBox(0, 64, 0).set(1, 64, 0, NON_SOLID);
        // non-solid blocks not ignored -> the block stays impassable -> still inactive
        assertFalse(defaultPolicy().shouldBeActive(villager("", 0, 64, 0), grid));
        // non-solid blocks ignored -> the block becomes walkable -> active
        assertTrue(policy(false, false, false, false, false, true, Set.of())
                .shouldBeActive(villager("", 0, 64, 0), grid));
    }

    @Test
    void professionBlockStaysBlockingEvenWhenIgnoreNonSolidBlocks() {
        // A non-solid profession block (lectern) is carved out of the ignoreNonSolidBlocks bypass,
        // so it must keep blocking movement even with the feature on -> inactive.
        TestGrid grid = sealedBox(0, 64, 0).set(1, 64, 0, LECTERN);
        assertFalse(policy(false, false, false, false, false, true, Set.of())
                .shouldBeActive(villager("", 0, 64, 0), grid));
    }

    @Test
    void checkRoofWithAirAboveForcesActive() {
        // Sealed at feet level, but roof (y+2) is AIR and checkRoof is on -> active.
        assertTrue(policy(false, false, false, true, false, false, Set.of())
                .shouldBeActive(villager("", 0, 64, 0), sealedBox(0, 64, 0)));
    }

    @Test
    void checkRoofTreatsCaveAndVoidAirAsOpen() {
        // Worldgen fills caves with CAVE_AIR and world bounds with VOID_AIR; both are open sky for the roof check.
        VillagerActivityPolicy p = policy(false, false, false, true, false, false, Set.of());
        for (Material air : new Material[] {Material.CAVE_AIR, Material.VOID_AIR}) {
            TestGrid grid = sealedBox(0, 64, 0).set(0, 66, 0, new BlockSnapshot(air, true, false));
            assertTrue(p.shouldBeActive(villager("", 0, 64, 0), grid), air.name());
        }
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

    @Test
    void traceRecordsEveryRuleInOrderForTrappedVillager() {
        ActivityDecision d = defaultPolicy().evaluate(villager("", 0, 64, 0), sealedBox(0, 64, 0));

        assertFalse(d.active());
        assertEquals(Rule.MOVEMENT, d.decidingRule());
        assertEquals(Rule.values().length, d.checks().size());
        for (int i = 0; i < Rule.values().length; i++) {
            assertEquals(Rule.values()[i], d.checks().get(i).rule());
        }
        assertEquals(Outcome.PASSED, outcomeOf(d, Rule.NOBRAIN_NAME));
        assertEquals(Outcome.DISABLED, outcomeOf(d, Rule.IN_VEHICLE));
        assertEquals(Outcome.DISABLED, outcomeOf(d, Rule.ROOF));
        assertEquals(Outcome.LOBOTOMIZED, outcomeOf(d, Rule.MOVEMENT));
        assertTrue(d.summary().contains("trapped"), d.summary());

        assertNotNull(d.movement());
        assertFalse(d.movement().roofed());
        assertEquals(4, d.movement().directions().size());
        for (DirectionTrace dir : d.movement().directions()) {
            assertFalse(dir.open(), dir.direction());
            assertEquals(BlockVerdict.LISTED_IMPASSABLE, dir.feetVerdict(), dir.direction());
            assertEquals(BlockVerdict.PASSABLE, dir.headVerdict(), dir.direction());
        }
    }

    @Test
    void traceNamesTheOpenDirection() {
        TestGrid grid = sealedBox(0, 64, 0).set(1, 64, 0, AIR);
        ActivityDecision d = defaultPolicy().evaluate(villager("", 0, 64, 0), grid);

        assertTrue(d.active());
        assertEquals(Outcome.KEPT_ACTIVE, outcomeOf(d, Rule.MOVEMENT));
        assertTrue(d.decidingCheck().detail().contains("+X (east)"), d.decidingCheck().detail());
        assertTrue(d.movement().directions().get(0).open());
    }

    @Test
    void earlyDecisionMarksLaterRulesNotReachedAndSkipsMovement() {
        ActivityDecision d = policy(false, false, false, true, false, false, Set.of())
                .evaluate(villager("", 0, 64, 0), sealedBox(0, 64, 0));

        assertTrue(d.active());
        assertEquals(Rule.ROOF, d.decidingRule());
        assertEquals(Outcome.KEPT_ACTIVE, outcomeOf(d, Rule.ROOF));
        assertEquals(Outcome.NOT_REACHED, outcomeOf(d, Rule.MOVEMENT));
        assertNull(d.movement());
    }

    @Test
    void nobrainTraceSkipsEverythingElse() {
        ActivityDecision d = defaultPolicy().evaluate(villager("mr nobrain", 0, 64, 0), new TestGrid());

        assertEquals(Rule.NOBRAIN_NAME, d.decidingRule());
        assertEquals(Outcome.LOBOTOMIZED, outcomeOf(d, Rule.NOBRAIN_NAME));
        for (int i = 1; i < d.checks().size(); i++) {
            assertEquals(Outcome.NOT_REACHED, d.checks().get(i).outcome(), d.checks().get(i).rule().name());
        }
    }

    @Test
    void traceExplainsWhyEachNeighbourIsOpen() {
        TestGrid grid = new TestGrid()
                .set(1, 64, 0, CARPET)
                .set(-1, 64, 0, DOOR)
                .set(0, 64, 1, NON_SOLID)
                .unload(0, -1);
        ActivityDecision d = policy(false, false, false, false, true, true, Set.of())
                .evaluate(villager("", 0, 64, 0), grid);

        List<DirectionTrace> dirs = d.movement().directions();
        assertEquals(BlockVerdict.BYPASS, dirs.get(0).feetVerdict());
        assertEquals(BlockVerdict.DOOR_IGNORED, dirs.get(1).feetVerdict());
        assertEquals(BlockVerdict.NON_SOLID_IGNORED, dirs.get(2).feetVerdict());
        assertEquals(BlockVerdict.UNLOADED, dirs.get(3).feetVerdict());
        assertFalse(dirs.get(3).open());
    }

    @Test
    void honeyFloorTraceReportsRoofAndBlockingUnderFeet() {
        TestGrid roofed = new TestGrid()
                .set(0, 63, 0, HONEY)
                .set(1, 63, 0, FENCE)
                .set(-1, 63, 0, FENCE)
                .set(0, 63, 1, FENCE)
                .set(0, 63, -1, FENCE);
        ActivityDecision d = defaultPolicy().evaluate(villager("", 0, 64, 0), roofed);

        assertTrue(d.movement().roofed());
        assertTrue(d.movement().roofReason().contains("honey"), d.movement().roofReason());
        for (DirectionTrace dir : d.movement().directions()) {
            assertEquals(BlockVerdict.LISTED_IMPASSABLE, dir.underFeetVerdict(), dir.direction());
        }
    }

    private static Outcome outcomeOf(ActivityDecision d, Rule rule) {
        return d.checks().stream().filter(c -> c.rule() == rule).findFirst().orElseThrow().outcome();
    }

    /** Standing on a carpet: feet 1/16 up, so a 1.95-tall hitbox tops out 0.0125 into the y+2 layer. */
    private static VillagerState onCarpet(int x, int y, int z) {
        return new VillagerState("", false, false, false, false, 10, x, y, z, y + 0.0625 + 1.95);
    }

    /** Sealed on three sides; +X is clear at feet and head height but has a block one layer higher. */
    private static TestGrid openEastUnderLowCeiling(BlockSnapshot ceiling) {
        return new TestGrid()
                .set(-1, 64, 0, STONE)
                .set(0, 64, 1, STONE)
                .set(0, 64, -1, STONE)
                .set(1, 66, 0, ceiling);
    }

    @Test
    void carpetRaisedVillagerIsTrappedByBlockAboveNeighbourHeadHeight() {
        ActivityDecision d = defaultPolicy().evaluate(onCarpet(0, 64, 0), openEastUnderLowCeiling(STONE));

        assertFalse(d.active());
        DirectionTrace east = d.movement().directions().get(0);
        assertEquals(BlockVerdict.PASSABLE, east.feetVerdict());
        assertEquals(BlockVerdict.PASSABLE, east.headVerdict());
        assertEquals(BlockVerdict.HITBOX_OVERLAP, east.overheadVerdict());
        assertFalse(east.open());
        assertTrue(d.movement().overhang() > 0.012 && d.movement().overhang() < 0.013, "overhang");
    }

    @Test
    void unraisedVillagerIgnoresBlockAboveNeighbourHeadHeight() {
        ActivityDecision d = defaultPolicy().evaluate(villager("", 0, 64, 0), openEastUnderLowCeiling(STONE));

        assertTrue(d.active());
        assertEquals(0.0, d.movement().overhang());
        assertNull(d.movement().directions().get(0).overheadVerdict());
    }

    @Test
    void villagerStandingOnFullBlockIsNotRaised() {
        VillagerState onGround = new VillagerState("", false, false, false, false, 10, 0, 64, 0, 64 + 1.95);
        assertTrue(defaultPolicy().shouldBeActive(onGround, openEastUnderLowCeiling(STONE)));
    }

    @Test
    void raisedVillagerCanPassUnderHighCollisionBlock() {
        // A top slab's collision starts halfway up the block, clear of a hitbox poking 0.0125 into the layer.
        TestGrid grid = new TestGrid() {
            @Override
            public double collisionBottomAt(int x, int y, int z) {
                return x == 1 && y == 66 && z == 0 ? 0.5 : super.collisionBottomAt(x, y, z);
            }
        };
        grid.set(-1, 64, 0, STONE).set(0, 64, 1, STONE).set(0, 64, -1, STONE)
                .set(1, 66, 0, new BlockSnapshot(Material.OAK_SLAB, false, true));

        ActivityDecision d = defaultPolicy().evaluate(onCarpet(0, 64, 0), grid);

        assertTrue(d.active());
        assertEquals(BlockVerdict.CLEARS_HITBOX, d.movement().directions().get(0).overheadVerdict());
    }

    @Test
    void raisedVillagerIsNotBlockedByPassableBlockAboveNeighbour() {
        assertTrue(defaultPolicy().shouldBeActive(onCarpet(0, 64, 0), openEastUnderLowCeiling(AIR)));
    }
}
