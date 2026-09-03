package com.matthewmariner.gunnarstools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

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
 *       gross quantity spent, over the number of kills it was spent on. Their
 *       quotient is the number the later milestones exist to multiply, and it is
 *       published by {@link #estimate(int)} rather than by a getter here — see
 *       {@link ConsumptionEstimate#getPerAttributedKill()} and
 *       {@link ConsumptionEstimate#getPerMonster()}, which carry the sample
 *       count and the denominator along with the figure.</li>
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
 *       not be priced — the other targets of an area attack, and anything that
 *       died outside a window this record paid for. It is a disclosure, not a
 *       denominator. <b>The true per-monster cost is not
 *       {@code consumed / (kills + unattributedDeaths)}</b>, which is what an
 *       earlier draft of this javadoc asserted as fact and which the README, the
 *       commit that introduced the correction and {@link ConsumptionEstimate}
 *       all now say is wrong: that column also holds deaths of monsters this
 *       record's ammunition never bought, and deaths of monsters that are not
 *       even this record's species, so dividing by it understates.
 *       {@link #getMonstersPriced()} is the denominator, and
 *       {@link ConsumptionEstimate} is where the reasoning lives.</li>
 * </ul>
 *
 * <p>Gains are only recorded for item ids that appear in the consumed column,
 * and the filter is here rather than in {@link ConsumptionMeter} because it is a
 * statement about this monster rather than about the tick. Coins, bones and
 * every other stackable drop would otherwise fill the recovery column with
 * things that have nothing to do with ammunition, and the column would stop
 * meaning "your consumption figure may be contaminated" — which is the only
 * thing it is for.
 *
 * <h2>The individual kills are kept, not just their total</h2>
 *
 * <p>{@link #estimates()} needs the spread, and a running sum cannot produce
 * one. So every kill appends one entry per item id to a {@link KillSamples}
 * series, and the invariant those series hold is worth stating because it is the
 * thing that most easily rots: <b>every series is exactly as long as
 * {@link #getKills()}, and sums to exactly that item's consumed total.</b>
 *
 * <p>Holding it costs a little bookkeeping in both directions. A kill that spent
 * nothing of an item already being tracked still appends a zero — dropping it
 * would raise the mean of what is left. And an item first seen on the fiftieth
 * kill is backfilled with forty-nine zeros, because forty-nine kills really did
 * cost none of it; the alternative is a series of length one sitting next to a
 * kill count of fifty, and a mean that disagrees with this record's own
 * consumed-over-kills by a factor of fifty.
 *
 * <p>The memory is one {@code long} per kill per item id, for the session. A
 * long Wilderness trip is a few hundred kills against two or three ids: tens of
 * kilobytes, which is not worth a cap that would distort the percentiles it
 * capped.
 */
public final class NpcAmmoRecord
{
	private final int npcId;
	private final String npcName;

	private int[] stats;
	private boolean statsPopulated;

	private int kills;
	private final AmmoTally lifetime = new AmmoTally();

	/** Item id to one entry per kill. See the class javadoc for the invariant. */
	private final Map<Integer, KillSamples> samples = new LinkedHashMap<>();

	private int abandonedFights;
	private final AmmoTally abandoned = new AmmoTally();

	private int unattributedDeaths;

	/**
	 * Monsters of <em>this same id</em> killed by the windows this record priced,
	 * other than the kills themselves. Anything else a barrage killed is somebody
	 * else's record and somebody else's unit; see {@link KillAttribution}.
	 */
	private int pricedCoVictims;

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

	/**
	 * @param coVictims other monsters killed by this same window, from
	 *                  {@link Attribution#getCoVictims()}. There is deliberately
	 *                  no one-argument convenience overload: it would have exactly
	 *                  one caller, none of them {@link AmmoLedger}, and a
	 *                  {@code recordKill(window)} sitting in the class would be a
	 *                  standing invitation to book a kill without its denominator.
	 */
	void recordKill(AmmoTally window, int coVictims)
	{
		kills++;
		pricedCoVictims += coVictims;
		appendSamples(window);
		lifetime.addConsumedAndRelevantGains(window);
	}

	/**
	 * Extends every series by this kill, keeping them all the length of
	 * {@link #getKills()}.
	 *
	 * <p>Two loops rather than one, and they are not interchangeable. The first
	 * appends to every id already tracked — including a zero for the ones this
	 * kill did not touch. The second backfills an id seen for the first time, and
	 * has to run afterwards because inserting into the map while the first loop
	 * iterates it is a {@code ConcurrentModificationException} in production and
	 * nowhere else.
	 */
	private void appendSamples(AmmoTally window)
	{
		for (Map.Entry<Integer, KillSamples> entry : samples.entrySet())
		{
			entry.getValue().add(window.consumedOf(entry.getKey()));
		}

		for (Integer itemId : window.getConsumed().keySet())
		{
			if (samples.containsKey(itemId))
			{
				continue;
			}
			final KillSamples series = new KillSamples();
			for (int earlier = 1; earlier < kills; earlier++)
			{
				series.add(0L);
			}
			series.add(window.consumedOf(itemId));
			samples.put(itemId, series);
		}
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
	 * @return other monsters of this id killed by the windows this record priced.
	 * Zero for every single-target trip there is, and zero for area damage that
	 * only ever caught something else.
	 */
	public int getPricedCoVictims()
	{
		return pricedCoVictims;
	}

	/**
	 * @return how many of <em>this</em> monster the priced windows killed — the
	 * kills plus their same-id co-victims. This, not {@link #getKills()}, is the
	 * denominator a projection wants, because it is counted in the same unit as
	 * the trip size that gets divided into it; see {@link ConsumptionEstimate}.
	 */
	public int getMonstersPriced()
	{
		return kills + pricedCoVictims;
	}

	/**
	 * @return the full estimate for one item, or null if this monster has never
	 * been measured spending it. Null rather than a zeroed estimate: "no arrows
	 * were ever spent on this" and "arrows cost nothing here" are different
	 * claims, and only one of them is ever true.
	 */
	@Nullable
	public ConsumptionEstimate estimate(int itemId)
	{
		final KillSamples series = samples.get(itemId);
		if (series == null)
		{
			return null;
		}
		return ConsumptionEstimate.of(itemId, series, getMonstersPriced(), lifetime.gainedOf(itemId));
	}

	/**
	 * @return one estimate per item this monster has cost, dearest first, with the
	 * item id as a tie-break so the order does not shuffle between reads
	 */
	public List<ConsumptionEstimate> estimates()
	{
		final List<ConsumptionEstimate> out = new ArrayList<>(samples.size());
		samples.forEach((itemId, series) -> out.add(
			ConsumptionEstimate.of(itemId, series, getMonstersPriced(), lifetime.gainedOf(itemId))));

		out.sort(Comparator.comparingLong(ConsumptionEstimate::getConsumed).reversed()
			.thenComparingInt(ConsumptionEstimate::getItemId));

		return Collections.unmodifiableList(out);
	}

	/** Every item id this monster has been measured spending. */
	public Set<Integer> getConsumedItemIds()
	{
		return Collections.unmodifiableSet(samples.keySet());
	}

	public int getNpcId()
	{
		return npcId;
	}

	/** For the overlay's title. Nothing decides anything from it. */
	public String getNpcName()
	{
		return npcName;
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
			+ ", monstersPriced=" + getMonstersPriced()
			+ ", consumed=" + lifetime.getConsumed()
			+ ", recovered=" + lifetime.getGained()
			+ ", abandoned=" + abandonedFights + "x" + abandoned.getConsumed()
			+ ", unattributedDeaths=" + unattributedDeaths
			+ ", stats=" + (statsPopulated ? java.util.Arrays.toString(stats) : "unpopulated") + ")";
	}
}
