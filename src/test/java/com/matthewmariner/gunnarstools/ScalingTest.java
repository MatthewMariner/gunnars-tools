package com.matthewmariner.gunnarstools;

import java.math.BigInteger;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The one piece of arithmetic that turns a rate into a quantity to carry.
 *
 * <p>{@code TripPlanTest} already exercises this through {@link TripPlan}. What
 * is here is the part {@link TripPlan} cannot reach: the {@link BigInteger}
 * entry point the projection uses, whose numerator is a gross total multiplied by
 * a monster's hitpoints before the trip size and the margin are applied at all.
 *
 * <p><b>The overflow tests use a unit price large enough to overflow.</b> This
 * repository has produced an integer-overflow test whose quantity was 1 — a test
 * that could not fail, sitting in the suite reading as coverage. Every figure
 * below is checked against what the same expression does in {@code long}
 * arithmetic, so a test that passes for the wrong reason is visible.
 */
public class ScalingTest
{
	@Test
	public void theCommonCaseIsAMultiplicationAndADivision()
	{
		// 25 arrows a monster, a hundred monsters, ten per cent on top.
		assertEquals(2750L, Scaling.ceilScale(750L, 30, 100, 10));
	}

	@Test
	public void itAlwaysRoundsUp()
	{
		// Half an arrow short is a trip that ends one kill early. 1/3 of a monster
		// over three monsters is one arrow, not zero.
		assertEquals(1L, Scaling.ceilScale(1L, 3, 1, 0));
		assertEquals(2L, Scaling.ceilScale(1L, 3, 4, 0));
	}

	@Test
	public void anExactDivisionIsNotRoundedUpAnyway()
	{
		// The other side of the ceiling. A remainder of zero must stay put, or every
		// clean figure comes out one high.
		assertEquals(100L, Scaling.ceilScale(10L, 1, 10, 0));
	}

	@Test
	public void aNegativeMarginIsClampedRatherThanApplied()
	{
		// A request to carry less than the measurement says is the one direction
		// this plugin exists to refuse, and below -100 it would flip the sign of the
		// whole answer.
		assertEquals(1000L, Scaling.ceilScale(10L, 1, 100, -50));
		assertEquals(1000L, Scaling.ceilScale(10L, 1, 100, -400));
	}

	@Test
	public void anythingWithoutAMeaningfulAnswerIsZero()
	{
		// An overlay is not the place to throw.
		assertEquals(0L, Scaling.ceilScale(0L, 30, 100, 10));
		assertEquals(0L, Scaling.ceilScale(-5L, 30, 100, 10));
		assertEquals(0L, Scaling.ceilScale(750L, 0, 100, 10));
		assertEquals(0L, Scaling.ceilScale(750L, -30, 100, 10));
		assertEquals(0L, Scaling.ceilScale(750L, 30, 0, 10));
		assertEquals(0L, Scaling.ceilScale(750L, 30, -1, 10));
	}

	@Test
	public void aProductThatOverflowsALongSaturatesInsteadOfGoingNegative()
	{
		// The failure this class is written in BigInteger to avoid, spelled out:
		// the same arithmetic in long really does come out negative, and a negative
		// quantity to bring is a shortfall the bank highlight declines to draw.
		final long quantity = Long.MAX_VALUE / 3L;
		final int trip = 100000;

		assertTrue("long arithmetic on these inputs wraps all the way to negative",
			quantity * trip < 0L);
		assertEquals(Long.MAX_VALUE, Scaling.ceilScale(quantity, 1, trip, 0));
	}

	@Test
	public void theBigIntegerEntryPointIsWhatTheProjectionMultipliesThrough()
	{
		// A rate measured over 40 monsters of 17 hitpoints, stretched onto one of
		// 850: 200 arrows bought 40 × 17 = 680 hitpoints, so 850 hitpoints is
		// 250 arrows, and ten of them is 2,500.
		assertEquals(2500L, Scaling.ceilScale(
			BigInteger.valueOf(200L).multiply(BigInteger.valueOf(850L)),
			BigInteger.valueOf(40L).multiply(BigInteger.valueOf(17L)),
			10, 0));
	}

	@Test
	public void theProjectionsNumeratorOverflowsALongBeforeTheMarginIsEvenApplied()
	{
		// This is the case the measured path cannot reach and the reason the
		// BigInteger overload exists. quantity × hitpoints wraps on its own, with no
		// trip size and no margin in sight.
		//
		// The wrap is asserted against the exact product rather than against zero,
		// and the first version of this test got that wrong: these particular inputs
		// wrap to a *positive* number, so "< 0" passed for the wrong reason and would
		// have gone on passing with the arithmetic done in long. "Not the right
		// answer" is the claim; "negative" is only one of the ways to be wrong.
		final long quantity = Long.MAX_VALUE / 100L;
		final long hitpoints = 850L;
		final BigInteger exact = BigInteger.valueOf(quantity).multiply(BigInteger.valueOf(hitpoints));

		assertNotEquals("the numerator alone is already inexact in long",
			exact, BigInteger.valueOf(quantity * hitpoints));

		assertEquals(Long.MAX_VALUE, Scaling.ceilScale(exact, BigInteger.ONE, 1, 0));
	}

	@Test
	public void aBigIntegerDenominatorOfZeroIsRefusedLikeAnyOther()
	{
		assertEquals(0L, Scaling.ceilScale(BigInteger.TEN, BigInteger.ZERO, 100, 10));
		assertEquals(0L, Scaling.ceilScale(BigInteger.ZERO, BigInteger.TEN, 100, 10));
	}

	@Test
	public void aQuotientOneBelowTheCeilingIsNotSaturated()
	{
		// The boundary of the saturation, from underneath. Long.MAX_VALUE - 1 has to
		// come back as itself rather than as the saturated value, or the guard is
		// hiding a real answer.
		assertEquals(Long.MAX_VALUE - 1L,
			Scaling.ceilScale(BigInteger.valueOf(Long.MAX_VALUE - 1L), BigInteger.ONE, 1, 0));
	}

	@Test
	public void exactlyTheCeilingIsTheCeiling()
	{
		assertEquals(Long.MAX_VALUE,
			Scaling.ceilScale(BigInteger.valueOf(Long.MAX_VALUE), BigInteger.ONE, 1, 0));
	}
}
