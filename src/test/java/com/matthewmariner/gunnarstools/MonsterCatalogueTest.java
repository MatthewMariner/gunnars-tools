package com.matthewmariner.gunnarstools;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The sweep: a slice at a time, nothing partial ever published, and no empty
 * answer ever latched as a permanent one.
 *
 * <p>The last of those is the one worth having a test class for. The client
 * streams its cache, so the first read of the NPC group comes back with nothing
 * and fixes itself a moment later — and a catalogue that recorded that as "this
 * client has no monsters" would answer "no such monster" to every question for the
 * rest of the session, from a condition that resolved itself while it was being
 * written down.
 */
public class MonsterCatalogueTest
{
	private static FakeNpcSource threeMonsters()
	{
		return new FakeNpcSource()
			.with(5265, "Spindel", 200)
			.with(6610, "Venenatis", 850)
			.with(3019, "Spider", 2);
	}

	@Test
	public void aFreshCatalogueHasReadNothing()
	{
		MonsterCatalogue catalogue = new MonsterCatalogue();

		assertEquals(MonsterCatalogue.State.WAITING, catalogue.getState());
		assertFalse(catalogue.isReady());
		assertNull(catalogue.getIndex());
		assertEquals(0, catalogue.getPercent());
	}

	@Test
	public void oneSweepThroughAndTheListIsThere()
	{
		MonsterCatalogue catalogue = new MonsterCatalogue();

		assertEquals(MonsterCatalogue.State.READY,
			catalogue.advance(threeMonsters(), MonsterCatalogue.SLICE));

		assertTrue(catalogue.isReady());
		assertEquals(100, catalogue.getPercent());
		assertEquals(3, catalogue.getIndex().size());
		assertEquals(850, catalogue.getIndex().resolve("Venenatis").get(0).getHitpoints());
	}

	/**
	 * {@code AGENTS.md} forbids scanning everything in one go, and sixteen thousand
	 * definitions is exactly the shape it means. Counted rather than read off the
	 * source: the reads are what cost a tick.
	 */
	@Test
	public void theSweepIsSpreadOverSeveralCallsRatherThanDoneInOne()
	{
		FakeNpcSource source = threeMonsters();
		MonsterCatalogue catalogue = new MonsterCatalogue();

		assertEquals(MonsterCatalogue.State.SCANNING, catalogue.advance(source, 1));
		assertEquals(1, source.reads());
		assertNull("nothing partial is ever visible", catalogue.getIndex());

		assertEquals(MonsterCatalogue.State.SCANNING, catalogue.advance(source, 1));
		assertEquals(2, source.reads());
		assertNull(catalogue.getIndex());

		assertEquals(MonsterCatalogue.State.READY, catalogue.advance(source, 1));
		assertEquals(3, source.reads());
		assertNotNull(catalogue.getIndex());
	}

	@Test
	public void progressClimbsWithTheSweep()
	{
		FakeNpcSource source = new FakeNpcSource()
			.with(1, "One", 10).with(2, "Two", 20).with(3, "Three", 30).with(4, "Four", 40);
		MonsterCatalogue catalogue = new MonsterCatalogue();

		catalogue.advance(source, 1);
		assertEquals(25, catalogue.getPercent());

		catalogue.advance(source, 1);
		assertEquals(50, catalogue.getPercent());

		catalogue.advance(source, 2);
		assertEquals(100, catalogue.getPercent());
	}

	@Test
	public void aSliceOfNothingStillMovesRatherThanStalling()
	{
		FakeNpcSource source = threeMonsters();
		MonsterCatalogue catalogue = new MonsterCatalogue();

		catalogue.advance(source, 0);
		assertEquals("a miscalculated slice must not wedge the sweep", 1, source.reads());

		catalogue.advance(source, -10);
		assertEquals(2, source.reads());
	}

	/**
	 * The archive's index has not been read in yet. Retryable, and the whole point
	 * is that the retry is allowed to happen.
	 */
	@Test
	public void noIdsIsNotYetRatherThanNever()
	{
		FakeNpcSource source = threeMonsters().withoutIds();
		MonsterCatalogue catalogue = new MonsterCatalogue();

		assertEquals(MonsterCatalogue.State.WAITING,
			catalogue.advance(source, MonsterCatalogue.SLICE));
		assertNull(catalogue.getIndex());
		assertEquals("and nothing was read, so nothing was wasted", 0, source.reads());

		source.nowAvailable();

		assertEquals(MonsterCatalogue.State.READY,
			catalogue.advance(source, MonsterCatalogue.SLICE));
		assertEquals(3, catalogue.getIndex().size());
	}

	@Test
	public void anEmptyIdListIsAlsoNotYet()
	{
		MonsterCatalogue catalogue = new MonsterCatalogue();

		assertEquals(MonsterCatalogue.State.WAITING,
			catalogue.advance(new FakeNpcSource(), MonsterCatalogue.SLICE));
		assertNull(catalogue.getIndex());
	}

	/**
	 * The subtler half. The ids are there, so the sweep runs to the end — and every
	 * definition comes back as the client's unnamed placeholder because the group
	 * behind them has not landed. Publishing that would be publishing an index that
	 * says every monster in the game does not exist.
	 */
	@Test
	public void aSweepThatNamedNothingIsThrownAwayAndTriedAgain()
	{
		FakeNpcSource source = threeMonsters().withoutDefinitions();
		MonsterCatalogue catalogue = new MonsterCatalogue();

		assertEquals(MonsterCatalogue.State.WAITING,
			catalogue.advance(source, MonsterCatalogue.SLICE));
		assertNull("an index of nothing is worse than no index", catalogue.getIndex());
		assertFalse(catalogue.isReady());

		source.nowAvailable();

		assertEquals(MonsterCatalogue.State.READY,
			catalogue.advance(source, MonsterCatalogue.SLICE));
		assertEquals(3, catalogue.getIndex().size());
	}

	@Test
	public void aFinishedSweepIsNotRunAgain()
	{
		FakeNpcSource source = threeMonsters();
		MonsterCatalogue catalogue = new MonsterCatalogue();

		catalogue.advance(source, MonsterCatalogue.SLICE);
		int afterFirst = source.reads();

		catalogue.advance(source, MonsterCatalogue.SLICE);
		catalogue.advance(source, MonsterCatalogue.SLICE);

		assertEquals("a tick after the list is read costs nothing", afterFirst, source.reads());
	}

	@Test
	public void whatIsPublishedIsSealed()
	{
		MonsterCatalogue catalogue = new MonsterCatalogue();
		catalogue.advance(threeMonsters(), MonsterCatalogue.SLICE);

		assertTrue("an index handed to the Swing thread must not still be growing",
			catalogue.getIndex().isSealed());
	}

	@Test
	public void clearingPutsItBackWhereItStarted()
	{
		MonsterCatalogue catalogue = new MonsterCatalogue();
		catalogue.advance(threeMonsters(), MonsterCatalogue.SLICE);
		assertTrue(catalogue.isReady());

		catalogue.clear();

		assertEquals(MonsterCatalogue.State.WAITING, catalogue.getState());
		assertNull(catalogue.getIndex());
		assertEquals(0, catalogue.getPercent());
	}

	@Test
	public void clearingPartWayThroughAlsoDropsTheHalfReadSweep()
	{
		FakeNpcSource source = threeMonsters();
		MonsterCatalogue catalogue = new MonsterCatalogue();
		catalogue.advance(source, 1);

		catalogue.clear();

		assertEquals(MonsterCatalogue.State.WAITING, catalogue.getState());

		// Started again from the top rather than resumed from where it was: the
		// half-read sweep is gone, so all three are read once more.
		catalogue.advance(source, MonsterCatalogue.SLICE);
		assertEquals(4, source.reads());
		assertEquals(3, catalogue.getIndex().size());
	}

	/**
	 * Every state has a headline and something to do about it, because an empty
	 * panel and a broken one were the same picture once already in this project.
	 * The one that mattered to the person who asked for this is {@code READY}: the
	 * panel is working, empty, and has to say what fills it in.
	 */
	@Test
	public void everyStateNamesTheActionThatMovesItOn()
	{
		for (MonsterCatalogue.State state : MonsterCatalogue.State.values())
		{
			assertFalse(state + " must have a headline", state.getHeadline().isEmpty());
			assertFalse(state + " must say what to do", state.getDetail().isEmpty());
		}

		assertEquals("type a monster's name", MonsterCatalogue.State.READY.getHeadline());
		assertTrue("the empty state has to teach, which is the complaint that produced it",
			MonsterCatalogue.State.READY.getDetail().contains("pick"));
	}

	@Test
	public void theSliceIsBigEnoughToFinishInAHandfulOfTicks()
	{
		// The archive holds sixteen thousand-odd definitions. At this slice that is
		// four ticks, which is under three seconds — the number is a constant rather
		// than a setting, so it is worth one assertion that it has not been edited
		// into a stall.
		assertTrue(MonsterCatalogue.SLICE >= 1024);
		assertTrue(MonsterCatalogue.SLICE <= 8192);
	}
}
