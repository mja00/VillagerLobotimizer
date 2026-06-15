package dev.mja00.villagerLobotomizer.policy;

/**
 * Supplies block snapshots by world coordinate. Implementations return {@code null} when the
 * coordinate's chunk is not loaded/available, which the policy treats as "cannot move there".
 */
@FunctionalInterface
public interface BlockGrid {
    BlockSnapshot at(int x, int y, int z);
}
