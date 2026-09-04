package com.matthewmariner.gunnarstools;

import java.util.Locale;
import java.util.OptionalLong;

/**
 * The answer: for a trip of {@code Q} monsters, bring this much of this item.
 *
 * <p>This is the sentence the whole plugin exists to produce, so it is worth
 * being exact about what it claims. {@link #getBring()} is <b>gross</b> quantity
 * — ammunition recovered off the floor is disclosed separately and never
 * subtracted, because a plugin cannot tell an arrow it picked back up from an
 * arrow the monster dropped, and guessing in the flattering direction ends a
 * Wilderness trip early. It is a projection of the <b>per-monster</b> rate, not
 * the per-attributed-kill rate; see {@link ConsumptionEstimate} for why those
 * are two different numbers and why this one multiplies the second. And it
 * carries the sample count it rests on everywhere it is shown, because the same
 * figure means different things at four kills and four hundred.
 *
 * <h2>The arithmetic is exact integers, and that is not fussiness</h2>
 *
 * <p>The ceiling is computed in exact integers and saturated at
 * {@link Long#MAX_VALUE}, in {@link Scaling}, which is where the argument for
 * doing it that way is written down. The short version: the obvious double
 * multiply loses exactness on inputs this plugin really does see, and the
 * obvious {@code long} multiply overflows into a <em>negative</em> quantity to
 * bring — which the bank highlight would then silently decline to draw, showing
 * a shortfall as "nothing needed".
 *
 * <h2>The safety margin is the dial, and there is deliberately no second one</h2>
 *
 * <p>A margin is the player's statement about how much they mind a shortfall,
 * and it is the only knob here that widens the answer. It would have been easy
 * to widen it a second time from the observed spread — plan against the
 * ninetieth-percentile kill rather than the mean — and that would have been a
 * model: the total for a hundred kills is not a hundred bad kills, and pretending
 * otherwise trebles what the player carries into a place where every surplus
 * arrow is a gift to whoever kills them.
 *
 * <p>{@link #getWorstCase()} is offered instead, and only when it is exactly
 * true. When no monster died to splash damage, each sample <em>is</em> a
 * per-monster observation, so "every kill costs what my worst tenth did" is
 * arithmetic on measured values with nothing assumed. Under area damage it would
 * require converting a per-attributed-kill order statistic onto a per-monster
 * basis, which needs the co-victim rate to be the same on every kill — so it is
 * withheld rather than estimated.
 */
public final class TripPlan
{
	private final ConsumptionEstimate estimate;
	private final int targetMonsters;
	private final int safetyMarginPercent;
	private final long bring;
	private final long worstCase;
	private final boolean worstCaseKnown;

	private TripPlan(ConsumptionEstimate estimate, int targetMonsters, int safetyMarginPercent,
		long bring, long worstCase, boolean worstCaseKnown)
	{
		this.estimate = estimate;
		this.targetMonsters = targetMonsters;
		this.safetyMarginPercent = safetyMarginPercent;
		this.bring = bring;
		this.worstCase = worstCase;
		this.worstCaseKnown = worstCaseKnown;
	}

	/**
	 * @param targetMonsters      how many of this monster the trip is for. Zero or
	 *                            negative yields a plan for nothing rather than an
	 *                            exception — the config's range guards the real
	 *                            path, and an overlay is not the place to throw.
	 * @param safetyMarginPercent extra to carry, as a percentage. <b>Clamped at
	 *                            zero from below.</b> A negative margin is a
	 *                            request to carry less than the measurement says,
	 *                            which is the one direction this plugin exists to
	 *                            refuse; below -100 it would also flip the sign of
	 *                            the whole answer.
	 */
	public static TripPlan forEstimate(ConsumptionEstimate estimate, int targetMonsters,
		int safetyMarginPercent)
	{
		final int margin = Math.max(0, safetyMarginPercent);

		final long bring = scale(estimate.getConsumed(), estimate.getMonstersPriced(),
			targetMonsters, margin);

		// Exact only when every sample is a per-monster observation, which is what
		// "no area damage seen" means. Withheld rather than approximated otherwise.
		final boolean worstCaseKnown = !estimate.isAreaDamageSeen() && estimate.getAttributedKills() > 0;
		final long worstCase = worstCaseKnown
			? scale(estimate.getNinetiethKill(), 1, targetMonsters, margin)
			: 0L;

		return new TripPlan(estimate, targetMonsters, margin, bring, worstCase, worstCaseKnown);
	}

	/**
	 * {@code ceil(quantity × targetMonsters × (100 + margin) / (over × 100))},
	 * saturating at {@link Long#MAX_VALUE}.
	 *
	 * <p>The body moved to {@link Scaling} when {@link ProjectedNeed} needed the
	 * same ceiling and the same overflow argument; the reasoning moved with it.
	 * This stays as a named method because the two call sites above read as
	 * arithmetic about a trip rather than about {@code BigInteger}.
	 */
	private static long scale(long quantity, int over, int targetMonsters, int marginPercent)
	{
		return Scaling.ceilScale(quantity, over, targetMonsters, marginPercent);
	}

	public ConsumptionEstimate getEstimate()
	{
		return estimate;
	}

	public int getItemId()
	{
		return estimate.getItemId();
	}

	public int getTargetMonsters()
	{
		return targetMonsters;
	}

	/** The margin actually applied, after the clamp. */
	public int getSafetyMarginPercent()
	{
		return safetyMarginPercent;
	}

	/** Gross quantity to carry, margin included. */
	public long getBring()
	{
		return bring;
	}

	/**
	 * @return what the trip would cost if every kill were as expensive as the
	 * worst tenth measured, or empty when that cannot be stated without assuming
	 * something. See the class javadoc.
	 */
	public OptionalLong getWorstCase()
	{
		return worstCaseKnown ? OptionalLong.of(worstCase) : OptionalLong.empty();
	}

	/**
	 * The claim, in words, with its basis attached.
	 *
	 * <p>A method rather than a {@code toString()} because it needs two names the
	 * measurement layer does not have — item and monster both come out of the
	 * client's caches — and because the exact wording is a thing worth having a
	 * test for. Everything that qualifies the number is inside the same sentence
	 * as the number: gross, which denominator, how many samples, how much margin.
	 */
	public String describe(String monsterName, String itemName)
	{
		if (estimate.getAttributedKills() == 0)
		{
			return "No kills of " + monsterName + " measured yet.";
		}

		final StringBuilder out = new StringBuilder();
		out.append(String.format(Locale.ROOT, "Bring %,d %s for %,d %s kills: %.2f gross per monster",
			bring, itemName, targetMonsters, monsterName, estimate.getPerMonster()));

		out.append(String.format(Locale.ROOT, " over %,d measured kill%s",
			estimate.getAttributedKills(), estimate.getAttributedKills() == 1 ? "" : "s"));

		if (estimate.isAreaDamageSeen())
		{
			out.append(String.format(Locale.ROOT, " covering %,d monsters",
				estimate.getMonstersPriced()));
		}

		out.append(String.format(Locale.ROOT, " (%s), plus a %d%% margin.",
			estimate.getConfidence().getLabel(), safetyMarginPercent));

		if (worstCaseKnown)
		{
			// Worded as the rate it assumes rather than as "the worst tenth",
			// because it is not the cost of the worst tenth of the trip — it is the
			// whole trip priced at the ninetieth-percentile kill. On a monster with
			// one catastrophic kill in ten that figure comes out *below* the
			// mean-based one, and a label implying otherwise would make a true
			// number read as a contradiction.
			out.append(String.format(Locale.ROOT,
				" Every kill at the ninetieth percentile: %,d.", worstCase));
		}

		return out.toString();
	}

	@Override
	public String toString()
	{
		return "TripPlan(item=" + getItemId() + ", target=" + targetMonsters
			+ ", margin=" + safetyMarginPercent + "%, bring=" + bring
			+ ", worstCase=" + (worstCaseKnown ? Long.toString(worstCase) : "unknown")
			+ ", " + estimate + ")";
	}
}
