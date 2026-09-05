package com.matthewmariner.gunnarstools;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
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

		List<MonsterIndex.Match> matches = index.resolve("spider");

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

		List<MonsterIndex.Match> matches = index.resolve("Spider");

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

		assertEquals(1, index.resolve("Spindel").get(0).getVariants());
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
		assertTrue(index.resolve("").isEmpty());
		assertTrue(index.resolve(null).isEmpty());
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

		MonsterIndex.Match unfilled = index.resolve("Unfilled").get(0);
		MonsterIndex.Match single = index.resolve("One hitpoint").get(0);

		assertEquals(0, unfilled.getHitpoints());
		assertFalse(unfilled.hasHitpoints());
		assertEquals(0, single.getHitpoints());
		assertFalse(single.hasHitpoints());
	}

	@Test
	public void aRealSizeIsPublished()
	{
		MonsterIndex index = indexOf(npc(SPINDEL, "Spindel", 200));

		assertTrue(index.resolve("Spindel").get(0).hasHitpoints());
		assertEquals(200, index.resolve("Spindel").get(0).getHitpoints());
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
		assertTrue(index.resolve("Zulrah").isEmpty());
	}

	// --- spelling it wrong -----------------------------------------------------

	/**
	 * The report this whole pass came from, as an assertion.
	 *
	 * <p>He wanted to prepare for the Dagannoth Kings, typed "Dagganoth" — one
	 * {@code g} too many and one {@code n} too few — and nothing happened. Three
	 * Kings, an ordinary Dagannoth and a spawn all answer to what he meant, so the
	 * right result is not one monster: it is all of them, with their own hitpoints
	 * beside them, for him to pick from.
	 */
	@Test
	public void theTypoThatFoundNothingFindsTheDagannothKings()
	{
		MonsterIndex index = dagannoths();

		List<String> found = names(index.search("dagganoth", 20));

		assertTrue("Rex", found.contains("Dagannoth Rex"));
		assertTrue("Prime", found.contains("Dagannoth Prime"));
		assertTrue("Supreme", found.contains("Dagannoth Supreme"));
		assertTrue("and the ordinary ones, which is exactly why picking one would be wrong",
			found.contains("Dagannoth") && found.contains("Dagannoth spawn"));
		assertEquals(5, index.search("dagganoth", 20).getTotal());
	}

	/** And their sizes come with them, which is what tells the Kings from the spawn. */
	@Test
	public void everyNearMissCarriesItsOwnSize()
	{
		List<MonsterIndex.Match> found = dagannoths().search("dagganoth", 20).getMatches();

		int checked = 0;
		for (MonsterIndex.Match match : found)
		{
			if (match.getName().equals("Dagannoth Rex"))
			{
				assertEquals(255, match.getHitpoints());
				checked++;
			}
			if (match.getName().equals("Dagannoth spawn"))
			{
				assertEquals("a factor of twenty-five apart, on screen", 10, match.getHitpoints());
				checked++;
			}
		}
		assertEquals("both rows were actually there to check", 2, checked);
		assertEquals(MonsterIndex.Tier.NEAR, found.get(0).getTier());
	}

	/**
	 * Every keystroke on the way to the word, not only the finished one.
	 *
	 * <p>What the owner asked for was autocomplete — "maybe have a fuzzy auto
	 * complete?" — and a search that only worked once the whole misspelt word had
	 * been typed would be a list that stayed empty while he typed it and filled in
	 * at the end. Four characters is where near matching starts; before that the
	 * prefix tier is doing the work.
	 */
	@Test
	public void theListFillsInAsTheWrongWordIsTyped()
	{
		MonsterIndex index = dagannoths();

		for (String typed : Arrays.asList("dagg", "dagga", "daggan", "dagganot", "dagganoth"))
		{
			assertTrue("nothing offered after typing \"" + typed + "\"",
				names(index.search(typed, 20)).contains("Dagannoth Rex"));
		}
	}

	/**
	 * A near miss is a fallback, not an addition, and this is the case that decides
	 * it: "spid" is one edit from "Spindel", so a near pass that always ran would
	 * put Spindel in the middle of a search for spiders.
	 */
	@Test
	public void aQueryThatMatchesProperlyIsNotDilutedByOneThatNearlyDoes()
	{
		MonsterIndex index = indexOf(
			npc(SPIDER, "Spider", 2),
			npc(GIANT_SPIDER, "Giant spider", 5),
			npc(SPINDEL, "Spindel", 200));

		assertEquals("Spindel is one edit away and must stay out of it",
			Arrays.asList("Spider", "Giant spider"), names(index.search("spid", 10)));
		assertEquals("on its own, though, it is found", 1, index.search("spndel", 10).getTotal());
	}

	@Test
	public void exactBeatsPrefixBeatsSubstringBeatsNearMiss()
	{
		MonsterIndex index = indexOf(
			npc(1, "Bear", 25),
			npc(2, "Bear cub", 20),
			npc(3, "Black bear", 30),
			npc(4, "Bean", 15));

		// "bear" matches three of them outright, so the fourth never gets a hearing.
		assertEquals(Arrays.asList("Bear", "Bear cub", "Black bear"),
			names(index.search("bear", 10)));
		assertEquals("and on its own, one letter out, it is a near miss",
			Arrays.asList("Bean"), names(index.search("baen", 10)));
	}

	@Test
	public void caseAndStrayWhitespaceAreNotASpellingMistake()
	{
		MonsterIndex index = indexOf(npc(2265, "Dagannoth Rex", 255));

		for (String typed : Arrays.asList("  DAGANNOTH   rex ", "dagannoth rex", "DagannothRex"))
		{
			assertEquals("\"" + typed + "\" is the same monster",
				1, index.search(typed, 10).getTotal());
		}
		assertEquals(1, index.resolve("  Dagannoth  Rex  ").size());
	}

	/**
	 * Three characters have too many neighbours to be a spelling mistake — at one
	 * edit "rat" reaches "bat", "rats" and "at" — so near matching does not start
	 * until there is enough typed to mean something.
	 */
	@Test
	public void aQueryTooShortToBeAMisspellingIsNotTreatedAsOne()
	{
		// Nothing here is called "Rat", so the near pass is the only one left to run.
		// Written the other way round — with a Rat in the index for "rat" to match
		// exactly — this could not fail whatever the floor was set to, because the
		// exact match would stop the near pass before it started. A mutation found it.
		MonsterIndex index = indexOf(npc(1, "Bat", 5), npc(2, "Cat", 4));

		assertEquals("three letters reach too many neighbours to guess from",
			0, index.search("rat", 10).getTotal());

		assertEquals("a fourth letter is enough to mean something", 1,
			indexOf(npc(1, "Bats", 5)).search("rats", 10).getTotal());
	}

	/**
	 * Two letters the wrong way round is one mistake, not two.
	 *
	 * <p>It is also the commonest one. Charged at two — which is what plain
	 * Levenshtein does — a six-letter name typed as "Sipder" falls outside the
	 * one-edit budget its length allows, while a completely different monster two
	 * substitutions away sits inside it. That is the wrong way round.
	 */
	@Test
	public void twoLettersSwappedIsOneMistake()
	{
		MonsterIndex index = indexOf(npc(SPIDER, "Spider", 2));

		assertEquals(1, MonsterIndex.prefixDistance("sipder", "spider", 1));
		assertEquals("six letters gets one edit, and a swap has to fit in it",
			1, index.search("sipder", 10).getTotal());
	}

	/**
	 * The budget is pinned by the literals it is supposed to produce rather than by
	 * the constants that produce it — a threshold asserted against itself is a
	 * threshold nothing is holding.
	 */
	@Test
	public void oneEditIsForgivenForAShortNameAndTwoForALongOne()
	{
		assertEquals(0, MonsterIndex.editBudget(0));
		assertEquals(0, MonsterIndex.editBudget(3));
		assertEquals(1, MonsterIndex.editBudget(4));
		assertEquals(1, MonsterIndex.editBudget(6));
		assertEquals("nine letters, two of them wrong: \"Dagganoth\"", 2,
			MonsterIndex.editBudget(7));
		assertEquals(2, MonsterIndex.editBudget(9));
		assertEquals("and never more, however long", 2, MonsterIndex.editBudget(40));
	}

	@Test
	public void twoEditsIsTheCeilingAndAThirdIsTooFar()
	{
		MonsterIndex index = indexOf(npc(VENENATIS, "Venenatis", 850));

		assertEquals("two wrong letters", 1, index.search("venenatsi", 10).getTotal());
		assertEquals("three is somebody else's monster", 0, index.search("xxnxnatis", 10).getTotal());
	}

	/**
	 * The distance is measured against the <em>start</em> of a name rather than the
	 * whole of it, which is what lets a one-word query reach a two-word monster.
	 */
	@Test
	public void theDistanceIsToTheStartOfTheNameRatherThanAllOfIt()
	{
		assertEquals(2, MonsterIndex.prefixDistance("dagganoth", "dagannoth rex", 2));
		assertEquals(0, MonsterIndex.prefixDistance("dagannoth", "dagannoth supreme", 2));
		assertTrue("but the query still has to reach the start of it",
			MonsterIndex.prefixDistance("rex", "dagannoth rex", 2) > 2);
	}

	/**
	 * The band is an optimisation and must not be a behaviour.
	 *
	 * <p>{@link MonsterIndex#prefixDistance} computes a fixed few cells per
	 * character instead of a whole matrix, because sixteen thousand names are
	 * scanned on the Swing thread between keystrokes. That is only sound if it
	 * agrees with the matrix everywhere it claims an answer, so it is checked
	 * against one — over every pair a fixed seed produces, which is a great many
	 * more than could be written out by hand and is the same list every run.
	 */
	@Test
	public void theBandedDistanceAgreesWithTheWholeMatrix()
	{
		final Random random = new Random(1994L);
		int within = 0;
		for (int trial = 0; trial < 20000; trial++)
		{
			final String needle = word(random, 1 + random.nextInt(12));
			final String name = word(random, random.nextInt(16));
			for (int budget = 1; budget <= MonsterIndex.MAX_EDITS; budget++)
			{
				final int banded = MonsterIndex.prefixDistance(needle, name, budget);
				final int matrix = wholeMatrixPrefixDistance(needle, name);
				if (matrix <= budget)
				{
					within++;
					assertEquals("\"" + needle + "\" against \"" + name + "\" within " + budget,
						matrix, banded);
				}
				else
				{
					assertTrue("\"" + needle + "\" against \"" + name + "\" is further than "
						+ budget, banded > budget);
				}
			}
		}
		assertTrue("a check where nothing was ever in range would prove nothing", within > 1000);
	}

	private static String word(Random random, int length)
	{
		final String alphabet = "abcde ";
		final StringBuilder out = new StringBuilder(length);
		for (int i = 0; i < length; i++)
		{
			out.append(alphabet.charAt(random.nextInt(alphabet.length())));
		}
		return out.toString();
	}

	/**
	 * The obvious algorithm: the whole matrix, minimum over the last row, with the
	 * same transposition rule spelled out separately from the one under test.
	 */
	private static int wholeMatrixPrefixDistance(String needle, String name)
	{
		final int rows = needle.length();
		final int columns = name.length();
		final int[][] matrix = new int[rows + 1][columns + 1];
		for (int column = 0; column <= columns; column++)
		{
			matrix[0][column] = column;
		}
		for (int row = 1; row <= rows; row++)
		{
			matrix[row][0] = row;
			for (int column = 1; column <= columns; column++)
			{
				matrix[row][column] = Math.min(
					matrix[row - 1][column - 1]
						+ (needle.charAt(row - 1) == name.charAt(column - 1) ? 0 : 1),
					Math.min(matrix[row - 1][column] + 1, matrix[row][column - 1] + 1));

				if (row > 1 && column > 1
					&& needle.charAt(row - 1) == name.charAt(column - 2)
					&& needle.charAt(row - 2) == name.charAt(column - 1))
				{
					matrix[row][column] = Math.min(matrix[row][column],
						matrix[row - 2][column - 2] + 1);
				}
			}
		}
		int best = Integer.MAX_VALUE;
		for (int column = 0; column <= columns; column++)
		{
			best = Math.min(best, matrix[rows][column]);
		}
		return best;
	}

	// --- what a typed name resolves to -----------------------------------------

	/**
	 * Only the best band comes back, and that is what keeps the ambiguity rule
	 * intact through the fuzzy search. An exact match wins outright over the
	 * prefixes and near misses standing behind it.
	 */
	@Test
	public void anExactMatchIsNotTurnedIntoAChoiceByTheThingsBehindIt()
	{
		MonsterIndex index = indexOf(
			npc(1, "Bear", 25),
			npc(2, "Bear cub", 20),
			npc(3, "Beer", 1));

		List<MonsterIndex.Match> resolved = index.resolve("Bear");

		assertEquals("one monster is called Bear", 1, resolved.size());
		assertEquals("Bear", resolved.get(0).getName());
	}

	/** And a misspelling that reaches several monsters stays a choice. */
	@Test
	public void aMisspellingThatReachesSeveralMonstersIsStillAChoice()
	{
		assertEquals(5, dagannoths().resolve("Dagganoth").size());
	}

	@Test
	public void aMisspellingThatReachesOneMonsterIsThatMonster()
	{
		MonsterIndex index = indexOf(npc(VENENATIS, "Venenatis", 850));

		List<MonsterIndex.Match> resolved = index.resolve("Venenatsi");

		assertEquals(1, resolved.size());
		assertEquals("Venenatis", resolved.get(0).getName());
		assertEquals(850, resolved.get(0).getHitpoints());
	}

	/** The three Kings, the ordinary Dagannoths and the spawns, as the cache holds them. */
	private static MonsterIndex dagannoths()
	{
		return indexOf(
			npc(2265, "Dagannoth Rex", 255),
			npc(2266, "Dagannoth Prime", 255),
			npc(2267, "Dagannoth Supreme", 255),
			npc(2243, "Dagannoth", 70),
			npc(2256, "Dagannoth spawn", 10));
	}

	private static List<String> names(MonsterIndex.Results results)
	{
		return results.getMatches().stream()
			.map(MonsterIndex.Match::getName)
			.collect(java.util.stream.Collectors.toList());
	}
}
