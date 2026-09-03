package com.matthewmariner.gunnarstools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A running sum of {@link AmmoDelta}s. Mutable on purpose — it is the
 * accumulator behind both the open attribution window in
 * {@link KillAttribution} and the lifetime totals in {@link NpcAmmoRecord}.
 *
 * <p>Keeps the two directions apart for the reason {@link AmmoDelta} explains
 * at length: a gain and a loss are different facts about the trip, and folding
 * them together destroys the only evidence that a figure might be contaminated
 * by floor pickups or by ammunition the monster dropped.
 *
 * <p>Insertion-ordered so a debug line reads in the order things happened
 * rather than in hash order, which matters when the log is the only view onto
 * this data in M1.
 */
public final class AmmoTally
{
	private final Map<Integer, Long> consumed = new LinkedHashMap<>();
	private final Map<Integer, Long> gained = new LinkedHashMap<>();

	public void add(AmmoDelta delta)
	{
		delta.getConsumed().forEach((id, qty) -> consumed.merge(id, qty, Long::sum));
		delta.getGained().forEach((id, qty) -> gained.merge(id, qty, Long::sum));
	}

	/**
	 * Folds another tally's gains in, but only for item ids this tally has
	 * already recorded as consumed — see
	 * {@link NpcAmmoRecord#recordKill(AmmoTally, int)} for why the filter exists and
	 * why it is applied here rather than at the meter.
	 */
	void addConsumedAndRelevantGains(AmmoTally other)
	{
		other.consumed.forEach((id, qty) -> consumed.merge(id, qty, Long::sum));
		other.gained.forEach((id, qty) ->
		{
			if (consumed.containsKey(id))
			{
				gained.merge(id, qty, Long::sum);
			}
		});
	}

	public long consumedOf(int itemId)
	{
		return consumed.getOrDefault(itemId, 0L);
	}

	public long gainedOf(int itemId)
	{
		return gained.getOrDefault(itemId, 0L);
	}

	public Map<Integer, Long> getConsumed()
	{
		return Collections.unmodifiableMap(consumed);
	}

	public Map<Integer, Long> getGained()
	{
		return Collections.unmodifiableMap(gained);
	}

	public boolean isEmpty()
	{
		return consumed.isEmpty() && gained.isEmpty();
	}

	public void clear()
	{
		consumed.clear();
		gained.clear();
	}

	/** A detached copy, so the caller can keep it after this tally is reused. */
	public AmmoTally copy()
	{
		AmmoTally copy = new AmmoTally();
		copy.consumed.putAll(consumed);
		copy.gained.putAll(gained);
		return copy;
	}

	@Override
	public String toString()
	{
		return "AmmoTally(consumed=" + consumed + ", gained=" + gained + ")";
	}
}
