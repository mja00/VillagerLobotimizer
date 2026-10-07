package dev.mja00.villagerLobotomizer.policy;

import java.util.List;

/**
 * The outcome of {@link VillagerActivityPolicy#evaluate} together with a trace of every rule, so an
 * admin can see which rule kept a villager awake or lobotomized it, and which rules were never
 * reached or are switched off in the config.
 *
 * @param active       whether the villager should keep its AI
 * @param decidingRule the rule that produced the result
 * @param checks       one entry per {@link Rule}, in evaluation order
 * @param movement     the per-direction movement trace, or {@code null} when an earlier rule decided
 */
public record ActivityDecision(boolean active, Rule decidingRule, List<RuleCheck> checks,
                               MovementTrace movement) {

    public ActivityDecision {
        checks = List.copyOf(checks);
    }

    /** Rules in the order the policy evaluates them. The first rule that decides wins. */
    public enum Rule {
        NOBRAIN_NAME("\"nobrain\" name"),
        EXEMPT_NAME("always-active-names"),
        SWIMMING("swimming"),
        IN_WATER("in water"),
        SLEEPING("sleeping"),
        IN_VEHICLE("always-lobotomize-villagers-in-vehicles"),
        NO_PROFESSION("only-lobotomize-villagers-with-professions"),
        NO_EXPERIENCE("only-lobotomize-villagers-with-experience"),
        ROOF("check-roof"),
        MOVEMENT("trapped (movement) check");

        private final String label;

        Rule(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    public enum Outcome {
        /** Evaluated, but did not decide; evaluation continued to the next rule. */
        PASSED("PASS"),
        /** Decided: the villager keeps its AI. */
        KEPT_ACTIVE("ACTIVE"),
        /** Decided: the villager is lobotomized. */
        LOBOTOMIZED("LOBOTOMIZE"),
        /** The rule's config option is off, so it was skipped. */
        DISABLED("OFF"),
        /** An earlier rule already decided. */
        NOT_REACHED("SKIP");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return this.tag;
        }
    }

    public record RuleCheck(Rule rule, Outcome outcome, String detail) {
    }

    /** Why a single block counted as blocking or open for movement. */
    public enum BlockVerdict {
        UNLOADED(true, "chunk not loaded"),
        LISTED_IMPASSABLE(true, "in impassable block list"),
        NOT_PASSABLE(true, "not passable"),
        PASSABLE(false, "passable"),
        WATER(false, "water"),
        BYPASS(false, "crop/bed/carpet, always walkable"),
        DOOR_IGNORED(false, "door, ignore-villagers-stuck-in-doors"),
        NON_SOLID_IGNORED(false, "non-solid, ignore-non-solid-blocks"),
        NOT_TALL(false, "not a wall/fence/gate"),
        HITBOX_OVERLAP(true, "collides with the villager's raised hitbox"),
        CLEARS_HITBOX(false, "above the villager's raised hitbox");

        private final boolean blocking;
        private final String description;

        BlockVerdict(boolean blocking, String description) {
            this.blocking = blocking;
            this.description = description;
        }

        public boolean blocking() {
            return this.blocking;
        }

        public String description() {
            return this.description;
        }
    }

    /**
     * One cardinal neighbour. {@code underFeet} only matters when {@link MovementTrace#roofed()} is
     * true; snapshots are {@code null} when the column's chunk is unloaded. {@code overhead} is the
     * block at {@code y + 2}, checked only when the villager's hitbox reaches into that layer;
     * otherwise it and {@code overheadVerdict} are {@code null}.
     */
    public record DirectionTrace(String direction, int x, int y, int z,
                                 BlockSnapshot head, BlockVerdict headVerdict,
                                 BlockSnapshot feet, BlockVerdict feetVerdict,
                                 BlockSnapshot underFeet, BlockVerdict underFeetVerdict,
                                 BlockSnapshot overhead, BlockVerdict overheadVerdict,
                                 boolean open) {
    }

    /**
     * @param floor      the block under the villager, or {@code null} when unloaded
     * @param roof       the block above the villager's head, or {@code null} when unloaded
     * @param roofed     whether a roof (or honey floor) makes the under-feet block of each neighbour count
     * @param roofReason human-readable reason for {@code roofed}
     * @param overhang   how far the villager's hitbox reaches into the {@code y + 2} layer, or 0
     */
    public record MovementTrace(BlockSnapshot floor, BlockSnapshot roof, boolean roofed, String roofReason,
                                double overhang, List<DirectionTrace> directions) {
        public MovementTrace {
            directions = List.copyOf(directions);
        }
    }

    /** The check of the deciding rule. */
    public RuleCheck decidingCheck() {
        for (RuleCheck check : this.checks) {
            if (check.rule() == this.decidingRule) {
                return check;
            }
        }
        throw new IllegalStateException("No check recorded for deciding rule " + this.decidingRule);
    }

    /** One-line summary for console logs, e.g. {@code lobotomized by trapped (movement) check: ...}. */
    public String summary() {
        return (this.active ? "kept active by " : "lobotomized by ")
                + this.decidingRule.label() + ": " + decidingCheck().detail();
    }
}
