package com.matthewmariner.gunnarstools;

/**
 * What one monster costs in one item, with everything needed to judge the figure
 * printed on the same label.
 *
 * <h2>Two rates, both published, neither one able to be mistaken for the other</h2>
 *
 * <p>{@link #getPerAttributedKill()} is gross quantity over the number of kills
 * this plugin was able to <em>price</em>. {@link #getPerMonster()} is the same
 * quantity over the number of monsters those priced windows actually killed.
 * They are equal — exactly, not approximately — whenever nothing died to splash
 * damage, which is every ranged and melee trip there is. They come apart under
 * area damage, and when they do the difference is large: a barrage that kills
 * three monsters puts one window's worth of runes against one kill, so the
 * per-attributed-kill figure overstates the real cost by about the number of
 * monsters caught in each cast. Measured at 3.0× in a four-barrage test.
 *
 * <p>Milestone 1 published only the first of those and said so in its
 * limitations. Publishing it alone <em>now</em>, next to a projection that
 * multiplies it by a hundred and twenty, would be repeating a claim this project
 * has already corrected in writing. So both are here, both are labelled, and
 * {@link TripPlan} multiplies the per-monster one, because "how many will I
 * kill" is a question about monsters.
 *
 * <h2>What the co-victim count is, and what it is not</h2>
 *
 * <p>{@link #getMonstersPriced()} is the kill count plus the deaths
 * {@link KillAttribution} saw inside the windows it priced. It is deliberately
 * <em>not</em> {@code kills + unattributedDeaths}, which is what the README's
 * limitation section suggested and which leaks in two ways this one does not.
 * An unattributed death is filed against the dead monster's own id, so under a
 * cross-id area attack the correction would be applied to a record that never
 * held the ammunition; and a monster the player damaged, walked away from and
 * that died later to somebody else is an unattributed death whose consumption
 * went into the abandoned column, so counting it would inflate the denominator
 * without inflating the numerator — an <em>understatement</em>, which is the
 * direction that ends a trip early. See {@link KillAttribution} for how the
 * co-victims are counted instead.
 *
 * <h2>Spread</h2>
 *
 * <p>{@link #getLowestKill()}, {@link #getMedianKill()},
 * {@link #getNinetiethKill()} and {@link #getHighestKill()} are nearest-rank
 * order statistics over the per-kill samples — real observed quantities, no
 * fitted curve. See {@link KillSamples}. They are stated per <em>attributed
 * kill</em>, because that is the unit each sample was measured in; converting an
 * order statistic onto the per-monster basis would mean assuming every window
 * caught the same number of co-victims, and that assumption is a model.
 */
public final class ConsumptionEstimate
{
	/**
	 * The high percentile published as the spread's upper shoulder. Ninety rather
	 * than a hundred because the maximum of a growing sample only ever goes up:
	 * it is the single unluckiest kill in the session and it drifts upward
	 * forever, which makes it a poor thing to plan against. The maximum is
	 * published too, as {@link #getHighestKill()}, but as an observation rather
	 * than as the shoulder.
	 */
	static final int UPPER_PERCENTILE = 90;

	static final int MEDIAN_PERCENTILE = 50;

	private final int itemId;
	private final int attributedKills;
	private final int killsWithConsumption;
	private final int monstersPriced;
	private final long consumed;
	private final long recovered;
	private final long lowestKill;
	private final long medianKill;
	private final long ninetiethKill;
	private final long highestKill;
	private final Confidence confidence;

	private ConsumptionEstimate(int itemId, KillSamples samples, int monstersPriced, long recovered)
	{
		this.itemId = itemId;
		this.attributedKills = samples.size();
		this.killsWithConsumption = samples.nonZero();
		this.monstersPriced = monstersPriced;
		this.consumed = samples.sum();
		this.recovered = recovered;
		this.lowestKill = samples.percentile(0);
		this.medianKill = samples.percentile(MEDIAN_PERCENTILE);
		this.ninetiethKill = samples.percentile(UPPER_PERCENTILE);
		this.highestKill = samples.percentile(100);
		this.confidence = Confidence.forSamples(this.attributedKills);
	}

	/**
	 * @param monstersPriced attributed kills plus the co-victims of the windows
	 *                       those kills closed. Never fewer than the sample count;
	 *                       a caller that passes fewer is describing something
	 *                       impossible — more windows than the deaths they
	 *                       contained — and would silently make
	 *                       {@link #getPerMonster()} larger than
	 *                       {@link #getPerAttributedKill()}, which is backwards.
	 */
	static ConsumptionEstimate of(int itemId, KillSamples samples, int monstersPriced, long recovered)
	{
		if (monstersPriced < samples.size())
		{
			throw new IllegalArgumentException(
				"monstersPriced " + monstersPriced + " is fewer than the " + samples.size()
					+ " kills that produced it");
		}
		return new ConsumptionEstimate(itemId, samples, monstersPriced, recovered);
	}

	public int getItemId()
	{
		return itemId;
	}

	/** The sample count. Every figure below is only as good as this number. */
	public int getAttributedKills()
	{
		return attributedKills;
	}

	/**
	 * @return how many of those kills spent any of this item. Equal to
	 * {@link #getAttributedKills()} for a real ammunition cost; much lower for
	 * something that landed in the record by accident.
	 */
	public int getKillsWithConsumption()
	{
		return killsWithConsumption;
	}

	/** Attributed kills plus the co-victims of the windows they closed. */
	public int getMonstersPriced()
	{
		return monstersPriced;
	}

	/** Gross quantity spent. Recovery is never subtracted; see {@link AmmoDelta}. */
	public long getConsumed()
	{
		return consumed;
	}

	/** Quantity of this same item gained back. Disclosed, never netted off. */
	public long getRecovered()
	{
		return recovered;
	}

	public long getLowestKill()
	{
		return lowestKill;
	}

	public long getMedianKill()
	{
		return medianKill;
	}

	/** The ninetieth-percentile kill: nine kills in ten cost this much or less. */
	public long getNinetiethKill()
	{
		return ninetiethKill;
	}

	public long getHighestKill()
	{
		return highestKill;
	}

	public Confidence getConfidence()
	{
		return confidence;
	}

	/** Gross quantity per kill the plugin could price. Zero with no samples. */
	public double getPerAttributedKill()
	{
		if (attributedKills == 0)
		{
			return 0.0d;
		}
		return (double) consumed / attributedKills;
	}

	/**
	 * @return gross quantity per monster those priced windows killed — the figure
	 * to multiply by a task's size. Equal to {@link #getPerAttributedKill()}
	 * whenever no area damage was seen.
	 */
	public double getPerMonster()
	{
		if (monstersPriced == 0)
		{
			return 0.0d;
		}
		return (double) consumed / monstersPriced;
	}

	/**
	 * @return whether any monster died inside a window other than the one that
	 * closed it — that is, whether the two rates above differ at all. False for
	 * every single-target trip, which makes the display's decision about how much
	 * to explain a measured one rather than a permanent extra line.
	 */
	public boolean isAreaDamageSeen()
	{
		return monstersPriced > attributedKills;
	}

	@Override
	public String toString()
	{
		return "ConsumptionEstimate(item=" + itemId
			+ ", consumed=" + consumed
			+ ", perKill=" + getPerAttributedKill()
			+ ", perMonster=" + getPerMonster()
			+ ", n=" + attributedKills + "/" + monstersPriced
			+ ", spread=" + lowestKill + "/" + medianKill + "/" + ninetiethKill + "/" + highestKill
			+ ", recovered=" + recovered
			+ ", confidence=" + confidence + ")";
	}
}
