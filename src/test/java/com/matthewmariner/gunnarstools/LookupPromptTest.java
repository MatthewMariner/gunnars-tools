package com.matthewmariner.gunnarstools;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The sentences the lookup shows when it is not showing monsters.
 *
 * <p>The owner's report was "I honestly don't know how to test this… right now
 * I'm lost", which is a report about empty space rather than about a number. So
 * these are assertions that the empty space says something, and specifically that
 * the one state where the plugin is working and has nothing to show names the
 * action that fills it in.
 */
public class LookupPromptTest
{
	private static List<LookupSummary.Line> prompt(MonsterCatalogue.State state, int percent,
		String query, int matches)
	{
		return LookupPrompt.of(state, percent, query, matches);
	}

	@Test
	public void beforeTheListIsReadItSaysSoAndSaysWhy()
	{
		List<LookupSummary.Line> lines = prompt(MonsterCatalogue.State.WAITING, 0, "", 0);

		assertEquals(MonsterCatalogue.State.WAITING.getHeadline(), lines.get(0).getLeft());
		assertEquals(MonsterCatalogue.State.WAITING.getDetail(), lines.get(1).getLeft());
		assertEquals("a percentage that has not started reads as a stuck job",
			"", lines.get(0).getRight());
		assertTrue(lines.get(0).isCaveat());
	}

	@Test
	public void whileTheListIsBeingReadItShowsHowFarThrough()
	{
		List<LookupSummary.Line> lines = prompt(MonsterCatalogue.State.SCANNING, 42, "spider", 0);

		assertEquals(MonsterCatalogue.State.SCANNING.getHeadline(), lines.get(0).getLeft());
		assertEquals("42%", lines.get(0).getRight());
	}

	@Test
	public void aQueryTypedTooEarlyDoesNotGetTheNoMatchAnswer()
	{
		// Nothing has been read, so "no monster by that name" would be a lie about
		// the monster rather than a fact about the list.
		List<LookupSummary.Line> lines = prompt(MonsterCatalogue.State.SCANNING, 10, "venenatis", 0);

		assertFalse(lines.get(0).getLeft().equals(LookupPrompt.NO_MATCH));
		assertEquals(MonsterCatalogue.State.SCANNING.getHeadline(), lines.get(0).getLeft());
	}

	/** The state the complaint was about: it works, it is empty, and it has to teach. */
	@Test
	public void readyAndEmptyNamesTheActionThatFillsItIn()
	{
		for (String nothing : Arrays.asList("", "   ", (String) null))
		{
			List<LookupSummary.Line> lines = prompt(MonsterCatalogue.State.READY, 100, nothing, 0);

			assertEquals(2, lines.size());
			assertEquals("type a monster's name", lines.get(0).getLeft());
			assertTrue("and what happens next", lines.get(1).getLeft().contains("pick"));
			assertFalse("nothing is wrong, so nothing is a caveat", lines.get(0).isCaveat());
		}
	}

	@Test
	public void aSearchThatFoundNothingSaysSoAndSuggestsBothFixes()
	{
		List<LookupSummary.Line> lines = prompt(MonsterCatalogue.State.READY, 100, "venenatsi", 0);

		assertEquals(LookupPrompt.NO_MATCH, lines.get(0).getLeft());
		assertEquals(LookupPrompt.NO_MATCH_DETAIL, lines.get(1).getLeft());
		assertTrue(lines.get(0).isCaveat());
		assertTrue("a typo", lines.get(1).getLeft().contains("spelling"));
		assertTrue("or a name the cache spells differently", lines.get(1).getLeft().contains("less"));
	}

	@Test
	public void aSearchWithResultsExplainsNothingBecauseTheResultsAreTheAnswer()
	{
		assertTrue(prompt(MonsterCatalogue.State.READY, 100, "spider", 4).isEmpty());
	}

	// --- the truncation line ---------------------------------------------------

	@Test
	public void aListShowingEverythingSaysNothingAboutCounts()
	{
		MonsterIndex.Results all = new MonsterIndex.Results(
			Collections.singletonList(match("Spindel", 200)), 1);

		assertNull(LookupPrompt.truncation(all));
	}

	@Test
	public void aListShowingSomeOfThemSaysHowManyItIsHiding()
	{
		MonsterIndex.Results some = new MonsterIndex.Results(
			Arrays.asList(match("Spider", 2), match("Spindel", 200)), 37);

		LookupSummary.Line line = LookupPrompt.truncation(some);

		assertNotNull(line);
		assertEquals("showing 2 of 37", line.getLeft());
		assertEquals("type more", line.getRight());
		assertTrue("a list that looks complete and is not is the failure", line.isCaveat());
	}

	private static MonsterIndex.Match match(String name, int hitpoints)
	{
		return new MonsterIndex.Match(name, hitpoints, Collections.singletonList(1),
			MonsterIndex.Tier.EXACT);
	}
}
