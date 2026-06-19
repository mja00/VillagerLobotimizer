package dev.mja00.villagerLobotomizer.policy;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Set;

/**
 * Pure decision logic for whether a villager should keep its AI ("active") or be lobotomized
 * ("inactive"). 
 */
public final class VillagerActivityPolicy {

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

    /**
     * Determines whether a villager should be active based on its state and environment.
     *
     * @param v the villager's current state
     * @param grid the surrounding block grid
     * @return {@code true} if the villager should be active, {@code false} if it should be lobotomized
     */
    public boolean shouldBeActive(VillagerState v, BlockGrid grid) {
        String name = v.name();
        if (name.contains("nobrain")) {
            return false;
        } else if (this.exemptNames.contains(name)) {
            return true;
        }

        if (v.swimming()) {
            return true;
        }

        BlockSnapshot feet = grid.at(v.blockX(), v.blockY(), v.blockZ());
        BlockSnapshot head = grid.at(v.blockX(), v.blockY() + 1, v.blockZ());
        if (isWater(feet) || isWater(head)) {
            return true;
        }

        if (v.sleeping()) {
            return true;
        }

        if (this.lobotomizePassengers && v.hasVehicle()) {
            return false;
        }

        if (this.onlyProfessions && v.professionNone()) {
            return true;
        }

        if (this.onlyWithExperience && v.experience() == 0) {
            return true;
        }

        BlockSnapshot floor = grid.at(v.blockX(), v.blockY() - 1, v.blockZ());
        BlockSnapshot roof = grid.at(v.blockX(), v.blockY() + 2, v.blockZ());

        if (this.checkRoof && (roof == null || roof.type() == Material.AIR)) {
            return true;
        }

        Material floorMaterial = floor == null ? Material.AIR : floor.type();
        boolean hasRoof = floorMaterial == Material.HONEY_BLOCK
                || testImpassable(this.blocks.impassableAll(), roof, false);

        return canMoveCardinally(grid, v.blockX(), v.blockY(), v.blockZ(), hasRoof);
    }

    /**
     * Determines whether the villager can move in any cardinal direction from the specified position.
     *
     * @return true if at least one cardinal direction is passable, false otherwise
     */
    private boolean canMoveCardinally(BlockGrid grid, int x, int y, int z, boolean roof) {
        boolean xPlus = canMoveThrough(grid, x + 1, y, z, roof);
        boolean xMinus = canMoveThrough(grid, x - 1, y, z, roof);
        boolean zPlus = canMoveThrough(grid, x, y, z + 1, roof);
        boolean zMinus = canMoveThrough(grid, x, y, z - 1, roof);
        return xPlus || xMinus || zPlus || zMinus;
    }

    /**
     * Determines whether a villager can move through a given block position.
     *
     * @param grid the block grid to query
     * @param roof whether the under-feet block must be passable
     * @return `true` if the villager can pass through the position, `false` otherwise
     */
    private boolean canMoveThrough(BlockGrid grid, int x, int y, int z, boolean roof) {
        BlockSnapshot head = grid.at(x, y + 1, z);
        BlockSnapshot feet = grid.at(x, y, z);
        BlockSnapshot underFeet = grid.at(x, y - 1, z);
        if (head == null || feet == null || underFeet == null) {
            return false;
        }
        boolean isHeadImpassable = testImpassable(this.blocks.impassableRegular(), head, false);
        boolean isFeetImpassable = testImpassable(this.blocks.impassableRegular(), feet, false);
        boolean isUnderFeetImpassable = testImpassable(this.blocks.impassableTall(), underFeet, true);
        return !isHeadImpassable && !isFeetImpassable && (!roof || !isUnderFeetImpassable);
    }

    /**
     * Determines whether a block should be treated as impassable.
     *
     * @param set             materials to classify as impassable
     * @param b               the block to evaluate
     * @param onlyTallBlocks  if true, blocks not in the set are never considered impassable
     * @return                true if the block is impassable, false otherwise
     */
    private boolean testImpassable(EnumSet<Material> set, BlockSnapshot b, boolean onlyTallBlocks) {
        if (b == null) {
            return false;
        }
        Material type = b.type();
        if (set.contains(type)) {
            return true;
        }
        if (onlyTallBlocks) {
            return false;
        }
        boolean isCarpet = type.name().contains("_CARPET");
        boolean isBed = type.name().contains("_BED");
        boolean isWater = type == Material.WATER;
        boolean isCrop = this.blocks.cropBlocks().contains(type);
        boolean isABypassBlock = (isCrop || isBed || isCarpet
                || (this.ignoreStuckInDoors && this.blocks.doorBlocks().contains(type)));
        boolean isNonSolid = !b.solid() && this.ignoreNonSolidBlocks
                && !this.blocks.professionBlocks().contains(type);
        return !isWater && !b.passable() && !isABypassBlock && !isNonSolid;
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
}
