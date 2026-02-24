# AGENTS.md

## Cursor Cloud specific instructions

This is a Java 21 / Gradle 9.2.1 Minecraft Paper plugin. JDK 21 is pre-installed in the VM. No external services (databases, Docker, etc.) are required.

### Quick reference

| Action | Command |
|---|---|
| Build (compile + test + shadow JAR) | `./gradlew build` |
| Unit tests only | `./gradlew test` |
| Dev server (interactive, long-running) | `./gradlew runServer` |

See `CLAUDE.md` for architecture details and coding conventions. See `.cursor/rules/gradle-and-run.mdc` for build/publish rules.

### Dev server notes

- The `runServer` task downloads Paper automatically and starts a full Minecraft server on port 25565.
- You must accept the EULA before first run: `echo "eula=true" > run/eula.txt`
- The server is interactive (reads stdin). Run it in the background with stdin redirected from `/dev/null` if you need non-blocking execution.
- The system property `villagerlobotimizer.dev=true` is set automatically during `runServer`, which disables production Sentry error reporting.
- The `run/` directory contains server data (worlds, config, logs). It is gitignored.

### Gotchas

- The build script runs `git log`, `git describe`, and `git rev-parse` during Gradle's configuration phase. The workspace must be a git repository with at least one commit, or the build will fail.
- The project name is intentionally misspelled as "VillagerLobotimizer" in artifact names and plugin metadata. Do not "fix" this.
- There is no dedicated lint command. Compilation (`./gradlew build`) is the primary static check.
