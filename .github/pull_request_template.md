<!--
Thanks for contributing to VillagerLobotimizer!

Reminder: do NOT open PRs from your fork's default branch (main/master).
PRs from a fork's default branch are auto-closed because maintainers cannot
push fix-up commits to a contributor's default branch. Use a feature branch.

See CONTRIBUTING.md for full guidelines.
-->

## Summary

<!-- What does this PR do, and why? Focus on the *why* — the diff shows the *what*. -->

## Type of Change

<!-- Check all that apply. -->

- [ ] feat — new feature
- [ ] fix — bug fix
- [ ] docs — documentation only
- [ ] refactor — non-behavioral cleanup
- [ ] chore — build, CI, dependencies
- [ ] perf — performance improvement

## Related Issues

<!-- e.g. "Closes #123" or "Refs #456". Delete if not applicable. -->

## How Was This Tested?

<!--
Describe what you ran. Examples:
- ./gradlew build && ./gradlew test
- Manual test on ./gradlew runServer with <scenario>
- Tested against Folia build #XYZ
-->

## Checklist

- [ ] Branch is **not** my fork's default branch
- [ ] `./gradlew build` passes locally
- [ ] `./gradlew test` passes locally
- [ ] No NMS imports (`net.minecraft.*`, `org.bukkit.craftbukkit.*`)
- [ ] Entity/chunk work uses `EntityScheduler` / `GlobalRegionScheduler` (Folia-safe)
- [ ] New config options have defaults in `src/main/resources/config.yml`
- [ ] New commands are registered in `LobotomizeCommand` and have permissions in `plugin.yml`
- [ ] Debug logging is gated behind `plugin.isDebugging()` / `plugin.isChunkDebugging()`
- [ ] Commits follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/)

## Notes for Reviewers

<!-- Anything reviewers should pay extra attention to, tradeoffs you considered, or follow-ups you're deferring. Delete if not applicable. -->
