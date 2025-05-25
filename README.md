![image](https://github.com/user-attachments/assets/2716b281-48a6-4a1a-9305-0560684d4e0f)

# VillagerLobotimizer

A Minecraft Paper plugin that improves server performance by turning off villagers' AI when they're confined to trading halls.

## Features

- Automatically detects when villagers are trapped in trading halls
- Disables AI for trapped villagers to improve server performance
- Maintains villager trading functionality while AI is disabled
- Automatically refreshes villager trades on a configurable schedule
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
#Interval between trapped checks, in ticks, for active villagers
check-interval: 150

#Interval between trapped checks, in ticks, for inactive villagers
inactive-check-interval: 150

#Interval between villager trade restocks, in milliseconds
restock-interval: 28800000

#Range (in milliseconds) before restock-interval to start random restock checks. If set to 0, restocking is not randomized. If equal to or greater than restock-interval, restock will always occur.
restock-random-range: 0

#Whether to only lobotomize villagers with jobs
only-lobotomize-villagers-with-professions: false

#Whether to lobotomize villagers in boats/minecarts. Does not apply to villagers riding on non-vehicle entities like horses.
always-lobotomize-villagers-in-vehicles: false

#The sound to play when a villager restocks. Leave empty ("") for default sounds.
restock-sound: ""

#The sound played when a villager is leveled up. Leave empty ("") for no sound.
level-up-sound: "ENTITY_VILLAGER_CELEBRATE"

#Debug mode. Prints debug messages to the console.
debug: false

#Chunk debug mode. Prints debug messages related to chunks
chunk-debug: false

#To ignore villagers stuck in doors, set this to true.
ignore-villagers-stuck-in-doors: false

#To not lobotomize villagers surrounded by non-solid blocks, set this to true.
ignore-non-solid-blocks: false

```

## Special Villager Names

- Name a villager with "nobrain" to force it to always be lobotomized
- Name a villager with "alwaysbrain" to prevent it from ever being lobotomized

## Requirements

- Paper 1.21+

## Installation

1. Download the latest release from [Modrinth](https://modrinth.com/plugin/villagerlobotomy)
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

The project uses Hangar for publishing:

```bash
./gradlew publishPluginPublicationToHangar
```

This requires the `HANGAR_API_KEY` environment variable to be set. The plugin will be published as:
- A release version if the current commit is tagged with a version matching the project version
- A snapshot version if there's no matching tag

## Support

If you encounter any issues, please report them on [GitHub](https://github.com/mja00/VillagerLobotimizer/issues).

## License

This project is maintained by mja00. See the plugin.yml file for more information. 
