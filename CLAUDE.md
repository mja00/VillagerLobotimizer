# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

VillagerLobotimizer is a Minecraft Paper plugin (Java 21) that improves server performance by disabling villager AI when they're trapped in trading halls. The plugin uses Paper's threading model and is Folia-compatible.

## Essential Commands

### Building
```bash
# Build the plugin (first run takes 2-10 minutes)
./gradlew build

# Shadow JAR is automatically built with build task
# Output: build/libs/VillagerLobotimizer-<version>.jar
```

### Testing
```bash
# Run a local Paper 1.21.10 server with the plugin installed
./gradlew runServer

# Commands to use in the server console:
# /lobotomy info - Show villager statistics
# /lobotomy debug toggle - Enable/disable debug mode
# /lobotomy wake - Restore AI to a villager
# /lobotomy reload - Reload configuration
```

### Publishing (Maintainers Only)
```bash
# Publish to Hangar (requires HANGAR_API_KEY env var)
./gradlew publishPluginPublicationToHangar

# Publish to Modrinth (requires MODRINTH_TOKEN env var)
./gradlew modrinth

# Publish to both
./gradlew publishAll
```

## Architecture

### Threading Model
- **Per-Entity Schedulers**: Each villager has its own scheduled task via Paper's `EntityScheduler` for thread-safe operations
- **Global Region Scheduler**: Chunk processing runs on Paper's `GlobalRegionScheduler`
- **Folia Compatibility**: Code is regionized-thread-safe; never touches entities from the wrong thread
- **Shutdown Flag**: `shuttingDown` flag prevents new tasks during disable to avoid exceptions

### Core Components

**VillagerLobotomizer.java** (Main Plugin Class)
- Lifecycle management: config loading, storage initialization, metrics setup
- Folia detection via `io.papermc.paper.threadedregions.RegionizedServer` class check
- Optional debug teams for glowing villagers (disabled on Folia - scoreboard is global state)
- Update checker using async HTTP to Modrinth API
- Server compatibility warnings (checks for dangerous forks, NMS cleanroom, etc.)

**LobotomizeStorage.java** (Core Logic)
- Maintains two concurrent sets: `activeVillagers` and `inactiveVillagers`
- Per-villager task tracking in `villagerTasks` map (UUID → ScheduledTask)
- Chunk change tracking with `changedChunks` map for efficient updates
- **Lobotomy Decision Logic**:
  - Name overrides: "nobrain" forces lobotomy, exempt names (e.g., "alwaysbrain") prevent it
  - Water check: villagers in water stay active (would sink if lobotomized)
  - Vehicle check: optional lobotomy for villagers in boats/minecarts
  - Profession check: optional skip for non-profession villagers
  - Movement check: uses `canMoveCardinally()` to detect if villager is trapped
  - Roof check: optional check for blocks above villager
- **Trade Refresh System**:
  - Uses PDC keys to track last restock time and day time
  - Calls `VillagerUtils.shouldRestock()` to determine if trades should refresh
  - Only restocks during daytime and when job site is nearby
  - Plays profession-specific or custom sounds on restock
  - Handles villager level-up with celebration effects
- **Block Material Sets**: Pre-computed EnumSets for efficient passability checks
  - `IMPASSABLE_REGULAR`: Occluding blocks + lava
  - `IMPASSABLE_FLOOR`: Carpet types
  - `IMPASSABLE_TALL`: Walls and fences
  - `PROFESSION_BLOCKS`: Workstation blocks (lectern, brewing stand, etc.)
  - `DOOR_BLOCKS`: Doors (cleared if `ignore-villagers-stuck-in-doors` is false)

**EntityListener.java** (Event Handlers)
- `EntityAddToWorldEvent`: Adds villagers to tracking on spawn/chunk load
- `EntityRemoveFromWorldEvent`: Removes villagers from tracking on death/unload
- `BlockBreakEvent`/`BlockPlaceEvent`: Triggers chunk updates via `handleBlockChange()`
- `PlayerInteractEntityEvent`: Optional prevention of trading with unlobotomized villagers
- Constructor loops through all loaded worlds/entities to track existing villagers on startup

**LobotomizeCommand.java** (Brigadier Commands)
- Registered via Paper lifecycle `LifecycleEvents.COMMANDS`
- Permission-gated by `lobotomy.command` (default: op)
- Subcommands: info, debug, wake, reload
- Uses raycasting to target villagers for debug/wake commands

**VillagerUtils.java** (Utilities)
- `PROFESSION_TO_WORKSTATION`: Maps Villager.Profession to workstation Materials
- `PROFESSION_TO_SOUND`: Default restock sounds per profession
- `isJobSiteNearby()`: Checks for profession block within 48 blocks
- `shouldRestock()`: Complex day-time based restock logic using PDC and world time

### Configuration System
All config values are read in constructors (VillagerLobotomizer and LobotomizeStorage). Key settings:
- `check-interval`: Ticks between trapped checks for active villagers
- `inactive-check-interval`: Ticks between checks for inactive villagers
- `restock-interval`: Milliseconds between trade restocks
- `restock-random-range`: Randomization window before restock
- `restock-sound`/`level-up-sound`: Custom sound keys (empty = default)
- `debug`/`chunk-debug`: Enable verbose logging
- `create-debug-teams`: Enable glowing villager teams (Folia-incompatible)
- `check-roof`: Require roof above villager before lobotomizing
- `ignore-non-solid-blocks`: Don't lobotomize villagers surrounded by non-solid blocks
- `disable-chunk-villager-updates`: Skip chunk change tracking entirely

### Persistent Data Container (PDC) Keys
- `lastRestock` (LONG): Timestamp of last trade refresh (in LobotomizeStorage)
- `lastRestockGameTime` (LONG): Game time at last restock (in VillagerUtils)
- `lastRestockCheckDayTime` (LONG): Day time at last restock check (in VillagerUtils)

## Development Guidelines

### Paper/Bukkit Rules
- **Never use NMS**: Do not import `net.minecraft.*` or `org.bukkit.craftbukkit.*`
- **Thread Safety**:
  - Always use Paper's EntityScheduler for per-entity operations
  - Use GlobalRegionScheduler for chunk/region operations
  - Never touch entities from the wrong thread
- **Chunk Loading**: Always check `chunk.isLoaded()` before accessing entities
- **Sound Registry**: Use `RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT)` with NamespacedKeys
- **Legacy Sound Names**: Convert legacy `ENTITY_VILLAGER_YES` format to `entity.villager.yes` using `StringUtils.convertLegacySoundNameFormat()`

### Code Patterns
- **Early Returns**: Use guard clauses to reduce nesting
- **Logging**: Gate debug logs behind `plugin.isDebugging()` or `plugin.isChunkDebugging()`
- **Config Changes**: Always add defaults to `src/main/resources/config.yml` with comments
- **New Commands**: Add to LobotomizeCommand and update permissions in `plugin.yml`
- **New PDC Keys**: Use `new NamespacedKey(plugin, "keyName")` to avoid collisions

### Testing Procedures
1. Build with `./gradlew build` (may take 2-10 minutes on first run)
2. Start server with `./gradlew runServer`
3. Test commands: `/lobotomy info`, `/lobotomy debug toggle`
4. Create test scenario: spawn villagers in a confined space
5. Verify villagers become inactive (check logs if debug enabled)
6. Break blocks around villagers and verify they become active again
7. Test trading with inactive villagers to ensure it still works

### Version Management
- Plugin version is defined in `build.gradle.kts` as `version = "1.11.3"`
- `plugin.yml` version is auto-replaced via resource filtering: `version: '${version}'`
- When bumping version:
  - Patch version for bug fixes and minor changes
  - Minor version for new features
  - Update `version` in `build.gradle.kts` only
- Tagged commits (e.g., `v1.11.3`) trigger Release builds; untagged trigger Snapshot builds

### Known Limitations
- Debug teams (glowing villagers) don't work on Folia due to global scoreboard state
- Sandboxed environments may fail builds due to `repo.papermc.io` network restrictions
- Plugman compatibility: Commands break after reload (Brigadier limitation)
- Villagers may remain lobotomized after plugin removal if removed without clean shutdown

## Important Conventions

### Shutdown Cleanup
- `LobotomizeStorage.flush()` sets `shuttingDown` flag to prevent new task scheduling
- Cancels all per-villager tasks before unloading
- Restores villager awareness with fallback to EntityScheduler if direct access fails
- `VillagerLobotomizer.onDisable()` calls flush() and cleans up debug teams

### Folia-Specific Handling
- Check `plugin.isFolia()` before using scoreboard teams
- Use EntityScheduler for per-entity operations (auto-routes to correct region thread)
- Use GlobalRegionScheduler for chunk processing
- Avoid global state mutations

### Async Operations
- Update checks run on `Bukkit.getAsyncScheduler()` with 5-second timeout
- Never touch entities, chunks, or world state from async tasks
- Return to entity scheduler for any entity modifications
- Always commit using conventional commits. If you need a refresher you can view the site here: https://www.conventionalcommits.org/en/v1.0.0/