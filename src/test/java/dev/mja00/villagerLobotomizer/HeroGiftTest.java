package dev.mja00.villagerLobotomizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootTables;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import dev.mja00.villagerLobotomizer.policy.HeroGiftPolicy;
import dev.mja00.villagerLobotomizer.policy.HeroGiftPolicy.GiftClock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link LobotomizeStorage#offerHeroGift} directly for the rules, and the hero's scan for the
 * wiring. The villager's own periodic check reaches the trapped-villager geometry, which MockBukkit
 * cannot evaluate, so no test here runs long enough for it to fire.
 */
class HeroGiftTest extends MockBukkitTestBase {

    private static final long SCAN = HeroGiftPolicy.SCAN_INTERVAL_TICKS;

    private VillagerLobotomizer plugin;
    private WorldMock world;
    private Villager villager;
    private PlayerMock hero;
    private final List<LootTables> rolledTables = new ArrayList<>();
    private final Map<Item, Villager> throwers = new HashMap<>();
    private boolean heroVisible = true;
    private boolean throwPathClear = true;

    @BeforeEach
    void setUp() {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
        world = server.addSimpleWorld("test");
        world.loadChunk(0, 0);

        villager = world.spawn(new Location(world, 8, 64, 8), Villager.class);
        villager.setProfession(Villager.Profession.LIBRARIAN);
        // Lobotomized: an aware villager runs vanilla's own gift behavior.
        villager.setAware(false);

        hero = server.addPlayer();
        hero.teleport(new Location(world, 10, 64, 8));
        hero.addPotionEffect(new PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE, 6000, 0));
        plugin.getHeroTracker().refresh(hero);

        useStubs();
    }

    private void useStubs() {
        rolledTables.clear();
        LobotomizeStorage storage = plugin.getStorage();
        storage.setHeroGiftLoot((v, table, random) -> {
            rolledTables.add(table);
            return List.of(new ItemStack(Material.BOOK));
        });
        storage.setHeroVisibility((v, player) -> heroVisible);
        // Open air, or a cell the item cannot get out of.
        storage.setThrowCollider(w -> box -> !throwPathClear);
        throwers.clear();
        storage.setRecordThrower(throwers::put);
    }

    private void offerAt(long tick) {
        world.setGameTime(tick);
        plugin.getStorage().offerHeroGift(villager, hero);
    }

    private Long remaining() {
        GiftClock clock = plugin.getStorage().heroGiftClock(villager);
        return clock == null ? null : clock.remainingTicks();
    }

    private void startClockAt(long remainingTicks) {
        plugin.getStorage().setHeroGiftClock(villager, new GiftClock(remainingTicks));
    }

    /** A ready villager turns to the hero on one scan and gives on the next. */
    private void giveReadyGift() {
        startClockAt(0L);
        offerAt(1000L);
        offerAt(1000L + SCAN);
    }

    private List<Item> droppedItems() {
        return world.getEntitiesByClass(Item.class).stream().toList();
    }

    @Test
    void firstSightStartsVanillasFirstGiftDelay() {
        offerAt(1000L);

        long first = remaining();
        assertTrue(first >= 600L && first <= 600L + HeroGiftPolicy.FIRST_GIFT_JITTER_TICKS, "first delay " + first);
        assertTrue(rolledTables.isEmpty());
    }

    @Test
    void nothingIsSavedOnTheVillager() {
        offerAt(1000L);
        offerAt(1000L + SCAN);

        assertTrue(villager.getPersistentDataContainer().getKeys().stream()
                        .noneMatch(key -> key.getKey().toLowerCase().contains("gift")),
                "like vanilla's, the timer restarts whenever the villager loads");
    }

    @Test
    void readyVillagerTurnsToTheHeroBeforeThrowing() {
        startClockAt(0L);
        villager.setRotation(90f, 0f);

        offerAt(1000L);

        assertTrue(rolledTables.isEmpty(), "vanilla waits for the head to turn first");
        assertEquals(-90f, villager.getLocation().getYaw(), 0.5f, "facing the hero, who is to the east");

        offerAt(1000L + SCAN);

        assertEquals(List.of(LootTables.LIBRARIAN_GIFT), rolledTables);
    }

    @Test
    void headTurnsBackAfterTheThrow() {
        villager.setRotation(90f, 0f);

        giveReadyGift();
        assertEquals(-90f, villager.getLocation().getYaw(), 0.5f, "faced the hero to throw");

        server.getScheduler().performTicks(2 * LobotomizeStorage.HEAD_TURN_BACK_TICKS + 1);

        assertEquals(90f, villager.getLocation().getYaw(), 0.5f, "back to where it was looking before");
    }

    @Test
    void shutdownPutsBackAHeadStillTurnedTowardAHero() {
        villager.setRotation(90f, 0f);
        giveReadyGift();
        assertEquals(-90f, villager.getLocation().getYaw(), 0.5f, "faced the hero to throw");

        // Before the turn-back task runs, which a real shutdown would cancel.
        plugin.getStorage().flush(LobotomizeStorage.FlushMode.SHUTDOWN);

        assertEquals(90f, villager.getLocation().getYaw(), 0.5f, "not saved facing the hero");
    }

    @Test
    void reloadPutsBackAHeadStillTurnedTowardAHero() {
        villager.setRotation(90f, 0f);
        giveReadyGift();

        plugin.getStorage().flush(LobotomizeStorage.FlushMode.RELOAD);

        assertEquals(90f, villager.getLocation().getYaw(), 0.5f, "the replacement storage must not inherit a turned head");
    }

    @Test
    void headStaysOnAHeroItIsStillWatching() {
        hero.teleport(new Location(world, 18, 64, 8));
        villager.setRotation(90f, 0f);
        startClockAt(0L);
        offerAt(1000L);

        server.getScheduler().performTicks(SCAN);
        plugin.getStorage().offerHeroGift(villager, hero);
        server.getScheduler().performTicks(SCAN);

        assertEquals(-90f, villager.getLocation().getYaw(), 0.5f, "faced the hero again within the turn-back delay");

        server.getScheduler().performTicks(2 * LobotomizeStorage.HEAD_TURN_BACK_TICKS);

        assertEquals(90f, villager.getLocation().getYaw(), 0.5f, "turned back once the hero was no longer faced");
    }

    @Test
    void giftIsThrownFromBelowTheVillagersEyesTowardTheHero() {
        giveReadyGift();

        List<Item> items = droppedItems();
        assertEquals(1, items.size());
        Item gift = items.get(0);
        assertEquals(Material.BOOK, gift.getItemStack().getType());
        Location expectedStart = villager.getEyeLocation().subtract(0.0, HeroGiftPolicy.THROW_HEIGHT_BELOW_EYES, 0.0);
        assertTrue(gift.getLocation().distanceSquared(expectedStart) < 1.0E-6, "thrown from just below the eyes");
        Vector velocity = gift.getVelocity();
        assertEquals(HeroGiftPolicy.THROW_SPEED, velocity.length(), 1.0E-6);
        assertTrue(velocity.getX() > 0.0, "toward the hero");
        assertEquals(villager, throwers.get(gift), "the villager is the thrower, for gift advancements");
        long next = remaining();
        assertTrue(next >= 600L && next <= 6600L, "rescheduled within vanilla's cooldown");
    }

    @Test
    void blockedThrowIsDroppedAtTheHerosFeet() {
        throwPathClear = false;

        giveReadyGift();

        List<Item> items = droppedItems();
        assertEquals(1, items.size());
        Item gift = items.get(0);
        assertTrue(gift.getLocation().distanceSquared(hero.getLocation()) < 1.0E-6, "dropped at the hero's feet");
        assertEquals(0.0, gift.getVelocity().lengthSquared(), 1.0E-9, "no random pop away from the hero");
        assertEquals(villager, throwers.get(gift), "still credited to the villager, for gift advancements");
    }

    @Test
    void cooldownRunsWhileTheHeroStaysInView() {
        startClockAt(2 * SCAN);
        offerAt(1000L);
        assertEquals(2 * SCAN, remaining(), "the hero just came into view, so no time has accrued");

        offerAt(1000L + SCAN);
        assertEquals(SCAN, remaining());

        offerAt(1000L + 2 * SCAN);
        assertEquals(0L, remaining());
        assertTrue(rolledTables.isEmpty(), "turning to the hero first");

        offerAt(1000L + 3 * SCAN);
        assertEquals(List.of(LootTables.LIBRARIAN_GIFT), rolledTables);
    }

    @Test
    void returningHeroDoesNotGetAnInstantGift() {
        // Vanilla only counts the cooldown down while a hero is in view, so time away must not count:
        // only the one scan the last sighting is remembered for.
        startClockAt(150L);
        offerAt(1000L);

        heroVisible = false;
        offerAt(1000L + SCAN);

        heroVisible = true;
        offerAt(50_000L);

        assertTrue(rolledTables.isEmpty());
        assertEquals(150L - SCAN, remaining());
    }

    @Test
    void aHeroSeenEveryOtherScanCountsAtHalfSpeed() {
        startClockAt(600L);
        for (long tick = 1000L; tick <= 1160L; tick += SCAN) {
            heroVisible = (tick / SCAN) % 2 == 0;
            offerAt(tick);
        }

        // Seen at 1000, 1040, 1080, 1120, 1160: four credited sightings of one scan each.
        assertEquals(600L - 4 * SCAN, remaining());
    }

    @Test
    void aHiddenSecondHeroDoesNotDiscardTheVisibleOnesTime() {
        PlayerMock hidden = server.addPlayer();
        hidden.teleport(new Location(world, 8, 64, 10));
        hidden.addPotionEffect(new PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE, 6000, 0));
        plugin.getStorage().setHeroVisibility((v, player) -> player != hidden);
        startClockAt(600L);

        for (long tick = 1000L; tick <= 1100L; tick += SCAN) {
            offerAt(tick);
            world.setGameTime(tick + 10L);
            plugin.getStorage().offerHeroGift(villager, hidden);
        }

        assertEquals(600L - 100L, remaining(), "every scan of the visible hero counts in full");
    }

    @Test
    void aCloserHeroDoesNotGetThrownAtOnTheFartherOnesHeadTurn() {
        PlayerMock far = hero;
        far.teleport(new Location(world, 18, 64, 8));
        PlayerMock near = server.addPlayer();
        near.teleport(new Location(world, 8, 64, 10));
        near.addPotionEffect(new PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE, 6000, 0));
        startClockAt(0L);
        offerAt(1000L);
        offerAt(1000L + SCAN);

        plugin.getStorage().offerHeroGift(villager, near);
        assertTrue(rolledTables.isEmpty(), "turns to the closer hero first");
        assertEquals(0f, villager.getLocation().getYaw(), 0.5f, "now facing south (yaw 0), toward it");

        world.setGameTime(1000L + 2 * SCAN);
        plugin.getStorage().offerHeroGift(villager, near);
        assertEquals(List.of(LootTables.LIBRARIAN_GIFT), rolledTables);
    }

    @Test
    void aCloserHeroArrivingBeforeItsFirstScanGetsTheGiftInstead() {
        // The first hero is already in range and faced, and scans first: the gift still goes to the
        // closer hero, after the villager turns to it.
        startClockAt(0L);
        offerAt(1000L);
        PlayerMock closer = server.addPlayer();
        closer.teleport(new Location(world, 8, 64, 9));
        closer.addPotionEffect(new PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE, 6000, 0));

        offerAt(1000L + SCAN);
        assertTrue(rolledTables.isEmpty(), "no throw at the farther hero");
        assertEquals(0f, villager.getLocation().getYaw(), 0.5f, "turned south, to the closer hero");

        world.setGameTime(1000L + 2 * SCAN);
        plugin.getStorage().offerHeroGift(villager, closer);
        assertEquals(List.of(LootTables.LIBRARIAN_GIFT), rolledTables);
        assertTrue(droppedItems().get(0).getVelocity().getZ() > 0.0, "thrown toward the closer hero");
    }

    @Test
    void aHeroBeyondGiftRangeStillRunsTheCooldownDown() {
        hero.teleport(new Location(world, 18, 64, 8));
        startClockAt(SCAN);
        offerAt(1000L);
        offerAt(1000L + SCAN);
        offerAt(1000L + 2 * SCAN);

        assertEquals(0L, remaining(), "a hero 10 blocks away is in view, like vanilla's 16-block sensor");
        assertTrue(rolledTables.isEmpty(), "but too far for the gift");

        hero.teleport(new Location(world, 11, 64, 8));
        offerAt(1000L + 3 * SCAN);

        assertEquals(List.of(LootTables.LIBRARIAN_GIFT), rolledTables, "the ready gift waits for the hero");
    }

    @Test
    void awareVillagerIsLeftToVanilla() {
        villager.setAware(true);
        startClockAt(0L);

        offerAt(1000L);
        offerAt(1000L + SCAN);

        assertTrue(rolledTables.isEmpty());
    }

    @Test
    void noGiftWithoutTheHeroEffect() {
        hero.removePotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE);
        plugin.getHeroTracker().refresh(hero);

        giveReadyGift();

        assertTrue(rolledTables.isEmpty());
        assertTrue(droppedItems().isEmpty());
    }

    @Test
    void staleTrackerEntryNeverGivesAGift() {
        // The tracker still lists the player, but the effect is gone: the live re-check must win.
        hero.removePotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE);

        giveReadyGift();

        assertTrue(rolledTables.isEmpty());
    }

    @Test
    void noGiftToAHeroOutOfView() {
        hero.teleport(new Location(world, 25, 64, 8));
        offerAt(1000L);

        assertNull(remaining(), "a hero 17 blocks away is never seen, so no timer starts");
    }

    @Test
    void noGiftToAHeroTheVillagerCannotSee() {
        heroVisible = false;

        giveReadyGift();

        assertTrue(rolledTables.isEmpty());
    }

    @Test
    void noGiftToASpectator() {
        hero.setGameMode(GameMode.SPECTATOR);
        offerAt(1000L);

        assertNull(remaining());
    }

    @Test
    void disabledInConfigDoesNothing() {
        plugin.getConfig().set("hero-gifts-from-lobotomized-villagers", false);
        // reloadPluginState re-reads config.yml from disk.
        plugin.saveConfig();
        plugin.reloadPluginState();
        useStubs();

        giveReadyGift();

        assertTrue(rolledTables.isEmpty());
        assertEquals("disabled in config", plugin.getStorage().describeHeroGift(villager));
    }

    @Test
    void wakingTheVillagerDropsItsGiftTimer() {
        offerAt(1000L);
        assertNotNull(remaining());

        plugin.getStorage().clearLobotomizedMarker(villager);

        assertNull(remaining());
    }

    @Test
    void debugReportDescribesTheTimerOfALobotomizedVillager() {
        Villager tracked = spawnTrackedLobotomized(new Location(world, 8, 64, 10));
        LobotomizeStorage storage = plugin.getStorage();
        assertTrue(storage.describeHeroGift(tracked).startsWith("no hero seen since it loaded"));

        storage.setHeroGiftClock(tracked, new GiftClock(250L));
        assertEquals("next after 250 more ticks with a hero in view", storage.describeHeroGift(tracked));

        storage.setHeroGiftClock(tracked, new GiftClock(0L));
        assertEquals("ready for the next hero in view within 5 blocks", storage.describeHeroGift(tracked));
    }

    @Test
    void debugReportHasNoTimerForActiveOrUntrackedVillagers() {
        LobotomizeStorage storage = plugin.getStorage();
        storage.addVillager(villager);
        assertTrue(storage.getActive().contains(villager), "precondition: tracked active (no marker)");
        assertEquals("given by vanilla AI (not lobotomized)", storage.describeHeroGift(villager));

        storage.removeVillager(villager);
        assertEquals("none (not tracked by the plugin)", storage.describeHeroGift(villager));
    }

    @Test
    void trackerFollowsPotionEffectEvents() {
        PlayerMock other = server.addPlayer();
        other.addPotionEffect(new PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE, 600, 0));
        assertTrue(plugin.getHeroTracker().candidates().contains(other.getUniqueId()), "added on effect");

        other.removePotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE);
        assertFalse(plugin.getHeroTracker().candidates().contains(other.getUniqueId()), "removed on effect loss");
    }

    @Test
    void heroScanGivesTrackedVillagersTheirGiftWithinTwoScans() {
        Villager tracked = spawnTrackedLobotomized(new Location(world, 8, 64, 10));
        plugin.getStorage().setHeroGiftClock(tracked, new GiftClock(0L));

        // Turn on the first scan, throw on the second: well short of the villager's own 150-tick
        // check, which MockBukkit could not evaluate.
        server.getScheduler().performTicks(2 * SCAN + 1);

        assertEquals(List.of(LootTables.LIBRARIAN_GIFT), rolledTables);
        assertEquals(1, droppedItems().size());
    }

    @Test
    void heroScanIgnoresUntrackedVillagers() {
        plugin.getStorage().removeVillager(villager);
        villager.setAware(false);
        startClockAt(0L);

        server.getScheduler().performTicks(2 * SCAN + 1);

        assertTrue(rolledTables.isEmpty());
    }

    @Test
    void heroScanStopsWhenTheEffectEnds() {
        Villager tracked = spawnTrackedLobotomized(new Location(world, 8, 64, 10));
        hero.removePotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE);
        plugin.getStorage().setHeroGiftClock(tracked, new GiftClock(0L));

        server.getScheduler().performTicks(3 * SCAN + 1);

        assertTrue(rolledTables.isEmpty());
        assertFalse(plugin.getHeroTracker().candidates().contains(hero.getUniqueId()));
    }

    /** Tracked lobotomized via the persisted marker, so no trapped-villager geometry is evaluated. */
    private Villager spawnTrackedLobotomized(Location location) {
        NamespacedKey markerKey = new NamespacedKey(plugin, LobotomizeStorage.LOBOTOMIZED_KEY);
        Villager tracked = world.spawn(location, Villager.class, v -> {
            v.setProfession(Villager.Profession.LIBRARIAN);
            v.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        });
        if (!plugin.getStorage().getLobotomized().contains(tracked)) {
            plugin.getStorage().addVillager(tracked);
        }
        assertTrue(plugin.getStorage().getLobotomized().contains(tracked), "precondition: tracked lobotomized");
        assertFalse(tracked.isAware(), "precondition: AI off");
        return tracked;
    }
}
