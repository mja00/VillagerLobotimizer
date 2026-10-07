# VillagerLobotimizer Development Instructions

VillagerLobotimizer is a Minecraft Paper plugin (compiled for Java 21) that optimizes server performance by disabling villager AI when they're trapped in trading halls. It supports Minecraft 1.21.11 and 26.1–26.3. The project uses Gradle for building and includes CI/CD for publishing to Hangar, Modrinth, and CurseForge.

**Always reference these instructions first and fallback to search or bash commands only when you encounter unexpected information that does not match the info here.**

## Working Effectively

### Prerequisites and Setup
- Set up JDK 21 or newer (CI builds with 21; `runServer` on Minecraft 26.x needs Java 25):
  ```bash
  export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64  # or temurin-25-jdk-amd64
  export PATH=$JAVA_HOME/bin:$PATH
  java -version  # Should show 21 or newer
  ```
- Make Gradle wrapper executable: `chmod +x ./gradlew`
- Verify Gradle setup: `./gradlew --version`

### Building the Plugin
- **CRITICAL**: Build takes 2-10 minutes on first run due to dependency downloads. NEVER CANCEL. Set timeout to 30+ minutes.
- `./gradlew build --no-daemon` -- downloads dependencies and compiles. NEVER CANCEL.
- **Network Dependency**: Requires access to `repo.papermc.io` for Paper development bundle
- **Known Issue**: Build fails in sandboxed environments due to `repo.papermc.io` being blocked with error "No address associated with hostname". This is expected in restricted environments.
- **Error Signature**: `Could not resolve io.papermc.paper:dev-bundle:1.21.11-R0.1-SNAPSHOT` indicates network restriction
- **Workaround**: If PaperMC repository is blocked, the build cannot complete. Document this limitation rather than attempting fixes.
- Built plugin JAR will be in `build/libs/VillagerLobotimizer-<version>.jar`
- Uses shadow plugin, so the actual artifact is the shaded JAR (no classifier)

### Running Test Server
- **CRITICAL**: Server download and startup takes 10-20 minutes. NEVER CANCEL. Set timeout to 60+ minutes.
- `./gradlew runServer` -- downloads a Paper server for Minecraft 26.3 (requires Java 25) and starts it with the plugin installed
- **Note**: This requires network access to download Paper server
- Server runs in interactive mode - you can issue Minecraft commands
- **Limitation**: Cannot interact with Minecraft GUI in headless environments

### Publishing (for maintainers)
- `./gradlew publishAll` -- publishes to both Hangar and Modrinth; CurseForge uses `scripts/publish-curseforge.sh`
- Requires `HANGAR_API_KEY` and `MODRINTH_TOKEN` (and `CURSEFORGE_TOKEN` for CurseForge) environment variables
- Auto-detects release vs snapshot based on git tags

## Validation

### Manual Testing Scenarios
After making changes, ALWAYS run through these validation steps:

1. **Build Validation**:
   - `./gradlew build --no-daemon`
   - Verify build/libs/ contains the plugin JAR
   - Check that no compilation errors occurred

2. **Plugin Loading Validation**:
   - `./gradlew runServer`
   - Wait for server startup (shows "Done" message)
   - Type `plugins` to verify VillagerLobotimizer is loaded
   - Type `stop` to shutdown server cleanly

3. **Command Testing** (if server is running):
   - `/lobotomy info` -- should show plugin statistics
   - `/lobotomy debug toggle` -- should enable debug mode
   - Create test villagers and verify plugin behavior

4. **CI Validation**:
   - Always check that GitHub Actions workflows pass
   - PR builds must complete successfully before merging

### Performance Testing
- Test with confined villagers in trading halls
- Verify AI is disabled for trapped villagers
- Confirm trading functionality still works
- Monitor console for debug messages when debug mode enabled

## Common Tasks

### Repository Structure
```
.
├── .github/workflows/     # CI/CD pipelines (pr-build.yml, publish.yml)
├── build.gradle.kts       # Kotlin DSL build configuration
├── gradle/               # Gradle wrapper files
├── gradlew              # Gradle wrapper script (Unix)
├── gradlew.bat          # Gradle wrapper script (Windows)
├── scripts/              # publish-curseforge.sh
├── settings.gradle.kts   # Gradle settings
├── src/main/
│   ├── java/dev/mja00/villagerLobotomizer/  # Java source code
│   └── resources/        # Plugin resources (config.yml, plugin.yml, paper-plugin.yml)
├── src/test/             # JUnit + MockBukkit tests
├── README.md            # Project documentation
└── LICENSE              # MIT license
```

### Key Source Files
- `VillagerLobotomizer.java` -- Main plugin class (the class name is spelled correctly; the project name is not)
- `LobotomizeCommand.java` -- Command handling (/lobotomy commands)
- `LobotomizeStorage.java` -- Villager tracking and state transitions
- `policy/` -- Pure, unit-tested lobotomy decision rules (`VillagerActivityPolicy`)
- `storage/` -- SQLite record of lobotomized villagers, used by `/lobotomy uninstall`
- `listeners/EntityListener.java` -- Entity event handling
- `utils/VillagerUtils.java` -- Villager-specific utilities

### Configuration Files
- `src/main/resources/plugin.yml` and `paper-plugin.yml` -- Plugin metadata for Paper (`api-version: '1.21.11'`; keep both in sync)
- `src/main/resources/config.yml` -- Default plugin configuration
- `build.gradle.kts` -- Build configuration with plugins:
  - `io.papermc.paperweight.userdev` -- Paper development
  - `xyz.jpenilla.run-paper` -- Test server runner
  - `com.gradleup.shadow` -- JAR shading
  - `io.papermc.hangar-publish-plugin` -- Hangar publishing
  - `com.modrinth.minotaur` -- Modrinth publishing

### Dependencies and Versions
- **Minecraft**: 1.21.11 and 26.1–26.3 (`supportedVersions` and `modrinthGameVersions` in `build.gradle.kts`)
- **Java**: compiled for 21; servers on Minecraft 26.x run Java 25
- **Gradle**: 9.4.1
- **Paper Dev Bundle**: 1.21.11-R0.1-SNAPSHOT (compile against the oldest supported version so the jar runs on all of them)
- **Tests**: JUnit 6 + MockBukkit 4.110 (`mockbukkit-v1.21`) against `paper-api` 1.21.11
- **Key Libraries**:
  - `org.bstats:bstats-bukkit:3.1.0` (metrics)
  - `io.sentry:sentry` (error reporting)
  - `org.xerial:sqlite-jdbc` (lobotomized-villager records)
  - `net.kyori:adventure-text-serializer-plain:4.22.0` (text handling)

### Timing Expectations
- **First build**: 2-10 minutes (dependency downloads). NEVER CANCEL.
- **Subsequent builds**: 30 seconds - 2 minutes
- **Server startup**: 5-15 minutes (downloads Paper server first time). NEVER CANCEL.
- **Plugin reload**: Instant (`/lobotomy reload` in-game)
- **Build failure (network restricted)**: 5-10 seconds with "No address associated with hostname" error

### Common Issues and Workarounds
- **Java version mismatch**: Building needs JDK 21+ (`java -version`); a 26.x `runServer` fails to start on anything below Java 25
- **Permission denied on gradlew**: Run `chmod +x ./gradlew`
- **Long build times**: This is normal for Paper plugins. Be patient and never cancel.
- **Gradle daemon issues**: Use `--no-daemon` flag to avoid daemon-related problems in CI environments

### Environment Validation Commands
Run these commands to verify your environment is properly configured:
```bash
# Verify JDK 21+
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
java -version  # Should show 21 or newer

# Test Gradle wrapper
chmod +x ./gradlew
./gradlew --version  # Should show "Gradle 9.4.1"

# Test network connectivity (will fail in restricted environments)
curl -I https://repo.papermc.io/repository/maven-public/
```

### Development Workflow
1. Make code changes in `src/main/java/`
2. Update configuration in `src/main/resources/` if needed  
3. Build: `./gradlew build --no-daemon`
4. Test: `./gradlew runServer` and validate plugin behavior
5. For CI testing: push to PR and verify GitHub Actions pass

### Important Development Notes
- **Always check** `src/main/resources/plugin.yml` when changing plugin metadata
- **Always check** `src/main/resources/config.yml` when adding new configuration options
- **Performance Critical**: This plugin modifies entity AI - test thoroughly with real villagers
- **Version Compatibility**: Plugin targets Paper 1.21.11+. When adding a Minecraft version, update `supportedVersions`, `modrinthGameVersions`, and `runServer`'s `minecraftVersion` in `build.gradle.kts`, plus the CurseForge version list in `README.md` and `publish.yml`
- **Folia Support**: Code must be compatible with Folia's regionized threading model
- Always use Conventional Commits formatting
- Always bump the plugin's version. If minor, bump the patch version, if major, bump the minor version. 

### Code Structure Guidelines
- **Main Plugin Class**: `VillagerLobotomizer.java` - handles plugin lifecycle
- **Command System**: `LobotomizeCommand.java` - add new commands here
- **Entity Logic**: `listeners/EntityListener.java` - villager AI modifications
- **Data Storage**: `LobotomizeStorage.java` - persistent data handling  
- **Utilities**: `utils/` package - helper functions for villager operations

### Plugin Functionality
- **Core Feature**: Automatically detects trapped villagers and disables their AI
- **Commands**: `/lobotomy info`, `/lobotomy debug`, `/lobotomy wake`, `/lobotomy reload`, `/lobotomy config`, `/lobotomy uninstall`
- **Configuration**: Customizable check intervals, restock timing, sounds, debug options
- **Permissions**: `lobotomy.command` (default: op)
- **Folia Support**: Yes, works with Folia servers

Remember: This is a performance optimization plugin for Minecraft servers. Always test actual gameplay scenarios, not just build success.
