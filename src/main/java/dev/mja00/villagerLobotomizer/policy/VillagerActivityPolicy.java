package dev.mja00.villagerLobotomizer.policy;

import dev.mja00.villagerLobotomizer.policy.ActivityDecision.BlockVerdict;
import dev.mja00.villagerLobotomizer.policy.ActivityDecision.DirectionTrace;
import dev.mja00.villagerLobotomizer.policy.ActivityDecision.MovementTrace;
import dev.mja00.villagerLobotomizer.policy.ActivityDecision.Outcome;
import dev.mja00.villagerLobotomizer.policy.ActivityDecision.Rule;
import dev.mja00.villagerLobotomizer.policy.ActivityDecision.RuleCheck;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Pure decision logic for whether a villager should keep its AI ("active") or be lobotomized
 * ("inactive"). 
 */
public final class VillagerActivityPolicy {

    // Ignores floating-point noise so a villager standing on a full block never counts as raised.
    private static final double OVERHANG_EPSILON = 1.0E-4;

    private final boolean lobotomizePassengers;
    private final boolean onlyProfessions;
    private final boolean onlyWithExperience;
    private final boolean checkRoof;
    private final boolean ignoreStuckInDoors;
    private final boolean ignoreNonSolidBlocks;
    private final Set<String> exemptNames;
    private final BlockClassifier blocks;

    /**
     * Creates a new activity policy with the specified configuration.
     */
    public VillagerActivityPolicy(boolean lobotomizePassengers, boolean onlyProfessions,
                                  boolean onlyWithExperience, boolean checkRoof,
                                  boolean ignoreStuckInDoors, boolean ignoreNonSolidBlocks,
                                  Set<String> exemptNames, BlockClassifier blocks) {
        this.lobotomizePassengers = lobotomizePassengers;
        this.onlyProfessions = onlyProfessions;
        this.onlyWithExperience = onlyWithExperience;
        this.checkRoof = checkRoof;
        this.ignoreStuckInDoors = ignoreStuckInDoors;
        this.ignoreNonSolidBlocks = ignoreNonSolidBlocks;
        // Defensive immutable copy so later caller mutations can't alter policy decisions.
        this.exemptNames = Set.copyOf(exemptNames);
        this.blocks = blocks;
    }

    public BlockClassifier blocks() {
        return this.blocks;
    }

    /**
     * Determines whether a villager should be active based on its state and environment.
     *
     * @param v the villager's current state
     * @param grid the surrounding block grid
     * @return {@code true} if the villager should be active, {@code false} if it should be lobotomized
     */
    public boolean shouldBeActive(VillagerState v, BlockGrid grid) {
        return evaluate(v, grid).active();
    }

    /**
     * Evaluates every rule in order and records why the villager should be active or lobotomized.
     * The first rule that decides wins; later rules are recorded as {@link Outcome#NOT_REACHED}.
     */
    public ActivityDecision evaluate(VillagerState v, BlockGrid grid) {
        Trace trace = new Trace();

        String name = v.name();
        if (name.contains("nobrain")) {
            return trace.decide(Rule.NOBRAIN_NAME, false, "name \"" + name + "\" contains \"nobrain\"");
        }
        trace.pass(Rule.NOBRAIN_NAME, name.isEmpty() ? "no custom name" : "name \"" + name + "\" has no \"nobrain\"");

        if (this.exemptNames.contains(name)) {
            return trace.decide(Rule.EXEMPT_NAME, true, "name \"" + name + "\" is listed");
        }
        trace.pass(Rule.EXEMPT_NAME, name.isEmpty() ? "no custom name" : "name not listed");

        if (v.swimming()) {
            return trace.decide(Rule.SWIMMING, true, "villager is swimming");
        }
        trace.pass(Rule.SWIMMING, "not swimming");

        BlockSnapshot feet = grid.at(v.blockX(), v.blockY(), v.blockZ());
        BlockSnapshot head = grid.at(v.blockX(), v.blockY() + 1, v.blockZ());
        if (isWater(feet) || isWater(head)) {
            return trace.decide(Rule.IN_WATER, true, "water at " + (isWater(feet) ? "feet" : "head"));
        }
        trace.pass(Rule.IN_WATER, "feet " + describe(feet) + ", head " + describe(head));

        if (v.sleeping()) {
            return trace.decide(Rule.SLEEPING, true, "villager is sleeping in a bed");
        }
        trace.pass(Rule.SLEEPING, "not sleeping");

        if (!this.lobotomizePassengers) {
            trace.disabled(Rule.IN_VEHICLE, v.hasVehicle() ? "option off (villager is in a vehicle)" : "option off");
        } else if (v.hasVehicle()) {
            return trace.decide(Rule.IN_VEHICLE, false, "villager is in a boat/minecart");
        } else {
            trace.pass(Rule.IN_VEHICLE, "not in a vehicle");
        }

        if (!this.onlyProfessions) {
            trace.disabled(Rule.NO_PROFESSION, "option off");
        } else if (v.professionNone()) {
            return trace.decide(Rule.NO_PROFESSION, true, "villager has no profession");
        } else {
            trace.pass(Rule.NO_PROFESSION, "has a profession");
        }

        if (!this.onlyWithExperience) {
            trace.disabled(Rule.NO_EXPERIENCE, "option off");
        } else if (v.experience() == 0) {
            return trace.decide(Rule.NO_EXPERIENCE, true, "experience is 0 (never traded with)");
        } else {
            trace.pass(Rule.NO_EXPERIENCE, "experience is " + v.experience());
        }

        BlockSnapshot floor = grid.at(v.blockX(), v.blockY() - 1, v.blockZ());
        BlockSnapshot roof = grid.at(v.blockX(), v.blockY() + 2, v.blockZ());
        int roofY = v.blockY() + 2;

        if (!this.checkRoof) {
            trace.disabled(Rule.ROOF, "option off");
        } else if (roof == null || isAir(roof.type())) {
            return trace.decide(Rule.ROOF, true, "no block above head (y=" + roofY + " is " + describe(roof) + ")");
        } else {
            trace.pass(Rule.ROOF, "roof at y=" + roofY + " is " + describe(roof));
        }

        Material floorMaterial = floor == null ? Material.AIR : floor.type();
        boolean honeyFloor = floorMaterial == Material.HONEY_BLOCK;
        BlockVerdict roofVerdict = classify(this.blocks.impassableAll(), roof, false);
        boolean roofBlocks = roof != null && roofVerdict.blocking();
        boolean hasRoof = honeyFloor || roofBlocks;
        String roofReason;
        if (honeyFloor) {
            roofReason = "standing on a honey block";
        } else if (roofBlocks) {
            roofReason = "roof " + describe(roof) + " is " + roofVerdict.description();
        } else {
            roofReason = "roof " + describe(roof) + " does not block (" + roofVerdict.description() + ")";
        }

        // Standing on a carpet or snow layer lifts a 1.95-tall villager into the y+2 layer, where a
        // ceiling beside it blocks sideways movement even though feet and head height are clear.
        double rawOverhang = v.bodyTop() - (v.blockY() + 2);
        double overhang = rawOverhang > OVERHANG_EPSILON ? rawOverhang : 0.0;

        List<DirectionTrace> directions = List.of(
                traceDirection("+X (east)", grid, v.blockX() + 1, v.blockY(), v.blockZ(), hasRoof, overhang),
                traceDirection("-X (west)", grid, v.blockX() - 1, v.blockY(), v.blockZ(), hasRoof, overhang),
                traceDirection("+Z (south)", grid, v.blockX(), v.blockY(), v.blockZ() + 1, hasRoof, overhang),
                traceDirection("-Z (north)", grid, v.blockX(), v.blockY(), v.blockZ() - 1, hasRoof, overhang));
        MovementTrace movement = new MovementTrace(floor, roof, hasRoof, roofReason, overhang, directions);

        List<String> open = new ArrayList<>();
        for (DirectionTrace d : directions) {
            if (d.open()) {
                open.add(d.direction());
            }
        }
        String detail = open.isEmpty()
                ? "trapped, all 4 directions blocked"
                : "can walk out " + String.join(", ", open);
        return trace.finish(Rule.MOVEMENT, !open.isEmpty(), detail, movement);
    }

    /**
     * Traces whether a villager can move through a given block position.
     *
     * @param roof     whether the under-feet block must be passable
     * @param overhang how far the villager's hitbox reaches into the {@code y + 2} layer, or 0
     */
    private DirectionTrace traceDirection(String direction, BlockGrid grid, int x, int y, int z, boolean roof,
                                          double overhang) {
        BlockSnapshot head = grid.at(x, y + 1, z);
        BlockSnapshot feet = grid.at(x, y, z);
        BlockSnapshot underFeet = grid.at(x, y - 1, z);
        boolean checkOverhead = overhang > 0;
        BlockSnapshot overhead = checkOverhead ? grid.at(x, y + 2, z) : null;
        if (head == null || feet == null || underFeet == null || (checkOverhead && overhead == null)) {
            BlockVerdict overheadVerdict = checkOverhead ? BlockVerdict.UNLOADED : null;
            return new DirectionTrace(direction, x, y, z,
                    head, BlockVerdict.UNLOADED, feet, BlockVerdict.UNLOADED, underFeet, BlockVerdict.UNLOADED,
                    overhead, overheadVerdict, false);
        }
        BlockVerdict headVerdict = classify(this.blocks.impassableRegular(), head, false);
        BlockVerdict feetVerdict = classify(this.blocks.impassableRegular(), feet, false);
        BlockVerdict underFeetVerdict = classify(this.blocks.impassableTall(), underFeet, true);
        BlockVerdict overheadVerdict = checkOverhead
                ? classifyOverhead(grid, overhead, x, y + 2, z, overhang)
                : null;
        boolean open = !headVerdict.blocking() && !feetVerdict.blocking()
                && (!roof || !underFeetVerdict.blocking())
                && (overheadVerdict == null || !overheadVerdict.blocking());
        return new DirectionTrace(direction, x, y, z,
                head, headVerdict, feet, feetVerdict, underFeet, underFeetVerdict,
                overhead, overheadVerdict, open);
    }

    /**
     * Whether a block in the {@code y + 2} layer reaches down far enough to collide with the part of
     * the villager's hitbox that pokes into that layer.
     */
    private static BlockVerdict classifyOverhead(BlockGrid grid, BlockSnapshot b, int x, int y, int z, double overhang) {
        if (b.type() == Material.WATER) {
            return BlockVerdict.WATER;
        }
        return grid.collisionBottomAt(x, y, z) < overhang ? BlockVerdict.HITBOX_OVERLAP : BlockVerdict.CLEARS_HITBOX;
    }

    /**
     * Classifies a block as blocking or open for movement, recording which test decided it.
     *
     * @param set             materials to classify as impassable
     * @param b               the block to evaluate; {@code null} means its chunk is unloaded
     * @param onlyTallBlocks  if true, blocks not in the set are never considered impassable
     */
    private BlockVerdict classify(EnumSet<Material> set, BlockSnapshot b, boolean onlyTallBlocks) {
        if (b == null) {
            return BlockVerdict.UNLOADED;
        }
        Material type = b.type();
        if (set.contains(type)) {
            return BlockVerdict.LISTED_IMPASSABLE;
        }
        if (onlyTallBlocks) {
            return BlockVerdict.NOT_TALL;
        }
        if (type == Material.WATER) {
            return BlockVerdict.WATER;
        }
        if (b.passable()) {
            return BlockVerdict.PASSABLE;
        }
        if (this.blocks.cropBlocks().contains(type) || type.name().contains("_BED")
                || type.name().contains("_CARPET")) {
            return BlockVerdict.BYPASS;
        }
        if (this.ignoreStuckInDoors && this.blocks.doorBlocks().contains(type)) {
            return BlockVerdict.DOOR_IGNORED;
        }
        if (!b.solid() && this.ignoreNonSolidBlocks && !this.blocks.professionBlocks().contains(type)) {
            return BlockVerdict.NON_SOLID_IGNORED;
        }
        return BlockVerdict.NOT_PASSABLE;
    }

    private static String describe(BlockSnapshot b) {
        return b == null ? "unloaded" : b.type().name();
    }

    /**
     * Determines if a block is water.
     *
     * @param  b the block to check
     * @return   {@code true} if the block is water, {@code false} otherwise
     */
    private static boolean isWater(BlockSnapshot b) {
        return b != null && b.type() == Material.WATER;
    }

    // Material#isAir resolves through the server registry, which this pure policy must not depend on.
    private static boolean isAir(Material type) {
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR;
    }

    /** Accumulates one {@link RuleCheck} per rule, in {@link Rule} order. */
    private static final class Trace {
        private final List<RuleCheck> checks = new ArrayList<>(Rule.values().length);

        void pass(Rule rule, String detail) {
            this.checks.add(new RuleCheck(rule, Outcome.PASSED, detail));
        }

        void disabled(Rule rule, String detail) {
            this.checks.add(new RuleCheck(rule, Outcome.DISABLED, detail));
        }

        ActivityDecision decide(Rule rule, boolean active, String detail) {
            return finish(rule, active, detail, null);
        }

        ActivityDecision finish(Rule rule, boolean active, String detail, MovementTrace movement) {
            this.checks.add(new RuleCheck(rule, active ? Outcome.KEPT_ACTIVE : Outcome.LOBOTOMIZED, detail));
            Rule[] rules = Rule.values();
            for (int i = rule.ordinal() + 1; i < rules.length; i++) {
                this.checks.add(new RuleCheck(rules[i], Outcome.NOT_REACHED, ""));
            }
            return new ActivityDecision(active, rule, this.checks, movement);
        }
    }
}
