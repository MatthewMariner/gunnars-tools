package com.matthewmariner.gunnarstools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.NPCComposition;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The running record: per-kill consumption, the sample count behind it, and the
 * columns that exist so nothing is quietly folded into the average.
 */
public class AmmoLedgerTest
{
	private static final int ARROW = 11;
	private static final int COINS = 995;

	private static final int SPINDEL = 5265;
	private static final int VENENATIS = 6610;

	private static final int[] SPINDEL_STATS = {130, 130, 130, 200, 1, 130};

	private static FoughtNpc spindel(int index)
	{
		return new FoughtNpc(index, SPINDEL, "Spindel", SPINDEL_STATS);
	}

	private static AmmoTally tally(Map<Integer, Long> consumed, Map<Integer, Long> gained)
	{
		AmmoTally out = new AmmoTally();
		out.add(new AmmoDelta(consumed, gained));
		return out;
	}

	private static Map<Integer, Long> one(int id, long quantity)
	{
		Map<Integer, Long> map = new LinkedHashMap<>();
		map.put(id, quantity);
		return map;
	}

	private static AmmoTally spent(long arrows)
	{
		return tally(one(ARROW, arrows), Collections.emptyMap());
	}

	// --- the measurement ------------------------------------------------------

	@Test
	public void perKillIsTheTotalOverTheSampleCount()
	{
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(spindel(40), spent(30)));
		ledger.apply(Attribution.kill(spindel(41), spent(20)));
		ledger.apply(Attribution.kill(spindel(42), spent(25)));

		NpcAmmoRecord record = ledger.get(SPINDEL);
		assertEquals(3, record.getKills());
		assertEquals(Long.valueOf(75L), record.getConsumed().get(ARROW));
		assertEquals(25.0d, record.consumedPerKill(ARROW), 1e-9d);
	}

	@Test
	public void quantitiesAreSummedRatherThanCounted()
	{
		// A record that counted events instead of quantities would say 2 here,
		// and would be wrong by two orders of magnitude.
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(spindel(40), spent(140)));
		ledger.apply(Attribution.kill(spindel(41), spent(160)));

		assertEquals(150.0d, ledger.get(SPINDEL).consumedPerKill(ARROW), 1e-9d);
	}

	@Test
	public void aFractionalFigureIsNotRoundedAway()
	{
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(spindel(40), spent(1)));
		ledger.apply(Attribution.kill(spindel(41), spent(0)));
		ledger.apply(Attribution.kill(spindel(42), spent(0)));

		assertEquals("a third of an arrow per kill is a fact, not a zero",
			1.0d / 3.0d, ledger.get(SPINDEL).consumedPerKill(ARROW), 1e-9d);
	}

	@Test
	public void aFigureWithNoSamplesIsZeroRatherThanADivisionByZero()
	{
		AmmoLedger ledger = new AmmoLedger();
		ledger.apply(Attribution.unattributedDeath(spindel(40)));

		NpcAmmoRecord record = ledger.get(SPINDEL);
		assertEquals(0, record.getKills());
		assertEquals(0.0d, record.consumedPerKill(ARROW), 1e-9d);
	}

	@Test
	public void monstersAreKeptApartByIdRatherThanByName()
	{
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(spindel(40), spent(30)));
		ledger.apply(Attribution.kill(
			new FoughtNpc(41, VENENATIS, "Venenatis", new int[]{200, 200, 200, 500, 1, 200}),
			spent(120)));

		assertEquals(2, ledger.size());
		assertEquals(30.0d, ledger.get(SPINDEL).consumedPerKill(ARROW), 1e-9d);
		assertEquals(120.0d, ledger.get(VENENATIS).consumedPerKill(ARROW), 1e-9d);
	}

	// --- recovery is disclosed, never netted ---------------------------------

	@Test
	public void ammunitionPickedBackUpIsNeverSubtractedFromWhatWasSpent()
	{
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(spindel(40), tally(one(ARROW, 100), one(ARROW, 40))));

		NpcAmmoRecord record = ledger.get(SPINDEL);
		assertEquals("the gross figure is the one a trip has to cover",
			100.0d, record.consumedPerKill(ARROW), 1e-9d);
		assertEquals("and the contamination sits beside it where it can be seen",
			Long.valueOf(40L), record.getRecovered().get(ARROW));
	}

	@Test
	public void lootIsNotRecordedAsRecoveredAmmunition()
	{
		// Coins are stackable and therefore metered, and every kill drops some.
		// Left unfiltered they would fill the recovery column with something that
		// has nothing to do with ammunition, and the column would stop meaning
		// "this consumption figure may be contaminated".
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(spindel(40), tally(one(ARROW, 100), one(COINS, 5000))));

		NpcAmmoRecord record = ledger.get(SPINDEL);
		assertFalse(record.getRecovered().containsKey(COINS));
		assertEquals(Collections.singleton(ARROW), record.getConsumed().keySet());
	}

	@Test
	public void aRecoveryOfSomethingAlreadySpentOnThisMonsterIsKept()
	{
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(spindel(40), spent(100)));
		// Second kill: arrows found before any were fired at this one.
		ledger.apply(Attribution.kill(spindel(41), tally(Collections.emptyMap(), one(ARROW, 30))));

		assertEquals("arrows are an item this monster costs, so a gain of them counts",
			Long.valueOf(30L), ledger.get(SPINDEL).getRecovered().get(ARROW));
	}

	// --- the two columns that stay out of the average -------------------------

	@Test
	public void anAbandonedFightRaisesNeitherTheNumeratorNorTheDenominator()
	{
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(spindel(40), spent(30)));
		ledger.apply(Attribution.abandoned(spindel(41), spent(90)));

		NpcAmmoRecord record = ledger.get(SPINDEL);
		assertEquals(1, record.getKills());
		assertEquals("thirty per kill, not sixty", 30.0d, record.consumedPerKill(ARROW), 1e-9d);
		assertEquals(1, record.getAbandonedFights());
		assertEquals(Long.valueOf(90L), record.getAbandoned().get(ARROW));
	}

	@Test
	public void anUnattributedDeathIsCountedAndChangesNothingElse()
	{
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(spindel(40), spent(30)));
		ledger.apply(Attribution.unattributedDeath(spindel(41)));
		ledger.apply(Attribution.unattributedDeath(spindel(42)));

		NpcAmmoRecord record = ledger.get(SPINDEL);
		assertEquals(1, record.getKills());
		assertEquals(30.0d, record.consumedPerKill(ARROW), 1e-9d);
		assertEquals(2, record.getUnattributedDeaths());
	}

	// --- stats ----------------------------------------------------------------

	@Test
	public void theLiveNpcsStatsAreStoredWithTheRecord()
	{
		AmmoLedger ledger = new AmmoLedger();
		ledger.apply(Attribution.kill(spindel(40), spent(30)));

		NpcAmmoRecord record = ledger.get(SPINDEL);
		assertTrue(record.hasStats());
		assertEquals(200, record.getStat(NPCComposition.STAT_HITPOINTS));
		org.junit.Assert.assertArrayEquals(SPINDEL_STATS, record.getStats());
	}

	@Test
	public void anUnpopulatedStatsArrayNeverOverwritesARealOne()
	{
		// The same monster read on a tick where its composition had not resolved.
		// Letting six ones win would replace a real 200 hitpoints with a 1 and
		// carry it into every later milestone's arithmetic.
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(spindel(40), spent(30)));
		ledger.apply(Attribution.kill(
			new FoughtNpc(41, SPINDEL, "Spindel", new int[]{1, 1, 1, 1, 1, 1}),
			spent(30)));

		NpcAmmoRecord record = ledger.get(SPINDEL);
		assertTrue(record.hasStats());
		assertEquals(200, record.getStat(NPCComposition.STAT_HITPOINTS));
	}

	@Test
	public void realStatsArrivingLaterFillInAnUnpopulatedRecord()
	{
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(
			new FoughtNpc(40, SPINDEL, "Spindel", new int[]{1, 1, 1, 1, 1, 1}), spent(30)));
		assertFalse(ledger.get(SPINDEL).hasStats());

		ledger.apply(Attribution.kill(spindel(41), spent(30)));

		assertTrue(ledger.get(SPINDEL).hasStats());
		assertEquals(200, ledger.get(SPINDEL).getStat(NPCComposition.STAT_HITPOINTS));
	}

	@Test
	public void theRecordsStatsArrayIsNotEditableFromOutside()
	{
		AmmoLedger ledger = new AmmoLedger();
		ledger.apply(Attribution.kill(spindel(40), spent(30)));

		ledger.get(SPINDEL).getStats()[NPCComposition.STAT_HITPOINTS] = 2;

		assertEquals(200, ledger.get(SPINDEL).getStat(NPCComposition.STAT_HITPOINTS));
	}

	// --- housekeeping ---------------------------------------------------------

	@Test
	public void anUnmeasuredMonsterHasNoRecordRatherThanAnEmptyOne()
	{
		AmmoLedger ledger = new AmmoLedger();
		assertNull(ledger.get(SPINDEL));
		assertTrue(ledger.isEmpty());
	}

	@Test
	public void clearLeavesNothingBehind()
	{
		AmmoLedger ledger = new AmmoLedger();
		ledger.apply(Attribution.kill(spindel(40), spent(30)));

		ledger.clear();

		assertTrue(ledger.isEmpty());
		assertEquals(0, ledger.size());
		assertNull(ledger.get(SPINDEL));
	}

	@Test
	public void aWindowHandedToTheLedgerIsCopiedRatherThanShared()
	{
		// KillAttribution reuses one tally for the open window, so a record that
		// held a reference to it would keep changing after the kill was banked.
		AmmoLedger ledger = new AmmoLedger();
		AmmoTally window = spent(30);

		ledger.apply(Attribution.kill(spindel(40), window));
		window.add(new AmmoDelta(one(ARROW, 500), Collections.emptyMap()));

		assertEquals(30.0d, ledger.get(SPINDEL).consumedPerKill(ARROW), 1e-9d);
	}
}
