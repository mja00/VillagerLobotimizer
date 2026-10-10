package dev.mja00.villagerLobotomizer.policy;

import org.bukkit.loot.LootTables;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Pure rules for Hero of the Village gifts from lobotomized villagers, mirroring vanilla's gift
 * behavior ({@code GiveGiftToHero}), which a villager with its AI off can no longer run itself.
 */
public final class HeroGiftPolicy {

    /** Vanilla villagers see players within their 16-block follow range, and count the cooldown down then. */
    public static final double VIEW_RANGE = 16.0;
    public static final double VIEW_RANGE_SQUARED = VIEW_RANGE * VIEW_RANGE;
    /** Vanilla only hands a gift over once the villager's block is closer than 5 blocks to the hero's. */
    public static final int THROW_RANGE_SQUARED = 5 * 5;
    /** Vanilla's player sensor refreshes what a villager can see every 20 ticks. */
    public static final long SCAN_INTERVAL_TICKS = 20L;
    /** Vanilla waits this long after turning toward the hero before throwing, so the head can finish turning. */
    public static final long HEAD_TURN_TICKS = 20L;
    /** Vanilla's gift timer, which lives in the villager's brain and so restarts at this on every load. */
    public static final long FIRST_GIFT_DELAY_TICKS = 600L;
    /**
     * Up to this much is added to each villager's first delay. Vanilla villagers' sensors scan at
     * their own offsets and walk over before throwing, so their first gifts naturally spread out;
     * without this, every villager seen by the same scan would throw at once.
     */
    public static final long FIRST_GIFT_JITTER_TICKS = 3 * SCAN_INTERVAL_TICKS;
    public static final long MIN_COOLDOWN_TICKS = 600L;
    public static final long MAX_COOLDOWN_TICKS = 6600L;
    /** Vanilla throws from this far below the villager's eyes... */
    public static final double THROW_HEIGHT_BELOW_EYES = 0.3;
    /** ...at this speed, in blocks per tick, toward the hero's feet. */
    public static final double THROW_SPEED = 0.3;
    /** Vanilla item physics: gravity per tick, then drag on the motion. */
    static final double ITEM_GRAVITY = 0.04;
    static final double ITEM_DRAG = 0.98;
    /** How long a thrown gift is followed before it counts as clear of the cell. */
    static final int MAX_THROW_TICKS = 40;
    /** An item entity's collision box: 0.25 wide and 0.25 tall. */
    static final double ITEM_HALF_WIDTH = 0.125;
    static final double ITEM_HEIGHT = 0.25;
    /** Half the item's width, so consecutive boxes overlap and nothing thin slips between them. */
    static final double MAX_THROW_SUBSTEP = ITEM_HALF_WIDTH;

    private static final long NEVER = Long.MIN_VALUE;

    private static final Map<String, LootTables> PROFESSION_GIFTS = Map.ofEntries(
            Map.entry("armorer", LootTables.ARMORER_GIFT),
            Map.entry("butcher", LootTables.BUTCHER_GIFT),
            Map.entry("cartographer", LootTables.CARTOGRAPHER_GIFT),
            Map.entry("cleric", LootTables.CLERIC_GIFT),
            Map.entry("farmer", LootTables.FARMER_GIFT),
            Map.entry("fisherman", LootTables.FISHERMAN_GIFT),
            Map.entry("fletcher", LootTables.FLETCHER_GIFT),
            Map.entry("leatherworker", LootTables.LEATHERWORKER_GIFT),
            Map.entry("librarian", LootTables.LIBRARIAN_GIFT),
            Map.entry("mason", LootTables.MASON_GIFT),
            Map.entry("shepherd", LootTables.SHEPHERD_GIFT),
            Map.entry("toolsmith", LootTables.TOOLSMITH_GIFT),
            Map.entry("weaponsmith", LootTables.WEAPONSMITH_GIFT));

    /** What a villager does on a scan that sees a hero. */
    public enum Step {
        /** Nothing for this hero: the cooldown is still running, or the villager is facing a closer hero. */
        WAIT,
        /** Gift ready: turn toward this hero, as vanilla does before throwing. */
        FACE,
        /** Facing this hero, which is in range: give the gift, then {@link #startNextCooldown}. */
        GIVE
    }

    /**
     * One villager's gift timer. Like vanilla's it is not saved: a villager starts at
     * {@link #FIRST_GIFT_DELAY_TICKS} whenever it loads. Only touched on the villager's own thread.
     */
    public static final class GiftClock {
        private long remainingTicks;
        private long lastSeenTick = NEVER;
        private @Nullable UUID recipient;
        private long recipientSeenTick = NEVER;
        private double recipientDistanceSquared;
        private long facingSinceTick = NEVER;

        public GiftClock() {
            this(FIRST_GIFT_DELAY_TICKS);
        }

        public GiftClock(long remainingTicks) {
            this.remainingTicks = remainingTicks;
        }

        public long remainingTicks() {
            return this.remainingTicks;
        }
    }

    private HeroGiftPolicy() {
    }

    /**
     * Whether a hero counts as in view, before the (costlier) line-of-sight check. Like vanilla this is
     * far wider than the gift range: every villager watching a hero runs its cooldown down.
     */
    public static boolean isCandidate(double distanceSquared, boolean hasHeroEffect, boolean spectator) {
        return hasHeroEffect && !spectator && distanceSquared < VIEW_RANGE_SQUARED;
    }

    /** Vanilla's gift range, measured between block positions. */
    public static boolean withinThrowingDistance(int dx, int dy, int dz) {
        return (long) dx * dx + (long) dy * dy + (long) dz * dz < THROW_RANGE_SQUARED;
    }

    /**
     * Advances a villager's clock for a scan that sees a hero.
     * <p>
     * Vanilla's sensor remembers a seen hero for one scan, so each sighting runs the cooldown down by
     * at most one scan interval: a hero seen on every other scan counts at half speed, and a failed
     * check needs no record. Heroes scanning the same villager share its clock, so between them they
     * never credit more than real time.
     * <p>
     * Once the cooldown is done the villager faces one hero, the closest it can see (vanilla targets
     * its nearest visible player), and throws {@link #HEAD_TURN_TICKS} later if that hero is in range.
     * Switching to another hero, or that hero leaving view, restarts the head turn. Unlike vanilla,
     * whose villager walks over and gives up after a few seconds, a trapped villager keeps the gift
     * until a hero comes close.
     *
     * @param distanceSquared the hero's squared distance from the villager
     */
    public static Step onSighting(GiftClock clock, long now, UUID hero, double distanceSquared, boolean inThrowRange) {
        long sinceLastSeen = clock.lastSeenTick == NEVER ? -1 : now - clock.lastSeenTick;
        clock.lastSeenTick = now;
        if (clock.remainingTicks > 0) {
            long credit = sinceLastSeen < 0 ? 0 : Math.min(sinceLastSeen, SCAN_INTERVAL_TICKS);
            clock.remainingTicks = Math.max(0L, clock.remainingTicks - credit);
            if (clock.remainingTicks > 0) {
                return Step.WAIT;
            }
        }
        long sinceRecipientSeen = clock.recipient == null ? -1 : now - clock.recipientSeenTick;
        boolean recipientInView = sinceRecipientSeen >= 0 && sinceRecipientSeen <= SCAN_INTERVAL_TICKS;
        if (!hero.equals(clock.recipient)) {
            if (recipientInView && distanceSquared >= clock.recipientDistanceSquared) {
                return Step.WAIT;
            }
            clock.recipient = hero;
            clock.recipientSeenTick = now;
            clock.recipientDistanceSquared = distanceSquared;
            clock.facingSinceTick = now;
            return Step.FACE;
        }
        clock.recipientSeenTick = now;
        clock.recipientDistanceSquared = distanceSquared;
        if (!recipientInView || now < clock.facingSinceTick) {
            // The hero left view, so the villager has to turn toward them again.
            clock.facingSinceTick = now;
            return Step.FACE;
        }
        return inThrowRange && now - clock.facingSinceTick >= HEAD_TURN_TICKS ? Step.GIVE : Step.FACE;
    }

    /**
     * Whether a gift thrown from {@code start} escapes the villager's cell. Moves the item's real
     * collision box along vanilla's item physics. Like vanilla's entity movement, each tick moves the
     * full vertical displacement first, then the larger horizontal axis, then the other; every axis
     * is swept in substeps of at most {@link #MAX_THROW_SUBSTEP} so nothing thin is skipped. The throw
     * is accepted only if the item first lands on a block top with its center outside the villager's
     * own block. Hitting a wall, a ceiling or anything else on the way, starting inside a block, or
     * landing back in the villager's block means it would stay in the cell. An item still in the
     * air after {@link #MAX_THROW_TICKS} has cleared everything nearby if it has left the villager's
     * block column; still above or below it, it would come down in the cell.
     *
     * @param start            the item's position (bottom center of its box)
     * @param villagerPosition the villager's feet, whose block is the one the gift must leave
     * @param collides         whether a box overlaps anything the item would collide with
     */
    public static boolean throwEscapes(Vector start, Vector velocity, Vector villagerPosition,
                                       Predicate<BoundingBox> collides) {
        Vector position = start.clone();
        if (collides.test(itemBox(position))) {
            return false;
        }
        Vector motion = velocity.clone();
        for (int tick = 0; tick < MAX_THROW_TICKS; tick++) {
            motion.setY(motion.getY() - ITEM_GRAVITY);
            if (!sweep(position, 0.0, motion.getY(), 0.0, collides)) {
                // Falling onto something lands the item; rising into something is a ceiling.
                return motion.getY() < 0.0 && leftColumn(position, villagerPosition);
            }
            boolean xFirst = Math.abs(motion.getX()) >= Math.abs(motion.getZ());
            double firstX = xFirst ? motion.getX() : 0.0;
            double firstZ = xFirst ? 0.0 : motion.getZ();
            if (!sweep(position, firstX, 0.0, firstZ, collides)
                    || !sweep(position, motion.getX() - firstX, 0.0, motion.getZ() - firstZ, collides)) {
                return false;
            }
            motion.multiply(ITEM_DRAG);
        }
        return leftColumn(position, villagerPosition);
    }

    private static boolean leftColumn(Vector position, Vector villagerPosition) {
        return position.getBlockX() != villagerPosition.getBlockX()
                || position.getBlockZ() != villagerPosition.getBlockZ();
    }

    /**
     * Moves {@code position} by the given displacement in substeps, stopping at the last free spot.
     *
     * @return {@code false} if the item's box collided on the way
     */
    private static boolean sweep(Vector position, double dx, double dy, double dz, Predicate<BoundingBox> collides) {
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance == 0.0) {
            return true;
        }
        int substeps = (int) Math.ceil(distance / MAX_THROW_SUBSTEP);
        Vector step = new Vector(dx / substeps, dy / substeps, dz / substeps);
        for (int i = 0; i < substeps; i++) {
            Vector next = position.clone().add(step);
            if (collides.test(itemBox(next))) {
                return false;
            }
            position.copy(next);
        }
        return true;
    }

    /** An item entity's collision box, 0.25 blocks on each side, standing on {@code position}. */
    static BoundingBox itemBox(Vector position) {
        return new BoundingBox(
                position.getX() - ITEM_HALF_WIDTH, position.getY(), position.getZ() - ITEM_HALF_WIDTH,
                position.getX() + ITEM_HALF_WIDTH, position.getY() + ITEM_HEIGHT, position.getZ() + ITEM_HALF_WIDTH);
    }

    /** The first gift delay for a villager that has not seen a hero since it loaded. */
    public static long firstGiftDelay(Random random) {
        return FIRST_GIFT_DELAY_TICKS + random.nextLong(FIRST_GIFT_JITTER_TICKS + 1);
    }

    /** Vanilla picks the next cooldown uniformly between 600 and 6600 ticks. */
    public static void startNextCooldown(GiftClock clock, Random random) {
        clock.remainingTicks = MIN_COOLDOWN_TICKS + random.nextLong(MAX_COOLDOWN_TICKS - MIN_COOLDOWN_TICKS + 1);
        clock.recipient = null;
        clock.recipientSeenTick = NEVER;
        clock.facingSinceTick = NEVER;
    }

    /**
     * Vanilla's gift table: babies use the baby table, known professions their own table, and
     * everyone else (unemployed, nitwit) the unemployed table.
     *
     * @param professionPath the profession key's path, e.g. {@code "librarian"}
     */
    public static LootTables giftTable(boolean baby, String professionPath) {
        if (baby) {
            return LootTables.BABY_VILLAGER_GIFT;
        }
        return PROFESSION_GIFTS.getOrDefault(professionPath, LootTables.UNEMPLOYED_GIFT);
    }
}
