package com.matthewmariner.gunnarstools;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * The per-kill series and the order statistics read off it.
 *
 * <p>Everything here is about a mean not being enough. The series exists so a
 * figure can be published with its spread, and the two ways it can quietly stop
 * meaning that are a sample silently dropped and a rank silently off by one.
 */
public class KillSamplesTest
{
	@Test
	public void everyKillIsOneEntryIncludingTheOnesThatSpentNothing()
	{
		KillSamples samples = new KillSamples();
		samples.add(30L);
		samples.add(0L);
		samples.add(12L);

		assertEquals("a kill that spent nothing is still a kill", 3, samples.size());
		assertEquals(42L, samples.sum());
		assertEquals("but it did not spend any of this", 2, samples.nonZero());
	}

	@Test
	public void samplesComeBackInTheOrderTheKillsHappened()
	{
		KillSamples samples = new KillSamples();
		samples.add(7L);
		samples.add(3L);
		samples.add(9L);

		assertEquals(7L, samples.at(0));
		assertEquals(3L, samples.at(1));
		assertEquals(9L, samples.at(2));
	}

	@Test
	public void theSeriesOutgrowsItsInitialCapacityWithoutLosingAnything()
	{
		// The backing array starts at eight. A slayer task is a hundred and fifty
		// kills, so the growth path is the normal path, not the edge case.
		KillSamples samples = new KillSamples();
		long expected = 0L;
		for (int kill = 1; kill <= 250; kill++)
		{
			samples.add(kill);
			expected += kill;
		}

		assertEquals(250, samples.size());
		assertEquals(expected, samples.sum());
		assertEquals("the last one added is still findable", 250L, samples.at(249));
		assertEquals(1L, samples.percentile(0));
		assertEquals(250L, samples.percentile(100));
	}

	// --- order statistics -----------------------------------------------------

	@Test
	public void thePercentileIsAValueThatActuallyHappened()
	{
		// Nearest rank, no interpolation. An interpolating median of these four
		// would be 25, which is a quantity no kill ever cost.
		KillSamples samples = new KillSamples();
		samples.add(10L);
		samples.add(20L);
		samples.add(30L);
		samples.add(40L);

		assertEquals(20L, samples.percentile(50));
	}

	@Test
	public void aRankThatIsExactlyAnIntegerIsNotRoundedUpPastIt()
	{
		// The floating-point trap, with the arithmetic that springs it. Seven
		// hundredths of a hundred is 7.000000000000001 as a double, so a rank
		// computed by Math.ceil on that product takes the eighth-smallest sample
		// instead of the seventh. Samples 1..100 make the two answers legible: 7
		// from the exact ceiling, 8 from the double.
		KillSamples samples = new KillSamples();
		for (int value = 1; value <= 100; value++)
		{
			samples.add(value);
		}

		assertEquals("rank 7 of 100, not rank 8", 7L, samples.percentile(7));
	}

	@Test
	public void theNinetiethIsTheShoulderAndNotTheWorstKill()
	{
		// Nineteen ordinary kills and one disaster. The maximum is the disaster;
		// the ninetieth is the shoulder, and it is the one worth planning against
		// because the maximum only ever climbs.
		KillSamples samples = new KillSamples();
		for (int kill = 0; kill < 19; kill++)
		{
			samples.add(25L);
		}
		samples.add(400L);

		assertEquals(25L, samples.percentile(90));
		assertEquals(400L, samples.percentile(100));
	}

	@Test
	public void percentilesOutsideTheScaleAreClampedToRealSamples()
	{
		KillSamples samples = new KillSamples();
		samples.add(5L);
		samples.add(9L);

		assertEquals("below zero is still the cheapest kill", 5L, samples.percentile(-40));
		assertEquals("above a hundred is still the dearest", 9L, samples.percentile(400));
	}

	@Test
	public void anEmptySeriesHasNoPercentileRatherThanAnException()
	{
		KillSamples samples = new KillSamples();

		assertEquals(0L, samples.percentile(0));
		assertEquals(0L, samples.percentile(50));
		assertEquals(0L, samples.percentile(100));
		assertEquals(0, samples.size());
		assertEquals(0L, samples.sum());
		assertEquals(0, samples.nonZero());
	}

	@Test
	public void theSortedViewIsRebuiltWhenASampleArrivesAfterAQuery()
	{
		// The cached sort is dropped on every add. A stale one would answer the
		// second query out of the first query's data, which is the shape of bug
		// that only shows up once the plugin is running.
		KillSamples samples = new KillSamples();
		samples.add(10L);
		samples.add(20L);
		assertEquals(20L, samples.percentile(100));

		samples.add(5L);

		assertEquals("the new cheapest kill has to be visible", 5L, samples.percentile(0));
		assertEquals(20L, samples.percentile(100));
		assertEquals(3, samples.size());
	}

	@Test
	public void aSeriesOfOneIsItsOwnEveryPercentile()
	{
		KillSamples samples = new KillSamples();
		samples.add(33L);

		assertEquals(33L, samples.percentile(0));
		assertEquals(33L, samples.percentile(50));
		assertEquals(33L, samples.percentile(90));
		assertEquals(33L, samples.percentile(100));
	}

	@Test(expected = IndexOutOfBoundsException.class)
	public void readingPastTheEndIsAnErrorRatherThanAZero()
	{
		KillSamples samples = new KillSamples();
		samples.add(1L);

		samples.at(1);
	}

	@Test(expected = IndexOutOfBoundsException.class)
	public void readingBeforeTheStartIsAnErrorToo()
	{
		KillSamples samples = new KillSamples();
		samples.add(1L);

		samples.at(-1);
	}
}
