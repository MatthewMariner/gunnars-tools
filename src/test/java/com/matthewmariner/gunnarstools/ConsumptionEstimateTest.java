package com.matthewmariner.gunnarstools;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The published figure, its denominator, and its spread.
 *
 * <p>The load-bearing test in here is the area-damage one. This project has
 * already had to correct, in writing, a claim that treated cost per attributed
 * kill as if it were cost per monster; the correction is only worth anything if
 * something breaks when it is undone.
 */
public class ConsumptionEstimateTest
{
	private static final int BLOOD_RUNE = 565;
	private static final int ARROW = 11;

	private static KillSamples samples(long... perKill)
	{
		KillSamples out = new KillSamples();
		for (long value : perKill)
		{
			out.add(value);
		}
		return out;
	}

	// --- the two rates --------------------------------------------------------

	@Test
	public void theTwoRatesAreTheSameNumberWhenNothingDiedToSplashDamage()
	{
		// The ordinary ranged trip, which is what this plugin is for. Four kills,
		// four monsters.
		ConsumptionEstimate estimate = ConsumptionEstimate.of(ARROW, samples(24, 26, 25, 25), 4, 0L);

		assertEquals(25.0d, estimate.getPerAttributedKill(), 1e-9d);
		assertEquals(25.0d, estimate.getPerMonster(), 1e-9d);
		assertFalse(estimate.isAreaDamageSeen());
	}

	@Test
	public void underAreaDamageThePerKillFigureOverstatesByTheMonstersPerCast()
	{
		// Four barrages of four runes, each killing three monsters: sixteen runes
		// over twelve monsters, four of which were the priced kills. The true cost
		// is 1.333 per monster and the per-attributed-kill figure is 4.0 — three
		// times it, which is the number of monsters each cast killed.
		ConsumptionEstimate estimate = ConsumptionEstimate.of(BLOOD_RUNE, samples(4, 4, 4, 4), 12, 0L);

		assertEquals("cost per kill the plugin could price", 4.0d,
			estimate.getPerAttributedKill(), 1e-9d);
		assertEquals("cost per monster that actually died", 16.0d / 12.0d,
			estimate.getPerMonster(), 1e-9d);
		assertEquals("and the overstatement is the monsters per cast", 3.0d,
			estimate.getPerAttributedKill() / estimate.getPerMonster(), 1e-9d);
		assertTrue(estimate.isAreaDamageSeen());
		assertEquals(4, estimate.getAttributedKills());
		assertEquals(12, estimate.getMonstersPriced());
	}

	@Test
	public void aWindowThatKilledOneExtraMonsterIsHalfTheCostPerMonster()
	{
		ConsumptionEstimate estimate = ConsumptionEstimate.of(BLOOD_RUNE, samples(10), 2, 0L);

		assertEquals(10.0d, estimate.getPerAttributedKill(), 1e-9d);
		assertEquals(5.0d, estimate.getPerMonster(), 1e-9d);
	}

	@Test
	public void aMonsterCountBelowTheKillCountIsRefusedRatherThanPublished()
	{
		// More windows than the deaths they contained is not a thing that can have
		// happened. Accepting it would make the per-monster figure larger than the
		// per-kill one, which is backwards, and would do it silently.
		try
		{
			ConsumptionEstimate.of(ARROW, samples(10, 10, 10), 2, 0L);
			fail("a monster count below the kill count has to be refused");
		}
		catch (IllegalArgumentException expected)
		{
			assertTrue(expected.getMessage().contains("2"));
			assertTrue(expected.getMessage().contains("3"));
		}
	}

	// --- the sample count and what it does not excuse -------------------------

	@Test
	public void nothingMeasuredIsZeroRatherThanADivisionByZero()
	{
		ConsumptionEstimate estimate = ConsumptionEstimate.of(ARROW, samples(), 0, 0L);

		assertEquals(0.0d, estimate.getPerAttributedKill(), 1e-9d);
		assertEquals(0.0d, estimate.getPerMonster(), 1e-9d);
		assertEquals(0L, estimate.getConsumed());
		assertEquals(Confidence.NONE, estimate.getConfidence());
		assertFalse("no kills is not area damage", estimate.isAreaDamageSeen());
	}

	@Test
	public void theConfidenceIsTheSampleCountsAndNotTheQuantitys()
	{
		// A big number measured twice is still measured twice.
		ConsumptionEstimate twoKills = ConsumptionEstimate.of(ARROW, samples(900, 900), 2, 0L);
		assertEquals(Confidence.ANECDOTAL, twoKills.getConfidence());
		assertEquals(2, twoKills.getAttributedKills());

		KillSamples many = new KillSamples();
		for (int kill = 0; kill < 120; kill++)
		{
			many.add(1L);
		}
		assertEquals(Confidence.SOLID,
			ConsumptionEstimate.of(ARROW, many, 120, 0L).getConfidence());
	}

	// --- the spread -----------------------------------------------------------

	@Test
	public void theSpreadIsReadOffTheObservedKills()
	{
		// Twenty samples, one per kill, and the count is chosen rather than
		// convenient. Below ten the ninetieth percentile and the maximum are the
		// same sample, and below eleven so are the minimum and the tenth — so a
		// spread test built on a handful of kills stays green with any of the four
		// statistics wired to the wrong end of the array. One to twenty makes all
		// four different numbers, and different from every neighbouring percentile.
		KillSamples series = new KillSamples();
		for (int kill = 1; kill <= 20; kill++)
		{
			series.add(kill);
		}
		ConsumptionEstimate estimate = ConsumptionEstimate.of(ARROW, series, 20, 0L);

		assertEquals("the cheapest kill, not the cheapest tenth", 1L, estimate.getLowestKill());
		assertEquals(10L, estimate.getMedianKill());
		assertEquals("the shoulder, not the worst kill seen", 18L, estimate.getNinetiethKill());
		assertEquals(20L, estimate.getHighestKill());
		assertEquals(10.5d, estimate.getPerAttributedKill(), 1e-9d);
	}

	@Test
	public void aMeanCanSitBetweenTwoValuesThatNeverHappened()
	{
		// The reason the spread is published at all: 137.5 is not what any kill
		// cost, and a figure that only ever showed the mean would hide a monster
		// that costs either 25 or 250 depending on how the fight goes.
		ConsumptionEstimate estimate = ConsumptionEstimate.of(ARROW, samples(25, 25, 250, 250), 4, 0L);

		assertEquals(137.5d, estimate.getPerAttributedKill(), 1e-9d);
		assertEquals(25L, estimate.getLowestKill());
		assertEquals(25L, estimate.getMedianKill());
		assertEquals(250L, estimate.getNinetiethKill());
	}

	// --- the disclosed contaminants ------------------------------------------

	@Test
	public void recoveredAmmunitionIsCarriedAndNeverSubtracted()
	{
		ConsumptionEstimate estimate = ConsumptionEstimate.of(ARROW, samples(100, 100), 2, 60L);

		assertEquals("the gross figure is the one a trip has to cover",
			100.0d, estimate.getPerAttributedKill(), 1e-9d);
		assertEquals(200L, estimate.getConsumed());
		assertEquals("and the contamination sits beside it", 60L, estimate.getRecovered());
	}

	@Test
	public void anItemSpentOnAMinorityOfKillsSaysSo()
	{
		// The looting-bag contaminant: coins booked as consumption on the two ticks
		// a stack went into the bag mid-fight, and on no other kill.
		ConsumptionEstimate estimate =
			ConsumptionEstimate.of(995, samples(0, 0, 5000, 0, 0, 0, 4000, 0), 8, 0L);

		assertEquals(8, estimate.getAttributedKills());
		assertEquals("two of eight, which a human reads correctly at a glance",
			2, estimate.getKillsWithConsumption());
	}

	@Test
	public void arrowsSpentOnEveryKillSaySoToo()
	{
		ConsumptionEstimate estimate = ConsumptionEstimate.of(ARROW, samples(24, 26, 25), 3, 0L);

		assertEquals(3, estimate.getKillsWithConsumption());
		assertEquals(3, estimate.getAttributedKills());
	}
}
