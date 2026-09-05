package com.matthewmariner.gunnarstools;

import java.util.Collections;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The four lines the sidebar draws under the search box.
 *
 * <p>Two things are being held to account. The first is that <b>an estimate never
 * reads as a measurement</b> — the whole plugin is built on that distinction, and a
 * summary is exactly the sort of layout decision that quietly drops a label to fit
 * a line. The second is that every state has something to say: a panel that goes
 * blank when it cannot answer is the bug this project has already fixed once, on a
 * different surface.
 */
public class LookupSummaryTest
{
	private static final int SPINDEL = 5265;
	private static final int VENENATIS = 6610;
	private static final int ARROW = 892;

	private static final Loadout SHORTBOW = new Loadout(861, ARROW);

	private static final IntFunction<String> NAMES = itemId ->
		itemId == ARROW ? "Rune arrow" : "item " + itemId;

	private final AmmoLedger ledger = new AmmoLedger();
	private final AmmoArchive archive = new AmmoArchive();

	private static FoughtNpc npc(int index, int id, String name, int hitpoints)
	{
		return new FoughtNpc(index, id, name, new int[]{130, 130, 130, hitpoints, 1, 130});
	}

	private static AmmoTally spent(long arrows)
	{
		AmmoTally tally = new AmmoTally();
		tally.add(new AmmoDelta(Collections.singletonMap(ARROW, arrows), Collections.emptyMap()));
		return tally;
	}

	private void measure(int npcId, String name, int hitpoints, int times, long perKill)
	{
		ledger.equipped(SHORTBOW);
		for (int kill = 0; kill < times; kill++)
		{
			ledger.apply(Attribution.kill(npc(40 + kill, npcId, name, hitpoints), spent(perKill), 0));
		}
	}

	private TripAdvice advise(PlanTarget target)
	{
		return TripAdvisor.advise(target, ledger, archive, SHORTBOW, 100, 10, true);
	}

	private static PlanTarget target(int npcId, String name, int hitpoints, PlanTarget.Source source)
	{
		return new PlanTarget(npcId, name, hitpoints, source);
	}

	private static List<LookupSummary.Line> summarise(TripAdvice advice)
	{
		return LookupSummary.of(advice, NAMES);
	}

	private static boolean says(List<LookupSummary.Line> lines, String text)
	{
		for (LookupSummary.Line line : lines)
		{
			if (line.getLeft().contains(text) || line.getRight().contains(text))
			{
				return true;
			}
		}
		return false;
	}

	private static LookupSummary.Line firstWith(List<LookupSummary.Line> lines, String left)
	{
		for (LookupSummary.Line line : lines)
		{
			if (line.getLeft().equals(left))
			{
				return line;
			}
		}
		throw new AssertionError("no line labelled " + left + " in " + lines);
	}

	// --- there is always something to say --------------------------------------

	@Test
	public void beforeThereIsAnAnswerAtAllItSaysWhatToDo()
	{
		List<LookupSummary.Line> lines = LookupSummary.of(null, NAMES);

		assertFalse(lines.isEmpty());
		assertTrue(says(lines, "pick a monster"));
	}

	@Test
	public void nothingChosenIsAStateWithItsOwnSentence()
	{
		List<LookupSummary.Line> lines = summarise(
			TripAdvice.waitingFor(null, TripAdvice.Waiting.A_TARGET));

		assertEquals(TripAdvice.Waiting.A_TARGET.getHeadline(), lines.get(0).getLeft());
		assertEquals(TripAdvice.Waiting.A_TARGET.getDetail(), lines.get(1).getLeft());
		assertTrue("a waiting headline is a caveat, not a figure", lines.get(0).isCaveat());
	}

	/**
	 * The two states a typed name produces, and the reason they are separate: a
	 * typo wants retyping and an umbrella name wants a choice. Both leave a value in
	 * the settings field that the player has to be able to get out of.
	 */
	@Test
	public void aNameThatMatchedNothingOffersTheWayOutOfIt()
	{
		List<LookupSummary.Line> lines = summarise(
			TripAdvice.waitingFor(null, TripAdvice.Waiting.UNKNOWN_MONSTER));

		assertEquals(TripAdvice.Waiting.UNKNOWN_MONSTER.getHeadline(), lines.get(0).getLeft());
		assertEquals(LookupSummary.Action.CLEAR_PIN,
			lines.get(lines.size() - 1).getAction());
	}

	@Test
	public void aNameSeveralMonstersAnswerToOffersTheSameWayOut()
	{
		List<LookupSummary.Line> lines = summarise(
			TripAdvice.waitingFor(null, TripAdvice.Waiting.AMBIGUOUS_MONSTER));

		assertEquals(TripAdvice.Waiting.AMBIGUOUS_MONSTER.getHeadline(), lines.get(0).getLeft());
		assertTrue(says(lines, "side panel"));
		assertEquals(LookupSummary.Action.CLEAR_PIN, lines.get(lines.size() - 1).getAction());
	}

	@Test
	public void nothingChosenAtAllDoesNotOfferToClearAPinThatIsNotThere()
	{
		List<LookupSummary.Line> lines = summarise(
			TripAdvice.waitingFor(null, TripAdvice.Waiting.A_TARGET));

		for (LookupSummary.Line line : lines)
		{
			assertEquals("there is no pin to clear", LookupSummary.Action.NONE, line.getAction());
		}
	}

	// --- a measurement ---------------------------------------------------------

	@Test
	public void aMeasurementNamesTheMonsterTheSizeAndWhatToBring()
	{
		measure(SPINDEL, "Spindel", 200, 4, 25L);

		List<LookupSummary.Line> lines = summarise(
			advise(target(SPINDEL, "Spindel", 200, PlanTarget.Source.FIGHTING)));

		assertEquals("Spindel", lines.get(0).getLeft());
		assertEquals("200 hp", lines.get(0).getRight());
		assertEquals("+10%", firstWith(lines, "for 100 kills").getRight());
		assertEquals("2,750", firstWith(lines, "Rune arrow").getRight());
		assertEquals("n=4 anecdotal", firstWith(lines, "measured").getRight());
	}

	@Test
	public void aMeasurementIsNeverDressedUpAsAnEstimate()
	{
		measure(SPINDEL, "Spindel", 200, 4, 25L);

		List<LookupSummary.Line> lines = summarise(
			advise(target(SPINDEL, "Spindel", 200, PlanTarget.Source.FIGHTING)));

		// The figure has to be on screen for its absence of caveats to mean anything:
		// a summary that drew nothing at all would pass both assertions below.
		assertEquals("2,750", firstWith(lines, "Rune arrow").getRight());
		assertFalse("no tilde on a figure that was watched being spent", says(lines, "~"));
		assertFalse(says(lines, "estimate"));
	}

	// --- and the other way round, which is the one that matters ----------------

	@Test
	public void anEstimateSaysSoBeforeItSaysANumber()
	{
		// Measured on a Spindel, asked about Venenatis: nothing here was watched.
		measure(SPINDEL, "Spindel", 200, 4, 25L);

		List<LookupSummary.Line> lines = summarise(
			advise(target(VENENATIS, "Venenatis", 850, PlanTarget.Source.PINNED)));

		int label = -1;
		int quantity = -1;
		for (int i = 0; i < lines.size(); i++)
		{
			if ("estimate".equals(lines.get(i).getLeft()))
			{
				label = i;
			}
			if ("Rune arrow".equals(lines.get(i).getLeft()))
			{
				quantity = i;
			}
		}

		assertTrue("the estimate label must exist", label >= 0);
		assertTrue("and the quantity must exist to be labelled", quantity >= 0);
		assertTrue("a reader who stops at the first number has already been told",
			label < quantity);
		assertEquals("not measured", lines.get(label).getRight());
		assertTrue("and it is drawn as a caveat", lines.get(label).isCaveat());
	}

	@Test
	public void everyEstimatedQuantityCarriesATilde()
	{
		// Redundant next to the label on purpose: a screenshot cropped to the
		// numbers is how a wrong figure gets quoted back at somebody.
		measure(SPINDEL, "Spindel", 200, 4, 25L);

		List<LookupSummary.Line> lines = summarise(
			advise(target(VENENATIS, "Venenatis", 850, PlanTarget.Source.PINNED)));

		assertTrue(firstWith(lines, "Rune arrow").getRight().startsWith("~"));
	}

	// --- the pin, and the way out of it ----------------------------------------

	@Test
	public void aPinnedMonsterOffersAWayBackToFollowingTheFight()
	{
		measure(SPINDEL, "Spindel", 200, 4, 25L);

		List<LookupSummary.Line> lines = summarise(
			advise(target(SPINDEL, "Spindel", 200, PlanTarget.Source.PINNED)));

		LookupSummary.Line last = lines.get(lines.size() - 1);
		assertEquals(LookupSummary.Action.CLEAR_PIN, last.getAction());
		assertEquals("clear", last.getRight());
	}

	@Test
	public void aMonsterYouAreSimplyFightingHasNoPinToClear()
	{
		measure(SPINDEL, "Spindel", 200, 4, 25L);

		List<LookupSummary.Line> lines = summarise(
			advise(target(SPINDEL, "Spindel", 200, PlanTarget.Source.FIGHTING)));

		for (LookupSummary.Line line : lines)
		{
			assertEquals(LookupSummary.Action.NONE, line.getAction());
		}
	}

	/**
	 * A pinned monster with no answer still gets the way out, and that is the case
	 * it is most needed in: picking the wrong spider out of a list is exactly how
	 * somebody arrives at "nothing measured" wondering what they did.
	 */
	@Test
	public void aPinWithNoAnswerYetStillOffersToBeCleared()
	{
		List<LookupSummary.Line> lines = summarise(
			advise(target(VENENATIS, "Venenatis", 850, PlanTarget.Source.PINNED)));

		assertEquals(TripAdvice.Waiting.EVIDENCE.getHeadline(), lines.get(1).getLeft());
		assertEquals(LookupSummary.Action.CLEAR_PIN, lines.get(lines.size() - 1).getAction());
	}

	@Test
	public void hitpointsThatDidNotResolveSaySoRatherThanShowingANumber()
	{
		List<LookupSummary.Line> lines = summarise(
			advise(target(VENENATIS, "Venenatis", 0, PlanTarget.Source.PINNED)));

		assertEquals("hp unknown", lines.get(0).getRight());
	}
}
