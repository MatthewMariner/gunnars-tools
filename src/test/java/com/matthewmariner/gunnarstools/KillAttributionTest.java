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

	// --- reset ---------------------------------------------------------------

	@Test
	public void resetLeavesNothingBehind()
	{
		KillAttribution attribution = new KillAttribution();
		FoughtNpc target = spider(40);

		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);
		attribution.tickEnded(spent(ARROW, 5));
		attribution.npcDied(spider(41));

		attribution.reset();

		assertNull(attribution.getOwner());
		assertTrue(attribution.getWindow().isEmpty());
		assertEquals(0, attribution.getDamageEvidenceCount());
		assertTrue("a buffered death must not survive the reset either",
			attribution.tickEnded(AmmoDelta.EMPTY).isEmpty());
	}
}
