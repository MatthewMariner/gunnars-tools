package com.matthewmariner.gunnarstools;

/**
 * How much of a figure's weight is carried by the number of kills behind it.
 *
 * <p>This exists for one reason, stated in the brief that asked for it: a figure
 * from two kills and a figure from two hundred must not look alike. Both are
 * "23.4 arrows per kill" on the face of it, and a player who packs a Wilderness
 * trip off the first one has been misled by a decimal point.
 *
 * <p><b>The bands are a reading aid over the sample count, not a statistical
 * claim.</b> Nothing here says a {@link #FAIR} figure is within any particular
 * distance of the truth, because saying that would require assuming a
 * distribution and this plugin's governing principle is that it does not. What
 * they do is make the sample count impossible to skim past: a word next to the
 * number, in a colour, that reads differently at four kills than at four
 * hundred. The raw {@code n} is published beside it in every surface, and the
 * observed spread — {@link ConsumptionEstimate#getLowestKill()} through
 * {@link ConsumptionEstimate#getHighestKill()} — is the part that carries real
 * information about how variable this monster is.
 *
 * <p>The thresholds are round numbers chosen for legibility and are written down
 * here rather than buried in an {@code if}: fewer than five kills is an anecdote,
 * twenty is where a mean stops swinging on every new sample for a typical
 * Wilderness Slayer monster, and a hundred is about a task's worth. They are not
 * derived from anything and are not presented as if they were.
 */
public enum Confidence
{
	/** No attributed kills at all. Nothing to publish. */
	NONE("no data"),

	/** One to four kills. A number, not yet an estimate. */
	ANECDOTAL("anecdotal"),

	/** Five to nineteen kills. */
	THIN("thin"),

	/** Twenty to ninety-nine kills. */
	FAIR("fair"),

	/** A hundred kills or more — about a Slayer task's worth. */
	SOLID("solid");

	static final int ANECDOTAL_FROM = 1;
	static final int THIN_FROM = 5;
	static final int FAIR_FROM = 20;
	static final int SOLID_FROM = 100;

	private final String label;

	Confidence(String label)
	{
		this.label = label;
	}

	/** The word shown next to a figure. Lower case; it sits inside a sentence. */
	public String getLabel()
	{
		return label;
	}

	/**
	 * @param samples the number of attributed kills behind a figure. Negative is
	 *                treated as none rather than rejected — there is no sample
	 *                count below zero that means anything other than "nothing was
	 *                measured", and a thrown exception here would take an overlay
	 *                down over a bookkeeping slip.
	 */
	public static Confidence forSamples(int samples)
	{
		if (samples >= SOLID_FROM)
		{
			return SOLID;
		}
		if (samples >= FAIR_FROM)
		{
			return FAIR;
		}
		if (samples >= THIN_FROM)
		{
			return THIN;
		}
		if (samples >= ANECDOTAL_FROM)
		{
			return ANECDOTAL;
		}
		return NONE;
	}
}
