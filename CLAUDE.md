VillagerLobotimizer is a Minecraft Paper plugin (Java 21) that disables villager AI when trapped in trading halls. Folia-compatible.

Note: The project is misspelled on purpose. You'll need to use VillagerLobotomizer and not VillagerLobotimizer.

## Commands
- Build: `./gradlew build`
- Test Server: `./gradlew runServer`
- In-game: `/lobotomy info|debug|wake|reload|uninstall`

## Architecture

### Shutdown vs uninstall
`flush(FlushMode.SHUTDOWN)` **preserves** no-AI state and markers when persistence is on, so a restart does not un-lobotomize every trading hall. `FlushMode.RELOAD` still wakes and clears, because the replacement storage rescans immediately. `quiesceForUninstall()` stops everything without touching a villager; `UninstallSweep` owns all entity work from there.

Note `processVillager`'s active branch wakes on every check, not just on transition: a villager tracked active but left asleep would otherwise stay frozen and, with trade prevention on, untradeable.

### Threading
- Per-entity tasks via `EntityScheduler` (thread-safe villager operations)
- Chunk processing via `GlobalRegionScheduler`
- Folia-safe: never touches entities from wrong thread
- `shuttingDown` flag prevents tasks during disable

### Core Files

**VillagerLobotomizer.java** - Main class: lifecycle, config, Folia detection, update checker, debug teams (non-Folia only)

**LobotomizeStorage.java** - Core logic:
- Sets: `activeVillagers`, `inactiveVillagers`
- Maps: `villagerTasks` (UUID→Task), `changedChunks`, `villagerTaskIntervals`
- Lobotomy decision delegated to `policy.VillagerActivityPolicy` (name overrides "nobrain"/exempt, water/vehicle/profession checks, movement check, optional roof check); `LobotomizeStorage` adapts a live `Villager`/`World` into `VillagerState`/`BlockGrid` per check (`villagerStateOf`, `gridOf`)
- Trade refresh: PDC-tracked restock timing, daytime-only, job site proximity, profession sounds, level-up effects

**policy/** - Pure, unit-tested lobotomy decision logic (no Bukkit live objects, scheduler, or shared sets):
- `VillagerActivityPolicy.shouldBeActive(VillagerState, BlockGrid)` - decision rules + `canMoveCardinally`/`canMoveThrough`/`testImpassable`
- `BlockClassifier` - Material sets (`impassableRegular`, `impassableTall`, `impassableAll`, `cropBlocks`, `doorBlocks`, `professionBlocks`), built once via `fromServerRegistry()`
- `BlockSnapshot` (type/passable/solid), `BlockGrid` (coord→snapshot, `null`=unloaded), `VillagerState` (villager properties)

**storage/** - `LobotomizedMarkerStore` (SQLite `state.db`) records which villagers carry the PDC marker, so uninstall can reach ones in unloaded chunks. Invariant: **a row exists exactly when the marker is written**. Writes go through a single intent map mutated via `compute()` and drained on an async task every 5s; suppression in both directions keeps steady-state writes near zero. `org.sqlite` is shaded **unrelocated** (the bundled native has `org/sqlite/core/NativeDB` compiled into its JNI bindings) and opened via `SQLiteDataSource`, never `DriverManager`.

**UninstallSweep.java** - `/lobotomy uninstall confirm`: quiesces storage, restores every loaded villager via its `EntityScheduler`, then walks remaining rows chunk-by-chunk (`getChunkAtAsync` with `generate=false`, 8 in flight, `isOwnedByCurrentRegion` guard then a `RegionScheduler` hop) plus a 3x3 second pass for stale coords. Keeps `state.db` and stays enabled if anything is skipped or unresolved; otherwise deletes it and self-disables.

**EntityListener.java** - Events: `EntityAddToWorldEvent`, `EntityRemoveFromWorldEvent`, `EntityRemoveEvent` (drops the tracking row for non-`UNLOAD` causes; `isDead()`/`isValid()` cannot tell death from unload), `BlockBreakEvent/PlaceEvent` (chunk updates), `InventoryOpenEvent` (optional trade prevention via merchant-inventory check)

**LobotomizeCommand.java** - Brigadier commands registered via `LifecycleEvents.COMMANDS`, permission `lobotomy.command` (op), raycasting for targeting. Wake command clears `isLobotomized` PDC marker. `uninstall` requires an explicit `confirm` literal.

**VillagerUtils.java** - Maps: `PROFESSION_TO_STATION`, `PROFESSION_TO_SOUND`. Methods: `isJobSiteNearby()` (3x3x3 box), `shouldRestock()` (PDC+day-time logic)

### Config (read in constructors)
`check-interval`, `inactive-check-interval`, `restock-interval`, `restock-random-range`, `restock-sound`, `level-up-sound`, `debug`, `chunk-debug`, `create-debug-teams` (Folia-incompatible), `check-roof`, `ignore-non-solid-blocks`, `disable-chunk-villager-updates`, `persist-lobotomized-state` (also gates opening `state.db`; forced off for the session if it cannot be opened)

### PDC Keys
- `lastRestock` (LONG): Last trade refresh timestamp
- `isLobotomized` (BYTE): Persistence marker (when `persist-lobotomized-state: true`). Survives restarts; mirrored by a row in `state.db`. Only ever written/cleared via `setLobotomizedMarker`/`clearLobotomizedMarker`, which keep the row in sync
- `lastRestockCheckDayTime` (LONG): Full game time (absolute ticks) at last restock check; used for day-rollover detection

## Development

### Paper Rules
- Never use NMS (`net.minecraft.*`, `org.bukkit.craftbukkit.*`)
- Thread safety: Use `EntityScheduler` for entities, `GlobalRegionScheduler` for chunks, never touch entities from wrong thread
- Check `chunk.isLoaded()` before accessing entities
- Sounds: `RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT)` with NamespacedKeys
- Legacy sounds: Convert via `StringUtils.convertLegacySoundNameFormat()`

### Patterns
- Early returns, guard clauses
- Gate debug logs: `plugin.isDebugging()`, `plugin.isChunkDebugging()`
- Config changes: Update `src/main/resources/config.yml` with defaults
- New commands: Add to LobotomizeCommand + `plugin.yml` permissions
- PDC keys: `new NamespacedKey(plugin, "keyName")`
