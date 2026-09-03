package com.matthewmariner.gunnarstools;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * The bands, and the boundaries between them.
 *
 * <p>The bands are a reading aid rather than a claim, but the boundaries are
 * still a contract: a band that silently moved would relabel every figure a user
 * has learned to read. Each one is pinned from both sides, because an
 * off-by-one in a chain of {@code >=} passes any test that only checks the
 * middle of a band.
 */
public class ConfidenceTest
{
	@Test
	public void twoKillsAndTwoHundredDoNotLookAlike()
	{
		// The whole reason this type exists, stated as an assertion.
		assertNotEquals(Confidence.forSamples(2), Confidence.forSamples(200));
	}

	@Test
	public void nothingMeasuredIsNotAnEstimate()
	{
		assertEquals(Confidence.NONE, Confidence.forSamples(0));
	}

	@Test
	public void aNegativeSampleCountIsNoneRatherThanAnException()
	{
		// An overlay is not the place to throw over a bookkeeping slip.
		assertEquals(Confidence.NONE, Confidence.forSamples(-1));
		assertEquals(Confidence.NONE, Confidence.forSamples(Integer.MIN_VALUE));
	}

	@Test
	public void oneKillIsAnecdotalAndFourStillIs()
	{
		assertEquals(Confidence.ANECDOTAL, Confidence.forSamples(1));
		assertEquals(Confidence.ANECDOTAL, Confidence.forSamples(4));
	}

	@Test
	public void fiveKillsIsThinAndNineteenStillIs()
	{
		assertEquals(Confidence.THIN, Confidence.forSamples(5));
		assertEquals(Confidence.THIN, Confidence.forSamples(19));
	}

	@Test
	public void twentyKillsIsFairAndNinetyNineStillIs()
	{
		assertEquals(Confidence.FAIR, Confidence.forSamples(20));
		assertEquals(Confidence.FAIR, Confidence.forSamples(99));
	}

	@Test
	public void aHundredKillsIsSolid()
	{
		assertEquals(Confidence.SOLID, Confidence.forSamples(100));
		assertEquals(Confidence.SOLID, Confidence.forSamples(Integer.MAX_VALUE));
	}

	@Test
	public void everyBandHasItsOwnWord()
	{
		// The label is what the overlay prints. Two bands sharing one word would
		// undo the separation the bands exist for, and an empty one would print a
		// bare "n=37" that reads as if the plugin had no opinion.
		java.util.Set<String> labels = new java.util.HashSet<>();
		for (Confidence confidence : Confidence.values())
		{
			assertNotEquals("a band with no word is a band that says nothing",
				"", confidence.getLabel());
			labels.add(confidence.getLabel());
		}
		assertEquals(Confidence.values().length, labels.size());
	}
}
