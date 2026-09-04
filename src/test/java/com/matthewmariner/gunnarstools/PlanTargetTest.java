package com.matthewmariner.gunnarstools;

import net.runelite.api.NPCComposition;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The monster a plan is about, and how it comes to be that monster.
 *
 * <p>Two things here are load-bearing enough to be worth stating before the
 * tests. The resolution order is the fix for a plugin that could only ever
 * describe the last thing it watched die. And the refusal to publish a hitpoints
 * value of 1 is the guard between an unfilled cache entry and an estimate two
 * orders of magnitude too large — see the class javadoc and {@link FoughtNpc}'s.
 */
public class PlanTargetTest
{
	private static final int SPINDEL = 5265;
	private static final int VENENATIS = 6610;

	private static FoughtNpc spindel(int index)
	{
		return new FoughtNpc(index, SPINDEL, "Spindel", new int[]{130, 130, 130, 200, 1, 130});
	}

	// --- hitpoints -------------------------------------------------------------

	@Test
	public void itPublishesTheLiveMonstersOwnHitpoints()
	{
		PlanTarget target = PlanTarget.of(spindel(40), PlanTarget.Source.FIGHTING);

		assertEquals(200, target.getHitpoints());
		assertTrue(target.hasHitpoints());
	}

	@Test
	public void anUnpopulatedStatsArrayPublishesNoHitpointsAtAll()
	{
		// The all-ones default the cache leaves behind. Not zero, which is why
		// FoughtNpc.hasStats tests for the pattern rather than for nullity.
		PlanTarget target = PlanTarget.of(
			new FoughtNpc(40, SPINDEL, "Spindel", new int[]{1, 1, 1, 1, 1, 1}),
			PlanTarget.Source.FIGHTING);

		assertEquals(0, target.getHitpoints());
		assertFalse(target.hasHitpoints());
	}

	@Test
	public void aHitpointsReadingOfOneIsRefusedRatherThanResolved()
	{
		// The subtle half. This array is populated — an attack of 130 proves it — so
		// hasStats() is true, and yet the hitpoints entry is the one value that is
		// ambiguous between "the cache left it alone" and "it really is 1". A
		// divisor is the worst place in the plugin to guess which: dividing a trip's
		// ammunition by 1 rather than by 200 is a figure two hundred times too big.
		PlanTarget target = PlanTarget.of(
			new FoughtNpc(40, SPINDEL, "Spindel", new int[]{130, 130, 130, 1, 1, 130}),
			PlanTarget.Source.FIGHTING);

		assertFalse("a 1 is not evidence of anything", target.hasHitpoints());
		assertEquals(0, target.getHitpoints());
	}

	@Test
	public void twoHitpointsIsTheLowestItWillPublish()
	{
		// The plain Spider really does have two, and it is a real monster on a real
		// Krystilia task. The boundary is where the guard is, so it is where the
		// test is.
		assertEquals(PlanTarget.MINIMUM_USABLE_HITPOINTS,
			new PlanTarget(1, "Spider", 2, PlanTarget.Source.FIGHTING).getHitpoints());
		assertTrue(new PlanTarget(1, "Spider", 2, PlanTarget.Source.FIGHTING).hasHitpoints());
	}

	@Test
	public void aNullNpcIsNoTargetRatherThanASubstitute()
	{
		assertNull(PlanTarget.of((FoughtNpc) null, PlanTarget.Source.FIGHTING));
	}

	@Test
	public void aRecordCarriesItsHitpointsThroughToTheTarget()
	{
		AmmoLedger ledger = new AmmoLedger();
		ledger.apply(Attribution.kill(spindel(40), new AmmoTally(), 0));

		PlanTarget target = PlanTarget.of(ledger.get(SPINDEL), PlanTarget.Source.LAST_KILL);

		assertEquals(SPINDEL, target.getNpcId());
		assertEquals("Spindel", target.getName());
		assertEquals(200, target.getHitpoints());
	}

	@Test
	public void anArchiveEntryCarriesItsRememberedHitpoints()
	{
		AmmoArchive archive = AmmoArchive.parse("1;6610,Venenatis,850,12,861,892,11:400");

		PlanTarget target = PlanTarget.of(archive.get(VENENATIS), PlanTarget.Source.PINNED);

		assertEquals(VENENATIS, target.getNpcId());
		assertEquals(850, target.getHitpoints());
		assertEquals(PlanTarget.Source.PINNED, target.getSource());
	}

	// --- resolution order ------------------------------------------------------

	@Test
	public void aPinOutranksEverything()
	{
		AmmoLedger ledger = new AmmoLedger();
		ledger.apply(Attribution.kill(spindel(40), new AmmoTally(), 0));
		PlanTarget pinned = new PlanTarget(VENENATIS, "Venenatis", 850, PlanTarget.Source.PINNED);

		PlanTarget resolved = PlanTarget.resolve(pinned,
			new FoughtNpc(41, 6612, "Skeleton", new int[]{20, 20, 20, 29, 1, 1}),
			ledger.get(SPINDEL));

		assertEquals("a player who says what they are going out for has said it",
			VENENATIS, resolved.getNpcId());
	}

	@Test
	public void whatYouAreFightingOutranksWhatYouLastKilled()
	{
		AmmoLedger ledger = new AmmoLedger();
		ledger.apply(Attribution.kill(spindel(40), new AmmoTally(), 0));

		PlanTarget resolved = PlanTarget.resolve(null,
			new FoughtNpc(41, VENENATIS, "Venenatis", new int[]{130, 130, 130, 850, 1, 130}),
			ledger.get(SPINDEL));

		assertEquals(VENENATIS, resolved.getNpcId());
		assertEquals(PlanTarget.Source.FIGHTING, resolved.getSource());
	}

	@Test
	public void theLastKillIsTheFallbackAndNotTheStartingPoint()
	{
		AmmoLedger ledger = new AmmoLedger();
		ledger.apply(Attribution.kill(spindel(40), new AmmoTally(), 0));

		PlanTarget resolved = PlanTarget.resolve(null, null, ledger.get(SPINDEL));

		assertEquals(SPINDEL, resolved.getNpcId());
		assertEquals(PlanTarget.Source.LAST_KILL, resolved.getSource());
	}

	@Test
	public void nothingChosenNothingFoughtNothingKilledIsNoTarget()
	{
		// Not an error. It is the state a fresh install is in, and the panel has a
		// sentence for it.
		assertNull(PlanTarget.resolve(null, null, null));
	}

	// --- the pin, across a logout ---------------------------------------------

	@Test
	public void aPinSurvivesBeingWrittenOutAndReadBack()
	{
		PlanTarget pinned = PlanTarget.of(
			new FoughtNpc(40, VENENATIS, "Venenatis", new int[]{130, 130, 130, 850, 1, 130}),
			PlanTarget.Source.PINNED);

		PlanTarget restored = PlanTarget.parse(pinned.format());

		assertEquals(VENENATIS, restored.getNpcId());
		assertEquals("Venenatis", restored.getName());
		assertEquals("the hitpoints are the whole reason the pin stores more than an id",
			850, restored.getHitpoints());
		assertEquals(PlanTarget.Source.PINNED, restored.getSource());
	}

	@Test
	public void aPinnedMonsterWithNoResolvedHitpointsRoundTripsAsUnresolved()
	{
		PlanTarget pinned = new PlanTarget(SPINDEL, "Spindel", 0, PlanTarget.Source.PINNED);

		assertFalse(PlanTarget.parse(pinned.format()).hasHitpoints());
	}

	@Test
	public void aNameWithASeparatorInItDoesNotEatTheHitpointsField()
	{
		PlanTarget pinned = new PlanTarget(123, "Sea, snake", 40, PlanTarget.Source.PINNED);

		PlanTarget restored = PlanTarget.parse(pinned.format());

		assertEquals(40, restored.getHitpoints());
		assertEquals("Sea  snake", restored.getName());
	}

	@Test
	public void aPinIsStoredUnderTheNameItIsMatchedBy()
	{
		// Sanitising on the way out alone left the stored name and the live one as
		// two different strings for any monster whose name holds a separator, so the
		// pin quietly stopped matching after a restart — at a bank, which is the one
		// place it exists to work. The name is settled once, at construction, so
		// every comparison downstream is between two strings that went through the
		// same door.
		PlanTarget pinned = new PlanTarget(123, "Sea, snake", 40, PlanTarget.Source.PINNED);

		assertEquals("the name the plugin holds is already the safe one",
			"Sea  snake", pinned.getName());
		assertEquals("and it is unchanged by the round trip",
			pinned.getName(), PlanTarget.parse(pinned.format()).getName());
	}

	@Test
	public void anOverlongNameIsSettledBeforeItIsEverCompared()
	{
		final StringBuilder tooLong = new StringBuilder();
		for (int i = 0; i < 100; i++)
		{
			tooLong.append('x');
		}

		PlanTarget pinned = new PlanTarget(123, tooLong.toString(), 40, PlanTarget.Source.PINNED);

		assertEquals(ConfigText.MAX_NAME_LENGTH, pinned.getName().length());
		assertEquals(pinned.getName(), PlanTarget.parse(pinned.format()).getName());
	}

	@Test
	public void anythingUnreadableIsNoPinRatherThanAnException()
	{
		assertNull(PlanTarget.parse(null));
		assertNull(PlanTarget.parse(""));
		assertNull(PlanTarget.parse("5265"));
		assertNull(PlanTarget.parse("5265,Spindel"));
		assertNull("an id that will not parse is not an id", PlanTarget.parse("x,Spindel,200"));
		assertNull("nor is a negative one", PlanTarget.parse("-1,Spindel,200"));
		assertNull("and a monster with no name is not a pin", PlanTarget.parse("5265, ,200"));
	}

	@Test
	public void unreadableHitpointsLoseTheHitpointsRatherThanThePin()
	{
		// A field that does not parse loses its field; it does not lose the entry.
		// The monster is still the monster.
		PlanTarget restored = PlanTarget.parse("5265,Spindel,nonsense");

		assertEquals(SPINDEL, restored.getNpcId());
		assertFalse(restored.hasHitpoints());
	}

	@Test
	public void negativeHitpointsInTheStoreBecomeUnresolvedRatherThanNegative()
	{
		assertFalse(PlanTarget.parse("5265,Spindel,-200").hasHitpoints());
	}

	// --- identity --------------------------------------------------------------

	@Test
	public void theSameMonsterByADifferentRouteIsADifferentTarget()
	{
		// Equality is the plugin's rebuild trigger, and the source is printed on the
		// panel. "Pinned Spindel" and "the Spindel you last killed" are different
		// claims about what the number is for.
		assertNotEquals(new PlanTarget(SPINDEL, "Spindel", 200, PlanTarget.Source.PINNED),
			new PlanTarget(SPINDEL, "Spindel", 200, PlanTarget.Source.LAST_KILL));
	}

	@Test
	public void theSameMonsterByTheSameRouteIsTheSameTarget()
	{
		PlanTarget a = new PlanTarget(SPINDEL, "Spindel", 200, PlanTarget.Source.FIGHTING);
		PlanTarget b = new PlanTarget(SPINDEL, "Spindel", 200, PlanTarget.Source.FIGHTING);

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
	}

	@Test
	public void hitpointsAppearingLaterMakeItADifferentTarget()
	{
		// A composition that resolved a tick after the first reading changes what
		// can be said, so it has to redraw.
		assertNotEquals(new PlanTarget(SPINDEL, "Spindel", 0, PlanTarget.Source.FIGHTING),
			new PlanTarget(SPINDEL, "Spindel", 200, PlanTarget.Source.FIGHTING));
	}

	@Test
	public void sameMonsterIgnoresHowEachWasChosen()
	{
		// The other comparison, and deliberately not the same one. This asks "is
		// this the same animal", which is a question about ids alone.
		PlanTarget pinned = new PlanTarget(SPINDEL, "Spindel", 200, PlanTarget.Source.PINNED);

		assertTrue(pinned.isSameMonsterAs(
			new PlanTarget(SPINDEL, "Spindel", 0, PlanTarget.Source.LAST_KILL)));
		assertFalse(pinned.isSameMonsterAs(
			new PlanTarget(VENENATIS, "Venenatis", 850, PlanTarget.Source.LAST_KILL)));
		assertFalse(pinned.isSameMonsterAs(null));
	}

	@Test
	public void itIsNotEqualToThingsThatAreNotTargets()
	{
		PlanTarget pinned = new PlanTarget(SPINDEL, "Spindel", 200, PlanTarget.Source.PINNED);

		assertFalse(pinned.equals(null));
		assertFalse(pinned.equals("Spindel"));
		assertTrue(pinned.equals(pinned));
	}

	@Test
	public void everySourceHasAWordForThePanel()
	{
		// The panel prints it, so an empty one is a blank line at the top of the
		// answer.
		for (PlanTarget.Source source : PlanTarget.Source.values())
		{
			assertFalse(source.name(), source.getLabel().isEmpty());
		}
	}

	@Test
	public void theHitpointsStatIndexIsTheOneTheGameMeans()
	{
		// Pinned because everything downstream indexes with it, and reading
		// STAT_RANGED by mistake would produce a plausible-looking wrong number
		// rather than an exception.
		assertEquals(200, spindel(40).getStat(NPCComposition.STAT_HITPOINTS));
	}
}
