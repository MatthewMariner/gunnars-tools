package com.matthewmariner.gunnarstools;

import java.math.BigInteger;
import java.util.Locale;

/**
 * An <b>estimate</b> of what a trip will cost, for a monster this session has not
 * measured.
 *
 * <p>This is the answer to the question the plugin used to be unable to hear.
 * Standing at a bank with a fresh Krystilia task, nothing has been killed, so
 * {@link AmmoLedger} is empty, {@link ConsumptionMeter} has measured nothing and
 * {@link TripPlan} — which is a statement about kills this session watched — has
 * nothing to say. That is the moment the answer is wanted.
 *
 * <h2>Why this is a separate type from {@link TripPlan} rather than a flag on it</h2>
 *
 * <p>Because the two are different claims, and the difference is the plugin's
 * whole credibility. A {@link TripPlan} says "I watched you kill thirty-seven of
 * these and this is what they cost", and it carries a sample count, a spread and
 * four observed order statistics to back it. A {@link ProjectedNeed} says "I have
 * not watched you kill any of these, and here is what I think from something
 * else." A boolean on one class would have made those interchangeable at every
 * call site; two classes make substituting one for the other a compile error.
 * The moment a modelled number is shown as a measured one, nothing else this
 * plugin prints is worth reading.
 *
 * <h2>The three bases, in the order they are preferred</h2>
 *
 * <p>Every one of them is arithmetic over quantities this player actually spent.
 * None of them is a table of monsters, and none of them predicts consumption from
 * accuracy and attack speed — that is the model this project refuses, and the
 * refusal is not weakened by any of what follows.
 *
 * <ul>
 *   <li>{@link Basis#LAST_SESSION} — a previous session measured this exact
 *       monster on this exact setup. Nothing is extrapolated at all; the only
 *       reason it is not a measurement is that the samples behind it are gone, so
 *       there is no spread and no honest way to say how variable it was.</li>
 *   <li>{@link Basis#LAST_SESSION_OTHER_GEAR} — the same, but measured with a
 *       different weapon or a different ammunition. Disclosed rather than hidden:
 *       it is the best figure available and it is about somebody holding a
 *       different bow, and the panel says which.</li>
 *   <li>{@link Basis#SCALED_BY_HITPOINTS} — nothing at all is known about this
 *       monster, so a rate measured on a <em>different</em> one, with the same
 *       weapon and ammunition, is scaled by the ratio of their hitpoints.</li>
 * </ul>
 *
 * <h2>What the hitpoints scaling assumes, stated plainly</h2>
 *
 * <p>It assumes your damage per shot is the same against both monsters. That is
 * not true — defence differs, and so does whether they are weak to your style —
 * so this is the one figure in the plugin that rests on something other than
 * arithmetic. It is offered anyway, labelled, because the alternative at a bank
 * is nothing at all, and because the error is bounded by something the panel can
 * show: a monster four times the size of one you have measured is a four-fold
 * extrapolation, and {@link TripAdvisor} picks the <em>closest</em> monster it has
 * evidence for precisely so that number stays small.
 *
 * <p>The hitpoints come off the live NPC — {@code NPCComposition.getStats()} at
 * {@code STAT_HITPOINTS}, through {@link FoughtNpc} and {@link PlanTarget} — which
 * is why no bundled monster table is needed and why "spider" never has to be
 * resolved to one of the thirty-odd things Krystilia means by it. The monster in
 * front of the player states its own size.
 */
public final class ProjectedNeed
{
	/** Where the number came from. Shown, always, on the same panel as the number. */
	public enum Basis
	{
		/** A previous session measured this monster with this weapon and ammunition. */
		LAST_SESSION("last session"),

		/** A previous session measured this monster with different gear. */
		LAST_SESSION_OTHER_GEAR("other gear"),

		/** A different monster's measured rate, scaled by the ratio of their hitpoints. */
		SCALED_BY_HITPOINTS("scaled");

		private final String label;

		Basis(String label)
		{
			this.label = label;
		}

		/** Lower case; it sits inside a sentence and beside a number. */
		public String getLabel()
		{
			return label;
		}
	}

	private final int itemId;
	private final Basis basis;
	private final long bring;
	private final int targetMonsters;
	private final int safetyMarginPercent;
	private final String sourceName;
	private final int sourceMonsters;
	private final int sourceHitpoints;
	private final int targetHitpoints;
	private final Confidence confidence;

	private ProjectedNeed(int itemId, Basis basis, long bring, int targetMonsters,
		int safetyMarginPercent, String sourceName, int sourceMonsters, int sourceHitpoints,
		int targetHitpoints)
	{
		this.itemId = itemId;
		this.basis = basis;
		this.bring = bring;
		this.targetMonsters = targetMonsters;
		this.safetyMarginPercent = safetyMarginPercent;
		this.sourceName = sourceName;
		this.sourceMonsters = sourceMonsters;
		this.sourceHitpoints = sourceHitpoints;
		this.targetHitpoints = targetHitpoints;
		this.confidence = Confidence.forSamples(sourceMonsters);
	}

	/**
	 * The same monster, remembered rather than measured.
	 *
	 * @param quantity   gross quantity the remembered session spent
	 * @param overMonsters how many monsters that quantity bought. The denominator
	 *                     is monsters and not kills for the same reason
	 *                     {@link TripPlan} divides by monsters: it is counted in
	 *                     the unit the trip size is counted in, and using kills
	 *                     reintroduces the threefold area-damage overstatement this
	 *                     project has already corrected once.
	 * @param sameGear   whether the remembered setup is the one worn now
	 */
	static ProjectedNeed remembered(int itemId, long quantity, int overMonsters, String monsterName,
		boolean sameGear, int targetMonsters, int safetyMarginPercent)
	{
		final int margin = Math.max(0, safetyMarginPercent);
		return new ProjectedNeed(itemId,
			sameGear ? Basis.LAST_SESSION : Basis.LAST_SESSION_OTHER_GEAR,
			Scaling.ceilScale(quantity, overMonsters, targetMonsters, margin),
			targetMonsters, margin, monsterName, overMonsters, 0, 0);
	}

	/**
	 * A different monster's rate, stretched onto this one's size.
	 *
	 * <p>{@code quantity × targetHitpoints} over {@code sourceMonsters ×
	 * sourceHitpoints} is a quantity per hitpoint multiplied back up, and it is
	 * computed in {@link Scaling} on {@link BigInteger} rather than in
	 * {@code long}. That is not decoration: the numerator here multiplies a gross
	 * total by a monster's hitpoints <em>before</em> the trip size and the margin
	 * are applied, so it reaches further into the range than the measured path ever
	 * does, and it reaches it on numbers that come from the game rather than from a
	 * config field with a {@code @Range} on it.
	 *
	 * <p>There is deliberately no guard here for hitpoints that did not resolve.
	 * One was written and then deleted: a target of zero makes the numerator zero,
	 * a source of zero makes the denominator zero, and {@link Scaling} already
	 * answers zero for both. A mutation pass proved the branch could be removed
	 * without a single test noticing, which is the definition of a line that reads
	 * as a guard and is not one — and a dead branch next to a live one makes the
	 * live one harder to trust. The refusal itself still happens, one layer up in
	 * {@link TripAdvisor}, where a monster with no resolved hitpoints is not a
	 * candidate to scale from and a target with none produces a stated reason
	 * rather than an answer.
	 *
	 * @param sourceHitpoints hitpoints of the monster the rate was measured on
	 * @param targetHitpoints hitpoints of the monster the trip is for
	 */
	static ProjectedNeed scaled(int itemId, long quantity, int sourceMonsters, int sourceHitpoints,
		int targetHitpoints, String sourceName, int targetMonsters, int safetyMarginPercent)
	{
		final int margin = Math.max(0, safetyMarginPercent);
		final long bring = Scaling.ceilScale(
			BigInteger.valueOf(quantity).multiply(BigInteger.valueOf(targetHitpoints)),
			BigInteger.valueOf(sourceMonsters).multiply(BigInteger.valueOf(sourceHitpoints)),
			targetMonsters, margin);

		return new ProjectedNeed(itemId, Basis.SCALED_BY_HITPOINTS, bring, targetMonsters, margin,
			sourceName, sourceMonsters, sourceHitpoints, targetHitpoints);
	}

	public int getItemId()
	{
		return itemId;
	}

	public Basis getBasis()
	{
		return basis;
	}

	/** Estimated gross quantity to carry, margin included. Never negative. */
	public long getBring()
	{
		return bring;
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

	/** The monster the figure was measured on — this one, or the one it was scaled from. */
	public String getSourceName()
	{
		return sourceName;
	}

	/** How many monsters of the source stand behind the rate. The evidence count. */
	public int getSourceMonsters()
	{
		return sourceMonsters;
	}

	/** Zero unless {@link Basis#SCALED_BY_HITPOINTS}. */
	public int getSourceHitpoints()
	{
		return sourceHitpoints;
	}

	/** Zero unless {@link Basis#SCALED_BY_HITPOINTS}. */
	public int getTargetHitpoints()
	{
		return targetHitpoints;
	}

	/**
	 * @return the confidence band of the evidence the estimate rests on. It is a
	 * reading aid over that count and nothing more — an estimate scaled from four
	 * hundred kills of a different monster is still an estimate, and the panel says
	 * so on the line above.
	 */
	public Confidence getConfidence()
	{
		return confidence;
	}

	/**
	 * The claim, in words, with the fact that it is a claim in the first four.
	 *
	 * <p>Every sentence here starts with "Estimate". The log is where a bug report
	 * gets pasted from, and a line that reads like a measurement in a bug report is
	 * a wrong number nobody can trace.
	 */
	public String describe(String monsterName, String itemName)
	{
		switch (basis)
		{
			case SCALED_BY_HITPOINTS:
				return String.format(Locale.ROOT,
					"Estimate: bring %,d %s for %,d %s, scaled from %,d %s (%d hp) onto %d hp (%s), "
						+ "plus a %d%% margin. Not measured on %s.",
					bring, itemName, targetMonsters, monsterName, sourceMonsters, sourceName,
					sourceHitpoints, targetHitpoints, confidence.getLabel(), safetyMarginPercent,
					monsterName);
			case LAST_SESSION_OTHER_GEAR:
				return String.format(Locale.ROOT,
					"Estimate: bring %,d %s for %,d %s, from %,d remembered (%s) — measured with "
						+ "different gear — plus a %d%% margin.",
					bring, itemName, targetMonsters, monsterName, sourceMonsters,
					confidence.getLabel(), safetyMarginPercent);
			default:
				return String.format(Locale.ROOT,
					"Estimate: bring %,d %s for %,d %s, from %,d remembered from a previous session "
						+ "(%s), plus a %d%% margin.",
					bring, itemName, targetMonsters, monsterName, sourceMonsters,
					confidence.getLabel(), safetyMarginPercent);
		}
	}

	@Override
	public String toString()
	{
		return "ProjectedNeed(item=" + itemId + ", " + basis + ", bring=" + bring
			+ ", target=" + targetMonsters + ", margin=" + safetyMarginPercent + "%"
			+ ", from=" + sourceName + " n=" + sourceMonsters
			+ (basis == Basis.SCALED_BY_HITPOINTS
				? ", hp " + sourceHitpoints + "->" + targetHitpoints : "")
			+ ")";
	}
}
