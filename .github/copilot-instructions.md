# VillagerLobotimizer Development Instructions

VillagerLobotimizer is a Minecraft Paper plugin written in Java 21 that optimizes server performance by disabling villager AI when they're trapped in trading halls. The project uses Gradle for building and includes sophisticated CI/CD for publishing to Hangar and Modrinth.

**Always reference these instructions first and fallback to search or bash commands only when you encounter unexpected information that does not match the info here.**

## Working Effectively

### Prerequisites and Setup
- Set up Java 21 (required):
  ```bash
  export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
  export PATH=$JAVA_HOME/bin:$PATH
  java -version  # Should show OpenJDK 21
  ```
- Make Gradle wrapper executable: `chmod +x ./gradlew`
- Verify Gradle setup: `./gradlew --version`
- Always use Conventional Commits formatting

### Building the Plugin
- **CRITICAL**: Build takes 2-10 minutes on first run due to dependency downloads. NEVER CANCEL. Set timeout to 30+ minutes.
- `./gradlew build --no-daemon` -- downloads dependencies and compiles. NEVER CANCEL.
- **Network Dependency**: Requires access to `repo.papermc.io` for Paper development bundle
- **Known Issue**: Build fails in sandboxed environments due to `repo.papermc.io` being blocked with error "No address associated with hostname". This is expected in restricted environments.
- **Error Signature**: `Could not resolve io.papermc.paper:dev-bundle:1.21.6-R0.1-SNAPSHOT` indicates network restriction
- **Workaround**: If PaperMC repository is blocked, the build cannot complete. Document this limitation rather than attempting fixes.
- Built plugin JAR will be in `build/libs/VillagerLobotimizer-<version>.jar`
- Uses shadow plugin, so the actual artifact is the shaded JAR (no classifier)

### Running Test Server
- **CRITICAL**: Server download and startup takes 10-20 minutes. NEVER CANCEL. Set timeout to 60+ minutes.
- `./gradlew runServer` -- downloads Paper server for Minecraft 1.21.8 and starts with plugin installed
- **Note**: This requires network access to download Paper server
- Server runs in interactive mode - you can issue Minecraft commands
- **Limitation**: Cannot interact with Minecraft GUI in headless environments

### Publishing (for maintainers)
- `./gradlew publishAll` -- publishes to both Hangar and Modrinth
- Requires `HANGAR_API_KEY` and `MODRINTH_TOKEN` environment variables
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
├── .github/workflows/     # CI/CD pipelines (pr-build.yml, test.yml, publish.yml)
├── build.gradle.kts       # Kotlin DSL build configuration
├── gradle/               # Gradle wrapper files
├── gradlew              # Gradle wrapper script (Unix)
├── gradlew.bat          # Gradle wrapper script (Windows)
├── settings.gradle.kts   # Gradle settings
├── src/main/
│   ├── java/dev/mja00/villagerLobotomizer/  # Java source code
│   └── resources/        # Plugin resources (config.yml, plugin.yml)
├── README.md            # Project documentation
└── LICENSE              # MIT license
```

### Key Source Files
- `VillagerLobotimizer.java` -- Main plugin class
- `LobotomizeCommand.java` -- Command handling (/lobotomy commands)
- `LobotomizeStorage.java` -- Data storage and management
- `listeners/EntityListener.java` -- Entity event handling
- `utils/VillagerUtils.java` -- Villager-specific utilities

### Configuration Files
- `src/main/resources/plugin.yml` -- Plugin metadata for Paper
- `src/main/resources/config.yml` -- Default plugin configuration
- `build.gradle.kts` -- Build configuration with plugins:
  - `io.papermc.paperweight.userdev` -- Paper development
  - `xyz.jpenilla.run-paper` -- Test server runner
  - `com.gradleup.shadow` -- JAR shading
  - `io.papermc.hangar-publish-plugin` -- Hangar publishing
  - `com.modrinth.minotaur` -- Modrinth publishing

### Dependencies and Versions
- **Minecraft**: 1.21.6, 1.21.7, 1.21.8 (Paper API)
- **Java**: 21 (required)
- **Gradle**: 8.14.3
- **Paper Dev Bundle**: 1.21.6-R0.1-SNAPSHOT (used for development; plugin is compatible with Paper 1.21.6–1.21.8 at runtime. For development against other patch versions, use the corresponding Paper Dev Bundle, e.g., 1.21.7-R0.1-SNAPSHOT or 1.21.8-R0.1-SNAPSHOT.)
- **Key Libraries**:
  - `org.bstats:bstats-bukkit:3.1.0` (metrics)
  - `net.kyori:adventure-text-serializer-plain:4.22.0` (text handling)

### Timing Expectations
- **First build**: 2-10 minutes (dependency downloads). NEVER CANCEL.
- **Subsequent builds**: 30 seconds - 2 minutes
- **Server startup**: 5-15 minutes (downloads Paper server first time). NEVER CANCEL.
- **Plugin reload**: Instant (`/lobotomy reload` in-game)
- **Build failure (network restricted)**: 5-10 seconds with "No address associated with hostname" error

### Common Issues and Workarounds
- **"repo.papermc.io" blocked**: Expected in restricted environments. Build cannot complete without PaperMC repository access.
  - **Error**: `repo.papermc.io: No address associated with hostname`
  - **Solution**: Work in environment with unrestricted network access or use pre-built artifacts
- **Java version mismatch**: Ensure Java 21 is active with `java -version`
- **Permission denied on gradlew**: Run `chmod +x ./gradlew`
- **Long build times**: This is normal for Paper plugins. Be patient and never cancel.
- **Gradle daemon issues**: Use `--no-daemon` flag to avoid daemon-related problems in CI environments

### Environment Validation Commands
Run these commands to verify your environment is properly configured:
```bash
# Verify Java 21
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
java -version  # Should show "openjdk version 21"

# Test Gradle wrapper
chmod +x ./gradlew
./gradlew --version  # Should show "Gradle 8.14.3"

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
- **Version Compatibility**: Plugin targets Paper 1.21.6+ - verify compatibility when updating dependencies
- **Folia Support**: Code must be compatible with Folia's regionized threading model

### Code Structure Guidelines
- **Main Plugin Class**: `VillagerLobotomizer.java` - handles plugin lifecycle
- **Command System**: `LobotomizeCommand.java` - add new commands here
- **Entity Logic**: `listeners/EntityListener.java` - villager AI modifications
- **Data Storage**: `LobotomizeStorage.java` - persistent data handling  
- **Utilities**: `utils/` package - helper functions for villager operations

### Plugin Functionality
- **Core Feature**: Automatically detects trapped villagers and disables their AI
- **Commands**: `/lobotomy info`, `/lobotomy debug`, `/lobotomy wake`, `/lobotomy reload`
- **Configuration**: Customizable check intervals, restock timing, sounds, debug options
- **Permissions**: `lobotomy.command` (default: op)
- **Folia Support**: Yes, works with Folia servers

Remember: This is a performance optimization plugin for Minecraft servers. Always test actual gameplay scenarios, not just build success.
