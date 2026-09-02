package com.matthewmariner.gunnarstools;

import java.util.Map;

/**
 * Everything measured about one monster id: what it costs to kill, how many
 * kills that figure rests on, and what the live NPC's combat stats were.
 *
 * <p>Keyed by NPC id rather than by name because names collide — there are
 * several distinct "Skeleton" and "Bear" ids in the Wilderness with different
 * hitpoints — and because an id is what {@code NPCComposition} agrees with. The
 * name is carried for the log line and for a human reading the record; nothing
 * decides anything from it.
 *
 * <h2>The columns, and why there are five of them rather than one</h2>
 *
 * <ul>
 *   <li>{@link #getConsumed()} and {@link #getKills()} are the measurement:
 *       gross quantity spent, over the number of kills it was spent on.
 *       {@link #consumedPerKill(int)} is their quotient and is the number the
 *       later milestones exist to multiply.</li>
 *   <li>{@link #getRecovered()} is every gain of an item the player also spends
 *       on this monster. It is <b>never subtracted</b> from the consumed
 *       column — see {@link AmmoDelta} for the argument, which comes down to a
 *       plugin being unable to tell an arrow picked back up from an arrow the
 *       monster dropped, and understating a trip's needs being the failure that
 *       actually costs something.</li>
 *   <li>{@link #getAbandoned()} is ammunition spent on this monster in fights
 *       that did not end in an observed kill, with {@link #getAbandonedFights()}
 *       counting them. Kept out of the average entirely.</li>
 *   <li>{@link #getUnattributedDeaths()} counts kills that were real but could
 *       not be priced — the other targets of an area attack. A record with many
 *       of these has a sound cost-per-kill figure and an understated kill
 *       count.</li>
 * </ul>
 *
 * <p>Gains are only recorded for item ids that appear in the consumed column,
 * and the filter is here rather than in {@link ConsumptionMeter} because it is a
 * statement about this monster rather than about the tick. Coins, bones and
 * every other stackable drop would otherwise fill the recovery column with
 * things that have nothing to do with ammunition, and the column would stop
 * meaning "your consumption figure may be contaminated" — which is the only
 * thing it is for.
 */
public final class NpcAmmoRecord
{
	private final int npcId;
	private final String npcName;

	private int[] stats;
	private boolean statsPopulated;

	private int kills;
	private final AmmoTally lifetime = new AmmoTally();

	private int abandonedFights;
	private final AmmoTally abandoned = new AmmoTally();

	private int unattributedDeaths;

	NpcAmmoRecord(FoughtNpc npc)
	{
		this.npcId = npc.getId();
		this.npcName = npc.getName();
		this.stats = npc.getStats();
		this.statsPopulated = npc.hasStats();
	}

	/**
	 * Folds in a fresh sighting of the same monster id.
	 *
	 * <p>Real stats are never overwritten by the {@code {1,1,1,1,1,1}} default.
	 * An NPC whose composition was not resolved on one kill — the transformed
	 * form of a demi-boss read a tick after it changed, say — must not erase
	 * what an earlier kill of the same id already established.
	 */
	void observeStats(FoughtNpc npc)
	{
		if (npc.hasStats())
		{
			this.stats = npc.getStats();
			this.statsPopulated = true;
		}
	}

	void recordKill(AmmoTally window)
	{
		kills++;
		lifetime.addConsumedAndRelevantGains(window);
	}

	void recordAbandoned(AmmoTally window)
	{
		abandonedFights++;
		abandoned.addConsumedAndRelevantGains(window);
	}

	void recordUnattributedDeath()
	{
		unattributedDeaths++;
	}

	/** The sample count. Every published figure is only as good as this number. */
	public int getKills()
	{
		return kills;
	}

	/** Item id to gross quantity spent across {@link #getKills()} kills. */
	public Map<Integer, Long> getConsumed()
	{
		return lifetime.getConsumed();
	}

	/** Item id to quantity gained back. Disclosed, never netted off. */
	public Map<Integer, Long> getRecovered()
	{
		return lifetime.getGained();
	}

	/** Item id to quantity spent in fights that produced no observed kill. */
	public Map<Integer, Long> getAbandoned()
	{
		return abandoned.getConsumed();
	}

	public int getAbandonedFights()
	{
		return abandonedFights;
	}

	public int getUnattributedDeaths()
	{
		return unattributedDeaths;
	}

	/**
	 * @return gross quantity of this item per kill, or 0 when there are no
	 * samples. Deliberately not rounded and deliberately not clamped: a caller
	 * that wants "arrows to bring" has to decide for itself how to round, and
	 * rounding here would hide a figure of 0.4 behind a 0.
	 */
	public double consumedPerKill(int itemId)
	{
		if (kills == 0)
		{
			return 0.0d;
		}
		return (double) lifetime.consumedOf(itemId) / kills;
	}

	/**
	 * @return whether the stats stored here are anything other than the cache's
	 * all-ones default. See {@link FoughtNpc#hasStats()} — this is not the same
	 * question as "is the array non-null", and it is not "is any entry non-zero"
	 * either.
	 */
	public boolean hasStats()
	{
		return statsPopulated;
	}

	/** A copy of the six stats, in {@code NPCComposition.STAT_*} order. */
	public int[] getStats()
	{
		return stats.clone();
	}

	public int getStat(int statIndex)
	{
		return stats[statIndex];
	}

	@Override
	public String toString()
	{
		return "NpcAmmoRecord(" + npcName + " #" + npcId + ", kills=" + kills
			+ ", consumed=" + lifetime.getConsumed()
			+ ", recovered=" + lifetime.getGained()
			+ ", abandoned=" + abandonedFights + "x" + abandoned.getConsumed()
			+ ", unattributedDeaths=" + unattributedDeaths
			+ ", stats=" + (statsPopulated ? java.util.Arrays.toString(stats) : "unpopulated") + ")";
	}
}
