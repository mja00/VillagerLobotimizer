package dev.mja00.villagerLobotomizer.policy;

/**
 * Plain snapshot of the villager properties the activity policy needs. {@code name} is the
 * plain-text custom name, lowercased, or "" when the villager has no custom name. Coordinates are
 * the block the villager occupies (already offset/floored by the caller).
 */
public record VillagerState(
        String name,
        boolean swimming,
        boolean sleeping,
        boolean hasVehicle,
        boolean professionNone,
        int experience,
        int blockX,
        int blockY,
        int blockZ) {
}
