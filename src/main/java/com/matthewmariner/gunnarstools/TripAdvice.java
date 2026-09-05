package com.matthewmariner.gunnarstools;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Everything the two overlays draw, in one immutable object: which monster the
 * plan is about, what to bring, whether that is a measurement or an estimate, and
 * — when it is neither — what the plugin is waiting for.
 *
 * <h2>The waiting state is the point of this class existing at all</h2>
 *
 * <p>Before this, an empty panel and a broken plugin were the same picture. The
 * overlay returned null whenever it had nothing, and "nothing" covered every one
 * of: you have not enabled it properly, you have not fought anything, you have
 * fought something the plugin could not identify, and you pinned a monster whose
 * name it cannot find. Four different problems with four different fixes, all
 * presented as a blank corner of the screen.
 *
 * <p>So {@link Waiting} is a field rather than a null, and each of its values
 * carries the two short lines the panel prints. They are built here rather than in
 * the overlay for the reason every decision in this plugin is: an overlay cannot
 * be exercised without a game client, so a sentence composed inside one is a
 * sentence no test can read.
 *
 * <h2>Measured and estimated are two lists, and only one is ever populated</h2>
 *
 * <p>They could have been one list of a common supertype. They are not, because
 * the display and the log both have to say which of the two they are looking at,
 * and a supertype is exactly the thing that lets a caller forget to ask. See
 * {@link ProjectedNeed} on why that distinction is worth a whole second type.
 */
public final class TripAdvice
{
	/** Why there is no answer, in the plugin's own words. */
	public enum Waiting
	{
		/** There is an answer. Both lines are empty. */
		NOTHING("", ""),

		/**
		 * No monster has been chosen and none has been fought. The commonest
		 * first-run state, and the one that used to look identical to a crash.
		 */
		A_TARGET("no monster yet", "attack or pin one"),

		/**
		 * A monster is chosen, but nothing anywhere — this session or the archive —
		 * gives a rate to work from.
		 */
		EVIDENCE("nothing measured", "kill one to start"),

		/**
		 * A monster is chosen and there is evidence to scale from, but the monster's
		 * own hitpoints did not resolve, so there is nothing to scale onto. See
		 * {@link PlanTarget} on why a hitpoints reading of 1 is refused rather than
		 * used.
		 */
		HITPOINTS("hitpoints unknown", "kill one to measure"),

		/** The name in the "Plan for" setting matches no monster the plugin knows. */
		UNKNOWN_MONSTER("no such monster", "clear \"Plan for\""),

		/**
		 * The name in the "Plan for" setting is one several monsters answer to, and
		 * they are not the same size.
		 *
		 * <p>This is nineteen of Krystilia's thirty-six tasks: "spider" is a
		 * two-hitpoint Spider and it is Venenatis at 850, and a plugin that picked
		 * one would be wrong by a factor of 425 while looking entirely confident. A
		 * settings text field has nowhere to offer a choice, so it says there is one
		 * and points at the surface that can — the lookup panel, where every
		 * candidate is listed with its own hitpoints beside it.
		 */
		AMBIGUOUS_MONSTER("several monsters match", "pick one in the side panel"),

		/**
		 * A name is in the "Plan for" setting and the game's monster list has not
		 * been read yet, so there is nothing to resolve it against.
		 *
		 * <p>Not a fact about the monster, which is why it is not
		 * {@link #UNKNOWN_MONSTER}. The sweep takes a few seconds after login and a
		 * name typed inside that window used to come back as "no such monster" —
		 * a message that was wrong, sounded certain, and pointed at the spelling.
		 * See {@link MonsterCatalogue} on why an empty answer there is "not yet"
		 * rather than "never".
		 */
		MONSTER_LIST("monster list not read yet", "give it a moment"),

		/**
		 * Estimates are switched off and this monster has not been measured. Said out
		 * loud rather than shown as "nothing measured", because the fix is a setting
		 * rather than a kill.
		 */
		ESTIMATES_OFF("estimates are off", "kill one to measure");

		private final String headline;
		private final String detail;

		Waiting(String headline, String detail)
		{
			this.headline = headline;
			this.detail = detail;
		}

		/** Short enough for the panel's left column. Lower case. */
		public String getHeadline()
		{
			return headline;
		}

		/** What to do about it, in three or four words. */
		public String getDetail()
		{
			return detail;
		}
	}

	private final PlanTarget target;
	private final List<TripPlan> measured;
	private final List<ProjectedNeed> projected;
	private final Map<Integer, Long> withdrawals;
	private final Waiting waiting;

	private TripAdvice(@Nullable PlanTarget target, List<TripPlan> measured,
		List<ProjectedNeed> projected, Map<Integer, Long> withdrawals, Waiting waiting)
	{
		this.target = target;
		this.measured = measured;
		this.projected = projected;
		this.withdrawals = withdrawals;
		this.waiting = waiting;
	}

	/** Nothing to say, and the reason why. */
	static TripAdvice waitingFor(@Nullable PlanTarget target, Waiting waiting)
	{
		return new TripAdvice(target, Collections.emptyList(), Collections.emptyList(),
			Collections.emptyMap(), waiting);
	}

	/** A figure this session watched being spent. */
	static TripAdvice measured(PlanTarget target, List<TripPlan> plans)
	{
		return new TripAdvice(target, plans, Collections.emptyList(),
			TripPlanner.withdrawals(plans), Waiting.NOTHING);
	}

	/** A figure derived from something else, labelled as one everywhere it appears. */
	static TripAdvice estimated(PlanTarget target, List<ProjectedNeed> needs)
	{
		return new TripAdvice(target, Collections.emptyList(), needs,
			TripPlanner.projectedWithdrawals(needs), Waiting.NOTHING);
	}

	/** The monster the answer is about, or null when none has been chosen. */
	@Nullable
	public PlanTarget getTarget()
	{
		return target;
	}

	/** One line per item, biggest first. Empty unless {@link #isMeasured()}. */
	public List<TripPlan> getMeasured()
	{
		return measured;
	}

	/** One line per item, biggest first. Empty unless {@link #isEstimated()}. */
	public List<ProjectedNeed> getProjected()
	{
		return projected;
	}

	/**
	 * Item id to gross quantity the trip needs, from whichever of the two lists is
	 * populated. What {@link BankWithdrawalOverlay} looks an item up in.
	 */
	public Map<Integer, Long> getWithdrawals()
	{
		return withdrawals;
	}

	/** True when the figures rest on kills this session watched. */
	public boolean isMeasured()
	{
		return !measured.isEmpty();
	}

	/** True when the figures rest on something other than this monster, this session. */
	public boolean isEstimated()
	{
		return !projected.isEmpty();
	}

	/** {@link Waiting#NOTHING} when there is an answer. */
	public Waiting getWaitingFor()
	{
		return waiting;
	}

	@Override
	public String toString()
	{
		return "TripAdvice(" + target + ", "
			+ (isMeasured() ? "measured " + measured.size() + " item(s)"
				: isEstimated() ? "estimated " + projected.size() + " item(s)"
					: "waiting for " + waiting)
			+ ")";
	}
}
