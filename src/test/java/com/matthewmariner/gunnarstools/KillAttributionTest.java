package com.matthewmariner.gunnarstools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The load-bearing half: which deaths were the player's kills, and whose
 * ammunition each tick's consumption was.
 *
 * <p>Every case here is a sequence of events inside one or more game ticks,
 * resolved by {@link KillAttribution#tickEnded(AmmoDelta)}. Nothing needs a
 * client, which is the point of the design — the decision that most easily goes
 * quietly wrong is the one that is cheapest to run a hundred times.
 */
public class KillAttributionTest
{
	private static final int ARROW = 11;

	/** Two spiders sharing an id, as a Wilderness spawn does. */
	private static final int SPIDER_ID = 5265;
	private static final int SKELETON_ID = 6612;

	private static FoughtNpc spider(int index)
	{
		return new FoughtNpc(index, SPIDER_ID, "Spindel", new int[]{100, 100, 100, 200, 1, 100});
	}

	private static FoughtNpc skeleton(int index)
	{
		return new FoughtNpc(index, SKELETON_ID, "Calvar'ion", new int[]{100, 100, 100, 150, 1, 100});
	}

	private static AmmoDelta spent(int itemId, long quantity)
	{
		Map<Integer, Long> consumed = new LinkedHashMap<>();
		consumed.put(itemId, quantity);
		return new AmmoDelta(consumed, Collections.emptyMap());
	}

	private static AmmoDelta found(int itemId, long quantity)
	{
		Map<Integer, Long> gained = new LinkedHashMap<>();
		gained.put(itemId, quantity);
		return new AmmoDelta(Collections.emptyMap(), gained);
	}

	// --- the golden path ------------------------------------------------------

	@Test
	public void aMonsterThePlayerFoughtAndDamagedIsAKillWithItsAmmunitionOnIt()
	{
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		assertTrue(attribution.tickEnded(AmmoDelta.EMPTY).isEmpty());

		attribution.damagedByMe(target);
		attribution.tickEnded(spent(ARROW, 1));
		attribution.tickEnded(spent(ARROW, 1));

		attribution.npcDied(target);
		List<Attribution> out = attribution.tickEnded(spent(ARROW, 1));

		assertEquals(1, out.size());
		assertEquals(Attribution.Kind.KILL, out.get(0).getKind());
		assertEquals(SPIDER_ID, out.get(0).getNpc().getId());
		assertEquals("the shot fired on the tick it died belongs to it",
			3L, out.get(0).getTally().consumedOf(ARROW));
	}

	@Test
	public void theWindowIsEmptyAgainAfterAKill()
	{
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);
		attribution.npcDied(target);
		attribution.tickEnded(spent(ARROW, 4));

		assertNull("nothing is being fought once the target is dead", attribution.getOwner());
		assertTrue(attribution.getWindow().isEmpty());

		// And the next tick's consumption must not land on the dead monster.
		assertTrue(attribution.tickEnded(spent(ARROW, 9)).isEmpty());
		assertTrue(attribution.getWindow().isEmpty());
	}

	@Test
	public void backToBackKillsEachGetTheirOwnAmmunition()
	{
		KillAttribution attribution = new KillAttribution();
		FoughtNpc first = spider(40);
		FoughtNpc second = spider(41);

		attribution.interacting(first);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(first);
		attribution.npcDied(first);
		List<Attribution> firstOut = attribution.tickEnded(spent(ARROW, 5));

		attribution.interacting(second);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(second);
		attribution.npcDied(second);
		List<Attribution> secondOut = attribution.tickEnded(spent(ARROW, 7));

		assertEquals(5L, firstOut.get(0).getTally().consumedOf(ARROW));
		assertEquals("the second kill starts from zero, not from five",
			7L, secondOut.get(0).getTally().consumedOf(ARROW));
	}

	// --- a death you did not cause is not your kill ---------------------------

	@Test
	public void aMonsterThatDiesWithoutThePlayerTouchingItIsNotAKill()
	{
		// Multi-combat Wilderness: other people's kills arrive on the same event
		// stream as yours, and there are more of them than of yours.
		KillAttribution attribution = new KillAttribution();

		attribution.npcDied(spider(40));

		assertTrue(attribution.tickEnded(AmmoDelta.EMPTY).isEmpty());
	}

	@Test
	public void aMonsterThePlayerClickedButNeverHitIsNotAKill()
	{
		// Clicking establishes the window, but somebody else finished it. No
		// hitsplat of the player's ever landed, so there is no evidence and no
		// sample.
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);

		attribution.npcDied(target);
		List<Attribution> out = attribution.tickEnded(AmmoDelta.EMPTY);

		assertTrue("interaction alone is not evidence of damage: " + out, out.isEmpty());
	}

	@Test
	public void aMonsterDamagedButNotFoughtIsCountedAndNotPriced()
	{
		// The other targets of an area attack. The kill is real; the ammunition
		// cannot be split between them without inventing the split.
		KillAttribution attribution = new KillAttribution();
		FoughtNpc onTarget = spider(40);
		FoughtNpc splashed = spider(41);

		attribution.interacting(onTarget);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(onTarget);
		attribution.damagedByMe(splashed);

		attribution.npcDied(splashed);
		List<Attribution> out = attribution.tickEnded(spent(ARROW, 2));

		assertEquals(1, out.size());
		assertEquals(Attribution.Kind.UNATTRIBUTED_DEATH, out.get(0).getKind());
		assertNull("an unattributed death has no window by definition", out.get(0).getTally());

		assertSame("and the real target keeps the window", onTarget, attribution.getOwner());
		assertEquals(2L, attribution.getWindow().consumedOf(ARROW));
	}

	// --- a despawn is not a death --------------------------------------------

	@Test
	public void aDespawnIsNeverAKill()
	{
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);
		attribution.tickEnded(spent(ARROW, 6));

		attribution.npcDespawned(40);
		List<Attribution> out = attribution.tickEnded(AmmoDelta.EMPTY);

		assertEquals(1, out.size());
		assertEquals("a monster that walked away is not a kill",
			Attribution.Kind.ABANDONED, out.get(0).getKind());
		assertEquals(6L, out.get(0).getTally().consumedOf(ARROW));
	}

	@Test
	public void despawningSomethingElseDoesNotDisturbTheFight()
	{
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);
		attribution.tickEnded(spent(ARROW, 3));

		attribution.npcDespawned(41);
		attribution.npcDespawned(99);
		assertTrue(attribution.tickEnded(spent(ARROW, 1)).isEmpty());

		assertSame(target, attribution.getOwner());
		assertEquals(4L, attribution.getWindow().consumedOf(ARROW));
	}

	@Test
	public void aDespawnWithNoDamageBanksNothing()
	{
		// A window opened by clicking a banker, or by clicking a monster that
		// somebody else then killed. Whatever the containers did in the meantime
		// was eating and looting, not ammunition.
		KillAttribution attribution = new KillAttribution();

		attribution.interacting(spider(40));
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.tickEnded(spent(ARROW, 20));

		attribution.npcDespawned(40);
		List<Attribution> out = attribution.tickEnded(AmmoDelta.EMPTY);

		assertTrue("no hitsplat, no fight, no record: " + out, out.isEmpty());
	}

	@Test
	public void aFightThatSpentNothingDespawnsWithoutBankingAZeroCostAbandonment()
	{
		// Melee carries no ammunition. A fight the player genuinely had —
		// engaged and damaged — that despawns having spent nothing must not
		// add a zero-cost row to the column that exists to flag "died without
		// a zero-health update"; an empty window is not evidence of anything.
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);
		attribution.tickEnded(AmmoDelta.EMPTY);

		attribution.npcDespawned(40);
		List<Attribution> out = attribution.tickEnded(AmmoDelta.EMPTY);

		assertTrue("a window with nothing spent in it is not an abandonment: " + out, out.isEmpty());
	}

	@Test
	public void deathThenDespawnInOneTickIsOneKillAndNoAbandonment()
	{
		// The ordinary shape of a kill: the health bar hits zero and the corpse
		// leaves, sometimes on the same tick.
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);

		attribution.npcDied(target);
		attribution.npcDespawned(40);
		List<Attribution> out = attribution.tickEnded(spent(ARROW, 5));

		assertEquals("one event, not a kill and an abandonment: " + out, 1, out.size());
		assertEquals(Attribution.Kind.KILL, out.get(0).getKind());
		assertEquals(5L, out.get(0).getTally().consumedOf(ARROW));
	}

	// --- index reuse ----------------------------------------------------------

	@Test
	public void aFreshNpcInsideARecycledIndexInheritsNoEvidence()
	{
		// Scene indices are reused. Keeping damage evidence past a despawn credits
		// the next occupant of the slot with the previous one's fight.
		KillAttribution attribution = new KillAttribution();

		attribution.damagedByMe(spider(40));
		attribution.npcDespawned(40);
		attribution.tickEnded(AmmoDelta.EMPTY);

		assertEquals(0, attribution.getDamageEvidenceCount());

		FoughtNpc newcomer = skeleton(40);
		attribution.interacting(newcomer);
		attribution.tickEnded(AmmoDelta.EMPTY);

		attribution.npcDied(newcomer);
		List<Attribution> out = attribution.tickEnded(AmmoDelta.EMPTY);

		assertTrue("index 40 is somebody else now: " + out, out.isEmpty());
	}

	@Test
	public void anNpcThatDespawnedThisTickDoesNotBecomeTheOwner()
	{
		// A hitsplat opens a window on the monster it hit. If that monster
		// despawns on the same tick, the pending engagement has to go with it —
		// otherwise a departed NPC owns index 40, and the fresh NPC that takes
		// slot 40 next can never claim it, because ownership is compared by index.
		KillAttribution attribution = new KillAttribution();

		attribution.damagedByMe(spider(40));
		attribution.npcDespawned(40);
		attribution.tickEnded(AmmoDelta.EMPTY);

		assertNull("nothing that left the scene is being fought", attribution.getOwner());

		FoughtNpc newcomer = skeleton(40);
		attribution.interacting(newcomer);
		attribution.tickEnded(AmmoDelta.EMPTY);

		assertSame("and slot 40's new occupant can be engaged normally",
			newcomer, attribution.getOwner());
	}

	@Test
	public void evidenceIsDroppedWhenTheMonsterItBelongedToDies()
	{
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);
		attribution.npcDied(target);
		attribution.tickEnded(AmmoDelta.EMPTY);

		assertEquals(0, attribution.getDamageEvidenceCount());
	}

	// --- stickiness -----------------------------------------------------------

	@Test
	public void aNullInteractionTargetDoesNotEndTheFight()
	{
		// The game reports the target going to null constantly — between attacks,
		// while walking. Treating it as disengagement drops the ammunition spent
		// in the gaps within a single fight.
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);
		attribution.tickEnded(spent(ARROW, 1));

		attribution.interacting(null);
		attribution.tickEnded(spent(ARROW, 1));
		attribution.interacting(null);
		attribution.tickEnded(spent(ARROW, 1));

		attribution.npcDied(target);
		List<Attribution> out = attribution.tickEnded(spent(ARROW, 1));

		assertEquals(1, out.size());
		assertEquals("all four shots, not just the ones with a target set",
			4L, out.get(0).getTally().consumedOf(ARROW));
	}

	@Test
	public void reEngagingTheSameMonsterDoesNotResetItsWindow()
	{
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);
		attribution.tickEnded(spent(ARROW, 3));

		// A second interaction event for the same NPC — a new FoughtNpc object,
		// same index. Identity is the index, not the object.
		attribution.interacting(spider(40));
		attribution.tickEnded(spent(ARROW, 2));

		attribution.npcDied(target);
		List<Attribution> out = attribution.tickEnded(AmmoDelta.EMPTY);

		assertEquals(5L, out.get(0).getTally().consumedOf(ARROW));
	}

	@Test
	public void switchingTargetsAbandonsTheOldFightRatherThanChargingItToTheNew()
	{
		KillAttribution attribution = new KillAttribution();
		FoughtNpc first = spider(40);
		FoughtNpc second = spider(41);

		attribution.interacting(first);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(first);
		attribution.tickEnded(spent(ARROW, 8));

		attribution.interacting(second);
		List<Attribution> out = attribution.tickEnded(AmmoDelta.EMPTY);

		assertEquals(1, out.size());
		assertEquals(Attribution.Kind.ABANDONED, out.get(0).getKind());
		assertEquals(8L, out.get(0).getTally().consumedOf(ARROW));

		assertSame(second, attribution.getOwner());
		assertTrue("the new target starts clean", attribution.getWindow().isEmpty());
	}

	// --- ordering within a tick ----------------------------------------------

	@Test
	public void aOneShotKillWorksWithTheHitsplatArrivingAfterTheDeath()
	{
		// Both come out of the same tick's packets and the order between them is
		// not something a plugin may rely on. Nothing is decided until the tick
		// closes, so both orders have to give the same answer.
		for (boolean deathFirst : new boolean[]{true, false})
		{
			KillAttribution attribution = new KillAttribution();
			FoughtNpc target = spider(40);

			attribution.interacting(target);
			attribution.tickEnded(AmmoDelta.EMPTY);

			if (deathFirst)
			{
				attribution.npcDied(target);
				attribution.damagedByMe(target);
			}
			else
			{
				attribution.damagedByMe(target);
				attribution.npcDied(target);
			}
			List<Attribution> out = attribution.tickEnded(spent(ARROW, 1));

			assertEquals("death first? " + deathFirst, 1, out.size());
			assertEquals("death first? " + deathFirst, Attribution.Kind.KILL, out.get(0).getKind());
			assertEquals(1L, out.get(0).getTally().consumedOf(ARROW));
		}
	}

	@Test
	public void clickingTheNextMonsterOnTheTickTheLastOneDiesStillCreditsTheKill()
	{
		// Interaction changes are applied after deaths resolve, precisely so this
		// costs nothing. Applying them first loses the whole kill.
		KillAttribution attribution = new KillAttribution();
		FoughtNpc dying = spider(40);
		FoughtNpc next = spider(41);

		attribution.interacting(dying);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(dying);

		attribution.npcDied(dying);
		attribution.interacting(next);
		List<Attribution> out = attribution.tickEnded(spent(ARROW, 2));

		assertEquals(1, out.size());
		assertEquals(Attribution.Kind.KILL, out.get(0).getKind());
		assertEquals(2L, out.get(0).getTally().consumedOf(ARROW));
		assertSame("and the next fight is already under way", next, attribution.getOwner());
	}

	// --- the window has to have an owner --------------------------------------

	@Test
	public void consumptionWithNothingBeingFoughtIsDiscarded()
	{
		// Banking, eating, dropping, alching. There is no monster to charge it to
		// and inventing one is worse than losing it.
		KillAttribution attribution = new KillAttribution();

		assertTrue(attribution.tickEnded(spent(ARROW, 500)).isEmpty());
		assertTrue(attribution.getWindow().isEmpty());

		FoughtNpc target = spider(40);
		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);
		attribution.npcDied(target);
		List<Attribution> out = attribution.tickEnded(spent(ARROW, 1));

		assertEquals("the 500 deposited at the bank are not on this spider",
			1L, out.get(0).getTally().consumedOf(ARROW));
	}

	@Test
	public void aHitsplatOpensAWindowWhenNoInteractionEverArrived()
	{
		// Autocast, or a fight already in progress when the plugin was switched
		// on. Without this the whole fight is discarded.
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.damagedByMe(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		assertSame(target, attribution.getOwner());

		attribution.tickEnded(spent(ARROW, 3));
		attribution.npcDied(target);
		List<Attribution> out = attribution.tickEnded(AmmoDelta.EMPTY);

		assertEquals(Attribution.Kind.KILL, out.get(0).getKind());
		assertEquals(3L, out.get(0).getTally().consumedOf(ARROW));
	}

	@Test
	public void aHitsplatOnAnotherMonsterDoesNotStealAnOpenWindow()
	{
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);

		attribution.damagedByMe(spider(41));
		attribution.tickEnded(spent(ARROW, 1));

		assertSame("an area attack must not pull the window off the real target",
			target, attribution.getOwner());
	}

	// --- gains ----------------------------------------------------------------

	@Test
	public void ammunitionPickedBackUpTravelsWithTheWindowRatherThanCancellingOut()
	{
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);
		attribution.tickEnded(spent(ARROW, 10));
		attribution.tickEnded(found(ARROW, 4));

		attribution.npcDied(target);
		List<Attribution> out = attribution.tickEnded(AmmoDelta.EMPTY);

		AmmoTally tally = out.get(0).getTally();
		assertEquals("the gross figure is what the trip has to cover",
			10L, tally.consumedOf(ARROW));
		assertEquals("and the recovery is disclosed beside it, not netted off",
			4L, tally.gainedOf(ARROW));
	}

	@Test
	public void gainsDoNotLeakFromOneWindowIntoTheNext()
	{
		// The window object backing the open fight is reused, kill after kill —
		// closing one clears it rather than replacing it. If clearing it forgot
		// the gained side, a pickup from one fight would keep showing up as a
		// recovery on every fight that follows, corrupting the one column that
		// exists to disclose contamination.
		KillAttribution attribution = new KillAttribution();
		FoughtNpc first = spider(40);
		FoughtNpc second = spider(41);

		attribution.interacting(first);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(first);
		attribution.tickEnded(found(ARROW, 40));
		attribution.npcDied(first);
		attribution.tickEnded(AmmoDelta.EMPTY);

		attribution.interacting(second);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(second);
		attribution.npcDied(second);
		// Nothing at all is gained on the second kill.
		List<Attribution> out = attribution.tickEnded(AmmoDelta.EMPTY);

		assertEquals("the first kill's pickup must not still be sitting in the second kill's tally",
			0L, out.get(0).getTally().gainedOf(ARROW));
	}

	// --- the player's own death ----------------------------------------------

	@Test
	public void thePlayersOwnDeathVoidsTheTickRatherThanBankingAnInventory()
	{
		// The single largest source of false consumption in the game: everything
		// not kept hits the floor on one tick.
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);
		attribution.tickEnded(spent(ARROW, 4));

		attribution.localPlayerDied();
		List<Attribution> out = attribution.tickEnded(spent(ARROW, 946));

		assertTrue("a dropped inventory is not 946 arrows fired: " + out, out.isEmpty());
		assertNull(attribution.getOwner());
		assertTrue(attribution.getWindow().isEmpty());
		assertEquals(0, attribution.getDamageEvidenceCount());
	}

	@Test
	public void nothingIsAttributedAfterTheRespawnUntilTheNextFight()
	{
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);
		attribution.localPlayerDied();
		attribution.tickEnded(spent(ARROW, 946));

		// Restocking at the bank afterwards.
		assertTrue(attribution.tickEnded(found(ARROW, 900)).isEmpty());
		assertTrue(attribution.getWindow().isEmpty());
	}

	@Test
	public void measurementResumesAfterTheDeathTickRatherThanStayingVoidedForTheSession()
	{
		// Two other tests cover the death itself, but neither one ever asks
		// this class to measure anything again afterwards — and an empty
		// result is what both a healthy recovery and a permanently wedged
		// "everything is voided" flag look like from the outside. A plugin
		// whose whole subject is dying to PKers cannot afford a death to be
		// the last thing it ever prices.
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);
		attribution.localPlayerDied();
		attribution.tickEnded(spent(ARROW, 946));

		// A fresh fight, well after the death tick.
		FoughtNpc next = spider(41);
		attribution.interacting(next);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(next);
		attribution.npcDied(next);
		List<Attribution> out = attribution.tickEnded(spent(ARROW, 3));

		assertEquals("a kill well after the death must still be priced, not swallowed",
			1, out.size());
		assertEquals(Attribution.Kind.KILL, out.get(0).getKind());
		assertEquals(3L, out.get(0).getTally().consumedOf(ARROW));
	}

	@Test
	public void aDeathBufferedOnTheTickThePlayerDiesIsDiscardedWithEverythingElse()
	{
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);

		attribution.npcDied(target);
		attribution.localPlayerDied();

		assertTrue(attribution.tickEnded(spent(ARROW, 946)).isEmpty());
	}

	// --- co-victims: how many monsters a priced window actually killed --------

	@Test
	public void aBarrageThatKillsThreeChargesOneWindowAndCountsTwoCoVictims()
	{
		// One window of four runes, three monsters dead. Refusing to split it keeps
		// the numerator honest; counting the co-victims is what lets a later
		// division produce a cost per monster rather than a cost per priced kill.
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);

		attribution.damagedByMe(target);
		attribution.damagedByMe(spider(41));
		attribution.damagedByMe(spider(42));
		attribution.npcDied(target);
		attribution.npcDied(spider(41));
		attribution.npcDied(spider(42));

		List<Attribution> out = attribution.tickEnded(spent(ARROW, 4));

		assertEquals(3, out.size());
		assertEquals(Attribution.Kind.KILL, out.get(0).getKind());
		assertEquals("four runes over three monsters, not over one",
			2, out.get(0).getCoVictims());
		assertEquals(4L, out.get(0).getTally().consumedOf(ARROW));
		assertEquals(Attribution.Kind.UNATTRIBUTED_DEATH, out.get(1).getKind());
		assertEquals(Attribution.Kind.UNATTRIBUTED_DEATH, out.get(2).getKind());
	}

	@Test
	public void aCoVictimResolvedBeforeTheKillInTheSameTickStillLandsOnIt()
	{
		// Deaths arrive within a tick in no guaranteed order, and a one-shot
		// barrage delivers all of them together. Counting as the loop goes would
		// credit these two to whatever window opens next, because the kill clears
		// the counter partway through.
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);

		attribution.damagedByMe(target);
		attribution.damagedByMe(spider(41));
		attribution.damagedByMe(spider(42));
		attribution.npcDied(spider(41));
		attribution.npcDied(target);
		attribution.npcDied(spider(42));

		List<Attribution> out = attribution.tickEnded(spent(ARROW, 4));

		assertEquals(Attribution.Kind.UNATTRIBUTED_DEATH, out.get(0).getKind());
		assertEquals(Attribution.Kind.KILL, out.get(1).getKind());
		assertEquals("arrival order within the tick must not change the count",
			2, out.get(1).getCoVictims());
		assertEquals(Attribution.Kind.UNATTRIBUTED_DEATH, out.get(2).getKind());
	}

	@Test
	public void coVictimsAccumulateAcrossEveryCastTheWindowPaidFor()
	{
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);

		attribution.damagedByMe(spider(41));
		attribution.npcDied(spider(41));
		attribution.tickEnded(spent(ARROW, 4));

		attribution.damagedByMe(spider(42));
		attribution.npcDied(spider(42));
		attribution.tickEnded(spent(ARROW, 4));

		attribution.npcDied(target);
		List<Attribution> out = attribution.tickEnded(spent(ARROW, 4));

		assertEquals(Attribution.Kind.KILL, out.get(0).getKind());
		assertEquals("both earlier casts killed something this window paid for",
			2, out.get(0).getCoVictims());
		assertEquals(12L, out.get(0).getTally().consumedOf(ARROW));
	}

	@Test
	public void theCountRidesOnTheKillEvenWhenTheCoVictimsAreADifferentSpecies()
	{
		// The unattributed deaths are filed against the skeletons' own id. If the
		// correction were applied there it would land on a record holding none of
		// the ammunition, and the spider's figure would stay uncorrected.
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);

		attribution.damagedByMe(target);
		attribution.damagedByMe(skeleton(41));
		attribution.npcDied(target);
		attribution.npcDied(skeleton(41));

		List<Attribution> out = attribution.tickEnded(spent(ARROW, 6));

		assertEquals(SPIDER_ID, out.get(0).getNpc().getId());
		assertEquals(1, out.get(0).getCoVictims());
		assertEquals(SKELETON_ID, out.get(1).getNpc().getId());
		assertEquals("and the co-victim's own verdict carries no correction",
			0, out.get(1).getCoVictims());
	}

	@Test
	public void aMonsterThePlayerWalkedAwayFromIsNotACoVictimOfTheNextFight()
	{
		// The leak the whole exclusion exists for. The spider's fight was banked as
		// abandoned — its ammunition is out of the numerator entirely — so letting
		// its later death raise the skeleton's denominator would understate what
		// the skeleton costs, and understating ends a trip early.
		KillAttribution attribution = new KillAttribution();
		FoughtNpc walkedAwayFrom = spider(40);
		FoughtNpc newTarget = skeleton(41);

		attribution.interacting(walkedAwayFrom);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(walkedAwayFrom);
		attribution.tickEnded(spent(ARROW, 3));

		attribution.interacting(newTarget);
		List<Attribution> abandoned = attribution.tickEnded(AmmoDelta.EMPTY);
		assertEquals(Attribution.Kind.ABANDONED, abandoned.get(0).getKind());

		attribution.damagedByMe(newTarget);
		attribution.tickEnded(spent(ARROW, 2));

		// Somebody else finishes the spider off.
		attribution.npcDied(walkedAwayFrom);
		List<Attribution> stray = attribution.tickEnded(AmmoDelta.EMPTY);
		assertEquals(Attribution.Kind.UNATTRIBUTED_DEATH, stray.get(0).getKind());
		assertEquals("a fight already banked as abandoned is not a co-victim",
			0, attribution.getWindowCoVictims());

		attribution.npcDied(newTarget);
		List<Attribution> out = attribution.tickEnded(spent(ARROW, 1));

		assertEquals(Attribution.Kind.KILL, out.get(0).getKind());
		assertEquals(0, out.get(0).getCoVictims());
		assertEquals(3L, out.get(0).getTally().consumedOf(ARROW));
	}

	@Test
	public void aDeathWithNoWindowOpenIsNotACoVictimOfAnything()
	{
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);
		FoughtNpc bystander = spider(41);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);
		attribution.damagedByMe(bystander);
		attribution.npcDied(target);
		attribution.tickEnded(spent(ARROW, 5));

		assertNull("the window closed with the kill", attribution.getOwner());

		attribution.npcDied(bystander);
		List<Attribution> out = attribution.tickEnded(AmmoDelta.EMPTY);

		assertEquals(Attribution.Kind.UNATTRIBUTED_DEATH, out.get(0).getKind());
		assertEquals("there is no window for it to be a co-victim of",
			0, attribution.getWindowCoVictims());
	}

	@Test
	public void theCoVictimCountIsClearedWithTheWindowItBelongedTo()
	{
		KillAttribution attribution = new KillAttribution();
		FoughtNpc first = spider(40);

		attribution.interacting(first);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(first);
		attribution.damagedByMe(spider(41));
		attribution.npcDied(spider(41));
		attribution.tickEnded(spent(ARROW, 4));
		assertEquals(1, attribution.getWindowCoVictims());

		attribution.npcDied(first);
		attribution.tickEnded(spent(ARROW, 4));

		assertEquals("the next fight starts from zero, not from one",
			0, attribution.getWindowCoVictims());

		FoughtNpc second = spider(42);
		attribution.interacting(second);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(second);
		attribution.npcDied(second);
		List<Attribution> out = attribution.tickEnded(spent(ARROW, 5));

		assertEquals(0, out.get(0).getCoVictims());
	}

	@Test
	public void aWindowAbandonedMidBarrageDoesNotHandItsCoVictimsToTheNextFight()
	{
		// The co-victim count belongs to the window that paid for it. Left standing
		// when the player switches targets, it would divide the next monster's cost
		// by monsters it never killed — an understatement, and understating is what
		// ends a trip early.
		KillAttribution attribution = new KillAttribution();
		FoughtNpc first = spider(40);
		FoughtNpc second = skeleton(41);

		attribution.interacting(first);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(first);
		attribution.damagedByMe(spider(42));
		attribution.npcDied(spider(42));
		attribution.tickEnded(spent(ARROW, 4));
		assertEquals("the count has to be there before dropping it means anything",
			1, attribution.getWindowCoVictims());

		attribution.interacting(second);
		attribution.tickEnded(AmmoDelta.EMPTY);
		assertEquals(0, attribution.getWindowCoVictims());

		attribution.damagedByMe(second);
		attribution.npcDied(second);
		List<Attribution> out = attribution.tickEnded(spent(ARROW, 6));

		assertEquals(Attribution.Kind.KILL, out.get(0).getKind());
		assertEquals("the skeleton killed nothing but itself", 0, out.get(0).getCoVictims());
	}

	@Test
	public void aDespawnedIndexIsForgottenBeforeItsSlotIsReused()
	{
		// The walked-away mark is keyed by scene index, and indices are recycled.
		// Left in place it would silently exempt the next occupant of slot 40 from
		// ever being counted as a co-victim.
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);
		attribution.tickEnded(spent(ARROW, 3));

		attribution.interacting(skeleton(41));
		attribution.tickEnded(AmmoDelta.EMPTY);
		assertEquals(1, attribution.getWalkedAwayCount());

		attribution.npcDespawned(40);
		attribution.tickEnded(AmmoDelta.EMPTY);

		assertEquals("index 40 means a different monster now",
			0, attribution.getWalkedAwayCount());
	}

	// --- reset ---------------------------------------------------------------

	@Test
	public void resetForgetsBothCoVictimBooksAsWellAsTheWindow()
	{
		// Both books loaded at once before the reset, because a teardown test that
		// starts from an empty state passes whether or not the teardown does
		// anything.
		KillAttribution attribution = new KillAttribution();
		FoughtNpc first = spider(40);
		FoughtNpc second = spider(41);

		attribution.interacting(first);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(first);
		attribution.tickEnded(spent(ARROW, 3));

		attribution.interacting(second);
		attribution.tickEnded(AmmoDelta.EMPTY);

		attribution.damagedByMe(second);
		attribution.damagedByMe(spider(42));
		attribution.npcDied(spider(42));
		attribution.tickEnded(spent(ARROW, 4));

		assertEquals("the state has to be there before the reset means anything",
			1, attribution.getWindowCoVictims());
		assertEquals(1, attribution.getWalkedAwayCount());

		attribution.reset();

		assertEquals(0, attribution.getWindowCoVictims());
		assertEquals(0, attribution.getWalkedAwayCount());
	}

	@Test
	public void resetLeavesNothingBehind()
	{
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);
		attribution.tickEnded(spent(ARROW, 5));
		// Buffered before it is ever resolved: the death is for the index that
		// was both damaged and the window's owner, exactly what a genuine kill
		// looks like — a death for some unrelated index would yield nothing
		// whether or not the buffer survives the reset, which proves nothing.
		attribution.npcDied(target);

		attribution.reset();

		assertNull(attribution.getOwner());
		assertTrue(attribution.getWindow().isEmpty());
		assertEquals(0, attribution.getDamageEvidenceCount());

		// Index 40 gets recycled for an unrelated monster on the next tick. If
		// the death buffered above had survived the reset, this hitsplat would
		// put 40 back into the damage-evidence set, and the leftover death
		// would then read as "damaged, so it must be an unattributed death" —
		// a phantom verdict for an NPC that is long gone, which also consumes
		// the newcomer's only piece of evidence before its own death is ever
		// judged.
		FoughtNpc newcomer = skeleton(40);
		attribution.damagedByMe(newcomer);
		assertTrue("a buffered death must not survive the reset either",
			attribution.tickEnded(AmmoDelta.EMPTY).isEmpty());
	}

	@Test
	public void aWorldHopForgetsAStaleDespawnRatherThanReplayingItOnTheNewWorld()
	{
		// A despawn buffered on the old world, never resolved before the hop
		// (game state changes replace the scene without waiting for a tick to
		// close). If reset() left it sitting in the buffer, index 40's fresh
		// occupant on the new world would have its own opening engagement
		// swallowed by a despawn that belongs to nothing anymore.
		KillAttribution attribution = new KillAttribution();

		attribution.npcDespawned(40);
		attribution.reset();

		FoughtNpc newcomer = skeleton(40);
		attribution.damagedByMe(newcomer);
		attribution.tickEnded(AmmoDelta.EMPTY);

		assertSame("a leftover despawn from the old world must not swallow the new one's engagement",
			newcomer, attribution.getOwner());
	}

	@Test
	public void aWorldHopForgetsAnEngagementThatWasNeverResolvedIntoAnOwner()
	{
		// The player clicked a monster, but the hop lands before the tick that
		// would have turned that click into the window's owner. If reset()
		// left the click sitting in pendingEngagement, the very next tick on
		// the new world — even one with no click and no hitsplat of its own —
		// would hand the window to a monster that is not even in this world's
		// scene anymore.
		KillAttribution attribution = new KillAttribution();
		FoughtNpc preHopTarget = spider(40);

		attribution.interacting(preHopTarget);
		attribution.reset();

		attribution.tickEnded(AmmoDelta.EMPTY);

		assertNull("a click from before the hop must not become this world's owner",
			attribution.getOwner());
	}
}
