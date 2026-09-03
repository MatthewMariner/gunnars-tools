package com.matthewmariner.gunnarstools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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

		ledger.apply(Attribution.kill(spindel(40), spent(30), 0));
		ledger.apply(Attribution.kill(spindel(41), spent(20), 0));
		ledger.apply(Attribution.kill(spindel(42), spent(25), 0));

		NpcAmmoRecord record = ledger.get(SPINDEL);
		assertEquals(3, record.getKills());
		assertEquals(Long.valueOf(75L), record.getConsumed().get(ARROW));
		assertEquals(25.0d, record.estimate(ARROW).getPerAttributedKill(), 1e-9d);
	}

	@Test
	public void quantitiesAreSummedRatherThanCounted()
	{
		// A record that counted events instead of quantities would say 2 here,
		// and would be wrong by two orders of magnitude.
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(spindel(40), spent(140), 0));
		ledger.apply(Attribution.kill(spindel(41), spent(160), 0));

		assertEquals(150.0d, ledger.get(SPINDEL).estimate(ARROW).getPerAttributedKill(), 1e-9d);
	}

	@Test
	public void aFractionalFigureIsNotRoundedAway()
	{
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(spindel(40), spent(1), 0));
		ledger.apply(Attribution.kill(spindel(41), spent(0), 0));
		ledger.apply(Attribution.kill(spindel(42), spent(0), 0));

		assertEquals("a third of an arrow per kill is a fact, not a zero",
			1.0d / 3.0d, ledger.get(SPINDEL).estimate(ARROW).getPerAttributedKill(), 1e-9d);
	}

	@Test
	public void aRecordWithNoSamplesPublishesNoFigureRatherThanADividedZero()
	{
		// A record can exist with no kills in it — an unattributed death opens one.
		// It has to answer "what does this cost" with nothing at all rather than
		// with a zero, because "no arrows were ever spent on this" and "arrows cost
		// nothing here" are different claims and only one of them is ever true.
		AmmoLedger ledger = new AmmoLedger();
		ledger.apply(Attribution.unattributedDeath(spindel(40)));

		NpcAmmoRecord record = ledger.get(SPINDEL);
		assertEquals(0, record.getKills());
		assertEquals(0, record.getMonstersPriced());
		assertNull(record.estimate(ARROW));
		assertTrue(record.estimates().isEmpty());
	}

	@Test
	public void monstersAreKeptApartByIdRatherThanByName()
	{
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(spindel(40), spent(30), 0));
		ledger.apply(Attribution.kill(
			new FoughtNpc(41, VENENATIS, "Venenatis", new int[]{200, 200, 200, 500, 1, 200}),
			spent(120), 0));

		assertEquals(2, ledger.size());
		assertEquals(30.0d, ledger.get(SPINDEL).estimate(ARROW).getPerAttributedKill(), 1e-9d);
		assertEquals(120.0d, ledger.get(VENENATIS).estimate(ARROW).getPerAttributedKill(), 1e-9d);
	}

	// --- recovery is disclosed, never netted ---------------------------------

	@Test
	public void ammunitionPickedBackUpIsNeverSubtractedFromWhatWasSpent()
	{
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(spindel(40), tally(one(ARROW, 100), one(ARROW, 40)), 0));

		NpcAmmoRecord record = ledger.get(SPINDEL);
		assertEquals("the gross figure is the one a trip has to cover",
			100.0d, record.estimate(ARROW).getPerAttributedKill(), 1e-9d);
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

		ledger.apply(Attribution.kill(spindel(40), tally(one(ARROW, 100), one(COINS, 5000)), 0));

		NpcAmmoRecord record = ledger.get(SPINDEL);
		assertFalse(record.getRecovered().containsKey(COINS));
		assertEquals(Collections.singleton(ARROW), record.getConsumed().keySet());
	}

	@Test
	public void aRecoveryOfSomethingAlreadySpentOnThisMonsterIsKept()
	{
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(spindel(40), spent(100), 0));
		// Second kill: arrows found before any were fired at this one.
		ledger.apply(Attribution.kill(spindel(41), tally(Collections.emptyMap(), one(ARROW, 30)), 0));

		assertEquals("arrows are an item this monster costs, so a gain of them counts",
			Long.valueOf(30L), ledger.get(SPINDEL).getRecovered().get(ARROW));
	}

	// --- the two columns that stay out of the average -------------------------

	@Test
	public void anAbandonedFightRaisesNeitherTheNumeratorNorTheDenominator()
	{
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(spindel(40), spent(30), 0));
		ledger.apply(Attribution.abandoned(spindel(41), spent(90)));

		NpcAmmoRecord record = ledger.get(SPINDEL);
		assertEquals(1, record.getKills());
		assertEquals("thirty per kill, not sixty",
			30.0d, record.estimate(ARROW).getPerAttributedKill(), 1e-9d);
		assertEquals(1, record.getAbandonedFights());
		assertEquals(Long.valueOf(90L), record.getAbandoned().get(ARROW));
	}

	@Test
	public void anUnattributedDeathIsCountedAndChangesNothingElse()
	{
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(spindel(40), spent(30), 0));
		ledger.apply(Attribution.unattributedDeath(spindel(41)));
		ledger.apply(Attribution.unattributedDeath(spindel(42)));

		NpcAmmoRecord record = ledger.get(SPINDEL);
		assertEquals(1, record.getKills());
		assertEquals(30.0d, record.estimate(ARROW).getPerAttributedKill(), 1e-9d);
		assertEquals(2, record.getUnattributedDeaths());
	}

	// --- stats ----------------------------------------------------------------

	@Test
	public void theLiveNpcsStatsAreStoredWithTheRecord()
	{
		AmmoLedger ledger = new AmmoLedger();
		ledger.apply(Attribution.kill(spindel(40), spent(30), 0));

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

		ledger.apply(Attribution.kill(spindel(40), spent(30), 0));
		ledger.apply(Attribution.kill(
			new FoughtNpc(41, SPINDEL, "Spindel", new int[]{1, 1, 1, 1, 1, 1}),
			spent(30), 0));

		NpcAmmoRecord record = ledger.get(SPINDEL);
		assertTrue(record.hasStats());
		assertEquals(200, record.getStat(NPCComposition.STAT_HITPOINTS));
	}

	@Test
	public void realStatsArrivingLaterFillInAnUnpopulatedRecord()
	{
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(
			new FoughtNpc(40, SPINDEL, "Spindel", new int[]{1, 1, 1, 1, 1, 1}), spent(30), 0));
		assertFalse(ledger.get(SPINDEL).hasStats());

		ledger.apply(Attribution.kill(spindel(41), spent(30), 0));

		assertTrue(ledger.get(SPINDEL).hasStats());
		assertEquals(200, ledger.get(SPINDEL).getStat(NPCComposition.STAT_HITPOINTS));
	}

	@Test
	public void theRecordsStatsArrayIsNotEditableFromOutside()
	{
		AmmoLedger ledger = new AmmoLedger();
		ledger.apply(Attribution.kill(spindel(40), spent(30), 0));

		ledger.get(SPINDEL).getStats()[NPCComposition.STAT_HITPOINTS] = 2;

		assertEquals(200, ledger.get(SPINDEL).getStat(NPCComposition.STAT_HITPOINTS));
	}

	// --- the samples behind the mean ------------------------------------------

	@Test
	public void everyKillLeavesOneSampleBehindAndTheySumToTheTotal()
	{
		// The invariant the whole spread rests on: one entry per kill, summing to
		// the consumed column. A series that drifted out of step with the kill
		// count would put a percentile from four kills next to an "n=6".
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(spindel(40), spent(30), 0));
		ledger.apply(Attribution.kill(spindel(41), spent(20), 0));
		ledger.apply(Attribution.kill(spindel(42), spent(25), 0));

		ConsumptionEstimate estimate = ledger.get(SPINDEL).estimate(ARROW);
		assertEquals(3, estimate.getAttributedKills());
		assertEquals(75L, estimate.getConsumed());
		assertEquals(20L, estimate.getLowestKill());
		assertEquals(25L, estimate.getMedianKill());
		assertEquals(30L, estimate.getHighestKill());
	}

	@Test
	public void aKillThatSpentNothingOfAnItemStillCountsAsASampleOfIt()
	{
		// Dropping the zero would leave two samples against three kills, and the
		// mean of what was left would be fifteen instead of ten.
		AmmoLedger ledger = new AmmoLedger();

		ledger.apply(Attribution.kill(spindel(40), spent(15), 0));
		ledger.apply(Attribution.kill(
			spindel(41), tally(Collections.emptyMap(), Collections.emptyMap()), 0));
		ledger.apply(Attribution.kill(spindel(42), spent(15), 0));

		ConsumptionEstimate estimate = ledger.get(SPINDEL).estimate(ARROW);
		assertEquals(3, estimate.getAttributedKills());
		assertEquals(10.0d, estimate.getPerAttributedKill(), 1e-9d);
		assertEquals("and the plugin says so rather than hiding it",
			2, estimate.getKillsWithConsumption());
		assertEquals(0L, estimate.getLowestKill());
	}

	@Test
	public void anItemFirstSeenLateIsBackfilledSoItsMeanMatchesTheTotal()
	{
		// Switching ammunition mid-session. The four kills before the switch really
		// did cost no bolts, and a series that started at the switch would report
		// forty bolts per kill instead of eight.
		AmmoLedger ledger = new AmmoLedger();
		final int BOLT = 9144;

		for (int kill = 40; kill < 44; kill++)
		{
			ledger.apply(Attribution.kill(spindel(kill), spent(30), 0));
		}
		ledger.apply(Attribution.kill(spindel(44), tally(one(BOLT, 40), Collections.emptyMap()), 0));

		NpcAmmoRecord record = ledger.get(SPINDEL);
		ConsumptionEstimate bolts = record.estimate(BOLT);
		assertEquals(5, bolts.getAttributedKills());
		assertEquals(8.0d, bolts.getPerAttributedKill(), 1e-9d);
		assertEquals("the series is exactly as long as the kill count",
			record.getKills(), bolts.getAttributedKills());
		assertEquals("and it sums to the record's own consumed total",
			record.getConsumed().get(BOLT), Long.valueOf(bolts.getConsumed()));
		assertEquals(1, bolts.getKillsWithConsumption());

		// And the arrows are unharmed by the newcomer.
		assertEquals(5, record.estimate(ARROW).getAttributedKills());
		assertEquals(24.0d, record.estimate(ARROW).getPerAttributedKill(), 1e-9d);
	}

	@Test
	public void anItemThisMonsterHasNeverCostHasNoEstimateRatherThanAZeroedOne()
	{
		AmmoLedger ledger = new AmmoLedger();
		ledger.apply(Attribution.kill(spindel(40), spent(30), 0));

		assertNull(ledger.get(SPINDEL).estimate(COINS));
		assertEquals(Collections.singleton(ARROW), ledger.get(SPINDEL).getConsumedItemIds());
	}

	@Test
	public void estimatesComeBackDearestFirst()
	{
		AmmoLedger ledger = new AmmoLedger();
		Map<Integer, Long> both = new LinkedHashMap<>();
		both.put(ARROW, 25L);
		both.put(COINS, 4000L);
		ledger.apply(Attribution.kill(spindel(40), tally(both, Collections.emptyMap()), 0));

		List<ConsumptionEstimate> estimates = ledger.get(SPINDEL).estimates();
		assertEquals(2, estimates.size());
		assertEquals(COINS, estimates.get(0).getItemId());
		assertEquals(ARROW, estimates.get(1).getItemId());
	}

	@Test
	public void theEstimateListIsNotEditableFromOutside()
	{
		// An overlay holds this list for as long as a frame takes to draw. It must
		// not be able to reorder the record's own view of itself.
		AmmoLedger ledger = new AmmoLedger();
		ledger.apply(Attribution.kill(spindel(40), spent(30), 0));

		try
		{
			ledger.get(SPINDEL).estimates().clear();
			org.junit.Assert.fail("the estimate list must be read-only");
		}
		catch (UnsupportedOperationException expected)
		{
			assertEquals(1, ledger.get(SPINDEL).estimates().size());
		}
	}

	@Test
	public void onlyAKillEverCarriesACoVictimCount()
	{
		// An abandoned window's ammunition is out of the average entirely, so a
		// co-victim count on it would be a divisor with no dividend; an
		// unattributed death is itself somebody else's co-victim. Pinned because
		// the record trusts the verdict rather than re-deriving it.
		assertEquals(0, Attribution.abandoned(spindel(40), spent(90)).getCoVictims());
		assertEquals(0, Attribution.unattributedDeath(spindel(41)).getCoVictims());
		assertEquals(2, Attribution.kill(spindel(42), spent(4), 2).getCoVictims());
	}

	// --- the corrected denominator --------------------------------------------

	@Test
	public void coVictimsRaiseTheMonsterCountWithoutRaisingTheKillCount()
	{
		// The four-barrage case from the README, run through the record: sixteen
		// runes, four priced kills, twelve monsters dead.
		AmmoLedger ledger = new AmmoLedger();
		for (int index = 40; index < 44; index++)
		{
			ledger.apply(Attribution.kill(spindel(index), spent(4), 2));
		}

		NpcAmmoRecord record = ledger.get(SPINDEL);
		assertEquals(4, record.getKills());
		assertEquals(8, record.getPricedCoVictims());
		assertEquals(12, record.getMonstersPriced());
		assertEquals("cost per kill the plugin could price", 4.0d,
			record.estimate(ARROW).getPerAttributedKill(), 1e-9d);
		assertEquals("cost per monster that actually died", 16.0d / 12.0d,
			record.estimate(ARROW).getPerMonster(), 1e-9d);
	}

	@Test
	public void withoutAreaDamageTheTwoDenominatorsAreTheSameNumber()
	{
		AmmoLedger ledger = new AmmoLedger();
		ledger.apply(Attribution.kill(spindel(40), spent(30), 0));
		ledger.apply(Attribution.kill(spindel(41), spent(20), 0));

		NpcAmmoRecord record = ledger.get(SPINDEL);
		assertEquals(2, record.getMonstersPriced());
		assertEquals(0, record.getPricedCoVictims());
		assertEquals(record.estimate(ARROW).getPerAttributedKill(),
			record.estimate(ARROW).getPerMonster(), 1e-9d);
		assertFalse(record.estimate(ARROW).isAreaDamageSeen());
	}

	@Test
	public void anUnattributedDeathDoesNotQuietlyBecomeACoVictim()
	{
		// The difference between the correction this record applies and the
		// simpler one the README's limitation section suggested. An unattributed
		// death whose ammunition went into the abandoned column must not raise the
		// denominator, or the per-monster figure comes out low — and low is the
		// direction that ends a trip early.
		AmmoLedger ledger = new AmmoLedger();
		ledger.apply(Attribution.kill(spindel(40), spent(30), 0));
		ledger.apply(Attribution.abandoned(spindel(41), spent(90)));
		ledger.apply(Attribution.unattributedDeath(spindel(41)));

		NpcAmmoRecord record = ledger.get(SPINDEL);
		assertEquals(1, record.getUnattributedDeaths());
		assertEquals("the monster count only counts what a priced window killed",
			1, record.getMonstersPriced());
		assertEquals(30.0d, record.estimate(ARROW).getPerMonster(), 1e-9d);
	}

	// --- what the plan is for -------------------------------------------------

	@Test
	public void theLedgerRemembersWhichMonsterWasKilledLast()
	{
		AmmoLedger ledger = new AmmoLedger();
		assertNull("nothing has been killed yet", ledger.getMostRecentKill());

		ledger.apply(Attribution.kill(spindel(40), spent(30), 0));
		assertEquals(SPINDEL, ledger.getMostRecentKill().getNpcId());

		ledger.apply(Attribution.kill(
			new FoughtNpc(41, VENENATIS, "Venenatis", new int[]{200, 200, 200, 500, 1, 200}),
			spent(120), 0));
		assertEquals(VENENATIS, ledger.getMostRecentKill().getNpcId());
	}

	@Test
	public void walkingPastSomethingDoesNotRepointTheShoppingList()
	{
		AmmoLedger ledger = new AmmoLedger();
		ledger.apply(Attribution.kill(spindel(40), spent(30), 0));

		ledger.apply(Attribution.abandoned(
			new FoughtNpc(41, VENENATIS, "Venenatis", new int[]{200, 200, 200, 500, 1, 200}),
			spent(5)));
		ledger.apply(Attribution.unattributedDeath(
			new FoughtNpc(42, VENENATIS, "Venenatis", new int[]{200, 200, 200, 500, 1, 200})));

		assertEquals("only a kill moves it", SPINDEL, ledger.getMostRecentKill().getNpcId());
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
		ledger.apply(Attribution.kill(spindel(40), spent(30), 0));

		ledger.clear();

		assertTrue(ledger.isEmpty());
		assertEquals(0, ledger.size());
		assertNull(ledger.get(SPINDEL));
		assertNull("including the monster the plan was about",
			ledger.getMostRecentKill());
	}

	@Test
	public void aWindowHandedToTheLedgerIsCopiedRatherThanShared()
	{
		// KillAttribution reuses one tally for the open window, so a record that
		// held a reference to it would keep changing after the kill was banked.
		AmmoLedger ledger = new AmmoLedger();
		AmmoTally window = spent(30);

		ledger.apply(Attribution.kill(spindel(40), window, 0));
		window.add(new AmmoDelta(one(ARROW, 500), Collections.emptyMap()));

		assertEquals(30.0d, ledger.get(SPINDEL).estimate(ARROW).getPerAttributedKill(), 1e-9d);
	}
}
