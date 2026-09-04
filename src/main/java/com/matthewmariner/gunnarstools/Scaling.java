package com.matthewmariner.gunnarstools;

import java.math.BigInteger;

/**
 * The one piece of arithmetic that turns a rate into a quantity to carry.
 *
 * <p>Extracted from {@link TripPlan}, which was its only caller until the
 * projection arrived. It is here rather than duplicated because the reason it is
 * written in {@link BigInteger} is not fussiness and would not survive being
 * retyped from memory in a second class.
 *
 * <p>{@code (long) Math.ceil(target * rate * (1 + margin))} is the obvious
 * spelling and it has two failure modes, both of which produce a number rather
 * than an error. Double multiplication loses exactness above 2<sup>53</sup>, and
 * the inputs get there: a single stack of coins is stackable and therefore
 * metered, so a record's gross total is not bounded by anything sensible.
 * Multiplying that by a trip size and a margin in {@code long} overflows into a
 * <em>negative</em> quantity to bring, which the bank highlight would then
 * silently decline to draw — a shortfall presented as "nothing needed".
 *
 * <p>The projection makes the overflow argument stronger rather than weaker. Its
 * numerator is a gross total multiplied by a monster's hitpoints before the trip
 * size and the margin are applied at all, so it reaches further into the range
 * than the measured path ever does, and it reaches it on inputs that come from
 * the game rather than from a config field with a {@code @Range} on it.
 *
 * <p>It runs once per kill, per config change and per target change — never in a
 * render loop — so the allocation costs nothing anybody can measure.
 */
final class Scaling
{
	private Scaling()
	{
	}

	/**
	 * {@code ceil(quantity × targetMonsters × (100 + margin) / (over × 100))},
	 * saturating at {@link Long#MAX_VALUE}.
	 *
	 * @param quantity       the numerator: a gross quantity, already multiplied by
	 *                       whatever the caller's rate is expressed over
	 * @param over           the denominator the quantity was measured across —
	 *                       monsters priced, or hitpoints of monsters priced
	 * @param targetMonsters how many monsters the trip is for
	 * @param marginPercent  extra to carry, as a percentage. Clamped at zero from
	 *                       below: a negative margin is a request to carry less
	 *                       than the figure says, which is the one direction this
	 *                       plugin exists to refuse, and below -100 it would flip
	 *                       the sign of the whole answer.
	 * @return zero for any input that cannot produce a meaningful quantity, so a
	 * caller drawing an overlay never has to catch anything
	 */
	static long ceilScale(BigInteger quantity, BigInteger over, int targetMonsters,
		int marginPercent)
	{
		if (quantity.signum() <= 0 || over.signum() <= 0 || targetMonsters <= 0)
		{
			return 0L;
		}

		final int margin = Math.max(0, marginPercent);

		final BigInteger numerator = quantity
			.multiply(BigInteger.valueOf(targetMonsters))
			.multiply(BigInteger.valueOf(100L + margin));
		final BigInteger denominator = over.multiply(BigInteger.valueOf(100L));

		final BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(denominator);
		BigInteger quotient = quotientAndRemainder[0];
		if (quotientAndRemainder[1].signum() != 0)
		{
			// Always up. Half an arrow short is a trip that ends one kill early.
			quotient = quotient.add(BigInteger.ONE);
		}

		final BigInteger max = BigInteger.valueOf(Long.MAX_VALUE);
		return quotient.compareTo(max) >= 0 ? Long.MAX_VALUE : quotient.longValue();
	}

	/** The common case: both sides are plain {@code long}s. */
	static long ceilScale(long quantity, long over, int targetMonsters, int marginPercent)
	{
		return ceilScale(BigInteger.valueOf(quantity), BigInteger.valueOf(over),
			targetMonsters, marginPercent);
	}
}
