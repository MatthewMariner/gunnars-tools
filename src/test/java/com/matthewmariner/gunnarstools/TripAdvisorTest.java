package com.matthewmariner.gunnarstools;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The decision that turned this plugin from a description of the last corpse
 * into something that can be read at a bank.
 *
 * <p>Everything here runs with no client. What is being held to account is the
 * order of preference — measurement, then this monster remembered, then another
 * monster stretched — and, just as importantly, the four reasons it gives when it
 * has nothing to say. Those four used to be one blank corner of the screen, which
 * is why "an empty panel and a broken plugin look identical" was a bug report
 * rather than an observation.
 */
public class TripAdvisorTest
{
	private static final int SPINDEL = 5265;
	private static final int SKELETON = 6612;
	private static final int VENENATIS = 6610;
	private static final int ARROW = 892;

	private static final Loadout SHORTBOW = new Loadout(861, ARROW);
	private static final Loadout CROSSBOW = new Loadout(21902, 9144);

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

	/** Kills {@code times} of a monster at {@code perKill} arrows, on {@code loadout}. */
	private void measure(int npcId, String name, int hitpoints, Loadout loadout, int times,
		long perKill)
	{
		ledger.equipped(loadout);
		for (int kill = 0; kill < times; kill++)
		{
			ledger.apply(Attribution.kill(npc(40 + kill, npcId, name, hitpoints),
				spent(perKill), 0));
		}
	}

	private void remember(int npcId, String name, int hitpoints, Loadout loadout, int times,
		long perKill)
	{
		AmmoLedger scratch = new AmmoLedger();
		scratch.equipped(loadout);
		for (int kill = 0; kill < times; kill++)
		{
			scratch.apply(Attribution.kill(npc(40 + kill, npcId, name, hitpoints),
				spent(perKill), 0));
		}
		archive.remember(scratch.get(npcId), loadout);
	}

	private TripAdvice advise(PlanTarget target, Loadout equipped)
	{
		return TripAdvisor.advise(target, ledger, archive, equipped, 100, 0, true);
	}

	private static PlanTarget target(int npcId, String name, int hitpoints)
	{
		return new PlanTarget(npcId, name, hitpoints, PlanTarget.Source.FIGHTING);
	}

	// --- a measurement wins ----------------------------------------------------

	@Test
	public void aMonsterMeasuredThisSessionIsAMeasurement()
	{
		measure(SPINDEL, "Spindel", 200, SHORTBOW, 4, 25L);

		TripAdvice advice = advise(target(SPINDEL, "Spindel", 200), SHORTBOW);

		assertTrue(advice.isMeasured());
		assertFalse(advice.isEstimated());
		assertEquals(2500L, advice.getMeasured().get(0).getBring());
		assertEquals(Long.valueOf(2500L), advice.getWithdrawals().get(ARROW));
		assertEquals(TripAdvice.Waiting.NOTHING, advice.getWaitingFor());
	}

	@Test
	public void aMeasurementOnADifferentWeaponIsNotThisWeaponsMeasurement()
	{
		// The whole reason Loadout exists. Twenty-five arrows a Spindel is a fact
		// about a shortbow; the same player on a crossbow has measured nothing.
		measure(SPINDEL, "Spindel", 200, SHORTBOW, 4, 25L);

		TripAdvice advice = advise(target(SPINDEL, "Spindel", 200), CROSSBOW);

		assertFalse("no measurement exists for the weapon in the player's hands",
			advice.isMeasured());
	}

	@Test
	public void aThinMeasurementLosesToABetterEvidencedMemory()
	{
		// The unobvious clause. On the first kill of a session, dropping a
		// two-hundred-monster remembered figure for a one-kill measurement is a real
		// loss of information dressed up as an upgrade.
		remember(SPINDEL, "Spindel", 200, SHORTBOW, 200, 25L);
		measure(SPINDEL, "Spindel", 200, SHORTBOW, 1, 40L);

		TripAdvice advice = advise(target(SPINDEL, "Spindel", 200), SHORTBOW);

		assertTrue(advice.isEstimated());
		assertEquals(2500L, advice.getProjected().get(0).getBring());
		assertEquals(200, advice.getProjected().get(0).getSourceMonsters());
	}

	@Test
	public void theMeasurementTakesOverTheMomentItMatchesTheMemory()
	{
		// The switchover, at the boundary, in both directions — because "at least as
		// much" and "more than" differ by exactly one sample and one of them leaves
		// the estimate up forever on a monster measured exactly as often as before.
		remember(SPINDEL, "Spindel", 200, SHORTBOW, 4, 25L);
		measure(SPINDEL, "Spindel", 200, SHORTBOW, 3, 40L);
		assertTrue("three is fewer than four", advise(target(SPINDEL, "Spindel", 200), SHORTBOW)
			.isEstimated());

		measure(SPINDEL, "Spindel", 200, SHORTBOW, 1, 40L);
		TripAdvice advice = advise(target(SPINDEL, "Spindel", 200), SHORTBOW);

		assertTrue("four is not fewer than four", advice.isMeasured());
		assertEquals(4000L, advice.getMeasured().get(0).getBring());
	}

	@Test
	public void withEstimatesOffAMeasurementWinsHoweverThinItIs()
	{
		// There is nothing else on offer, and a thin measurement is still a
		// measurement.
		remember(SPINDEL, "Spindel", 200, SHORTBOW, 200, 25L);
		measure(SPINDEL, "Spindel", 200, SHORTBOW, 1, 40L);

		TripAdvice advice = TripAdvisor.advise(target(SPINDEL, "Spindel", 200), ledger, archive,
			SHORTBOW, 100, 0, false);

		assertTrue(advice.isMeasured());
		assertEquals(4000L, advice.getMeasured().get(0).getBring());
	}

	// --- the same monster, remembered ------------------------------------------

	@Test
	public void aMonsterOnlyAPreviousSessionMeasuredIsAnEstimate()
	{
		// The answer at a bank, before anything has been killed this session. This
		// is the case the whole archive exists for.
		remember(SPINDEL, "Spindel", 200, SHORTBOW, 40, 25L);

		TripAdvice advice = advise(target(SPINDEL, "Spindel", 200), SHORTBOW);

		assertTrue(advice.isEstimated());
		assertFalse("and never as a measurement", advice.isMeasured());
		assertEquals(ProjectedNeed.Basis.LAST_SESSION, advice.getProjected().get(0).getBasis());
		assertEquals(2500L, advice.getProjected().get(0).getBring());
		assertEquals(Long.valueOf(2500L), advice.getWithdrawals().get(ARROW));
	}

	@Test
	public void aRememberedMonsterOnDifferentGearIsOfferedAndDisclosed()
	{
		remember(SPINDEL, "Spindel", 200, CROSSBOW, 40, 25L);

		TripAdvice advice = advise(target(SPINDEL, "Spindel", 200), SHORTBOW);

		assertTrue(advice.isEstimated());
		assertEquals(ProjectedNeed.Basis.LAST_SESSION_OTHER_GEAR,
			advice.getProjected().get(0).getBasis());
	}

	@Test
	public void aGearCaveatIsNotAppliedBeforeTheEquipmentHasBeenRead()
	{
		// The state the plugin is in between startUp() and the first game tick.
		// Comparing a known remembered setup against an unread worn one with plain
		// equality announces a gear change that may not have happened — a review
		// caught it as a wrong "other gear" label on the very first panel drawn
		// after enabling the plugin, which is exactly the panel a player judges it
		// by. Saying nothing about the gear is the honest answer when nothing is
		// known about it.
		remember(SPINDEL, "Spindel", 200, SHORTBOW, 40, 25L);

		TripAdvice advice = advise(target(SPINDEL, "Spindel", 200), Loadout.UNKNOWN);

		assertEquals(ProjectedNeed.Basis.LAST_SESSION, advice.getProjected().get(0).getBasis());
	}

	@Test
	public void aRememberedEntryThatNeverKnewItsOwnGearMakesNoClaimEither()
	{
		// The other side: an archive written by a session that never read its own
		// equipment container. Unknown on either side is neither.
		AmmoArchive unknownGear = AmmoArchive.parse(
			"1;5265,Spindel,200,40,-1,-1," + ARROW + ":1000");

		TripAdvice advice = TripAdvisor.advise(target(SPINDEL, "Spindel", 200), ledger,
			unknownGear, SHORTBOW, 100, 0, true);

		assertEquals(ProjectedNeed.Basis.LAST_SESSION, advice.getProjected().get(0).getBasis());
	}

	@Test
	public void aBetterEvidencedMemoryOnOtherGearDoesNotSuppressThisWeaponsMeasurement()
	{
		// Comparing counts across a gear change compares two different questions.
		// Four hundred remembered crossbow monsters outnumber four fresh shortbow
		// kills on raw count, and the comparison was on raw count — so the panel
		// showed a crossbow estimate and hid a real measurement of the bow in the
		// player's hands, which is the averaging failure Loadout exists to prevent
		// reached from the other side.
		remember(SPINDEL, "Spindel", 200, CROSSBOW, 400, 40L);
		measure(SPINDEL, "Spindel", 200, SHORTBOW, 4, 25L);

		TripAdvice advice = advise(target(SPINDEL, "Spindel", 200), SHORTBOW);

		assertTrue("a figure about a different weapon does not outrank one about this weapon",
			advice.isMeasured());
		assertEquals(2500L, advice.getMeasured().get(0).getBring());
	}

	@Test
	public void aBetterEvidencedMemoryOnTheSameGearStillWins()
	{
		// And the clause it must not break. Same weapon, so the counts are comparable
		// and the better evidenced one is the better answer.
		remember(SPINDEL, "Spindel", 200, SHORTBOW, 400, 25L);
		measure(SPINDEL, "Spindel", 200, SHORTBOW, 4, 40L);

		assertTrue(advise(target(SPINDEL, "Spindel", 200), SHORTBOW).isEstimated());
	}

	@Test
	public void aRememberedMonsterNeedsNoHitpointsBecauseNothingIsStretched()
	{
		// An archive written by a session whose composition cache never resolved
		// still answers for its own monster. Only the stretch onto a different one
		// needs the hitpoints.
		remember(SPINDEL, "Spindel", 0, SHORTBOW, 40, 25L);

		TripAdvice advice = advise(target(SPINDEL, "Spindel", 0), SHORTBOW);

		assertTrue(advice.isEstimated());
		assertEquals(2500L, advice.getProjected().get(0).getBring());
	}

	// --- a different monster, stretched ----------------------------------------

	@Test
	public void anUnfoughtMonsterIsEstimatedFromOneYouHaveFought()
	{
		// The cold start. Nothing has ever been killed of this monster, by this
		// session or any other, and there is still an answer — from the player's own
		// measurements, scaled by the size the monster states about itself.
		measure(SKELETON, "Skeleton", 17, SHORTBOW, 40, 5L);

		TripAdvice advice = advise(target(VENENATIS, "Venenatis", 850), SHORTBOW);

		assertTrue(advice.isEstimated());
		ProjectedNeed need = advice.getProjected().get(0);
		assertEquals(ProjectedNeed.Basis.SCALED_BY_HITPOINTS, need.getBasis());
		assertEquals("Skeleton", need.getSourceName());
		assertEquals(17, need.getSourceHitpoints());
		assertEquals(850, need.getTargetHitpoints());

		// 200 arrows bought 40 × 17 = 680 hitpoints. Venenatis is 850, so 250
		// arrows each, and a hundred of her is 25,000.
		assertEquals(25000L, need.getBring());
	}

	@Test
	public void theArchiveCanBeTheThingStretchedTooWhenTheSessionHasNothing()
	{
		remember(SKELETON, "Skeleton", 17, SHORTBOW, 40, 5L);

		TripAdvice advice = advise(target(VENENATIS, "Venenatis", 850), SHORTBOW);

		assertTrue(advice.isEstimated());
		assertEquals("Skeleton", advice.getProjected().get(0).getSourceName());
		assertEquals(25000L, advice.getProjected().get(0).getBring());
	}

	@Test
	public void theClosestMonsterIsStretchedFromRatherThanTheBestEvidenced()
	{
		// Four hundred skeletons is far more evidence than nine Spindels, and it is
		// a fiftyfold extrapolation onto Venenatis where the Spindel is a bit over
		// fourfold. The error of a hitpoints-linear projection grows with how far it
		// is stretched, so distance wins.
		measure(SKELETON, "Skeleton", 17, SHORTBOW, 40, 5L);
		measure(SPINDEL, "Spindel", 200, SHORTBOW, 9, 50L);

		TripAdvice advice = advise(target(VENENATIS, "Venenatis", 850), SHORTBOW);

		assertEquals("Spindel", advice.getProjected().get(0).getSourceName());
	}

	@Test
	public void evidenceBreaksATieBetweenTwoEquallyDistantMonsters()
	{
		// 100 and 400 are both a factor of two from 200, on opposite sides. Nothing
		// about the stretch separates them, so the better evidenced wins.
		measure(1001, "Small", 100, SHORTBOW, 3, 10L);
		measure(1002, "Large", 400, SHORTBOW, 30, 40L);

		TripAdvice advice = advise(target(SPINDEL, "Spindel", 200), SHORTBOW);

		assertEquals("Large", advice.getProjected().get(0).getSourceName());
	}

	@Test
	public void aRateMeasuredOnADifferentWeaponIsNeverStretched()
	{
		// A projection already assumes damage per shot is constant across monsters.
		// Doing it across weapons as well is two assumptions stacked with no way to
		// tell which one was wrong.
		measure(SKELETON, "Skeleton", 17, CROSSBOW, 400, 5L);

		TripAdvice advice = advise(target(VENENATIS, "Venenatis", 850), SHORTBOW);

		assertFalse(advice.isEstimated());
		assertEquals(TripAdvice.Waiting.EVIDENCE, advice.getWaitingFor());
	}

	@Test
	public void aSessionRecordShadowsTheSameMonstersArchiveEntry()
	{
		// A rate measured an hour ago on the gear currently worn is a better basis
		// than the same monster's entry from last week, and counting both would let
		// one monster contribute twice to the choice.
		remember(SKELETON, "Skeleton", 17, SHORTBOW, 400, 50L);
		measure(SKELETON, "Skeleton", 17, SHORTBOW, 4, 5L);

		TripAdvice advice = advise(target(VENENATIS, "Venenatis", 850), SHORTBOW);

		assertEquals("Skeleton", advice.getProjected().get(0).getSourceName());
		assertEquals("this session's four, not last week's four hundred",
			4, advice.getProjected().get(0).getSourceMonsters());
	}

	@Test
	public void aMonsterWithUnresolvedHitpointsIsNeverStretchedFrom()
	{
		// A record whose composition cache never filled in has no size to divide by.
		ledger.equipped(SHORTBOW);
		ledger.apply(Attribution.kill(
			new FoughtNpc(40, SKELETON, "Skeleton", new int[]{1, 1, 1, 1, 1, 1}), spent(5L), 0));

		TripAdvice advice = advise(target(VENENATIS, "Venenatis", 850), SHORTBOW);

		assertEquals(TripAdvice.Waiting.EVIDENCE, advice.getWaitingFor());
	}

	@Test
	public void aMonsterWhoseHitpointsReadAsOneIsNeverStretchedFrom()
	{
		// The case the test above does not reach, and a mutation pass proved it: this
		// array is populated, so hasStats() is true and the record survives every
		// other filter. The hitpoints entry alone is the cache's untouched 1, and
		// dividing a gross total by one hitpoint would produce an estimate two
		// hundred times too large — silently, and labelled with the confidence of
		// however many kills were behind it.
		ledger.equipped(SHORTBOW);
		ledger.apply(Attribution.kill(
			new FoughtNpc(40, SKELETON, "Skeleton", new int[]{20, 20, 20, 1, 1, 20}),
			spent(5L), 0));

		TripAdvice advice = advise(target(VENENATIS, "Venenatis", 850), SHORTBOW);

		assertFalse("a 1 is not a size", advice.isEstimated());
		assertEquals(TripAdvice.Waiting.EVIDENCE, advice.getWaitingFor());
	}

	@Test
	public void anArchivedMonsterWithNoSizeIsNeverStretchedFromEither()
	{
		// The same guard on the other list of candidates, which a mutation pass
		// caught untested while its twin in the ledger was covered twice over. An
		// archive is a config string: it can hold a monster whose stats never
		// resolved, and it can be hand-edited to hold anything at all.
		AmmoArchive unresolved = AmmoArchive.parse(
			"1;6612,Skeleton,0,40,861," + ARROW + "," + ARROW + ":200"
				+ ";6613,Skeleton,1,40,861," + ARROW + "," + ARROW + ":200");

		TripAdvice advice = TripAdvisor.advise(target(VENENATIS, "Venenatis", 850), ledger,
			unresolved, SHORTBOW, 100, 0, true);

		assertFalse("neither a zero nor a one is a size", advice.isEstimated());
		assertEquals(TripAdvice.Waiting.EVIDENCE, advice.getWaitingFor());
	}

	@Test
	public void anEstimateWorthNothingIsNotALine()
	{
		// A trip of no monsters, which is what the config's own range exists to stop
		// and what an overlay must survive anyway. The measured path already refuses
		// it — TripPlanner drops a zero line because a bank highlight promising
		// "withdraw 0" is worse than no highlight — and the estimated path has to
		// refuse it on the same grounds rather than publishing a plan for nothing.
		remember(SPINDEL, "Spindel", 200, SHORTBOW, 40, 25L);

		TripAdvice advice = TripAdvisor.advise(target(SPINDEL, "Spindel", 200), ledger, archive,
			SHORTBOW, 0, 0, true);

		assertFalse(advice.isEstimated());
		assertTrue(advice.getWithdrawals().isEmpty());
		assertEquals(TripAdvice.Waiting.EVIDENCE, advice.getWaitingFor());
	}

	// --- the four reasons for saying nothing -----------------------------------

	@Test
	public void noTargetAtAllSaysSo()
	{
		TripAdvice advice = advise(null, SHORTBOW);

		assertNull(advice.getTarget());
		assertEquals(TripAdvice.Waiting.A_TARGET, advice.getWaitingFor());
		assertTrue(advice.getWithdrawals().isEmpty());
	}

	@Test
	public void aTargetWithNothingToScaleFromAsksForAKill()
	{
		TripAdvice advice = advise(target(SPINDEL, "Spindel", 200), SHORTBOW);

		assertEquals(TripAdvice.Waiting.EVIDENCE, advice.getWaitingFor());
		assertEquals("and still names the monster it is waiting on",
			SPINDEL, advice.getTarget().getNpcId());
	}

	@Test
	public void aTargetWhoseHitpointsDidNotResolveSaysThatInstead()
	{
		// The distinction that makes the waiting state worth having. There is plenty
		// to scale from; what is missing is the monster's own size, and "kill one" is
		// still the fix but the reason is different and the panel says which.
		measure(SKELETON, "Skeleton", 17, SHORTBOW, 40, 5L);

		TripAdvice advice = advise(target(VENENATIS, "Venenatis", 0), SHORTBOW);

		assertEquals(TripAdvice.Waiting.HITPOINTS, advice.getWaitingFor());
	}

	@Test
	public void estimatesSwitchedOffSayThatRatherThanNothingMeasured()
	{
		// The fix is a setting rather than a kill, so the sentence is a different
		// sentence.
		remember(SPINDEL, "Spindel", 200, SHORTBOW, 40, 25L);

		TripAdvice advice = TripAdvisor.advise(target(SPINDEL, "Spindel", 200), ledger, archive,
			SHORTBOW, 100, 0, false);

		assertEquals(TripAdvice.Waiting.ESTIMATES_OFF, advice.getWaitingFor());
		assertTrue(advice.getWithdrawals().isEmpty());
	}

	@Test
	public void everyWaitingReasonHasBothOfItsLines()
	{
		// The panel draws both. An empty one is a blank row that reads as a
		// rendering fault.
		for (TripAdvice.Waiting waiting : TripAdvice.Waiting.values())
		{
			if (waiting == TripAdvice.Waiting.NOTHING)
			{
				continue;
			}
			assertFalse(waiting.name(), waiting.getHeadline().isEmpty());
			assertFalse(waiting.name(), waiting.getDetail().isEmpty());
		}
	}

	@Test
	public void havingAnAnswerIsNotAWaitingState()
	{
		assertTrue(TripAdvice.Waiting.NOTHING.getHeadline().isEmpty());
		assertTrue(TripAdvice.Waiting.NOTHING.getDetail().isEmpty());
	}

	// --- resolving a typed name ------------------------------------------------

	@Test
	public void aTypedNameMatchesThePinTheMenuWrote()
	{
		PlanTarget pin = new PlanTarget(VENENATIS, "Venenatis", 850, PlanTarget.Source.PINNED);

		PlanTarget resolved = TripAdvisor.resolvePin("venenatis", pin, null, ledger, archive, null);

		assertEquals(VENENATIS, resolved.getNpcId());
		assertEquals("the pin is what carries hitpoints for a monster never killed",
			850, resolved.getHitpoints());
	}

	@Test
	public void aTypedNameMatchesTheMonsterBeingFought()
	{
		// Without this, a player who typed a name the plugin had never seen would go
		// and attack one and still be told it does not exist.
		PlanTarget resolved = TripAdvisor.resolvePin("Venenatis", null,
			npc(40, VENENATIS, "Venenatis", 850), ledger, archive, null);

		assertEquals(VENENATIS, resolved.getNpcId());
		assertEquals(850, resolved.getHitpoints());
		assertEquals(PlanTarget.Source.PINNED, resolved.getSource());
	}

	@Test
	public void aTypedNameMatchesWhatThisSessionMeasured()
	{
		measure(SPINDEL, "Spindel", 200, SHORTBOW, 4, 25L);

		PlanTarget resolved = TripAdvisor.resolvePin("SPINDEL", null, null, ledger, archive, null);

		assertEquals(SPINDEL, resolved.getNpcId());
		assertEquals(200, resolved.getHitpoints());
	}

	@Test
	public void aTypedNameMatchesTheArchiveWhenNothingElseHasIt()
	{
		remember(VENENATIS, "Venenatis", 850, SHORTBOW, 12, 100L);

		PlanTarget resolved = TripAdvisor.resolvePin("venenatis", null, null, ledger, archive, null);

		assertEquals(VENENATIS, resolved.getNpcId());
		assertEquals(850, resolved.getHitpoints());
	}

	@Test
	public void aTypedNameThatMatchesNothingResolvesToNothing()
	{
		// Reported rather than fallen back from. Quietly planning for a different
		// monster under the name the player chose is wrong and looks right.
		assertNull(TripAdvisor.resolvePin("Zulrah", null, null, ledger, archive, null));
	}

	@Test
	public void aStalePinIsIgnoredWhenTheTypedNameNoLongerMatchesIt()
	{
		// Blanking or retyping the visible field is how a pin is cleared, so the
		// hidden companion must never win on its own.
		PlanTarget pin = new PlanTarget(VENENATIS, "Venenatis", 850, PlanTarget.Source.PINNED);

		assertNull(TripAdvisor.resolvePin("Callisto", pin, null, ledger, archive, null));
	}

	@Test
	public void aTypedNameStillResolvesAfterAWeaponSwap()
	{
		// This test used to assert the opposite, and a review was right that the
		// opposite was a conflation. Naming a monster and having measured it with
		// the weapon in your hand are two questions: the first is about identity,
		// and a bow does not unname a spider. Resolving it is also what makes the
		// panel able to say "estimate, from a different setup" — a target it refused
		// to resolve is a target it can say nothing at all about.
		measure(SPINDEL, "Spindel", 200, CROSSBOW, 4, 25L);
		ledger.equipped(SHORTBOW);

		PlanTarget resolved = TripAdvisor.resolvePin("Spindel", null, null, ledger, archive, null);

		assertEquals(SPINDEL, resolved.getNpcId());
		assertEquals(200, resolved.getHitpoints());

		// And the measurement is still refused, which is the half that matters.
		assertFalse("the crossbow's four kills are not the shortbow's measurement",
			advise(resolved, SHORTBOW).isMeasured());
	}

	// --- the game's own monster list ------------------------------------------

	/**
	 * The gap this closed. A typed name used to resolve only against something
	 * already pinned, fought, measured or remembered — so the field could name a
	 * monster you had killed and nothing else, which is the wrong half of the
	 * problem. The monster you have never killed is the one whose cost you cannot
	 * guess.
	 */
	@Test
	public void aNameOnlyTheGameKnowsResolvesThroughTheMonsterList()
	{
		MonsterIndex index = indexOf(npc(0, VENENATIS, "Venenatis", 850));

		PlanTarget resolved = TripAdvisor.resolvePin("venenatis", null, null, ledger, archive, index);

		assertEquals(VENENATIS, resolved.getNpcId());
		assertEquals(850, resolved.getHitpoints());
		assertEquals(PlanTarget.Source.PINNED, resolved.getSource());
	}

	@Test
	public void theMonsterListIsTheLastPlaceLookedRatherThanTheFirst()
	{
		// Everything above it carries either evidence or a live reading. A record of
		// four kills is a better answer than a name out of a list, and it has to win
		// even when both know the monster.
		measure(SPINDEL, "Spindel", 200, SHORTBOW, 4, 25L);
		MonsterIndex index = indexOf(npc(0, SPINDEL + 1, "Spindel", 999));

		PlanTarget resolved = TripAdvisor.resolvePin("Spindel", null, null, ledger, archive, index);

		assertEquals(SPINDEL, resolved.getNpcId());
		assertEquals(200, resolved.getHitpoints());
	}

	/**
	 * The refusal that matters. "Spider" is a two-hitpoint Spider and it is
	 * Venenatis at 850; picking one would be wrong by a factor of 425 while looking
	 * entirely confident.
	 */
	@Test
	public void anUmbrellaNameResolvesToNothingRatherThanToOneOfThem()
	{
		MonsterIndex index = indexOf(
			npc(0, 3019, "Spider", 2),
			npc(0, VENENATIS, "Spider", 850));

		assertNull(TripAdvisor.resolvePin("Spider", null, null, ledger, archive, index));
		assertEquals(TripAdvice.Waiting.AMBIGUOUS_MONSTER,
			TripAdvisor.whyPinFailed("Spider", index, true));
	}

	/**
	 * The report that produced the fuzzy search, at the layer that has to answer it.
	 *
	 * <p>He typed "Dagganoth" — one {@code g} too many and one {@code n} too few —
	 * and the field resolved nothing. It still does not resolve, because the name is
	 * six monsters rather than one, but the reason it gives is now the reason a
	 * player can act on: there is a choice, and the panel is where it is offered.
	 */
	@Test
	public void aMisspeltNameIsAChoiceRatherThanAMonsterThatDoesNotExist()
	{
		MonsterIndex index = indexOf(
			npc(0, 2265, "Dagannoth Rex", 255),
			npc(0, 2266, "Dagannoth Prime", 255),
			npc(0, 2267, "Dagannoth Supreme", 255),
			npc(0, 2243, "Dagannoth", 70),
			npc(0, 2256, "Dagannoth spawn", 10));

		assertNull("five monsters answer to it; picking one would be the 425-fold error",
			TripAdvisor.resolvePin("Dagganoth", null, null, ledger, archive, index));
		assertEquals(TripAdvice.Waiting.AMBIGUOUS_MONSTER,
			TripAdvisor.whyPinFailed("Dagganoth", index, true));
	}

	/**
	 * And a single near miss is resolved rather than reported, because there is
	 * nothing to choose between.
	 */
	@Test
	public void aTypoWithOneAnswerResolvesToIt()
	{
		MonsterIndex index = indexOf(npc(0, VENENATIS, "Venenatis", 850));

		PlanTarget resolved = TripAdvisor.resolvePin("Venenatsi", null, null, ledger, archive,
			index);

		assertEquals(VENENATIS, resolved.getNpcId());
		assertEquals("and it is the monster's own size, not the typo's", 850,
			resolved.getHitpoints());
	}

	@Test
	public void aNameNothingIsEvenCloseToIsStillAMonsterThatDoesNotExist()
	{
		MonsterIndex index = indexOf(npc(0, VENENATIS, "Venenatis", 850));

		assertNull(TripAdvisor.resolvePin("Zulrah", null, null, ledger, archive, index));
		assertEquals(TripAdvice.Waiting.UNKNOWN_MONSTER,
			TripAdvisor.whyPinFailed("Zulrah", index, true));
	}

	/**
	 * A list that has not been read is not a fact about the monster.
	 *
	 * <p>The sweep takes a few seconds after login, and a name typed inside that
	 * window used to be reported as a monster that does not exist — certain, wrong,
	 * and pointing at the spelling. Switch the lookup off and no list is coming at
	 * all, which is the previous behaviour the setting promises, so the old answer
	 * is the honest one again.
	 */
	@Test
	public void aMonsterListThatHasNotArrivedSaysSoRatherThanBlamingTheName()
	{
		assertEquals(TripAdvice.Waiting.MONSTER_LIST,
			TripAdvisor.whyPinFailed("Spider", null, true));
		assertEquals(TripAdvice.Waiting.UNKNOWN_MONSTER,
			TripAdvisor.whyPinFailed("Spider", null, false));
	}

	@Test
	public void severalIdsSharingANameAndASizeAreOneMonsterRatherThanAChoice()
	{
		MonsterIndex index = indexOf(
			npc(0, 700, "Bandit", 60),
			npc(0, 701, "Bandit", 60));

		PlanTarget resolved = TripAdvisor.resolvePin("Bandit", null, null, ledger, archive, index);

		assertEquals("nothing to choose between: same name, same size, same answer",
			700, resolved.getNpcId());
		assertEquals(60, resolved.getHitpoints());
	}

	// --- which of several ids a plan is filed under ----------------------------

	@Test
	public void withNoEvidenceAnywhereTheLowestIdWins()
	{
		assertEquals(700, TripAdvisor.preferMeasured(Arrays.asList(702, 700, 701),
			ledger, archive, SHORTBOW));
	}

	@Test
	public void anIdMeasuredOnTheWornSetupWinsOutright()
	{
		measure(702, "Bandit", 60, SHORTBOW, 3, 20L);

		assertEquals(702, TripAdvisor.preferMeasured(Arrays.asList(700, 701, 702),
			ledger, archive, SHORTBOW));
	}

	/**
	 * The case that separates the first rule from the second, and the one a
	 * mutation pass caught missing: with the first rule deleted, every other test
	 * here still passed because the id with the worn setup's record also happened
	 * to be the id with <em>a</em> record. Two ids, both measured, only one of them
	 * on the weapon in the player's hands — and the wrong one is the lower.
	 */
	@Test
	public void theWornSetupsRecordBeatsAnotherSetupsEvenAtALowerId()
	{
		measure(700, "Bandit", 60, CROSSBOW, 9, 20L);
		measure(702, "Bandit", 60, SHORTBOW, 1, 20L);

		assertEquals(702, TripAdvisor.preferMeasured(Arrays.asList(700, 701, 702),
			ledger, archive, SHORTBOW));
	}

	@Test
	public void anIdMeasuredOnAnotherSetupStillBeatsOneMeasuredNowhere()
	{
		// The record is filed under the crossbow, and the plan is about the shortbow —
		// so it will not be published as this weapon's measurement. It is still the id
		// with a history behind it, and pointing the plan somewhere else would throw
		// that away for nothing.
		measure(702, "Bandit", 60, CROSSBOW, 3, 20L);

		assertEquals(702, TripAdvisor.preferMeasured(Arrays.asList(700, 701, 702),
			ledger, archive, SHORTBOW));
	}

	@Test
	public void anIdOnlyAPreviousSessionSawStillBeatsOneNobodyHasEverFought()
	{
		remember(701, "Bandit", 60, SHORTBOW, 5, 20L);

		assertEquals(701, TripAdvisor.preferMeasured(Arrays.asList(700, 701, 702),
			ledger, archive, SHORTBOW));
	}

	@Test
	public void thisSessionOutranksWhatAPreviousOneRemembered()
	{
		remember(701, "Bandit", 60, SHORTBOW, 50, 20L);
		measure(702, "Bandit", 60, SHORTBOW, 1, 20L);

		assertEquals("the setup in the player's hands, now",
			702, TripAdvisor.preferMeasured(Arrays.asList(700, 701, 702),
				ledger, archive, SHORTBOW));
	}

	private static MonsterIndex indexOf(FoughtNpc... monsters)
	{
		MonsterIndex index = new MonsterIndex();
		for (FoughtNpc monster : monsters)
		{
			index.add(monster);
		}
		return index.seal();
	}
}
