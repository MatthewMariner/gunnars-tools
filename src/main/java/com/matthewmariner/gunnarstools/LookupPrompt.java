package com.matthewmariner.gunnarstools;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * What the lookup panel says when it is not showing a list of monsters.
 *
 * <p>Every one of those states is an empty panel, and this project has already
 * learned once what an empty panel costs: {@link TripAdvice.Waiting} exists
 * because a plugin that had never been fed a monster, one waiting on a first kill
 * and one whose hitpoints did not resolve all drew the same blank corner of the
 * screen. The lookup arrived with the same four-problems-one-blank-space shape —
 * the list not read yet, the list part-read, the list ready and nothing typed,
 * and something typed that matched nothing — and the owner's complaint that
 * produced the whole feature was <em>"I don't know how to test this, I'm
 * lost"</em>. So the empty states name the action that fills them in.
 *
 * <p>Static and taking primitives, for the reason every decision in this plugin
 * is: a sentence composed inside a Swing panel is a sentence no test can read.
 */
final class LookupPrompt
{
	/** Said when a search ran and matched nothing. */
	static final String NO_MATCH = "no monster by that name";

	/**
	 * And what to do about it. Two suggestions rather than one because the two
	 * failures are different: a typo wants retyping, and a full name the cache
	 * spells differently ("Spider (Level 2)") wants less of it typed, which is what
	 * the substring tier in {@link MonsterIndex#search} is for.
	 */
	static final String NO_MATCH_DETAIL = "check the spelling, or type less of it";

	private LookupPrompt()
	{
	}

	/**
	 * @param state   how far {@link MonsterCatalogue} has got
	 * @param percent {@link MonsterCatalogue#getPercent()}
	 * @param query   what is in the search box
	 * @param matches how many monsters that query found
	 * @return the headline and the detail, in that order, or an <b>empty list</b>
	 * when there are results to draw and nothing needs explaining. Empty is the
	 * only case with nothing to say, because it is the only case that is not empty.
	 */
	static List<LookupSummary.Line> of(MonsterCatalogue.State state, int percent,
		@Nullable String query, int matches)
	{
		if (state != MonsterCatalogue.State.READY)
		{
			// The percentage only while there is one worth showing. "0%" against a
			// list that has not started reads as a stuck job rather than a waiting one.
			return Arrays.asList(
				new LookupSummary.Line(state.getHeadline(),
					state == MonsterCatalogue.State.SCANNING ? percent + "%" : "", true),
				new LookupSummary.Line(state.getDetail(), "", false));
		}

		final String typed = query == null ? "" : query.trim();
		if (typed.isEmpty())
		{
			return Arrays.asList(
				new LookupSummary.Line(state.getHeadline(), "", false),
				new LookupSummary.Line(state.getDetail(), "", false));
		}

		if (matches <= 0)
		{
			return Arrays.asList(
				new LookupSummary.Line(NO_MATCH, "", true),
				new LookupSummary.Line(NO_MATCH_DETAIL, "", false));
		}

		return Collections.emptyList();
	}

	/**
	 * The line above a truncated results list.
	 *
	 * <p>Said rather than swallowed. A panel that drew the first twenty of two
	 * hundred matches without saying so would be hiding a hundred and eighty
	 * monsters behind a list that looks complete — the same failure as picking one
	 * of an umbrella's thirty spiders, arrived at through the layout instead of
	 * through the search.
	 *
	 * @return the count line, or null when the whole result set is on screen
	 */
	@Nullable
	static LookupSummary.Line truncation(MonsterIndex.Results results)
	{
		return results.isTruncated()
			? new LookupSummary.Line("showing " + results.getMatches().size()
				+ " of " + results.getTotal(), "type more", true)
			: null;
	}
}
