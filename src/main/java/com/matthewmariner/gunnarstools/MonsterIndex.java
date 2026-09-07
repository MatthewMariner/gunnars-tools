package com.matthewmariner.gunnarstools;

import java.util.ArrayList;
import java.util.Arrays;
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
		CONTAINS,

		/**
		 * The name starts with something close enough to what was typed.
		 * "Dagannoth Rex" for "dagganoth" — see {@link #prefixDistance}.
		 */
		NEAR,

		/**
		 * Found only after a slang word somewhere in the query was rewritten to a
		 * real one — see {@link MonsterAliases} and {@link #matching}. Ranked after
		 * every tier above and never disguised as one of them: {@link #EXACT} means
		 * the cache's own spelling was typed, and "abby demon" resolving to Abyssal
		 * demon is not that, however confidently it resolves. A caller that cannot
		 * tell an alias hit from a real one cannot tell the player either — see
		 * {@code LookupPrompt#aliasNotice}.
		 */
		ALIAS
	}

	/**
	 * Shorter than this and a near match is not attempted at all.
	 *
	 * <p>Three characters have too many neighbours to be a spelling mistake: at one
	 * edit, "rat" reaches "bat", "rats", "at" and a hundred others, and a list of
	 * everything three letters could have meant is not an answer. The cheap tiers
	 * already cover a short query — {@link Tier#PREFIX} and {@link Tier#CONTAINS}
	 * are what "spid" wants.
	 */
	static final int NEAR_MATCH_MINIMUM = 4;

	/**
	 * From this many characters typed, two edits are forgiven rather than one.
	 *
	 * <p>The owner typed "Dagganoth" for "Dagannoth", which is two substitutions in
	 * nine characters, and a threshold that only ever forgave one would have missed
	 * exactly the report that produced this. A long word carries enough signal that
	 * two edits still identify it; a five-letter one does not.
	 */
	static final int TWO_EDITS_FROM = 7;

	/**
	 * The most edits ever forgiven, however long the query.
	 *
	 * <p>A ceiling rather than a scale, because the cost of a near match is not the
	 * arithmetic — it is the wrong monster appearing in a list the player then picks
	 * from. Three edits into a twelve-letter name reaches things nobody typing it
	 * meant.
	 */
	static final int MAX_EDITS = 2;

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
		private final int distance;

		Match(String name, int hitpoints, List<Integer> npcIds, Tier tier)
		{
			this(name, hitpoints, npcIds, tier, 0);
		}

		Match(String name, int hitpoints, List<Integer> npcIds, Tier tier, int distance)
		{
			this.name = name;
			this.hitpoints = hitpoints;
			this.distance = distance;

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

		/**
		 * How many edits away from what was typed, for a {@link Tier#NEAR} match.
		 * Zero for every other tier, which is what makes it a sort key the cheap
		 * tiers do not have to know about.
		 */
		int getDistance()
		{
			return distance;
		}

		@Override
		public String toString()
		{
			return "Match(" + name + ", hp=" + (hitpoints > 0 ? Integer.toString(hitpoints) : "unresolved")
				+ ", ids=" + npcIds + ", " + tier + (distance > 0 ? " " + distance : "") + ")";
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
		@Nullable
		private final String aliasedQuery;

		Results(List<Match> matches, int total)
		{
			this(matches, total, null);
		}

		/**
		 * @param aliasedQuery what the query was rewritten to before it found these
		 *                     matches, or null if it matched as typed — see
		 *                     {@link #getAliasedQuery()}
		 */
		Results(List<Match> matches, int total, @Nullable String aliasedQuery)
		{
			this.matches = Collections.unmodifiableList(matches);
			this.total = total;
			this.aliasedQuery = aliasedQuery;
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

		/**
		 * What the query was rewritten to by {@link MonsterAliases} before these
		 * matches were found, or null if the query the player typed found them
		 * directly and nothing was rewritten.
		 *
		 * <p>Carried on the result rather than left for the caller to recompute,
		 * because the caller — {@code MonsterLookupPanel} — has no other way to know
		 * a rewrite happened at all: every {@link Match} it draws already looks like
		 * an ordinary row. Displaying that fact is what {@code LookupPrompt
		 * #aliasNotice} exists for; a rewrite the player cannot see is a search that
		 * appears to have ignored what they typed.
		 */
		@Nullable
		String getAliasedQuery()
		{
			return aliasedQuery;
		}

		/** Whether {@link #getMatches()} answered a nickname rather than a name. */
		boolean isViaAlias()
		{
			return aliasedQuery != null;
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
		final List<Group> groups = byName.computeIfAbsent(normalise(name),
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
	 * with it, then names containing it anywhere, then names starting with something
	 * close enough — and within a tier it is how close, then the name, then the size,
	 * then the lowest id. Every one of those is a total order over the data, so the
	 * same cache and the same query produce the same list every time; a results list
	 * that reshuffled between keystrokes would be one nobody could click.
	 *
	 * <p>Called on every keystroke: this is the autocomplete. The three cheap tiers
	 * are a scan of the keys and nothing else, and the fourth only runs when they
	 * came back empty — see {@link #matching}.
	 *
	 * @param query what the player typed. Trimmed, case-folded and space-collapsed
	 *              here; empty matches nothing, because a panel listing all sixteen
	 *              thousand NPCs is a panel that has not been searched.
	 * @param limit the most rows to return. {@link Results#getTotal()} still counts
	 *              the rest, so a truncated list can say it is one.
	 */
	Results search(@Nullable String query, int limit)
	{
		final String needle = normalise(query);
		if (needle.isEmpty() || limit <= 0)
		{
			return new Results(Collections.emptyList(), 0);
		}

		final List<Match> found = matching(needle);
		final int total = found.size();

		// Recomputed rather than threaded back out of matching() itself: whether the
		// first row is Tier.ALIAS already says a rewrite happened, and MonsterAliases
		// .expand is a pure function of the needle, so asking it again for the string
		// to show is cheaper than giving every caller of matching() a second return
		// value it does not need.
		final String aliasedQuery = !found.isEmpty() && found.get(0).getTier() == Tier.ALIAS
			? MonsterAliases.expand(needle)
			: null;
		return new Results(new ArrayList<>(found.subList(0, Math.min(limit, total))), total,
			aliasedQuery);
	}

	/**
	 * The monsters a typed name could mean, at the best quality anything matched it.
	 *
	 * <p>What the "Plan for" setting resolves through, and the reason it is one
	 * method rather than an exact lookup: the owner typed "Dagganoth" and nothing
	 * happened, because an index that only answers to the spelling it holds cannot
	 * tell a typo from a monster that does not exist.
	 *
	 * <p><b>Only the best band is returned, and that is what keeps the ambiguity
	 * rule intact.</b> An exact match wins outright over the prefixes and near
	 * misses standing behind it, so "Bear" is still the Bear and not a choice
	 * between the Bear and the Bear cub. What comes back with more than one row in
	 * it is a genuine choice between monsters that matched equally well — "spider"
	 * naming a two-hitpoint Spider and Venenatis at 850, or "dagganoth" naming the
	 * three Kings, the ordinary Dagannoths and the spawns — and the caller reports
	 * that rather than picking, because a silent pick is the failure this whole
	 * class exists to avoid.
	 *
	 * <p><b>Unlike {@link #search}, nothing here says whether a nickname settled
	 * it.</b> {@link Results#isViaAlias()} exists because {@code MonsterLookupPanel}
	 * has no other way to tell a player their query was rewritten; a bare {@code
	 * List<Match>} has no equivalent slot, and this deliberately does not grow one.
	 * The gap is narrower than it looks: the caller is the "Plan for" setting field,
	 * which — unlike a search box a player is still looking at — shows the plan it
	 * settled on afterward, real name and all, so a rewrite here is not hidden, only
	 * unannounced at the moment it happens. Adding a signal this method's one
	 * caller has never needed would be a wart grown for a design worry rather than
	 * a real one.
	 */
	List<Match> resolve(@Nullable String name)
	{
		final String needle = normalise(name);
		if (needle.isEmpty())
		{
			return Collections.emptyList();
		}

		final List<Match> found = matching(needle);
		if (found.isEmpty())
		{
			return Collections.emptyList();
		}

		final Tier best = found.get(0).getTier();
		final List<Match> band = new ArrayList<>(found.size());
		for (Match match : found)
		{
			if (match.getTier() != best)
			{
				break;
			}
			band.add(match);
		}
		return Collections.unmodifiableList(band);
	}

	/**
	 * Every match for an already-normalised needle, best first — trying the
	 * needle as typed, and only ever falling back to {@link MonsterAliases} if
	 * that came back with nothing at all.
	 *
	 * <p><b>The alias pass is a fallback behind a fallback, and the rule is the
	 * same as the near tier's: it runs only when {@link #matchingWithoutAlias}
	 * found nothing whatsoever.</b> That single rule is what guarantees every
	 * search this project already had keeps working unchanged — a real name, a
	 * typo the near tier already forgives, always wins outright over a nickname
	 * standing behind it, because a nickname is never even consulted while either
	 * of those has an answer. "abby" alone is already a near match for both Abyssal
	 * demon and Abyssal Sire, so it is never routed through here at all; "abby
	 * demon" is, because the extra word he typed correctly made the whole-string
	 * near match worse rather than better.
	 *
	 * <p>What runs on a miss is not a second matcher. {@link MonsterAliases#expand}
	 * rewrites the slang tokens in the needle to a distinctive token of the real
	 * name and the rewritten needle is handed straight back to
	 * {@link #matchingWithoutAlias} — the exact same three cheap tiers and the same
	 * near fallback, on a different string. Every {@link Match} that comes back is
	 * then relabelled {@link Tier#ALIAS} rather than left as whatever tier the
	 * rewritten string happened to hit, because the caller needs to know a
	 * nickname was involved and {@link Tier#EXACT} would be a lie about that.
	 *
	 * <p><b>Relabelling is a rename, never a reorder.</b> {@link #matchingWithoutAlias}
	 * already sorted {@code viaAlias} by the real tier it matched at and, within a
	 * tier, by how close — that ordering is sorted out before either of the two
	 * facts it depends on gets thrown away, which is what makes it safe to carry
	 * forward rather than recompute. The distance travels onto the relabelled
	 * {@link Match} unchanged rather than being zeroed, and the relabelled list is
	 * <em>not</em> sorted a second time: every relabelled match shares the same
	 * {@link Tier#ALIAS}, so re-running {@link #ORDER} over them would have nothing
	 * left to compare but distance and name — which is exactly backwards when two
	 * matches came from different original tiers (both reported as distance zero,
	 * so a real order between them is only visible before relabelling) and exactly
	 * wrong when two came from the near tier at different distances, either way
	 * replacing "how well each one actually matched" with alphabetical order. Two
	 * table entries expanding to two different tiers, or one expansion the near
	 * tier reaches by more than one distance, are the cases this matters for, and
	 * it is only dormant today because the seven shipped entries happen not to
	 * produce either mix.
	 */
	private List<Match> matching(String needle)
	{
		final List<Match> found = matchingWithoutAlias(needle);
		if (!found.isEmpty())
		{
			return found;
		}

		final String aliased = MonsterAliases.expand(needle);
		if (aliased.equals(needle))
		{
			// Nothing in the query was a known nickname — the miss stands.
			return found;
		}

		final List<Match> viaAlias = matchingWithoutAlias(aliased);
		if (viaAlias.isEmpty())
		{
			return viaAlias;
		}

		// Already ordered by the tier and distance the rewritten string actually
		// matched at, before either is discarded below — matchingWithoutAlias sorts
		// with this same ORDER before returning. A defensive copy rather than a
		// trust that the list handed back stays sorted forever: the sort this
		// method's ordering guarantee rests on has to be visible at the point it is
		// relied on, not three calls away in a method nothing here should have to
		// re-read to believe.
		final List<Match> byOriginalOrder = new ArrayList<>(viaAlias);
		byOriginalOrder.sort(ORDER);

		final List<Match> relabelled = new ArrayList<>(byOriginalOrder.size());
		for (Match match : byOriginalOrder)
		{
			relabelled.add(new Match(match.getName(), match.getHitpoints(), match.getNpcIds(),
				Tier.ALIAS, match.getDistance()));
		}
		return relabelled;
	}

	/**
	 * The three cheap tiers, then the near tier if those found nothing — every
	 * match for {@code needle} exactly as typed, with no knowledge that
	 * {@link #matching} might try it again through {@link MonsterAliases}.
	 *
	 * <p>The near tier is a <b>fallback rather than an addition</b>, and that is a
	 * decision worth stating. "spid" is one edit from "Spindel", so a near pass that
	 * always ran would put Spindel in the middle of a search for spiders — a list
	 * polluted at exactly the moment it was working. It costs nothing to run the
	 * expensive pass only when the cheap ones found nothing, and what it buys is
	 * that a query which matches properly is never diluted by one that nearly does.
	 */
	private List<Match> matchingWithoutAlias(String needle)
	{
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

		if (found.isEmpty())
		{
			final int budget = editBudget(needle.length());
			for (Map.Entry<String, List<Group>> entry : byName.entrySet())
			{
				final int distance = prefixDistance(needle, entry.getKey(), budget);
				if (distance > budget)
				{
					continue;
				}
				for (Group group : entry.getValue())
				{
					found.add(new Match(group.name, group.hitpoints, group.npcIds, Tier.NEAR,
						distance));
				}
			}
		}

		found.sort(ORDER);
		return found;
	}

	/**
	 * @return which of the three cheap tiers {@code name} matches {@code needle} in,
	 * or null for no match. Both arguments are already {@link #normalise}d.
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
	 * How many edits a query of this length is allowed to be wrong by.
	 *
	 * <p>Zero below {@link #NEAR_MATCH_MINIMUM} is what switches near matching off
	 * altogether rather than being a threshold nothing can meet: a distance of zero
	 * is an exact match, which the cheap tiers already caught, so a budget of zero
	 * cannot admit anything the pass above did not.
	 */
	static int editBudget(int typedLength)
	{
		if (typedLength < NEAR_MATCH_MINIMUM)
		{
			return 0;
		}
		return typedLength < TWO_EDITS_FROM ? 1 : MAX_EDITS;
	}

	/**
	 * How many edits turn {@code needle} into the <em>start</em> of {@code name}.
	 *
	 * <p>Anchored at the start rather than measured over the whole name, and that is
	 * the half that makes it useful here. "Dagganoth" against "Dagannoth Rex" scored
	 * end to end is four characters adrift and would be refused; scored against the
	 * prefixes of that name it is the two substitutions the player actually made.
	 * Krystilia's tasks are full of names that are one word plus a qualifier, so a
	 * query is routinely a prefix of the answer and mistyped as well.
	 *
	 * <p>An insertion, a deletion, a substitution or <b>two neighbouring letters the
	 * wrong way round</b> each count one. The last of those is not a refinement: it
	 * is the commonest typing mistake there is, and plain Levenshtein charges two for
	 * it, so a six-letter name typed as "Sipder" would sit outside a one-edit budget
	 * while a completely different monster two substitutions away sat inside it.
	 * Counting a swap as the single mistake it was is what makes a tight budget
	 * affordable.
	 *
	 * <p>Computed in a band {@code budget} wide either side of the diagonal. The band
	 * is not an approximation: a prefix whose length differs from the query's by more
	 * than {@code budget} needs at least that many insertions or deletions, so
	 * nothing outside it could have scored within budget anyway. What it buys is a
	 * fixed handful of cells per character instead of a whole matrix — over sixteen
	 * thousand names, on the Swing thread, between keystrokes.
	 *
	 * @return the distance, or anything above {@code budget} for "further away than
	 * that". The exact value beyond the budget is not meaningful.
	 */
	static int prefixDistance(String needle, String name, int budget)
	{
		final int typed = needle.length();
		final int infinite = budget + 1;
		if (budget <= 0 || name.length() < typed - budget)
		{
			// Too short to reach: turning the needle into any prefix of this name
			// costs at least one deletion per character it does not have.
			return infinite;
		}

		// No prefix longer than this can be within budget, for the same reason.
		final int limit = Math.min(name.length(), typed + budget);

		// Three rows rather than two, because a swap looks back two of each.
		int[] older = new int[limit + 1];
		int[] previous = new int[limit + 1];
		int[] current = new int[limit + 1];
		Arrays.fill(older, infinite);
		for (int j = 0; j <= limit; j++)
		{
			previous[j] = Math.min(j, infinite);
		}

		for (int i = 1; i <= typed; i++)
		{
			final int lo = Math.max(0, i - budget);
			final int hi = Math.min(limit, i + budget);

			// The two cells just outside the band, which still hold whatever an older
			// row left there. Written rather than read stale — the band shifts one
			// column per row, so exactly one cell on each side falls out of date, and
			// filling whole rows instead would undo the point of banding them.
			if (lo >= 1)
			{
				current[lo - 1] = infinite;
			}
			if (i + budget <= limit)
			{
				previous[i + budget] = infinite;
			}

			if (lo == 0)
			{
				current[0] = Math.min(i, infinite);
			}

			for (int j = Math.max(1, lo); j <= hi; j++)
			{
				final int substitute = previous[j - 1]
					+ (needle.charAt(i - 1) == name.charAt(j - 1) ? 0 : 1);
				final int insert = current[j - 1] + 1;
				final int delete = previous[j] + 1;
				int best = Math.min(substitute, Math.min(insert, delete));

				if (i > 1 && j > 1
					&& needle.charAt(i - 1) == name.charAt(j - 2)
					&& needle.charAt(i - 2) == name.charAt(j - 1))
				{
					best = Math.min(best, older[j - 2] + 1);
				}

				current[j] = Math.min(infinite, best);
			}

			final int[] recycled = older;
			older = previous;
			previous = current;
			current = recycled;
		}

		int best = infinite;
		for (int j = Math.max(0, typed - budget); j <= limit; j++)
		{
			best = Math.min(best, previous[j]);
		}
		return best;
	}

	/**
	 * One spelling of a name, used on both sides of every comparison: lower case,
	 * trimmed, and runs of whitespace collapsed to a single space.
	 *
	 * <p>Both halves earn their place. Case, because it is a string somebody typed
	 * rather than one the game supplied. Whitespace, because "dagannoth  rex" and a
	 * name with a trailing space are the same monster and a player who pasted one
	 * should not be told it does not exist. Done here, once, so that a name stored
	 * one way and matched another cannot happen — the failure {@link #add}'s
	 * sanitising note is about, reached through spacing instead of punctuation.
	 */
	private static String normalise(@Nullable String text)
	{
		if (text == null)
		{
			return "";
		}

		final String lower = text.toLowerCase(Locale.ROOT);
		final StringBuilder out = new StringBuilder(lower.length());
		boolean pendingSpace = false;
		for (int i = 0; i < lower.length(); i++)
		{
			final char c = lower.charAt(i);
			if (Character.isWhitespace(c))
			{
				pendingSpace = out.length() > 0;
				continue;
			}
			if (pendingSpace)
			{
				out.append(' ');
				pendingSpace = false;
			}
			out.append(c);
		}
		return out.toString();
	}

	/**
	 * Tier, then how close, then name, then size, then lowest id — see
	 * {@link #search}.
	 *
	 * <p>The distance is second so that a near-miss list runs closest first: a
	 * player who typed one letter wrong should not have to read past the ones who
	 * typed two. It is zero on every other tier, so it changes nothing there.
	 *
	 * <p>The size is compared ascending rather than descending because a list read
	 * top to bottom then runs weakest to strongest within a name, which is the
	 * order the umbrella tasks are usually spoken in. It is a presentation choice;
	 * what is not a choice is that it is deterministic.
	 */
	private static final Comparator<Match> ORDER =
		Comparator.<Match>comparingInt(match -> match.tier.ordinal())
			.thenComparingInt(Match::getDistance)
			.thenComparing(match -> match.name, String.CASE_INSENSITIVE_ORDER)
			.thenComparingInt(Match::getHitpoints)
			.thenComparingInt(match -> match.npcIds.get(0));

	@Override
	public String toString()
	{
		return "MonsterIndex(" + size() + " monster(s)" + (sealed ? ", sealed" : "") + ")";
	}
}
