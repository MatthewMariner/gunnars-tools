package com.matthewmariner.gunnarstools;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The estimated answer, and the two arithmetics behind it.
 *
 * <p>Every figure below was worked out by hand before it was written down, and
 * the working is in the comment beside it. A projection is the one number in this
 * plugin that rests on an assumption rather than on measurement, so a test that
 * merely agreed with whatever the code produced would be defending nothing.
 */
public class ProjectedNeedTest
{
	private static final int ARROW = 892;

	// --- the same monster, remembered ------------------------------------------

	@Test
	public void aRememberedMonsterIsItsOwnRateWithNothingStretched()
	{
		// 100 arrows over 4 monsters is 25 a monster; a hundred monsters at ten per
		// cent on top is 2,750.
		ProjectedNeed need = ProjectedNeed.remembered(ARROW, 100L, 4, "Spindel", true, 100, 10);

		assertEquals(2750L, need.getBring());
		assertEquals(ProjectedNeed.Basis.LAST_SESSION, need.getBasis());
		assertEquals("Spindel", need.getSourceName());
		assertEquals(4, need.getSourceMonsters());
		assertEquals("nothing was stretched, so there is no stretch to show",
			0, need.getSourceHitpoints());
		assertEquals(0, need.getTargetHitpoints());
	}

	@Test
	public void adifferentSetupIsADifferentBasisAndSaysSo()
	{
		// The number is identical. What changes is the label, which is the whole
		// point: the figure is the best available and it is about somebody holding a
		// different bow.
		ProjectedNeed same = ProjectedNeed.remembered(ARROW, 100L, 4, "Spindel", true, 100, 10);
		ProjectedNeed other = ProjectedNeed.remembered(ARROW, 100L, 4, "Spindel", false, 100, 10);

		assertEquals(same.getBring(), other.getBring());
		assertEquals(ProjectedNeed.Basis.LAST_SESSION_OTHER_GEAR, other.getBasis());
	}

	// --- a different monster, scaled -------------------------------------------

	@Test
	public void aRateIsStretchedByTheRatioOfTheirHitpoints()
	{
		// 200 arrows over 40 skeletons of 17 hitpoints is 200 arrows per 680
		// hitpoints. Venenatis is 850, which is 250 arrows; ten of her, no margin,
		// is 2,500.
		ProjectedNeed need = ProjectedNeed.scaled(ARROW, 200L, 40, 17, 850, "Skeleton", 10, 0);

		assertEquals(2500L, need.getBring());
		assertEquals(ProjectedNeed.Basis.SCALED_BY_HITPOINTS, need.getBasis());
		assertEquals(17, need.getSourceHitpoints());
		assertEquals(850, need.getTargetHitpoints());
		assertEquals("Skeleton", need.getSourceName());
	}

	@Test
	public void stretchingDownwardsIsTheSameArithmeticBackwards()
	{
		// The opposite direction, so a sign or an inversion cannot pass. 400 arrows
		// over 4 Venenatis of 850 hitpoints is 400 per 3,400; a 17-hitpoint skeleton
		// is two arrows, and a hundred of them is 200.
		assertEquals(200L, ProjectedNeed.scaled(ARROW, 400L, 4, 850, 17, "Venenatis", 100, 0)
			.getBring());
	}

	@Test
	public void aMonsterTheSameSizeIsTheRateItself()
	{
		// The identity case. Scaling 200 hitpoints onto 200 must not move anything,
		// which is what catches a ratio applied to the wrong side.
		assertEquals(2500L, ProjectedNeed.scaled(ARROW, 100L, 4, 200, 200, "Spindel", 100, 0)
			.getBring());
	}

	@Test
	public void theMarginIsAppliedToAScaledFigureToo()
	{
		// 2,500 plus a tenth.
		assertEquals(2750L, ProjectedNeed.scaled(ARROW, 200L, 40, 17, 850, "Skeleton", 10, 10)
			.getBring());
	}

	@Test
	public void aNegativeMarginIsClampedRatherThanShrinkingTheEstimate()
	{
		ProjectedNeed need = ProjectedNeed.scaled(ARROW, 200L, 40, 17, 850, "Skeleton", 10, -50);

		assertEquals(2500L, need.getBring());
		assertEquals(0, need.getSafetyMarginPercent());
	}

	@Test
	public void hitpointsThatDidNotResolveProduceNothingRatherThanADivisionByZero()
	{
		// Both directions. An unresolved target is the case PlanTarget refuses to
		// publish a 1 for; an unresolved source is an archive entry written by a
		// session whose cache never filled the stats in.
		assertEquals(0L, ProjectedNeed.scaled(ARROW, 200L, 40, 17, 0, "Skeleton", 10, 0).getBring());
		assertEquals(0L, ProjectedNeed.scaled(ARROW, 200L, 40, 0, 850, "Skeleton", 10, 0).getBring());
	}

	@Test
	public void aQuantityLargeEnoughToOverflowSaturatesRatherThanWrapping()
	{
		// The reason the scaled path goes through BigInteger: the numerator here is
		// a gross total multiplied by hitpoints before the trip size or the margin
		// are anywhere near it.
		ProjectedNeed need = ProjectedNeed.scaled(ARROW, Long.MAX_VALUE / 10L, 1, 1, 850,
			"Something", 100000, 200);

		assertEquals(Long.MAX_VALUE, need.getBring());
	}

	// --- confidence ------------------------------------------------------------

	@Test
	public void theConfidenceBandIsOverTheEvidenceBehindTheRate()
	{
		assertEquals(Confidence.SOLID,
			ProjectedNeed.remembered(ARROW, 100L, 200, "Spindel", true, 100, 10).getConfidence());
		assertEquals(Confidence.ANECDOTAL,
			ProjectedNeed.remembered(ARROW, 100L, 2, "Spindel", true, 100, 10).getConfidence());
		assertEquals(Confidence.NONE,
			ProjectedNeed.scaled(ARROW, 100L, 0, 17, 850, "Skeleton", 100, 10).getConfidence());
	}

	// --- what it says ----------------------------------------------------------

	@Test
	public void everySentenceStartsBySayingItIsAnEstimate()
	{
		// The log is where a bug report is pasted from. A line that reads like a
		// measurement in a bug report is a wrong number nobody can trace back.
		assertTrue(ProjectedNeed.remembered(ARROW, 100L, 4, "Spindel", true, 100, 10)
			.describe("Spindel", "Rune arrow").startsWith("Estimate:"));
		assertTrue(ProjectedNeed.remembered(ARROW, 100L, 4, "Spindel", false, 100, 10)
			.describe("Spindel", "Rune arrow").startsWith("Estimate:"));
		assertTrue(ProjectedNeed.scaled(ARROW, 200L, 40, 17, 850, "Skeleton", 10, 0)
			.describe("Venenatis", "Rune arrow").startsWith("Estimate:"));
	}

	@Test
	public void aStretchedSentenceNamesBothMonstersAndBothSizes()
	{
		String sentence = ProjectedNeed.scaled(ARROW, 200L, 40, 17, 850, "Skeleton", 10, 0)
			.describe("Venenatis", "Rune arrow");

		assertTrue(sentence, sentence.contains("2,500 Rune arrow"));
		assertTrue(sentence, sentence.contains("10 Venenatis"));
		assertTrue(sentence, sentence.contains("40 Skeleton"));
		assertTrue(sentence, sentence.contains("17 hp"));
		assertTrue(sentence, sentence.contains("850 hp"));
		assertTrue("and says out loud that it never watched one die",
			sentence.contains("Not measured on Venenatis"));
	}

	@Test
	public void aRememberedSentenceOnOtherGearSaysWhichGear()
	{
		String sentence = ProjectedNeed.remembered(ARROW, 100L, 4, "Spindel", false, 100, 10)
			.describe("Spindel", "Rune arrow");

		assertTrue(sentence, sentence.contains("different gear"));
	}

	@Test
	public void aRememberedSentenceOnTheSameGearDoesNotClaimAGearChange()
	{
		String sentence = ProjectedNeed.remembered(ARROW, 100L, 4, "Spindel", true, 100, 10)
			.describe("Spindel", "Rune arrow");

		assertTrue(sentence, sentence.contains("previous session"));
		assertTrue(sentence, !sentence.contains("different gear"));
	}
}
