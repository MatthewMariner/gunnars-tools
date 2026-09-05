package com.matthewmariner.gunnarstools;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The name index: what goes in, what comes out, and — the part that matters —
 * what it refuses to decide.
 *
 * <p>Every case here runs with no game client, which is the point of the split:
 * {@link NpcSource} reads the cache and this decides what the reading means.
 */
public class MonsterIndexTest
{
	private static final int SPIDER = 3019;
	private static final int GIANT_SPIDER = 59;
	private static final int SPINDEL = 5265;
	private static final int VENENATIS = 6610;

	private static FoughtNpc npc(int id, String name, int hitpoints)
	{
		return new FoughtNpc(FoughtNpc.NO_INDEX, id, name,
			new int[]{70, 70, 70, hitpoints, 1, 70});
	}

	private static MonsterIndex indexOf(FoughtNpc... monsters)
	{
		final MonsterIndex index = new MonsterIndex();
		for (FoughtNpc monster : monsters)
		{
			index.add(monster);
		}
		return index.seal();
	}

	@Test
	public void aNameFindsItsMonster()
	{
		MonsterIndex index = indexOf(npc(VENENATIS, "Venenatis", 850));

		MonsterIndex.Results found = index.search("venenatis", 10);

		assertEquals(1, found.getTotal());
		assertEquals("Venenatis", found.getMatches().get(0).getName());
		assertEquals(850, found.getMatches().get(0).getHitpoints());
		assertEquals(Arrays.asList(VENENATIS), found.getMatches().get(0).getNpcIds());
	}

	/**
	 * The whole reason this class exists rather than a lookup returning one answer.
	 *
	 * <p>Krystilia's "spider" is a two-hitpoint Spider and it is Venenatis at 850, a
	 * factor of 425 apart, and the plugin's entire design is built around never
	 * silently choosing between two numbers like that.
	 */
	@Test
	public void anUmbrellaNameReturnsEveryMonsterUnderIt()
	{
		MonsterIndex index = indexOf(
			npc(SPIDER, "Spider", 2),
			npc(GIANT_SPIDER, "Giant spider", 5),
			npc(SPINDEL, "Spindel", 200),
			npc(VENENATIS, "Venenatis", 850));

		MonsterIndex.Results found = index.search("spid", 10);

		assertEquals("Giant spider, Spider — and nothing that is not a spider",
			2, found.getTotal());
		List<Integer> sizes = Arrays.asList(
			found.getMatches().get(0).getHitpoints(),
			found.getMatches().get(1).getHitpoints());
		assertTrue("both sizes are on offer rather than one of them picked",
			sizes.contains(2) && sizes.contains(5));
	}

	@Test
	public void exactMatchesSeparateMonstersThatShareANameAndNotASize()
	{
		MonsterIndex index = indexOf(
			npc(SPIDER, "Spider", 2),
			npc(SPIDER + 1, "Spider", 8));

		List<MonsterIndex.Match> matches = index.exactMatches("spider");

		assertEquals("two sizes are two monsters", 2, matches.size());
		assertEquals(2, matches.get(0).getHitpoints());
		assertEquals(8, matches.get(1).getHitpoints());
	}

	/**
	 * The other half of the same rule. Several ids for one monster placed in
	 * several regions is not a choice worth offering — it is a dozen identical rows
	 * — so they fold into one, and the row says how many folded.
	 */
	@Test
	public void idsSharingANameAndASizeFoldIntoOneRowThatSaysHowMany()
	{
		MonsterIndex index = indexOf(
			npc(SPIDER + 2, "Spider", 2),
			npc(SPIDER, "Spider", 2),
			npc(SPIDER + 1, "Spider", 2));

		List<MonsterIndex.Match> matches = index.exactMatches("Spider");

		assertEquals(1, matches.size());
		assertEquals(3, matches.get(0).getVariants());
		assertEquals("lowest id first, whatever order the sweep found them in",
			Arrays.asList(SPIDER, SPIDER + 1, SPIDER + 2), matches.get(0).getNpcIds());
	}

	@Test
	public void theSameIdTwiceIsStillOneVariant()
	{
		// A sweep that ran twice, or an archive listing an id twice, must not make a
		// monster look like three of itself.
		MonsterIndex index = indexOf(
			npc(SPINDEL, "Spindel", 200),
			npc(SPINDEL, "Spindel", 200));

		assertEquals(1, index.exactMatches("Spindel").get(0).getVariants());
	}

	@Test
	public void exactBeatsPrefixWhichBeatsSubstring()
	{
		MonsterIndex index = indexOf(
			npc(1, "Black bear", 30),
			npc(2, "Bear", 25),
			npc(3, "Bear cub", 20));

		List<MonsterIndex.Match> matches = index.search("bear", 10).getMatches();

		assertEquals(Arrays.asList("Bear", "Bear cub", "Black bear"),
			Arrays.asList(matches.get(0).getName(), matches.get(1).getName(),
				matches.get(2).getName()));
		assertEquals(MonsterIndex.Tier.EXACT, matches.get(0).getTier());
		assertEquals(MonsterIndex.Tier.PREFIX, matches.get(1).getTier());
		assertEquals(MonsterIndex.Tier.CONTAINS, matches.get(2).getTier());
	}

	@Test
	public void theSameQueryTwiceGivesTheSameListInTheSameOrder()
	{
		// A list that reshuffled between keystrokes is a list nobody can click.
		MonsterIndex index = indexOf(
			npc(4, "Skeleton", 29),
			npc(5, "Skeleton", 17),
			npc(6, "Skeleton Hellhound", 100),
			npc(7, "Ankou skeleton", 60));

		// Deliberately *not* "run it twice and compare". Two calls to a pure function
		// over an unchanged map agree whatever the ordering is — including when it is
		// the accident of a hash bucket — so that assertion cannot fail and would read
		// as coverage that is not there. The order is pinned instead.
		assertEquals("and the two sizes of Skeleton come out weakest first",
			Arrays.asList("Skeleton", "Skeleton", "Skeleton Hellhound", "Ankou skeleton"),
			names(index.search("skele", 10)));
		assertEquals(17, index.search("skele", 10).getMatches().get(0).getHitpoints());
	}

	@Test
	public void aTruncatedListStillKnowsHowManyThereWere()
	{
		MonsterIndex index = indexOf(
			npc(1, "Spider one", 2), npc(2, "Spider two", 3), npc(3, "Spider three", 4));

		MonsterIndex.Results found = index.search("spider", 2);

		assertEquals(2, found.getMatches().size());
		assertEquals("hiding one behind a list that looks complete is the failure",
			3, found.getTotal());
		assertTrue(found.isTruncated());
	}

	@Test
	public void aCompleteListDoesNotClaimToBeTruncated()
	{
		MonsterIndex index = indexOf(npc(1, "Spider", 2));

		assertFalse(index.search("spider", 20).isTruncated());
	}

	@Test
	public void anEmptyQueryMatchesNothingRatherThanEverything()
	{
		MonsterIndex index = indexOf(npc(SPINDEL, "Spindel", 200));

		assertEquals(0, index.search("", 10).getTotal());
		assertEquals(0, index.search("   ", 10).getTotal());
		assertEquals(0, index.search(null, 10).getTotal());
		assertTrue(index.exactMatches("").isEmpty());
		assertTrue(index.exactMatches(null).isEmpty());
	}

	@Test
	public void aLimitOfNothingReturnsNothingRatherThanEverything()
	{
		MonsterIndex index = indexOf(npc(SPINDEL, "Spindel", 200));

		assertEquals(0, index.search("spindel", 0).getMatches().size());
		assertEquals(0, index.search("spindel", -5).getMatches().size());
	}

	/**
	 * Most of the sixteen thousand ids in the archive have no definition, and the
	 * client names those "null". Indexing them would answer a player's spelling
	 * question with a monster that does not exist.
	 */
	@Test
	public void theClientsPlaceholderNameIsNotAMonster()
	{
		MonsterIndex index = indexOf(
			new FoughtNpc(FoughtNpc.NO_INDEX, 1, "null", null),
			new FoughtNpc(FoughtNpc.NO_INDEX, 2, "NULL", null),
			new FoughtNpc(FoughtNpc.NO_INDEX, 3, "  ", null),
			new FoughtNpc(FoughtNpc.NO_INDEX, 4, null, null));

		assertTrue(index.isEmpty());
		assertEquals(0, index.size());
	}

	@Test
	public void aNullReadingIsSkippedRatherThanIndexed()
	{
		MonsterIndex index = new MonsterIndex();
		index.add(null);

		assertTrue(index.isEmpty());
	}

	/**
	 * The name is sanitised on the way in, not on the way out.
	 *
	 * <p>This project has already had to fix a pin stored under one spelling and
	 * matched by another; the fix was to decide the name once, at the door. An index
	 * that stored raw names would reopen it from the other side, because a pin is
	 * written through {@link ConfigText#sanitise} and would then never match the
	 * index entry it came from.
	 */
	@Test
	public void theNameIsTheOneAPinWouldBeStoredUnder()
	{
		MonsterIndex index = indexOf(npc(1, "Sea, snake|3", 60));

		assertEquals(ConfigText.sanitise("Sea, snake|3"),
			index.search("sea", 10).getMatches().get(0).getName());
		assertNotEquals("Sea, snake|3", index.search("sea", 10).getMatches().get(0).getName());
	}

	/**
	 * The same refusal {@link PlanTarget} makes, for the same reason: a stats array
	 * the cache never filled in reads as all ones, so a hitpoints value of one is
	 * ambiguous between "nothing was filled in" and "it really is one" — and it is
	 * about to become a divisor.
	 */
	@Test
	public void hitpointsThatCannotBeTrustedAreRefusedRatherThanPublished()
	{
		MonsterIndex index = indexOf(
			new FoughtNpc(FoughtNpc.NO_INDEX, 1, "Unfilled", null),
			new FoughtNpc(FoughtNpc.NO_INDEX, 2, "One hitpoint", new int[]{70, 70, 70, 1, 1, 70}));

		MonsterIndex.Match unfilled = index.exactMatches("Unfilled").get(0);
		MonsterIndex.Match single = index.exactMatches("One hitpoint").get(0);

		assertEquals(0, unfilled.getHitpoints());
		assertFalse(unfilled.hasHitpoints());
		assertEquals(0, single.getHitpoints());
		assertFalse(single.hasHitpoints());
	}

	@Test
	public void aRealSizeIsPublished()
	{
		MonsterIndex index = indexOf(npc(SPINDEL, "Spindel", 200));

		assertTrue(index.exactMatches("Spindel").get(0).hasHitpoints());
		assertEquals(200, index.exactMatches("Spindel").get(0).getHitpoints());
	}

	@Test
	public void sealingIsWhatMakesItSafeToHandToAnotherThread()
	{
		MonsterIndex index = new MonsterIndex();
		index.add(npc(SPINDEL, "Spindel", 200));

		assertFalse(index.isSealed());
		assertTrue(index.seal().isSealed());
		assertTrue("sealing twice is not an error", index.seal().isSealed());

		try
		{
			index.add(npc(VENENATIS, "Venenatis", 850));
			fail("a published index must refuse to change under its readers");
		}
		catch (IllegalStateException expected)
		{
			assertEquals(1, index.size());
		}
	}

	@Test
	public void sizeCountsMonstersRatherThanIds()
	{
		MonsterIndex index = indexOf(
			npc(SPIDER, "Spider", 2),
			npc(SPIDER + 1, "Spider", 2),
			npc(SPIDER + 2, "Spider", 8),
			npc(SPINDEL, "Spindel", 200));

		assertEquals("two Spiders of one size are one monster, plus the other two",
			3, index.size());
	}

	@Test
	public void aNameNobodyHasIsNotAMatch()
	{
		MonsterIndex index = indexOf(npc(SPINDEL, "Spindel", 200));

		assertEquals(0, index.search("zulrah", 10).getTotal());
		assertTrue(index.exactMatches("Zulrah").isEmpty());
	}

	private static List<String> names(MonsterIndex.Results results)
	{
		return results.getMatches().stream()
			.map(MonsterIndex.Match::getName)
			.collect(java.util.stream.Collectors.toList());
	}
}
