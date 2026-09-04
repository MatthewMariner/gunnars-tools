package com.matthewmariner.gunnarstools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What survives a restart, and the promise that reading it back can never take
 * the plugin down.
 *
 * <p>The value being parsed is a string in a user-editable profile. It may have
 * been written by a different version, hand-edited, truncated by a crash mid-save
 * or synchronised in from another machine. A {@code startUp()} that throws on one
 * of those leaves the player with a plugin that will not start, which is a worse
 * outcome than any amount of lost history — so every malformed shape below is
 * asserted to produce a smaller archive rather than an exception.
 */
public class AmmoArchiveTest
{
	private static final int SPINDEL = 5265;
	private static final int SKELETON = 6612;
	private static final int ARROW = 892;
	private static final int BOLT = 9144;

	private static final Loadout SHORTBOW = new Loadout(861, ARROW);
	private static final Loadout CROSSBOW = new Loadout(21902, BOLT);

	private static FoughtNpc npc(int index, int id, String name, int hitpoints)
	{
		return new FoughtNpc(index, id, name, new int[]{130, 130, 130, hitpoints, 1, 130});
	}

	private static AmmoTally spent(int itemId, long quantity)
	{
		AmmoTally tally = new AmmoTally();
		tally.add(new AmmoDelta(Collections.singletonMap(itemId, quantity), Collections.emptyMap()));
		return tally;
	}

	/** A ledger with one monster killed {@code kills} times at {@code perKill} arrows. */
	private static NpcAmmoRecord record(int npcId, String name, int hitpoints, Loadout loadout,
		int kills, long perKill)
	{
		AmmoLedger ledger = new AmmoLedger();
		ledger.equipped(loadout);
		for (int kill = 0; kill < kills; kill++)
		{
			ledger.apply(Attribution.kill(npc(40 + kill, npcId, name, hitpoints),
				spent(ARROW, perKill), 0));
		}
		return ledger.get(npcId);
	}

	// --- what gets remembered --------------------------------------------------

	@Test
	public void aMeasuredMonsterIsRememberedWithItsDenominatorInMonsters()
	{
		AmmoArchive archive = new AmmoArchive();
		archive.remember(record(SPINDEL, "Spindel", 200, SHORTBOW, 4, 25L), SHORTBOW);

		AmmoArchive.Entry entry = archive.get(SPINDEL);
		assertEquals(SPINDEL, entry.getNpcId());
		assertEquals("Spindel", entry.getName());
		assertEquals(200, entry.getHitpoints());
		assertEquals("monsters priced, not kills — the unit a trip size is counted in",
			4, entry.getMonsters());
		assertEquals(Long.valueOf(100L), entry.getConsumed().get(ARROW));
		assertEquals(SHORTBOW, entry.getLoadout());
	}

	@Test
	public void aBarragesCoVictimsAreInTheDenominatorTheArchiveKeeps()
	{
		// The threefold overstatement this project has already had to correct once.
		// Storing the kill count instead of the monsters priced would reintroduce it
		// across a restart, where nothing else would catch it.
		AmmoLedger ledger = new AmmoLedger();
		ledger.equipped(SHORTBOW);
		ledger.apply(Attribution.kill(npc(40, SPINDEL, "Spindel", 200), spent(ARROW, 12L), 2));

		AmmoArchive archive = new AmmoArchive();
		archive.remember(ledger.get(SPINDEL), SHORTBOW);

		assertEquals(1, ledger.get(SPINDEL).getKills());
		assertEquals("one kill, three monsters", 3, archive.get(SPINDEL).getMonsters());
	}

	@Test
	public void aRecordWithNothingMeasuredIsNotRemembered()
	{
		// An entry whose denominator is zero is not a rate, and keeping it would
		// occupy one of the forty slots to say nothing.
		AmmoLedger ledger = new AmmoLedger();
		ledger.equipped(SHORTBOW);
		ledger.apply(Attribution.unattributedDeath(npc(40, SPINDEL, "Spindel", 200)));

		AmmoArchive archive = new AmmoArchive();
		archive.remember(ledger.get(SPINDEL), SHORTBOW);

		assertTrue(archive.isEmpty());
	}

	@Test
	public void aKillThatSpentNothingIsNotRemembered()
	{
		AmmoLedger ledger = new AmmoLedger();
		ledger.equipped(SHORTBOW);
		ledger.apply(Attribution.kill(npc(40, SPINDEL, "Spindel", 200), new AmmoTally(), 0));

		AmmoArchive archive = new AmmoArchive();
		archive.remember(ledger.get(SPINDEL), SHORTBOW);

		assertTrue("a monster with no measured cost has no rate to keep", archive.isEmpty());
	}

	@Test
	public void rememberingAMonsterAgainReplacesItRatherThanAddingToIt()
	{
		// Adding would sum two setups into one rate with nothing to say so, and
		// would double-count a session saved twice.
		AmmoArchive archive = new AmmoArchive();
		archive.remember(record(SPINDEL, "Spindel", 200, SHORTBOW, 4, 25L), SHORTBOW);
		archive.remember(record(SPINDEL, "Spindel", 200, SHORTBOW, 2, 30L), SHORTBOW);

		assertEquals(1, archive.size());
		assertEquals(2, archive.get(SPINDEL).getMonsters());
		assertEquals(Long.valueOf(60L), archive.get(SPINDEL).getConsumed().get(ARROW));
	}

	@Test
	public void reRememberingAMonsterMakesItTheMostRecentlyMeasured()
	{
		// LinkedHashMap keeps a key's original position on a plain overwrite, which
		// would make "most recently measured" mean "first ever measured" and evict
		// exactly the wrong entries at the cap. The removal before the put is what
		// stops that, and this is the assertion that notices if it goes.
		AmmoArchive archive = new AmmoArchive();
		archive.remember(record(SPINDEL, "Spindel", 200, SHORTBOW, 4, 25L), SHORTBOW);
		archive.remember(record(SKELETON, "Skeleton", 29, SHORTBOW, 4, 5L), SHORTBOW);
		archive.remember(record(SPINDEL, "Spindel", 200, SHORTBOW, 5, 25L), SHORTBOW);

		List<Integer> order = new ArrayList<>();
		archive.getEntries().forEach(entry -> order.add(entry.getNpcId()));

		assertEquals("least recently measured first", java.util.Arrays.asList(SKELETON, SPINDEL),
			order);
	}

	@Test
	public void theOldestEntryIsDroppedAtTheCap()
	{
		AmmoArchive archive = new AmmoArchive();
		for (int i = 0; i < AmmoArchive.MAX_ENTRIES + 5; i++)
		{
			archive.remember(record(1000 + i, "Monster " + i, 50, SHORTBOW, 1, 10L), SHORTBOW);
		}

		assertEquals(AmmoArchive.MAX_ENTRIES, archive.size());
		assertNull("the first five are gone", archive.get(1000));
		assertNull(archive.get(1004));
		assertNotNull("the newest is not", archive.get(1000 + AmmoArchive.MAX_ENTRIES + 4));
	}

	@Test
	public void forgettingOneMonsterLeavesTheRest()
	{
		AmmoArchive archive = new AmmoArchive();
		archive.remember(record(SPINDEL, "Spindel", 200, SHORTBOW, 4, 25L), SHORTBOW);
		archive.remember(record(SKELETON, "Skeleton", 29, SHORTBOW, 4, 5L), SHORTBOW);

		archive.forget(SPINDEL);

		assertNull(archive.get(SPINDEL));
		assertNotNull(archive.get(SKELETON));
	}

	@Test
	public void clearingLeavesItLikeANewOne()
	{
		AmmoArchive archive = new AmmoArchive();
		archive.remember(record(SPINDEL, "Spindel", 200, SHORTBOW, 4, 25L), SHORTBOW);

		archive.clear();

		assertTrue(archive.isEmpty());
		assertEquals(0, archive.size());
		assertEquals("", archive.format());
	}

	// --- the round trip --------------------------------------------------------

	@Test
	public void everythingRemembersSurvivesTheRoundTrip()
	{
		AmmoArchive archive = new AmmoArchive();
		archive.remember(record(SPINDEL, "Spindel", 200, SHORTBOW, 4, 25L), SHORTBOW);
		archive.remember(record(SKELETON, "Skeleton", 29, CROSSBOW, 12, 8L), CROSSBOW);

		AmmoArchive restored = AmmoArchive.parse(archive.format());

		assertEquals(2, restored.size());
		assertEquals("Spindel", restored.get(SPINDEL).getName());
		assertEquals(200, restored.get(SPINDEL).getHitpoints());
		assertEquals(4, restored.get(SPINDEL).getMonsters());
		assertEquals(Long.valueOf(100L), restored.get(SPINDEL).getConsumed().get(ARROW));
		assertEquals(SHORTBOW, restored.get(SPINDEL).getLoadout());
		assertEquals("the setup a figure was measured on is what lets it be said out loud",
			CROSSBOW, restored.get(SKELETON).getLoadout());
	}

	@Test
	public void aMonsterWithTwoAmmunitionTypesKeepsBoth()
	{
		AmmoLedger ledger = new AmmoLedger();
		ledger.equipped(SHORTBOW);
		AmmoTally window = new AmmoTally();
		window.add(new AmmoDelta(Collections.singletonMap(ARROW, 20L), Collections.emptyMap()));
		window.add(new AmmoDelta(Collections.singletonMap(BOLT, 3L), Collections.emptyMap()));
		ledger.apply(Attribution.kill(npc(40, SPINDEL, "Spindel", 200), window, 0));

		AmmoArchive archive = new AmmoArchive();
		archive.remember(ledger.get(SPINDEL), SHORTBOW);

		AmmoArchive restored = AmmoArchive.parse(archive.format());
		assertEquals(Long.valueOf(20L), restored.get(SPINDEL).getConsumed().get(ARROW));
		assertEquals(Long.valueOf(3L), restored.get(SPINDEL).getConsumed().get(BOLT));
	}

	@Test
	public void anEmptyArchiveWritesAnEmptyString()
	{
		// Not a bare version marker. An empty string is what an unset config key
		// already reads as, so an empty archive and a never-saved one are the same
		// value rather than two shapes that both have to be handled.
		assertEquals("", new AmmoArchive().format());
	}

	@Test
	public void aStoredEntryCannotBeEditedThroughItsMap()
	{
		AmmoArchive archive = AmmoArchive.parse("1;5265,Spindel,200,4,861,892,892:100");

		try
		{
			archive.get(SPINDEL).getConsumed().put(ARROW, 1L);
			org.junit.Assert.fail("an entry is a reading, not a handle");
		}
		catch (UnsupportedOperationException expected)
		{
			assertEquals(Long.valueOf(100L), archive.get(SPINDEL).getConsumed().get(ARROW));
		}
	}

	// --- hostile input ---------------------------------------------------------

	@Test
	public void nothingStoredIsAnEmptyArchive()
	{
		assertTrue(AmmoArchive.parse(null).isEmpty());
		assertTrue(AmmoArchive.parse("").isEmpty());
	}

	@Test
	public void anUnknownVersionIsDiscardedRatherThanGuessedAt()
	{
		// A later shape is introduced by bumping the version. Reading one written by
		// a newer plugin as if it were this one would file a monster's ammunition
		// under its hitpoints.
		assertTrue(AmmoArchive.parse("2;5265,Spindel,200,4,861,892,892:100").isEmpty());
		assertTrue(AmmoArchive.parse("nonsense;5265,Spindel,200,4,861,892,892:100").isEmpty());
		assertEquals("1", AmmoArchive.VERSION);
	}

	@Test
	public void aMalformedEntryLosesItselfAndNotTheRest()
	{
		AmmoArchive archive = AmmoArchive.parse(
			"1;5265,Spindel,200,4,861,892,892:100;garbage;6612,Skeleton,29,12,861,892,892:96");

		assertEquals(2, archive.size());
		assertNotNull(archive.get(SPINDEL));
		assertNotNull(archive.get(SKELETON));
	}

	@Test
	public void anEntryWithTooFewFieldsIsDropped()
	{
		assertTrue(AmmoArchive.parse("1;5265,Spindel,200,4,861,892").isEmpty());
	}

	@Test
	public void anEntryWithTooManyFieldsIsDroppedRatherThanReadShifted()
	{
		// The check was "at least seven", which reads a hand-edited entry with a
		// stray comma in the name as a well-formed *different* entry: every field
		// after the name slides along one, so the hitpoints silently become zero and
		// the weapon and ammunition take their neighbours' values. A malformed value
		// read as a valid one is the single outcome this parser exists to make
		// impossible; losing the entry is the cheaper failure.
		assertTrue(AmmoArchive.parse("1;5265,Sea,snake,200,4,861,892,892:100").isEmpty());
		assertTrue(AmmoArchive.parse("1;5265,Spindel,200,4,861,892,892:100,extra").isEmpty());
	}

	@Test
	public void aNameIsStoredTheSameWayWhicheverDoorItCameIn()
	{
		// remember() kept the raw name and parseEntry() kept the sanitised one, so a
		// monster whose name holds a separator matched by name before a restart and
		// not after it. One name, settled once, at construction.
		AmmoLedger ledger = new AmmoLedger();
		ledger.equipped(SHORTBOW);
		ledger.apply(Attribution.kill(npc(40, 123, "Sea, snake", 40), spent(ARROW, 10L), 0));

		AmmoArchive archive = new AmmoArchive();
		archive.remember(ledger.get(123), SHORTBOW);

		assertEquals("Sea  snake", archive.get(123).getName());
		assertEquals(archive.get(123).getName(),
			AmmoArchive.parse(archive.format()).get(123).getName());
	}

	@Test
	public void anEntryWithNoMonstersIsDropped()
	{
		// Its denominator is zero, so it is not a rate.
		assertTrue(AmmoArchive.parse("1;5265,Spindel,200,0,861,892,892:100").isEmpty());
		assertTrue(AmmoArchive.parse("1;5265,Spindel,200,-3,861,892,892:100").isEmpty());
	}

	@Test
	public void anEntryWithNoUsableQuantitiesIsDropped()
	{
		assertTrue(AmmoArchive.parse("1;5265,Spindel,200,4,861,892,892:0").isEmpty());
		assertTrue(AmmoArchive.parse("1;5265,Spindel,200,4,861,892,-1:100").isEmpty());
		assertTrue(AmmoArchive.parse("1;5265,Spindel,200,4,861,892,").isEmpty());
	}

	@Test
	public void oneBadItemPairDoesNotLoseTheMonstersOtherAmmunition()
	{
		AmmoArchive archive = AmmoArchive.parse(
			"1;5265,Spindel,200,4,861,892,892:100|nonsense|9144:12");

		assertEquals(Long.valueOf(100L), archive.get(SPINDEL).getConsumed().get(ARROW));
		assertEquals(Long.valueOf(12L), archive.get(SPINDEL).getConsumed().get(BOLT));
	}

	@Test
	public void anUnreadableIdLosesTheEntryRatherThanBecomingMonsterZero()
	{
		assertTrue(AmmoArchive.parse("1;x,Spindel,200,4,861,892,892:100").isEmpty());
	}

	@Test
	public void unreadableHitpointsLoseTheHitpointsAndKeepTheRate()
	{
		// A field that does not parse loses its field; the entry survives. The rate
		// is still usable for this same monster — only the scaling onto a different
		// one needs the hitpoints.
		AmmoArchive archive = AmmoArchive.parse("1;5265,Spindel,x,4,861,892,892:100");

		assertEquals(0, archive.get(SPINDEL).getHitpoints());
		assertEquals(Long.valueOf(100L), archive.get(SPINDEL).getConsumed().get(ARROW));
	}

	@Test
	public void negativeHitpointsInTheStoreAreFlattenedToUnresolved()
	{
		assertEquals(0, AmmoArchive.parse("1;5265,Spindel,-200,4,861,892,892:100")
			.get(SPINDEL).getHitpoints());
	}

	@Test
	public void unreadableLoadoutFieldsBecomeTheEmptySlotRatherThanItemZero()
	{
		// Item id 0 is a real item. A weapon that fails to parse must not become one.
		AmmoArchive.Entry entry =
			AmmoArchive.parse("1;5265,Spindel,200,4,x,y,892:100").get(SPINDEL);

		assertEquals(Loadout.UNKNOWN, entry.getLoadout());
	}

	@Test
	public void aStoredArchiveOverTheCapIsTrimmedOnTheWayIn()
	{
		// A profile hand-edited, or written by a version with a larger cap. The cap
		// bounds the value this plugin writes back, so it has to bound what it reads.
		StringBuilder oversized = new StringBuilder(AmmoArchive.VERSION);
		for (int i = 0; i < AmmoArchive.MAX_ENTRIES + 10; i++)
		{
			oversized.append(";").append(2000 + i).append(",Monster,50,1,861,892,892:10");
		}

		AmmoArchive archive = AmmoArchive.parse(oversized.toString());

		assertEquals(AmmoArchive.MAX_ENTRIES, archive.size());
		assertNull("the oldest went", archive.get(2000));
		assertNotNull("the newest stayed", archive.get(2000 + AmmoArchive.MAX_ENTRIES + 9));
	}

	@Test
	public void theEntriesCollectionCannotBeEditedFromOutside()
	{
		AmmoArchive archive = AmmoArchive.parse("1;5265,Spindel,200,4,861,892,892:100");

		try
		{
			archive.getEntries().clear();
			org.junit.Assert.fail("the archive is not a handle onto its own storage");
		}
		catch (UnsupportedOperationException expected)
		{
			assertFalse(archive.isEmpty());
		}
	}
}
