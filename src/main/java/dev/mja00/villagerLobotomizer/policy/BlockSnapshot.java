package dev.mja00.villagerLobotomizer.policy;

import org.bukkit.Material;

/**
 * Immutable view of a block as the activity policy needs it: its material, whether an entity can
 * pass through it (Block#isPassable), and whether the material is solid (Material#isSolid).
 */
public record BlockSnapshot(Material type, boolean passable, boolean solid) {
}
