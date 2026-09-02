package com.matthewmariner.gunnarstools;

import net.runelite.api.NPCComposition;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The trap this whole class exists for: {@code NPCComposition.getStats()}
 * defaults to {@code {1,1,1,1,1,1}}, not to zeros.
 *
 * <p>Two checks that look like they would work do not, and both are asserted
 * here as failures rather than described in a comment — {@code stats != null}
 * passes on the default, and so does "any element is non-zero". A milestone that
 * relied on either would publish "this monster has 1 hitpoint" as a measurement.
 */
public class FoughtNpcTest
{
	private static final int[] REAL = {80, 55, 80, 255, 1, 40};

	@Test
	public void theAllOnesDefaultIsReportedAsUnpopulated()
	{
		FoughtNpc npc = new FoughtNpc(7, 1234, "Bear", new int[]{1, 1, 1, 1, 1, 1});

		assertFalse("six ones is what the cache leaves behind, not a monster's stats",
			npc.hasStats());
	}

	@Test
	public void theTwoChecksThatLookRightAreProvedWrongOnTheSameArray()
	{
		int[] unset = {1, 1, 1, 1, 1, 1};
		FoughtNpc npc = new FoughtNpc(7, 1234, "Bear", unset);

		// This is the point of the test. Both of these are true of the default,
		// so neither can be used as "the stats are populated"; hasStats() has to
		// disagree with both of them, and does.
		assertTrue("a null check would pass here", npc.getStats() != null);
		for (int stat : npc.getStats())
		{
			assertTrue("a non-zero check would pass here", stat != 0);
		}
		assertFalse(npc.hasStats());
	}

	@Test
	public void realStatsAreReportedAsPopulated()
	{
		FoughtNpc npc = new FoughtNpc(7, 5866, "Callisto", REAL);

		assertTrue(npc.hasStats());
		assertArrayEquals(REAL, npc.getStats());
		assertEquals(255, npc.getStat(NPCComposition.STAT_HITPOINTS));
		assertEquals(40, npc.getStat(NPCComposition.STAT_MAGIC));
	}

	@Test
	public void aSingleOneInsideARealArrayDoesNotMakeItUnpopulated()
	{
		// A stat of 1 inside an otherwise filled-in array is ordinary — the
		// fixture carries one at STAT_RANGED. An "any element is 1" test would
		// throw the whole array away over it.
		FoughtNpc npc = new FoughtNpc(7, 5866, "Callisto", REAL);

		assertEquals(1, npc.getStat(NPCComposition.STAT_RANGED));
		assertTrue(npc.hasStats());
	}

	@Test
	public void aNullStatsArrayBecomesTheDocumentedDefaultRatherThanAnException()
	{
		FoughtNpc npc = new FoughtNpc(7, 1234, "Bear", null);

		assertFalse(npc.hasStats());
		assertArrayEquals(new int[]{1, 1, 1, 1, 1, 1}, npc.getStats());
		assertEquals("indexing by STAT_MAGIC must not go out of bounds",
			1, npc.getStat(NPCComposition.STAT_MAGIC));
	}

	@Test
	public void aShortStatsArrayIsPaddedRatherThanTruncatingTheIndexSpace()
	{
		FoughtNpc npc = new FoughtNpc(7, 1234, "Bear", new int[]{80, 55});

		assertArrayEquals(new int[]{80, 55, 1, 1, 1, 1}, npc.getStats());
		assertTrue("the two real entries still make it populated", npc.hasStats());
		assertEquals(1, npc.getStat(NPCComposition.STAT_MAGIC));
	}

	@Test
	public void theStatsArrayIsCopiedInBothDirections()
	{
		int[] source = REAL.clone();
		FoughtNpc npc = new FoughtNpc(7, 5866, "Callisto", source);

		source[NPCComposition.STAT_HITPOINTS] = 2;
		assertEquals("editing the caller's array must not reach into the record",
			255, npc.getStat(NPCComposition.STAT_HITPOINTS));

		npc.getStats()[NPCComposition.STAT_HITPOINTS] = 2;
		assertEquals("editing what getStats() handed back must not either",
			255, npc.getStat(NPCComposition.STAT_HITPOINTS));
	}

	@Test
	public void theIdAndTheStatsComeFromTheSameComposition()
	{
		// A transforming NPC has an id and a stats array per form. Reading the id
		// from the NPC and the stats from a composition is how Calvar'ion's
		// hitpoints get filed under Vet'ion's id; of() takes both from one object.
		NPCComposition transformed = new FakeNpcComposition(6612, "Calvar'ion", REAL);

		FoughtNpc npc = FoughtNpc.of(41, transformed);

		assertEquals(6612, npc.getId());
		assertEquals("Calvar'ion", npc.getName());
		assertEquals(41, npc.getIndex());
		assertArrayEquals(REAL, npc.getStats());
	}

	@Test
	public void aNullCompositionProducesNoRecordAtAll()
	{
		assertNull("a monster whose composition did not resolve is skipped, not invented",
			FoughtNpc.of(41, null));
	}

	@Test
	public void ofCarriesTheUnpopulatedFlagThroughFromTheComposition()
	{
		FoughtNpc npc = FoughtNpc.of(41, FakeNpcComposition.withUnsetStats(1234, "Bear"));

		assertFalse(npc.hasStats());
	}
}
