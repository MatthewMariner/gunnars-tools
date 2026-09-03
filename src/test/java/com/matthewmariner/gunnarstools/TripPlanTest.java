package com.matthewmariner.gunnarstools;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The answer: for a trip of this many monsters, bring this much.
 *
 * <p>Three things are being held to account here. That the projection uses the
 * per-monster rate rather than the per-attributed-kill one, which is a claim this
 * project has already had to correct once in writing. That the arithmetic is
 * exact and saturates, because a "bring" that overflows into a negative renders
 * as nothing needed. And that the sentence says what it is claiming, since a
 * number with no basis attached is the failure the whole confidence apparatus
 * exists to prevent.
 */
public class TripPlanTest
{
	private static final int ARROW = 11;
	private static final int BLOOD_RUNE = 565;

	private static KillSamples samples(long... perKill)
	{
		KillSamples out = new KillSamples();
		for (long value : perKill)
		{
			out.add(value);
		}
		return out;
	}

	/** Four kills at twenty-five arrows each, nothing lost to splash damage. */
	private static ConsumptionEstimate fourOrdinaryKills()
	{
		return ConsumptionEstimate.of(ARROW, samples(24, 26, 25, 25), 4, 0L);
	}

	/** Four barrages of four runes, each killing three monsters. */
	private static ConsumptionEstimate fourBarrages()
	{
		return ConsumptionEstimate.of(BLOOD_RUNE, samples(4, 4, 4, 4), 12, 0L);
	}

	// --- the projection -------------------------------------------------------

	@Test
	public void theAnswerIsTheMeasuredRateTimesTheTripSize()
	{
		TripPlan plan = TripPlan.forEstimate(fourOrdinaryKills(), 120, 0);

		assertEquals(3000L, plan.getBring());
		assertEquals(120, plan.getTargetMonsters());
	}

	@Test
	public void theMarginIsAddedOnTopOfTheMeasurement()
	{
		TripPlan plan = TripPlan.forEstimate(fourOrdinaryKills(), 120, 10);

		assertEquals(3300L, plan.getBring());
		assertEquals(10, plan.getSafetyMarginPercent());
	}

	@Test
	public void theProjectionMultipliesThePerMonsterRateAndNotThePerKillOne()
	{
		// The correction, as an assertion. Sixteen runes killed twelve monsters, so
		// a hundred and twenty monsters cost a hundred and sixty runes. Multiplying
		// the per-attributed-kill figure instead gives 480 — three times too much,
		// and this plugin's own README says in as many words that publishing that
		// as the answer would be wrong.
		TripPlan plan = TripPlan.forEstimate(fourBarrages(), 120, 0);

		assertEquals(160L, plan.getBring());
	}

	@Test
	public void aFractionalRequirementIsRoundedUpRatherThanDown()
	{
		// One arrow over three kills is a third of an arrow each; ten kills is three
		// and a third. Rounding down ends the trip one kill early, which is the
		// exact failure this plugin exists to prevent.
		TripPlan plan = TripPlan.forEstimate(
			ConsumptionEstimate.of(ARROW, samples(1, 0, 0), 3, 0L), 10, 0);

		assertEquals(4L, plan.getBring());
	}

	@Test
	public void aRateThatDividesExactlyIsNotRoundedUpToTheNextWholeArrow()
	{
		// The other side of the ceiling: it must not add one to an exact answer.
		TripPlan plan = TripPlan.forEstimate(
			ConsumptionEstimate.of(ARROW, samples(10, 10), 2, 0L), 5, 0);

		assertEquals(50L, plan.getBring());
	}

	@Test
	public void aTripOfNoMonstersNeedsNothing()
	{
		TripPlan plan = TripPlan.forEstimate(fourOrdinaryKills(), 0, 10);

		assertEquals(0L, plan.getBring());
	}

	@Test
	public void aNegativeTripSizeNeedsNothingRatherThanANegativeQuantity()
	{
		// The config's range keeps a user out of here, but the method is public and
		// the failure is not a wrong number — a negative quantity to bring is one
		// the bank highlight declines to draw at all, which presents a shortfall as
		// "nothing needed". Zero multiplied out is harmless; a negative is not, so
		// the guard is tested at the value that actually needs it.
		TripPlan plan = TripPlan.forEstimate(fourOrdinaryKills(), -5, 0);

		assertEquals(0L, plan.getBring());
	}

	@Test
	public void aMonsterNeverMeasuredNeedsNothing()
	{
		TripPlan plan = TripPlan.forEstimate(
			ConsumptionEstimate.of(ARROW, samples(), 0, 0L), 120, 10);

		assertEquals(0L, plan.getBring());
		assertFalse("and there is no worst case to state either",
			plan.getWorstCase().isPresent());
	}

	// --- the margin only ever widens -----------------------------------------

	@Test
	public void aNegativeMarginCannotShrinkTheAnswer()
	{
		// Carrying less than the measurement says is the one direction this plugin
		// refuses. Below minus a hundred it would also flip the sign of the whole
		// answer, which a bank highlight would render as nothing needed.
		TripPlan clamped = TripPlan.forEstimate(fourOrdinaryKills(), 120, -50);
		TripPlan none = TripPlan.forEstimate(fourOrdinaryKills(), 120, 0);

		assertEquals(none.getBring(), clamped.getBring());
		assertEquals(0, clamped.getSafetyMarginPercent());
	}

	@Test
	public void aMarginBelowMinusOneHundredStillProducesAPositiveAnswer()
	{
		TripPlan plan = TripPlan.forEstimate(fourOrdinaryKills(), 120, -400);

		assertEquals(3000L, plan.getBring());
		assertTrue(plan.getBring() > 0L);
	}

	// --- the arithmetic -------------------------------------------------------

	@Test
	public void anIntermediateProductTooBigForALongStillGivesTheExactAnswer()
	{
		// Synthetic quantities, real arithmetic. Coins are stackable and therefore
		// metered, so a record's gross total is not bounded by anything sensible.
		//
		//   4e17 x 100000 x 200 = 8e24, which is a thousand times Long.MAX_VALUE
		//
		// while the answer it divides down to, 8e17, fits in a long with room to
		// spare. A projection done in long arithmetic wraps in the middle and
		// produces a number with no relationship to this one; a projection done in
		// doubles loses the low bits. Only exact integer arithmetic lands here.
		ConsumptionEstimate estimate =
			ConsumptionEstimate.of(995, samples(400_000_000_000_000_000L), 100_000, 0L);

		TripPlan plan = TripPlan.forEstimate(estimate, 100_000, 100);

		assertEquals(800_000_000_000_000_000L, plan.getBring());
	}

	@Test
	public void anAnswerTooBigForALongSaturatesRatherThanWrappingNegative()
	{
		ConsumptionEstimate estimate =
			ConsumptionEstimate.of(995, samples(Long.MAX_VALUE), 1, 0L);

		TripPlan plan = TripPlan.forEstimate(estimate, 100_000, 200);

		assertEquals(Long.MAX_VALUE, plan.getBring());
		assertTrue("a negative quantity to bring renders as nothing needed",
			plan.getBring() > 0L);
	}

	// --- the worst case, and when it is withheld ------------------------------

	@Test
	public void theWorstCaseIsStatedWhenEverySampleIsAPerMonsterObservation()
	{
		// Ninetieth percentile of 24, 25, 25, 26 is 26; a hundred and twenty of
		// those, plus a tenth, is 3,432.
		TripPlan plan = TripPlan.forEstimate(fourOrdinaryKills(), 120, 10);

		assertTrue(plan.getWorstCase().isPresent());
		assertEquals(3432L, plan.getWorstCase().getAsLong());
		assertTrue("and it is above the figure it qualifies",
			plan.getWorstCase().getAsLong() > plan.getBring());
	}

	@Test
	public void theWorstCaseIsTheShoulderAndNotTheSingleWorstKill()
	{
		// Nine ordinary kills and one disaster. The ninetieth percentile is 25 and
		// the maximum is 400, and only ten samples make them different numbers —
		// below that the two collide and a worst-case wired straight to the maximum
		// passes every test. Planning against the maximum would have the player
		// carry sixteen times what the shoulder asks for, off one bad fight.
		ConsumptionEstimate estimate = ConsumptionEstimate.of(
			ARROW, samples(25, 25, 25, 25, 25, 25, 25, 25, 25, 400), 10, 0L);

		TripPlan plan = TripPlan.forEstimate(estimate, 100, 0);

		assertEquals(2500L, plan.getWorstCase().getAsLong());
		assertEquals("and the recommendation, mean-based, is dragged up by the disaster",
			6250L, plan.getBring());
	}

	@Test
	public void theWorstCaseIsWithheldUnderAreaDamageRatherThanEstimated()
	{
		// Converting a per-attributed-kill order statistic onto the per-monster
		// basis would need the co-victim rate to be the same on every kill, and
		// that is a model. Withheld rather than guessed.
		TripPlan plan = TripPlan.forEstimate(fourBarrages(), 120, 10);

		assertFalse(plan.getWorstCase().isPresent());
		assertTrue("but the answer itself is still there", plan.getBring() > 0L);
	}

	// --- the sentence ---------------------------------------------------------

	@Test
	public void theSentenceSaysWhichDenominatorAndHowManySamples()
	{
		TripPlan plan = TripPlan.forEstimate(fourOrdinaryKills(), 120, 10);

		assertEquals("Bring 3,300 Rune arrow for 120 Spindel kills: 25.00 gross per monster"
				+ " over 4 measured kills (anecdotal), plus a 10% margin."
				+ " Every kill at the ninetieth percentile: 3,432.",
			plan.describe("Spindel", "Rune arrow"));
	}

	@Test
	public void theSentenceNamesBothCountsWhenTheyDiffer()
	{
		TripPlan plan = TripPlan.forEstimate(fourBarrages(), 120, 0);

		assertEquals("Bring 160 Blood rune for 120 Spindel kills: 1.33 gross per monster"
				+ " over 4 measured kills covering 12 monsters (anecdotal), plus a 0% margin.",
			plan.describe("Spindel", "Blood rune"));
	}

	@Test
	public void theSentenceIsSingularForASingleKill()
	{
		TripPlan plan = TripPlan.forEstimate(
			ConsumptionEstimate.of(ARROW, samples(25), 1, 0L), 10, 0);

		assertEquals("Bring 250 Rune arrow for 10 Spindel kills: 25.00 gross per monster"
				+ " over 1 measured kill (anecdotal), plus a 0% margin."
				+ " Every kill at the ninetieth percentile: 250.",
			plan.describe("Spindel", "Rune arrow"));
	}

	@Test
	public void theSentenceForAnUnmeasuredMonsterClaimsNothing()
	{
		TripPlan plan = TripPlan.forEstimate(
			ConsumptionEstimate.of(ARROW, samples(), 0, 0L), 120, 10);

		assertEquals("No kills of Callisto measured yet.", plan.describe("Callisto", "Rune arrow"));
	}
}
