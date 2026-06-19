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
}
