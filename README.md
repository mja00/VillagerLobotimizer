![image](https://github.com/user-attachments/assets/2716b281-48a6-4a1a-9305-0560684d4e0f)

# VillagerLobotimizer

A Minecraft Paper plugin that improves server performance by turning off villagers' AI when they're confined to trading halls.

<a href="https://modrinth.com/plugin/villagerlobotomy" target="_blank" rel="noopener noreferrer"><img alt="modrinth" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg"></a> <a href="https://hangar.papermc.io/mja00/VillagerLobotimizer"><img alt="hangar" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/hangar_vector.svg"></a>

![](https://img.shields.io/bstats/players/25704?color=green?label=Players) ![](https://img.shields.io/bstats/servers/25704?color=green?label=Servers) ![](https://modrinth.roughness.technology/full_villagerlobotomy_downloads.svg)

## Features

- Automatically detects when villagers are trapped in trading halls
- Disables AI for trapped villagers to improve server performance
- Maintains villager trading functionality while AI is disabled
- Automatically refreshes villager trades on a configurable schedule with randomization support
- Profession-specific restock sounds and level-up celebrations
- Optimized job site detection for better performance
- Night-time trade refresh functionality
- Allows naming villagers to control their behavior ("nobrain", "alwaysbrain")
- Debug mode for troubleshooting
- Command system for checking villager status and managing the plugin

## Commands

- `/lobotomy info` - Shows statistics about lobotomized and active villagers
- `/lobotomy debug` - Shows detailed information about the villager you're looking at
- `/lobotomy debug <entity>` - Shows detailed information about a specific villager
- `/lobotomy debug toggle` - Toggles debug mode
- `/lobotomy wake` - Manually restores AI to the villager you're looking at
- `/lobotomy reload` - Reloads the configuration and applies changes to all villagers

## Configuration

```yaml
#Configuration version - DO NOT MODIFY MANUALLY
config-version: 1

#List of names that will always keep villagers active (case-insensitive)
always-active-names:
  - "alwaysbrain"

#Interval between trapped checks, in ticks, for active villagers
check-interval: 150

#Interval between trapped checks, in ticks, for inactive villagers
inactive-check-interval: 150

#Interval between villager trade restocks, in milliseconds
restock-interval: 540000

#Range (in milliseconds) before restock-interval to start random restock checks. If set to 0, restocking is not randomized. If equal to or greater than restock-interval, restock will always occur.
restock-random-range: 0

#Whether to only lobotomize villagers with jobs
only-lobotomize-villagers-with-professions: false

#Whether to lobotomize villagers in boats/minecarts. Does not apply to villagers riding on non-vehicle entities like horses.
always-lobotomize-villagers-in-vehicles: false

#The sound to play when a villager restocks. Leave empty ("") for default sounds.
#A list of sounds can be found at https://jd.papermc.io/paper/1.21.6/io/papermc/paper/registry/keys/SoundEventKeys.html
#Use the name found in the description column, e.g. "entity.villager.celebrate" for the sound played when a villager restocks.
restock-sound: ""

#The sound played when a villager is leveled up. Leave empty ("") for no sound.
level-up-sound: "entity.villager.celebrate"

#Debug mode. Prints debug messages to the console.
debug: false

#Chunk debug mode. Prints debug messages related to chunks
chunk-debug: false

#To ignore villagers stuck in doors, set this to true.
ignore-villagers-stuck-in-doors: false

#To not lobotomize villagers surrounded by non-solid blocks, set this to true.
ignore-non-solid-blocks: false

#To check if there is a roof above a villager before lobotomizing, set this to true
check-roof: true

#Create teams for debugging purposes. This will create colored teams for inactive and active villagers. We use this to color their glowing effect.
create-debug-teams: false

#Disable the update checker. You can disable this if you don't want to be notified about updates.
disable-update-checker: false

#Disable chunk forced Villager updating. This'll disable changes to blocks in a chunk from triggering Villagers in the chunk to be updated.
disable-chunk-villager-updates: false

#Prevent trading with unlobotomized villagers. When enabled, players can only trade with villagers that have been lobotomized.
prevent-trading-with-unlobotomized-villagers: false
```

## Special Villager Names

- Name a villager with "nobrain" to force it to always be lobotomized
- Name a villager with "alwaysbrain" to prevent it from ever being lobotomized (configurable in `always-active-names`)

## Sound System

The plugin features an enhanced sound system:
- **Default restock sounds**: When `restock-sound` is left empty, villagers will play default sounds when restocking
- **Customizable sounds**: You can override the default sounds by specifying a custom sound in the configuration
- **Sound reference**: A complete list of available sounds can be found in the [Paper API documentation](https://jd.papermc.io/paper/1.21.6/io/papermc/paper/registry/keys/SoundEventKeys.html)
- **Level-up celebrations**: Villagers play celebration sounds when they level up their trades

## Requirements

- Paper (or its forks) 1.21.6+
- Java Development Kit (JDK) 21 for development

## Installation

1. Download the latest release from [Modrinth](https://modrinth.com/plugin/villagerlobotomy) or [Hangar](https://hangar.papermc.io/mja00/VillagerLobotimizer)
2. Place the .jar file in your server's plugins folder
3. Restart your server or use a plugin manager to load the plugin
4. Configure the plugin settings in `plugins/VillagerLobotimizer/config.yml` if needed

## Development

### Prerequisites

- Java Development Kit (JDK) 21
- Gradle (wrapper included)

### Building

1. Clone the repository
   ```bash
   git clone https://github.com/mja00/VillagerLobotimizer.git
   cd VillagerLobotimizer
   ```

2. Build the plugin
   ```bash
   ./gradlew build
   ```
   The built plugin will be in `build/libs/VillagerLobotimizer-<version>.jar`

### Running a test server

The project uses the run-paper plugin to easily test changes:

```bash
./gradlew runServer
```

This will download a Paper server for Minecraft 1.21.5 and start it with the plugin installed.

### Publishing

You can publish to Hangar and Modrinth using the same shaded artifact built by `shadowJar`.

- **Hangar**:

  ```bash
  ./gradlew publishPluginPublicationToHangar
  ```

  Requires `HANGAR_API_KEY` in the environment. The task auto-detects whether the current commit is tagged to decide Release vs Snapshot.

- **Modrinth**:

  ```bash
  ./gradlew modrinth
  ```

  Requires `MODRINTH_TOKEN` in the environment. Optionally set the project id/slug via Gradle property:

  ```bash
  ./gradlew modrinth -Pmodrinth.projectId=villagerlobotomy
  ```

  Game versions are published for `1.21.6`, `1.21.7`, and `1.21.8`. Tagged commits publish a Release; otherwise a Snapshot-like Beta with a short git hash suffix.

- **Publish everywhere**:

  ```bash
  ./gradlew publishAll
  ```

## Support

If you encounter any issues, please report them on [GitHub](https://github.com/mja00/VillagerLobotimizer/issues).

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
