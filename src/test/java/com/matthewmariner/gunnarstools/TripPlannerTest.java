package com.matthewmariner.gunnarstools;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The shopping list: every item a monster costs, biggest first, and the lookup
 * the bank highlight reads.
 */
public class TripPlannerTest
{
	private static final int ARROW = 11;
	private static final int BLOOD_RUNE = 565;
	private static final int COINS = 995;

	private static final int SPINDEL = 5265;

	private static FoughtNpc spindel(int index)
	{
		return new FoughtNpc(index, SPINDEL, "Spindel", new int[]{130, 130, 130, 200, 1, 130});
	}

	private static AmmoTally window(Map<Integer, Long> consumed)
	{
		AmmoTally out = new AmmoTally();
		out.add(new AmmoDelta(consumed, Collections.emptyMap()));
		return out;
	}

	private static Map<Integer, Long> spent(Object... idsAndQuantities)
	{
		Map<Integer, Long> out = new LinkedHashMap<>();
		for (int i = 0; i < idsAndQuantities.length; i += 2)
		{
			out.put((Integer) idsAndQuantities[i], ((Number) idsAndQuantities[i + 1]).longValue());
		}
		return out;
	}

	private static NpcAmmoRecord twoKillsOfEach()
	{
		AmmoLedger ledger = new AmmoLedger();
		ledger.apply(Attribution.kill(spindel(40), window(spent(ARROW, 25L, BLOOD_RUNE, 4L)), 0));
		ledger.apply(Attribution.kill(spindel(41), window(spent(ARROW, 25L, BLOOD_RUNE, 4L)), 0));
		return ledger.get(SPINDEL);
	}

	@Test
	public void everyMeteredItemGetsALineAndTheBiggestComesFirst()
	{
		List<TripPlan> plans = TripPlanner.plan(twoKillsOfEach(), 100, 0);

		assertEquals(2, plans.size());
		assertEquals("arrows before runes, because that is the order a packer wants",
			ARROW, plans.get(0).getItemId());
		assertEquals(2500L, plans.get(0).getBring());
		assertEquals(BLOOD_RUNE, plans.get(1).getItemId());
		assertEquals(400L, plans.get(1).getBring());
	}

	@Test
	public void anItemOnAMinorityOfKillsIsStillPlannedForRatherThanHidden()
	{
		// Coins land in the record when a stack goes into the looting bag mid-fight.
		// Dropping the line would be dropping a shortfall, so the honest signal is
		// published beside it instead: spent on one of two kills.
		AmmoLedger ledger = new AmmoLedger();
		ledger.apply(Attribution.kill(spindel(40), window(spent(ARROW, 25L, COINS, 5000L)), 0));
		ledger.apply(Attribution.kill(spindel(41), window(spent(ARROW, 25L)), 0));

		List<TripPlan> plans = TripPlanner.plan(ledger.get(SPINDEL), 100, 0);

		assertEquals(2, plans.size());
		assertEquals("the biggest number is still first", COINS, plans.get(0).getItemId());
		assertEquals(250000L, plans.get(0).getBring());
		assertEquals(1, plans.get(0).getEstimate().getKillsWithConsumption());
		assertEquals(2, plans.get(0).getEstimate().getAttributedKills());
	}

	@Test
	public void aLineWorthNothingIsNotDrawn()
	{
		// A trip of no monsters. A bank highlight promising "withdraw 0" is worse
		// than no highlight at all.
		List<TripPlan> plans = TripPlanner.plan(twoKillsOfEach(), 0, 50);

		assertTrue(plans.isEmpty());
	}

	@Test
	public void noRecordIsAnEmptyPlanRatherThanANullPointer()
	{
		assertTrue(TripPlanner.plan(null, 100, 10).isEmpty());
		assertTrue(TripPlanner.withdrawals(Collections.emptyList()).isEmpty());
	}

	@Test
	public void anUnmeasuredMonsterHasNothingToPlan()
	{
		AmmoLedger ledger = new AmmoLedger();
		ledger.apply(Attribution.unattributedDeath(spindel(40)));

		assertTrue(TripPlanner.plan(ledger.get(SPINDEL), 100, 10).isEmpty());
	}

	// --- the bank lookup ------------------------------------------------------

	@Test
	public void theWithdrawalLookupCarriesEveryLineAtItsPlannedQuantity()
	{
		List<TripPlan> plans = TripPlanner.plan(twoKillsOfEach(), 100, 10);
		Map<Integer, Long> withdrawals = TripPlanner.withdrawals(plans);

		assertEquals(2, withdrawals.size());
		assertEquals(Long.valueOf(2750L), withdrawals.get(ARROW));
		assertEquals(Long.valueOf(440L), withdrawals.get(BLOOD_RUNE));
	}

	@Test
	public void theWithdrawalLookupKeepsThePlansOrder()
	{
		Map<Integer, Long> withdrawals =
			TripPlanner.withdrawals(TripPlanner.plan(twoKillsOfEach(), 100, 0));

		assertEquals(Arrays.asList(ARROW, BLOOD_RUNE),
			new java.util.ArrayList<>(withdrawals.keySet()));
	}

	@Test
	public void twoLinesForOneItemWouldBeAddedRatherThanOneOverwritingTheOther()
	{
		// Nothing produces this today — estimates are keyed by item id — and that is
		// the point: a later milestone planning for two monsters at once must not
		// silently publish only the second one's arrows.
		ConsumptionEstimate estimate = ConsumptionEstimate.of(
			ARROW, singleSample(25L), 1, 0L);
		TripPlan first = TripPlan.forEstimate(estimate, 100, 0);
		TripPlan second = TripPlan.forEstimate(estimate, 40, 0);

		Map<Integer, Long> withdrawals = TripPlanner.withdrawals(Arrays.asList(first, second));

		assertEquals(Long.valueOf(3500L), withdrawals.get(ARROW));
	}

	@Test
	public void theEstimatedLookupIsTheSameLookupOverTheOtherType()
	{
		List<ProjectedNeed> needs = Arrays.asList(
			ProjectedNeed.remembered(ARROW, 100L, 4, "Spindel", true, 100, 0),
			ProjectedNeed.remembered(BLOOD_RUNE, 16L, 4, "Spindel", true, 100, 0));

		Map<Integer, Long> withdrawals = TripPlanner.projectedWithdrawals(needs);

		assertEquals(Long.valueOf(2500L), withdrawals.get(ARROW));
		assertEquals(Long.valueOf(400L), withdrawals.get(BLOOD_RUNE));
		assertEquals(Arrays.asList(ARROW, BLOOD_RUNE),
			new java.util.ArrayList<>(withdrawals.keySet()));
	}

	@Test
	public void twoEstimatedLinesForOneItemAreAddedRatherThanOverwritten()
	{
		Map<Integer, Long> withdrawals = TripPlanner.projectedWithdrawals(Arrays.asList(
			ProjectedNeed.remembered(ARROW, 100L, 4, "Spindel", true, 100, 0),
			ProjectedNeed.remembered(ARROW, 100L, 4, "Spindel", true, 40, 0)));

		assertEquals(Long.valueOf(3500L), withdrawals.get(ARROW));
	}

	@Test
	public void anEmptyEstimatedListIsAnEmptyLookup()
	{
		assertTrue(TripPlanner.projectedWithdrawals(Collections.emptyList()).isEmpty());
	}

	// --- what is left to withdraw ---------------------------------------------

	@Test
	public void whatIsAlreadyCarriedComesOffTheRequirement()
	{
		assertEquals(1200L, TripPlanner.shortfall(2000L, 800L));
	}

	@Test
	public void carryingTheWholeTripLeavesNothingToWithdraw()
	{
		assertEquals(0L, TripPlanner.shortfall(2000L, 2000L));
	}

	@Test
	public void carryingMoreThanTheTripNeedsIsNotANegativeWithdrawal()
	{
		// The guard that matters. A negative reaching the highlight would either be
		// drawn as a quantity or compared against the banked amount and painted red,
		// telling somebody they are short of an item they have a surplus of.
		assertEquals(0L, TripPlanner.shortfall(2000L, 5000L));
	}

	@Test
	public void carryingNothingLeavesTheRequirementAlone()
	{
		assertEquals(2000L, TripPlanner.shortfall(2000L, 0L));
		assertEquals("and a nonsensical negative holding does not inflate it",
			2000L, TripPlanner.shortfall(2000L, -50L));
	}

	@Test
	public void aRequirementOfNothingStaysNothing()
	{
		assertEquals(0L, TripPlanner.shortfall(0L, 800L));
		assertEquals(0L, TripPlanner.shortfall(-5L, 800L));
	}

	@Test
	public void aSaturatedRequirementIsNotUnderflowedByASmallHolding()
	{
		// The plan saturates at Long.MAX_VALUE rather than wrapping, so the
		// subtraction has to stay in range too.
		assertEquals(Long.MAX_VALUE - 1L, TripPlanner.shortfall(Long.MAX_VALUE, 1L));
	}

	private static KillSamples singleSample(long value)
	{
		KillSamples samples = new KillSamples();
		samples.add(value);
		return samples;
	}
}
