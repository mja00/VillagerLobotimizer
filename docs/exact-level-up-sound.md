# Exact resource-pack level-up sound

REQ-001: WHEN level-up-sound is a valid non-minecraft namespaced resource-pack event THE SYSTEM SHALL play that exact key at the existing villager position, NEUTRAL category, volume and pitch, without registry rejection or configuration rewrite.
REQ-002: THE SYSTEM SHALL preserve vanilla and legacy sound resolution, blank/invalid behavior, restock sounds, villager level/reward/regeneration policy, entity scheduling and all persistent state.

SPEAR: inspect current upstream main and actual installed 1.17.0 bytecode; specify keys/acceptance and establish config-load regression; add the smallest platform adapter; verify existing regression suite and canonical build. No project-local EARS/phase helper exists; this document records the bounded requirements/evidence. No native client audibility or real-villager level-up acceptance is implied by tests.

Task SOUND-001: regression and implementation pending. Source PR targets current main; a TEST build must preserve installed 1.17.0 behavior rather than silently importing unrelated 1.17.1 changes.

Prove: actual pre-implementation reload regression failed with NoSuchFieldException customLevelUpSound; both existing legacy-resolution regressions passed. Engine: valid non-minecraft keys bypass server registry resolution; vanilla/legacy config paths remain unchanged. Existing needsLevelUp/region scheduling gate calls the same world-position/category/gain/pitch delivery adapter once. Added direct delivery regression. Refine: canonical Gradle build passes 190 tests with zero failures/errors/skips. Native sound audibility and real-villager acceptance remain pending.
