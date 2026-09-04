package com.matthewmariner.gunnarstools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Turns one monster's record into the shopping list for a trip.
 *
 * <p>All of the plugin's decision-making about <em>what to show and what to
 * highlight</em> lives here rather than in the plugin class or in an overlay,
 * which is the same rule the measurement layer already follows: an overlay runs
 * every frame and cannot be exercised without a game, so it is left with nothing
 * to get wrong but the drawing.
 *
 * <h2>Every consumed item is planned for, including the ones that look like
 * noise</h2>
 *
 * <p>It is tempting to drop an item that only appears on a minority of kills —
 * the coins that get booked as consumption when a stack goes into the looting
 * bag mid-fight, which the README lists as a known contaminant. The temptation
 * is refused. A filter that hides an item hides a shortfall, and a shortfall is
 * the failure this plugin exists to prevent; an item genuinely spent on a third
 * of kills is exactly the sort of thing a player forgets to pack. So the honest
 * signal is published instead of acted on:
 * {@link ConsumptionEstimate#getKillsWithConsumption()} says "spent on 3 of 37
 * kills", which a human reads correctly in one glance and no threshold can.
 *
 * <p>Ordering is by quantity to bring, descending — the arrows before the stray
 * coins — with the item id as a tie-break so the list is stable between frames
 * rather than reordering on a hash.
 */
public final class TripPlanner
{
	private TripPlanner()
	{
	}

	/**
	 * @param record          the monster to plan for, or null for none
	 * @param targetMonsters  how many of it the trip is for
	 * @param marginPercent   safety margin, per {@link TripPlan#forEstimate}
	 * @return one line per item worth carrying, biggest first. Never null; empty
	 * when there is nothing measured or nothing to bring.
	 */
	public static List<TripPlan> plan(@Nullable NpcAmmoRecord record, int targetMonsters,
		int marginPercent)
	{
		if (record == null)
		{
			return Collections.emptyList();
		}

		final List<TripPlan> plans = new ArrayList<>();
		for (ConsumptionEstimate estimate : record.estimates())
		{
			final TripPlan plan = TripPlan.forEstimate(estimate, targetMonsters, marginPercent);

			// A zero line is not a plan. It is reachable — a trip of no monsters, or
			// an item that appeared in a window at quantity zero — and a bank
			// highlight promising "withdraw 0" is worse than no highlight.
			if (plan.getBring() > 0L)
			{
				plans.add(plan);
			}
		}

		plans.sort(Comparator.comparingLong(TripPlan::getBring).reversed()
			.thenComparingInt(TripPlan::getItemId));

		return Collections.unmodifiableList(plans);
	}

	/**
	 * The same list as a lookup for the bank highlight.
	 *
	 * <p>Insertion-ordered from the sorted plan, so iterating it reads biggest
	 * first like the panel does.
	 */
	public static Map<Integer, Long> withdrawals(List<TripPlan> plans)
	{
		final Map<Integer, Long> out = new LinkedHashMap<>();
		for (TripPlan plan : plans)
		{
			// merge rather than put: two plans for one id would otherwise let the
			// last one win silently. There is no path that produces one today —
			// estimates() is keyed by item id — and this is what keeps it that way
			// if a later milestone plans for more than one monster at a time.
			out.merge(plan.getItemId(), plan.getBring(), Long::sum);
		}
		return Collections.unmodifiableMap(out);
	}

	/**
	 * The same lookup for an estimated trip.
	 *
	 * <p>A near-duplicate of the method above, and deliberately not unified with it
	 * by a shared interface. {@link TripPlan} and {@link ProjectedNeed} are two
	 * types on purpose — see {@link ProjectedNeed} — and a common supertype
	 * introduced to save nine lines here would be a common supertype available
	 * everywhere else, which is exactly the place a measured figure and a modelled
	 * one get mixed up.
	 */
	public static Map<Integer, Long> projectedWithdrawals(List<ProjectedNeed> needs)
	{
		final Map<Integer, Long> out = new LinkedHashMap<>();
		for (ProjectedNeed need : needs)
		{
			out.merge(need.getItemId(), need.getBring(), Long::sum);
		}
		return Collections.unmodifiableMap(out);
	}

	/**
	 * How much of a requirement is still in the bank rather than on the player.
	 *
	 * <p>Six characters of arithmetic with two guards on it, kept here rather than
	 * written inline in {@link BankWithdrawalOverlay} so that both guards have a
	 * test. Saturating at zero is the one that matters: a player already carrying
	 * more than the trip needs produces a negative, and a negative reaching the
	 * highlight would either be drawn as a withdrawal quantity or, worse, compared
	 * against the banked amount and painted red — telling somebody they are short
	 * of an item they are carrying a surplus of.
	 *
	 * @param required what the trip needs in total, gross
	 * @param carried  what the player already holds across inventory, worn
	 *                 equipment and the quiver
	 * @return what is left to withdraw, never negative
	 */
	public static long shortfall(long required, long carried)
	{
		if (carried <= 0L || required <= 0L)
		{
			return Math.max(0L, required);
		}
		return Math.max(0L, required - carried);
	}
}
