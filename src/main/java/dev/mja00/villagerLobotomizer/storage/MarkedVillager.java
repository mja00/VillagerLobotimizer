package dev.mja00.villagerLobotomizer.storage;

import java.util.UUID;

/**
 * A villager that carries the plugin's persistent lobotomy marker, and where to find it.
 *
 * @param entityId the villager's entity UUID
 * @param worldId  the UUID of the world it was last seen in
 * @param chunkX   chunk X it was last seen in
 * @param chunkZ   chunk Z it was last seen in
 */
public record MarkedVillager(UUID entityId, UUID worldId, int chunkX, int chunkZ) {
}
