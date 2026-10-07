package dev.mja00.villagerLobotomizer.policy;

/**
 * Plain snapshot of the villager properties the activity policy needs. {@code name} is the
 * plain-text custom name, lowercased, or "" when the villager has no custom name. Coordinates are
 * the block the villager occupies (already offset/floored by the caller). {@code bodyTop} is the
 * absolute Y of the top of the villager's hitbox; standing on a carpet or snow layer can lift it
 * into the {@code blockY + 2} layer, where neighbouring blocks then stop sideways movement.
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
        int blockZ,
        double bodyTop) {

    /** A villager whose hitbox stays below {@code blockY + 2}. */
    public VillagerState(String name, boolean swimming, boolean sleeping, boolean hasVehicle,
                         boolean professionNone, int experience, int blockX, int blockY, int blockZ) {
        this(name, swimming, sleeping, hasVehicle, professionNone, experience, blockX, blockY, blockZ, blockY + 2.0);
    }
}
