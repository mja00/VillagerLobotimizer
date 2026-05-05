# Contributing to VillagerLobotimizer

Thanks for your interest in contributing! This document covers everything you need to know to get a change merged.

> **Note on the name:** The repo is intentionally misspelled as `VillagerLobotimizer`. The plugin/package name uses the correct spelling `VillagerLobotomizer`.

## Code of Conduct

Be respectful. Disagreements about code are fine; personal attacks are not.

## Getting Started

### Prerequisites

- JDK 21
- Git
- A GitHub account (for opening pull requests)

### Setup

```bash
git clone https://github.com/<your-username>/VillagerLobotimizer.git
cd VillagerLobotimizer
./gradlew build
```

### Running a Test Server

```bash
./gradlew runServer
```

This downloads a Paper server and runs the plugin against it. Useful for manual testing.

## Branching & Pull Requests

**Do not open PRs from your fork's default branch (`main`/`master`).** Maintainers cannot push commits to a contributor's default branch, which makes review feedback and small fix-ups impossible. PRs opened from a fork's default branch will be **automatically closed** by a workflow with instructions to re-open from a feature branch.

The right workflow:

```bash
# Sync your fork's main with upstream first
git checkout main
git pull upstream main

# Create a feature branch
git checkout -b fix/villager-restock-on-lectern

# Make changes, commit, push
git push -u origin fix/villager-restock-on-lectern
```

Then open a PR from `fix/villager-restock-on-lectern` against `mja00/VillagerLobotimizer:main`.

### Branch Naming

Use a descriptive prefix:

- `feat/...` — new feature
- `fix/...` — bug fix
- `docs/...` — documentation only
- `refactor/...` — non-behavioral cleanup
- `chore/...` — build, CI, dependencies

### Commit Messages

Use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/), all lowercase except for proper nouns and acronyms:

```
feat: add roof check toggle for nether villagers
fix: prevent NPE when villager unloads mid-restock
docs: clarify always-active-names behavior
chore: bump paper api to 1.21.8
```

Keep commits focused. One logical change per commit makes review and bisecting easier.

### Pull Request Checklist

Before requesting review:

- [ ] Branch is **not** your fork's default branch
- [ ] `./gradlew build` passes locally
- [ ] `./gradlew test` passes locally
- [ ] New config options have defaults in `src/main/resources/config.yml`
- [ ] New commands are registered in `LobotomizeCommand.java` and have permissions in `plugin.yml`
- [ ] Debug logging is gated behind `plugin.isDebugging()` or `plugin.isChunkDebugging()`
- [ ] PR description explains the *why*, not just the *what*

## Coding Guidelines

### Paper API Rules

- **Never use NMS** (`net.minecraft.*`, `org.bukkit.craftbukkit.*`). PRs that touch NMS will be rejected.
- Use `RegistryAccess` for sound lookups, not deprecated enums.
- Convert legacy sound names via `StringUtils.convertLegacySoundNameFormat()` when accepting user input.

### Threading & Folia Compatibility

This plugin is Folia-compatible. That means:

- **Per-entity work** runs on `EntityScheduler` (`entity.getScheduler()`).
- **Per-chunk work** runs on `GlobalRegionScheduler`.
- Never touch an entity from the wrong region thread.
- Always check `chunk.isLoaded()` before accessing entities in it.
- Respect the `shuttingDown` flag during plugin disable to avoid scheduling tasks against a torn-down server.

If your change adds scheduling, make sure it works on both Paper and Folia. Some features (like `create-debug-teams`) are explicitly Folia-incompatible and gated accordingly — follow that pattern for any new feature that depends on global state.

### Style

- Early returns and guard clauses over deep nesting.
- Default to no comments; only add one when the *why* is non-obvious.
- Match the surrounding code's formatting.
- New PDC keys: `new NamespacedKey(plugin, "keyName")`.

## Testing

Run the full test suite before pushing:

```bash
./gradlew test
./gradlew build
```

For changes that affect runtime behavior, also test in-game with `./gradlew runServer`. Useful commands while testing:

- `/lobotomy info` — counts of active vs. lobotomized villagers
- `/lobotomy debug` — info about the villager you're looking at
- `/lobotomy debug toggle` — toggle debug logging
- `/lobotomy wake` — manually un-lobotomize the targeted villager
- `/lobotomy reload` — reload config

## Reporting Bugs

Open an issue at <https://github.com/mja00/VillagerLobotimizer/issues> with:

- Server software and version (Paper, Purpur, Folia, ...)
- Minecraft version
- Plugin version
- Relevant config (especially anything you've changed from defaults)
- Steps to reproduce
- Logs / stack traces if applicable

## Questions

If you're unsure whether a change makes sense before investing time in it, open a draft issue describing the idea first. Faster than writing code that gets rejected.
