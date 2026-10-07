package dev.mja00.villagerLobotomizer.policy;

/**
 * Supplies block snapshots by world coordinate. Implementations return {@code null} when the
 * coordinate's chunk is not loaded/available, which the policy treats as "cannot move there".
 */
@FunctionalInterface
public interface BlockGrid {
    /**
     * Retrieves the block snapshot at the specified world coordinates.
     *
     * @param x the x-coordinate
     * @param y the y-coordinate
     * @param z the z-coordinate
     * @return the block snapshot at the specified coordinates, or {@code null} if the corresponding chunk is not loaded or available
     */
    BlockSnapshot at(int x, int y, int z);

    /**
     * The lowest point of the block's collision shape, relative to the block (0 = bottom face, 1 = no
     * collision). Only queried for the few blocks a raised hitbox reaches into. The default treats
     * every non-passable block as full height; live grids read the real collision shape so a top slab
     * or similar high block is not mistaken for one that touches the villager.
     */
    default double collisionBottomAt(int x, int y, int z) {
        BlockSnapshot b = at(x, y, z);
        return b == null || b.passable() ? 1.0 : 0.0;
    }
}
