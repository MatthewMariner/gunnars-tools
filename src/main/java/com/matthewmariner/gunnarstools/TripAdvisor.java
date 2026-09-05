package com.matthewmariner.gunnarstools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.NPCComposition;

/**
 * Decides what the panel says: a measurement, an estimate, or what it is waiting
 * for.
 *
 * <p>{@link TripPlanner} turns one record into a shopping list. This chooses
 * <em>which</em> record, and what to do when there is not one — which is the
 * whole of the difference between a plugin that answers a question at a bank and
 * one that only ever describes the thing it just watched die.
 *
 * <p>Everything here is static and takes its inputs as arguments, so the entire
 * decision runs with no game client. {@link GunnarsToolsPlugin} does the reading
 * and the caching; it decides nothing.
 *
 * <h2>The order of preference, and the one place it is not obvious</h2>
 *
 * <ol>
 *   <li><b>This session's measurement of this monster on this setup</b>, when it
 *       has at least as much evidence as anything remembered. A measurement wins
 *       because it has a spread and a sample count behind it and because it is
 *       about the player as they are now.</li>
 *   <li><b>What a previous session measured about this same monster</b>, published
 *       as an estimate — never as a measurement, because the samples are gone and
 *       without them there is no spread and no honest way to call it measured. On
 *       a different weapon or ammunition it is still offered, and the panel says
 *       so.</li>
 *   <li><b>Another monster's rate, scaled by hitpoints</b>, on the same weapon and
 *       ammunition. The last resort, and the only figure in this plugin that rests
 *       on an assumption rather than on arithmetic; see {@link ProjectedNeed}.</li>
 * </ol>
 *
 * <p>The unobvious part is the "at least as much evidence" clause on the first
 * one. It would have been simpler to say a measurement always wins, and it would
 * have been worse: on the first kill of a session the panel would drop a
 * two-hundred-kill remembered figure and replace it with a one-kill measurement,
 * which is a real loss of information dressed up as an upgrade. So the better
 * evidenced of the two is shown, each labelled for what it is, and the switchover
 * happens exactly once — the session's count only ever climbs. Both counts are
 * printed, so the moment it happens is legible rather than mysterious.
 *
 * <h2>Which monster a rate is scaled from</h2>
 *
 * <p>The <em>closest in hitpoints</em>, not the best evidenced. The error in a
 * hitpoints-linear projection grows with how far it is stretched, so a
 * four-hundred-kill series on a 17-hitpoint skeleton is a worse basis for
 * Venenatis at 850 than a nine-kill series on a 200-hitpoint Spindel: the first is
 * a fiftyfold extrapolation and the second is a bit over fourfold. Evidence breaks
 * ties, and the panel prints both hitpoint figures so the size of the stretch is
 * on screen rather than implied.
 */
public final class TripAdvisor
{
	private TripAdvisor()
	{
	}

	/**
	 * @param target           what the plan is about, from
	 *                         {@link PlanTarget#resolve}, or null when the player
	 *                         has neither pinned, fought nor killed anything
	 * @param ledger           this session's measurements
	 * @param archive          what previous sessions left behind
	 * @param equipped         the weapon and ammunition worn now. Only rates
	 *                         measured on this setup are scaled onto another
	 *                         monster: a projection already assumes damage per shot
	 *                         is constant across monsters, and doing it across
	 *                         weapons as well would be two assumptions stacked with
	 *                         no way to tell which one was wrong.
	 * @param targetMonsters   how many the trip is for
	 * @param marginPercent    safety margin, per {@link TripPlan#forEstimate}
	 * @param estimatesAllowed whether the player wants anything other than
	 *                         measurements. False is answered honestly — the panel
	 *                         says estimates are off rather than pretending there is
	 *                         nothing to say.
	 * @return never null. Every path produces either an answer or a stated reason
	 * there is not one.
	 */
	public static TripAdvice advise(@Nullable PlanTarget target, AmmoLedger ledger,
		AmmoArchive archive, Loadout equipped, int targetMonsters, int marginPercent,
		boolean estimatesAllowed)
	{
		if (target == null)
		{
			return TripAdvice.waitingFor(null, TripAdvice.Waiting.A_TARGET);
		}

		final NpcAmmoRecord measuredRecord = ledger.get(target.getNpcId(), equipped);
		final List<TripPlan> plans = TripPlanner.plan(measuredRecord, targetMonsters, marginPercent);
		final AmmoArchive.Entry remembered = archive.get(target.getNpcId());

		final int measuredEvidence = measuredRecord == null ? 0 : measuredRecord.getMonstersPriced();

		// Zero unless what was remembered is about the setup being worn. Comparing
		// counts across a gear change compares two different questions, and a review
		// found what that costs: three hundred remembered shortbow monsters
		// suppressing a fifty-kill crossbow measurement, which is the averaging
		// failure Loadout exists to prevent reached from the other side. A figure
		// about a different weapon does not outrank one about this weapon however
		// many kills stand behind it. It still fills the gap when there is no
		// measurement at all — that is what the projection below is for.
		final int rememberedEvidence = remembered != null && sameGear(remembered, equipped)
			? remembered.getMonsters()
			: 0;

		// The measurement wins outright when it is at least as well evidenced as
		// what was remembered, and when estimates are switched off it wins whatever
		// the counts say — there is nothing else on offer then.
		if (!plans.isEmpty() && (!estimatesAllowed || measuredEvidence >= rememberedEvidence))
		{
			return TripAdvice.measured(target, plans);
		}

		final List<ProjectedNeed> projection = project(target, ledger, archive, equipped, remembered,
			targetMonsters, marginPercent);

		if (!projection.isEmpty())
		{
			return estimatesAllowed
				? TripAdvice.estimated(target, projection)
				: TripAdvice.waitingFor(target, TripAdvice.Waiting.ESTIMATES_OFF);
		}

		// A measurement that lost the comparison above only because something
		// remembered claimed more evidence, and then the projection off that
		// remembered entry came to nothing — an entry whose every quantity rounds
		// away, say. The measurement is still true; publish it rather than a
		// waiting state.
		if (!plans.isEmpty())
		{
			return TripAdvice.measured(target, plans);
		}

		return TripAdvice.waitingFor(target, whyNothing(target, ledger, archive, equipped));
	}

	/**
	 * Everything that could be scaled onto the target, or the remembered entry for
	 * the target itself when there is one.
	 *
	 * @return one estimate per item, biggest first, or empty when nothing can be
	 * projected at all
	 */
	private static List<ProjectedNeed> project(PlanTarget target, AmmoLedger ledger,
		AmmoArchive archive, Loadout equipped, @Nullable AmmoArchive.Entry remembered,
		int targetMonsters, int marginPercent)
	{
		if (remembered != null && remembered.getMonsters() > 0)
		{
			// The same monster. Nothing is stretched onto anything, so the hitpoints
			// are not needed and a monster whose stats never resolved still gets an
			// answer here.
			final List<ProjectedNeed> out = new ArrayList<>();
			for (Map.Entry<Integer, Long> item : remembered.getConsumed().entrySet())
			{
				out.add(ProjectedNeed.remembered(item.getKey(), item.getValue(),
					remembered.getMonsters(), remembered.getName(), sameGear(remembered, equipped),
					targetMonsters, marginPercent));
			}
			return ordered(out);
		}

		if (!target.hasHitpoints())
		{
			return Collections.emptyList();
		}

		final RateSource source = closestTo(target.getHitpoints(),
			candidates(target.getNpcId(), ledger, archive, equipped));
		if (source == null)
		{
			return Collections.emptyList();
		}

		final List<ProjectedNeed> out = new ArrayList<>();
		for (Map.Entry<Integer, Long> item : source.consumed.entrySet())
		{
			out.add(ProjectedNeed.scaled(item.getKey(), item.getValue(), source.monsters,
				source.hitpoints, target.getHitpoints(), source.name, targetMonsters,
				marginPercent));
		}
		return ordered(out);
	}

	/**
	 * Which of {@link TripAdvice.Waiting}'s reasons applies when nothing could be
	 * projected.
	 *
	 * <p>Distinguishing the two is the entire value of the waiting state. "Kill one
	 * to start" and "this monster's hitpoints did not resolve" have different fixes,
	 * and a single blank panel for both is what made the previous version look
	 * broken rather than empty.
	 */
	private static TripAdvice.Waiting whyNothing(PlanTarget target, AmmoLedger ledger,
		AmmoArchive archive, Loadout equipped)
	{
		if (!target.hasHitpoints()
			&& !candidates(target.getNpcId(), ledger, archive, equipped).isEmpty())
		{
			return TripAdvice.Waiting.HITPOINTS;
		}
		return TripAdvice.Waiting.EVIDENCE;
	}

	/**
	 * Resolves the name in the "Plan for" setting to a monster.
	 *
	 * <p>Five places to look, in the order that gets the most complete answer: the
	 * pin the menu action wrote (which carries the live NPC's own hitpoints, for a
	 * monster that may never have been killed), then the monster being fought right
	 * now, then this session's records, then the archive, and finally the game's own
	 * monster list. Case-insensitive throughout, because it is a string somebody
	 * typed rather than one the game supplied.
	 *
	 * <p>The monster being fought is in that list for a reason worth naming. A
	 * player who types a name the plugin has never seen gets "no such monster",
	 * which is correct — and then walks over and attacks one, at which point the
	 * plugin plainly does know it. Without this branch the panel would go on saying
	 * the monster does not exist while the player was hitting it.
	 *
	 * <p><b>{@link MonsterCatalogue}'s index is last, and it is what makes this
	 * field usable at all.</b> Before it, a typed name only resolved to something
	 * already fought, pinned or remembered — so the field could name a monster you
	 * had killed and nothing else, which is the wrong half of the problem: the
	 * monster you have never killed is the one whose cost you cannot guess. The
	 * index is consulted last because everything above it carries either evidence or
	 * a live reading, and it is consulted <em>only when the name is
	 * unambiguous</em>: see {@link #whyPinFailed}.
	 *
	 * @param typed the setting's value, already trimmed. Never empty here.
	 * @param pin   the hidden companion setting, or null
	 * @param index the game's own monster list, or null before it has been read
	 * @return the pinned target, or null when the name matches nothing or matches
	 * too much — which the caller reports through {@link #whyPinFailed} rather than
	 * silently falling back, because a silent fallback would plan for the wrong
	 * monster under a name the player chose
	 */
	@Nullable
	public static PlanTarget resolvePin(String typed, @Nullable PlanTarget pin,
		@Nullable FoughtNpc fighting, AmmoLedger ledger, AmmoArchive archive,
		@Nullable MonsterIndex index)
	{
		if (pin != null && pin.getName().equalsIgnoreCase(typed))
		{
			return pin;
		}

		if (fighting != null && fighting.getName().equalsIgnoreCase(typed))
		{
			return PlanTarget.of(fighting, PlanTarget.Source.PINNED);
		}

		final NpcAmmoRecord measured = ledger.findByName(typed);
		if (measured != null)
		{
			return PlanTarget.of(measured, PlanTarget.Source.PINNED);
		}

		for (AmmoArchive.Entry entry : archive.getEntries())
		{
			if (entry.getName().equalsIgnoreCase(typed))
			{
				return PlanTarget.of(entry, PlanTarget.Source.PINNED);
			}
		}

		if (index != null)
		{
			final List<MonsterIndex.Match> matches = index.exactMatches(typed);
			if (matches.size() == 1)
			{
				final MonsterIndex.Match match = matches.get(0);
				return new PlanTarget(
					preferMeasured(match.getNpcIds(), ledger, archive, ledger.getEquipped()),
					match.getName(), match.getHitpoints(), PlanTarget.Source.PINNED);
			}
		}
		return null;
	}

	/**
	 * Why {@link #resolvePin} came back with nothing.
	 *
	 * <p>Two very different problems wear the same empty result. "Venenatsi" is a
	 * typo and the fix is to retype it; "spider" is nineteen of Krystilia's
	 * thirty-six tasks and the fix is to say <em>which</em> spider, which a text
	 * field cannot offer and the side panel can. Reporting both as "no such monster"
	 * would send a player looking for a spelling mistake that is not there.
	 *
	 * <p>More than one exact match always means more than one <em>size</em>:
	 * {@link MonsterIndex} folds ids that share a name and hitpoints into one row,
	 * so two rows are two genuinely different monsters. That is precisely the
	 * condition under which picking silently would be a factor-of-425 error.
	 */
	public static TripAdvice.Waiting whyPinFailed(String typed, @Nullable MonsterIndex index)
	{
		return index != null && index.exactMatches(typed).size() > 1
			? TripAdvice.Waiting.AMBIGUOUS_MONSTER
			: TripAdvice.Waiting.UNKNOWN_MONSTER;
	}

	/**
	 * Which of several NPC ids sharing one name and one size a plan should be filed
	 * under.
	 *
	 * <p>Records are keyed by monster <em>and</em> loadout, and one monster placed
	 * in several regions is several ids. Picking the wrong one of those files the
	 * plan against an id with no history, so a player who has measured a hundred of
	 * something gets an estimate instead of their own measurement — the record is
	 * right there and the plan is looking one id to its left.
	 *
	 * <p>So the id with evidence wins, and the strongest evidence wins first: a
	 * record measured on the setup being worn, then any record this session, then
	 * anything a previous session remembered, then the lowest id as the tie-break
	 * that keeps the choice deterministic. <b>Nothing is merged.</b> This chooses
	 * which existing key to look under; it never averages two ids' records into one,
	 * which is the failure {@link AmmoLedger}'s keying exists to make
	 * unrepresentable.
	 *
	 * @param npcIds   every id sharing the name and the hitpoints. Never empty.
	 * @param equipped the setup worn now
	 */
	public static int preferMeasured(List<Integer> npcIds, AmmoLedger ledger, AmmoArchive archive,
		Loadout equipped)
	{
		// Sorted here rather than trusted to arrive sorted. MonsterIndex.Match does
		// guarantee it, and this method taking the guarantee on faith is how a second
		// caller one day gets a tie-break that depends on iteration order — which is
		// not a tie-break. It costs a copy of a list that is almost always one long.
		final List<Integer> ordered = new ArrayList<>(npcIds);
		Collections.sort(ordered);

		for (int npcId : ordered)
		{
			if (ledger.get(npcId, equipped) != null)
			{
				return npcId;
			}
		}
		for (int npcId : ordered)
		{
			if (ledger.bestFor(npcId) != null)
			{
				return npcId;
			}
		}
		for (int npcId : ordered)
		{
			if (archive.get(npcId) != null)
			{
				return npcId;
			}
		}
		return ordered.get(0);
	}

	/**
	 * Whether a remembered figure may be shown without a gear caveat on it. See
	 * {@link Loadout#knownToDiffer} for why this is not {@code equals}.
	 */
	private static boolean sameGear(AmmoArchive.Entry remembered, Loadout equipped)
	{
		return !Loadout.knownToDiffer(remembered.getLoadout(), equipped);
	}

	/** A measured rate that could be stretched onto another monster. */
	private static final class RateSource
	{
		private final String name;
		private final int monsters;
		private final int hitpoints;
		private final Map<Integer, Long> consumed;

		private RateSource(String name, int monsters, int hitpoints, Map<Integer, Long> consumed)
		{
			this.name = name;
			this.monsters = monsters;
			this.hitpoints = hitpoints;
			this.consumed = consumed;
		}
	}

	/**
	 * Every monster other than the target whose rate this player measured on the
	 * setup they are wearing, and whose own hitpoints are known.
	 *
	 * <p>The session's records come first and shadow the archive's entry for the
	 * same monster, because a rate measured an hour ago on the gear currently worn
	 * is a better basis than the same monster's entry from last week.
	 */
	private static List<RateSource> candidates(int excludeNpcId, AmmoLedger ledger,
		AmmoArchive archive, Loadout equipped)
	{
		final List<RateSource> out = new ArrayList<>();
		final Set<Integer> covered = new LinkedHashSet<>();

		for (NpcAmmoRecord record : ledger.getRecords())
		{
			if (record.getNpcId() == excludeNpcId
				|| !record.getLoadout().equals(equipped)
				|| record.getMonstersPriced() <= 0
				|| record.getConsumed().isEmpty()
				|| !record.hasStats())
			{
				continue;
			}
			final int hitpoints = record.getStat(NPCComposition.STAT_HITPOINTS);
			if (hitpoints < PlanTarget.MINIMUM_USABLE_HITPOINTS)
			{
				continue;
			}
			covered.add(record.getNpcId());
			out.add(new RateSource(record.getNpcName(), record.getMonstersPriced(), hitpoints,
				record.getConsumed()));
		}

		for (AmmoArchive.Entry entry : archive.getEntries())
		{
			if (entry.getNpcId() == excludeNpcId
				|| covered.contains(entry.getNpcId())
				|| !entry.getLoadout().equals(equipped)
				|| entry.getMonsters() <= 0
				|| entry.getConsumed().isEmpty()
				|| entry.getHitpoints() < PlanTarget.MINIMUM_USABLE_HITPOINTS)
			{
				continue;
			}
			out.add(new RateSource(entry.getName(), entry.getMonsters(), entry.getHitpoints(),
				entry.getConsumed()));
		}
		return out;
	}

	/**
	 * The candidate needing the smallest stretch, with evidence breaking ties.
	 *
	 * <p>The comparison is integer arithmetic on a cross-multiplied ratio rather
	 * than a division, so two candidates the same distance apart on opposite sides
	 * of the target are genuinely tied instead of being separated by whichever way
	 * a double rounded. Hitpoints are small enough that the products cannot
	 * overflow a {@code long} by any margin worth guarding.
	 */
	@Nullable
	private static RateSource closestTo(int targetHitpoints, List<RateSource> candidates)
	{
		RateSource best = null;
		for (RateSource candidate : candidates)
		{
			if (best == null || closer(candidate.hitpoints, best.hitpoints, targetHitpoints)
				|| (sameStretch(candidate.hitpoints, best.hitpoints, targetHitpoints)
					&& candidate.monsters > best.monsters))
			{
				best = candidate;
			}
		}
		return best;
	}

	/** Whether {@code a} needs a smaller stretch onto {@code target} than {@code b}. */
	private static boolean closer(int a, int b, int target)
	{
		return stretchCompare(a, b, target) < 0;
	}

	private static boolean sameStretch(int a, int b, int target)
	{
		return stretchCompare(a, b, target) == 0;
	}

	/**
	 * {@code max(a,t)/min(a,t)} against {@code max(b,t)/min(b,t)}, cross-multiplied.
	 */
	private static int stretchCompare(int a, int b, int target)
	{
		final long left = (long) Math.max(a, target) * Math.min(b, target);
		final long right = (long) Math.max(b, target) * Math.min(a, target);
		return Long.compare(left, right);
	}

	/**
	 * Biggest first, item id as the tie-break so the list does not shuffle between
	 * frames, and nothing that came out at zero — an estimate of "bring 0" is worse
	 * than no line, exactly as {@link TripPlanner#plan} argues for the measured case.
	 */
	private static List<ProjectedNeed> ordered(List<ProjectedNeed> needs)
	{
		needs.removeIf(need -> need.getBring() <= 0L);
		needs.sort(Comparator.comparingLong(ProjectedNeed::getBring).reversed()
			.thenComparingInt(ProjectedNeed::getItemId));
		return Collections.unmodifiableList(needs);
	}
}
