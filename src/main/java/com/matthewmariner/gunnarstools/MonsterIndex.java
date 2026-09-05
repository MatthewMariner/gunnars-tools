package com.matthewmariner.gunnarstools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import net.runelite.api.NPCComposition;

/**
 * Every monster the game's own cache knows about, searchable by name.
 *
 * <p>This is the piece that was missing. Until it existed the only way to name
 * the subject of a plan was to shift-right-click a monster in the world, which
 * meant standing next to the thing you were planning to fight — and the question
 * "how many arrows should I bring?" is asked at a bank, where there is nothing to
 * right-click. {@link PlanTarget} separated the plan's subject from the
 * measurement; this separates naming a subject from having one in front of you.
 *
 * <h2>It is not a bundled monster table, and the difference is the whole point</h2>
 *
 * <p>Nothing here ships with the plugin. The names and the hitpoints are read out
 * of the client's own cache at runtime, one NPC at a time, through
 * {@link NpcSource} — so they are the numbers this player's client actually holds
 * on the day they are read, and a game update changes them without anything here
 * being edited. A table checked into the repository would be stale the week after
 * it was generated and would have to be right about hitpoints it could not
 * observe. See {@link FoughtNpc} for what reading a monster's own stats buys.
 *
 * <h2>Ambiguity is shown, never resolved silently</h2>
 *
 * <p>Nineteen of Krystilia's thirty-six Wilderness Slayer tasks are umbrella
 * names. "Spider" spans a two-hitpoint Spider and Venenatis at 850, a factor of
 * 425, and any code that picks one of those on the player's behalf is wrong four
 * hundred times out of a possible four hundred and twenty-five with no way for
 * them to tell. So a search returns <em>every</em> distinct monster the name
 * matches and the caller shows the list.
 *
 * <p>Distinct means distinct in the two things a trip plan depends on: the
 * <b>name</b> and the <b>hitpoints</b>. Several NPC ids routinely share both —
 * one monster placed in several regions is several ids — and listing those
 * separately would be a menu of a dozen identical rows, which is a different way
 * of failing to answer. They are folded into one {@link Match} that carries all
 * of its ids, so nothing is hidden: {@link Match#getNpcIds()} is the whole group,
 * {@link Match#getVariants()} is how many, and choosing which of them a plan is
 * filed under is a decision made where the evidence is, in
 * {@link TripAdvisor#preferMeasured}, not guessed at here.
 *
 * <h2>Built on one thread, read on another</h2>
 *
 * <p>{@link #add} runs on the client thread — {@code Client.getNpcDefinition}
 * refuses to run anywhere else — and searching runs on the Swing thread, because
 * that is where a side panel's text field lives. The two never overlap, because
 * an index is filled in privately and only handed over once {@link #seal()} has
 * made it unmodifiable. A sealed index is immutable, and a thread that sees the
 * reference is guaranteed by JLS 17.5 to see everything reachable from it — the
 * same argument {@link GunnarsToolsPlugin}'s cached advice rests on, and it holds
 * here for the same reason: nothing is mutated after publication.
 */
final class MonsterIndex
{
	/**
	 * The name the client gives an NPC id nothing was ever defined for.
	 *
	 * <p>Most of the sixteen thousand ids in the archive are one of these. They
	 * are dropped rather than indexed, because a search that answers "null" is a
	 * search that has told the player their spelling was fine and their monster
	 * does not exist.
	 */
	private static final String UNNAMED = "null";

	/**
	 * How closely a monster's name matched what was typed. The ordinal is the sort
	 * key, so the tiers are declared best-first and nothing else has to know how
	 * many there are.
	 */
	enum Tier
	{
		/** The typed name, exactly. "Venenatis" for "venenatis". */
		EXACT,

		/** The name starts with what was typed. "Spindel" for "spin". */
		PREFIX,

		/** The name contains it anywhere. "Giant spider" for "spider". */
		CONTAINS
	}

	/**
	 * One monster, as something to plan for: a name, a size, and every NPC id that
	 * shares both.
	 */
	static final class Match
	{
		private final String name;
		private final int hitpoints;
		private final List<Integer> npcIds;
		private final Tier tier;

		Match(String name, int hitpoints, List<Integer> npcIds, Tier tier)
		{
			this.name = name;
			this.hitpoints = hitpoints;

			// Sorted rather than left in the order the sweep found them. The archive's
			// id list arrives in whatever order the client's index has it, and the
			// lowest id is the documented tie-break both here and in
			// TripAdvisor.preferMeasured — a tie-break that depends on iteration order
			// is not one.
			final List<Integer> sorted = new ArrayList<>(npcIds);
			Collections.sort(sorted);
			this.npcIds = Collections.unmodifiableList(sorted);
			this.tier = tier;
		}

		/** Already {@link ConfigText#sanitise}d, so it is the string a pin is stored under. */
		String getName()
		{
			return name;
		}

		/**
		 * Hitpoints, or zero when the cache's reading cannot be trusted — the same
		 * rule and the same threshold as {@link PlanTarget}, arrived at through the
		 * same {@link FoughtNpc#hasStats()} check rather than a second copy of it.
		 */
		int getHitpoints()
		{
			return hitpoints;
		}

		boolean hasHitpoints()
		{
			return hitpoints > 0;
		}

		/** Every id sharing this name and size, lowest first. Never empty. */
		List<Integer> getNpcIds()
		{
			return npcIds;
		}

		/**
		 * How many ids are behind this one row. One for most monsters; more for a
		 * monster the game places in several regions. Shown rather than swallowed —
		 * see the class javadoc.
		 */
		int getVariants()
		{
			return npcIds.size();
		}

		Tier getTier()
		{
			return tier;
		}

		@Override
		public String toString()
		{
			return "Match(" + name + ", hp=" + (hitpoints > 0 ? Integer.toString(hitpoints) : "unresolved")
				+ ", ids=" + npcIds + ", " + tier + ")";
		}
	}

	/**
	 * What a search found: the rows worth drawing, and how many there were
	 * altogether.
	 *
	 * <p>The two are separate numbers on purpose. A panel that silently drew the
	 * first twenty of two hundred matches would be hiding a hundred and eighty
	 * monsters behind a list that looks complete, which is the same failure as
	 * picking one of an umbrella's thirty spiders — it is wrong and it looks right.
	 * {@link #getTotal()} is what lets the panel say so.
	 */
	static final class Results
	{
		private final List<Match> matches;
		private final int total;

		Results(List<Match> matches, int total)
		{
			this.matches = Collections.unmodifiableList(matches);
			this.total = total;
		}

		/** At most the limit the caller asked for, best match first. */
		List<Match> getMatches()
		{
			return matches;
		}

		/** How many distinct monsters matched, including the ones not returned. */
		int getTotal()
		{
			return total;
		}

		/** Whether {@link #getMatches()} is short of {@link #getTotal()}. */
		boolean isTruncated()
		{
			return matches.size() < total;
		}
	}

	/** One name and one size, and the ids that share them. Mutable only while filling. */
	private static final class Group
	{
		private final String name;
		private final int hitpoints;
		private final List<Integer> npcIds = new ArrayList<>(1);

		private Group(String name, int hitpoints)
		{
			this.name = name;
			this.hitpoints = hitpoints;
		}
	}

	/**
	 * Lower-cased name to the groups under it, one per distinct hitpoints value.
	 *
	 * <p>Grouping happens here rather than at search time so that a keystroke costs
	 * a scan of the keys and nothing else. Insertion-ordered so that two runs over
	 * the same cache produce the same list in the same order, which is what makes
	 * the ordering assertable.
	 */
	private Map<String, List<Group>> byName = new LinkedHashMap<>();

	private boolean sealed;

	/**
	 * Files one NPC under its name.
	 *
	 * <p>Nulls, blanks and the client's {@code "null"} placeholder are dropped;
	 * everything else is {@link ConfigText#sanitise}d <em>here</em>, on the way in,
	 * for the reason {@link PlanTarget} and {@link AmmoArchive.Entry} both sanitise
	 * in their constructors: a name stored one way and matched another way stops
	 * matching, and this project has already had to fix that once. One name,
	 * decided once, and every comparison downstream is between two strings that
	 * went through the same door.
	 *
	 * @param npc a monster read out of the cache, or null, which is skipped —
	 *            see {@link NpcSource}
	 */
	void add(@Nullable FoughtNpc npc)
	{
		if (sealed)
		{
			throw new IllegalStateException("a sealed index is published and must not change");
		}
		if (npc == null)
		{
			return;
		}

		final String raw = npc.getName();
		if (raw == null || raw.trim().isEmpty() || UNNAMED.equalsIgnoreCase(raw.trim()))
		{
			return;
		}

		final String name = ConfigText.sanitise(raw);
		final int hitpoints = usableHitpoints(npc);
		final List<Group> groups = byName.computeIfAbsent(name.toLowerCase(Locale.ROOT),
			key -> new ArrayList<>(1));

		for (Group group : groups)
		{
			if (group.hitpoints == hitpoints)
			{
				if (!group.npcIds.contains(npc.getId()))
				{
					group.npcIds.add(npc.getId());
				}
				return;
			}
		}

		final Group group = new Group(name, hitpoints);
		group.npcIds.add(npc.getId());
		groups.add(group);
	}

	/**
	 * The one number this class refuses to publish, refused the same way
	 * {@link PlanTarget} refuses it.
	 *
	 * <p>A stats array the cache never filled in reads as {@code {1,1,1,1,1,1}},
	 * so a hitpoints reading of 1 is ambiguous between "nothing was filled in" and
	 * "it really is 1" — and dividing a trip's ammunition by it would turn an
	 * unfilled cache entry into an estimate two orders of magnitude too large.
	 * Zero means "did not resolve", which the panel says out loud.
	 */
	private static int usableHitpoints(FoughtNpc npc)
	{
		if (!npc.hasStats())
		{
			return 0;
		}
		final int hitpoints = npc.getStat(NPCComposition.STAT_HITPOINTS);
		return hitpoints < PlanTarget.MINIMUM_USABLE_HITPOINTS ? 0 : hitpoints;
	}

	/**
	 * Freezes the index so it can be handed to another thread.
	 *
	 * @return this, so a caller can publish the result of the call
	 */
	MonsterIndex seal()
	{
		if (!sealed)
		{
			byName = Collections.unmodifiableMap(byName);
			sealed = true;
		}
		return this;
	}

	boolean isSealed()
	{
		return sealed;
	}

	/** How many distinct name-and-size monsters are indexed. */
	int size()
	{
		int total = 0;
		for (List<Group> groups : byName.values())
		{
			total += groups.size();
		}
		return total;
	}

	boolean isEmpty()
	{
		return byName.isEmpty();
	}

	/**
	 * Every monster whose name matches {@code query}, best match first.
	 *
	 * <p>Best is {@link Tier} order — exactly what was typed, then names starting
	 * with it, then names containing it anywhere — and within a tier it is the
	 * name, then the size, then the lowest id. Every one of those is a total order
	 * over the data, so the same cache and the same query produce the same list
	 * every time; a results list that reshuffled between keystrokes would be one
	 * nobody could click.
	 *
	 * @param query what the player typed. Trimmed and case-folded here; empty
	 *              matches nothing, because a panel listing all sixteen thousand
	 *              NPCs is a panel that has not been searched.
	 * @param limit the most rows to return. {@link Results#getTotal()} still counts
	 *              the rest, so a truncated list can say it is one.
	 */
	Results search(@Nullable String query, int limit)
	{
		final String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		if (needle.isEmpty() || limit <= 0)
		{
			return new Results(Collections.emptyList(), 0);
		}

		final List<Match> found = new ArrayList<>();
		for (Map.Entry<String, List<Group>> entry : byName.entrySet())
		{
			final Tier tier = tierOf(entry.getKey(), needle);
			if (tier == null)
			{
				continue;
			}
			for (Group group : entry.getValue())
			{
				found.add(new Match(group.name, group.hitpoints, group.npcIds, tier));
			}
		}

		found.sort(ORDER);
		final int total = found.size();
		return new Results(new ArrayList<>(found.subList(0, Math.min(limit, total))), total);
	}

	/**
	 * Every monster called exactly {@code name}.
	 *
	 * <p>What the "Plan for" setting resolves through. More than one result is the
	 * umbrella case — "spider" naming several monsters of different sizes — and the
	 * caller reports that rather than picking, because a settings field has no room
	 * to offer a choice and a silent pick is the failure this whole class exists to
	 * avoid.
	 */
	List<Match> exactMatches(@Nullable String name)
	{
		final String needle = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
		final List<Group> groups = needle.isEmpty() ? null : byName.get(needle);
		if (groups == null)
		{
			return Collections.emptyList();
		}

		final List<Match> out = new ArrayList<>(groups.size());
		for (Group group : groups)
		{
			out.add(new Match(group.name, group.hitpoints, group.npcIds, Tier.EXACT));
		}
		out.sort(ORDER);
		return Collections.unmodifiableList(out);
	}

	/**
	 * @return which tier {@code name} matches {@code needle} in, or null for no
	 * match at all. Both arguments are already lower case.
	 */
	@Nullable
	private static Tier tierOf(String name, String needle)
	{
		if (name.equals(needle))
		{
			return Tier.EXACT;
		}
		if (name.startsWith(needle))
		{
			return Tier.PREFIX;
		}
		return name.contains(needle) ? Tier.CONTAINS : null;
	}

	/**
	 * Tier, then name, then size, then lowest id — see {@link #search}.
	 *
	 * <p>The size is compared ascending rather than descending because a list read
	 * top to bottom then runs weakest to strongest within a name, which is the
	 * order the umbrella tasks are usually spoken in. It is a presentation choice;
	 * what is not a choice is that it is deterministic.
	 */
	private static final Comparator<Match> ORDER =
		Comparator.<Match>comparingInt(match -> match.tier.ordinal())
			.thenComparing(match -> match.name, String.CASE_INSENSITIVE_ORDER)
			.thenComparingInt(Match::getHitpoints)
			.thenComparingInt(match -> match.npcIds.get(0));

	@Override
	public String toString()
	{
		return "MonsterIndex(" + size() + " monster(s)" + (sealed ? ", sealed" : "") + ")";
	}
}
