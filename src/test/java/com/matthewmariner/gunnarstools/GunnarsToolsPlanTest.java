package com.matthewmariner.gunnarstools;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.api.Item;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.events.ConfigChanged;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The plugin's one piece of owned state: the cached projection the two overlays
 * draw.
 *
 * <p>Everything it decides is in {@link TripPlanner}. What is being held to
 * account here is the wiring — which monster it is about, where the trip size
 * comes from, and when it is allowed to go stale — because that wiring is the
 * part an overlay silently renders and no offline test would otherwise touch.
 */
public class GunnarsToolsPlanTest
{
	private static final int ARROW = 11;
	private static final int SPINDEL = 5265;
	private static final int CALLISTO = 6609;
	private static final int SKELETON = 6612;

	private final FakeConfig config = new FakeConfig();

	private GunnarsToolsPlugin plugin()
	{
		GunnarsToolsPlugin plugin = new GunnarsToolsPlugin();
		plugin.config = config;
		plugin.configStore = config;
		plugin.clientThread = Runnable::run;
		plugin.overlayRegistry = new RecordingOverlays();
		plugin.tripPanelOverlay = new TripPanelOverlay(plugin, config, null);
		plugin.bankWithdrawalOverlay = new BankWithdrawalOverlay(plugin, config);
		return plugin;
	}

	private static FoughtNpc npc(int index, int id, String name)
	{
		return new FoughtNpc(index, id, name, new int[]{130, 130, 130, 200, 1, 130});
	}

	private static AmmoTally spent(long arrows)
	{
		AmmoTally tally = new AmmoTally();
		tally.add(new AmmoDelta(Collections.singletonMap(ARROW, arrows), Collections.emptyMap()));
		return tally;
	}

	@Test
	public void thereIsNothingToDrawBeforeTheFirstKill()
	{
		GunnarsToolsPlugin plugin = plugin();

		plugin.rebuildPlan();

		assertNull(plugin.getPlanSubject());
		assertTrue(plugin.getPlan().isEmpty());
		assertTrue(plugin.getWithdrawals().isEmpty());
	}

	@Test
	public void thePlanIsAboutTheMonsterMostRecentlyKilled()
	{
		// A player who finishes a task and starts another should not be packing for
		// the previous one.
		GunnarsToolsPlugin plugin = plugin();
		config.withTripKills(100).withSafetyMargin(0);

		plugin.getLedger().apply(Attribution.kill(npc(40, SPINDEL, "Spindel"), spent(20), 0));
		plugin.rebuildPlan();
		assertEquals(SPINDEL, plugin.getPlanSubject().getNpcId());
		assertEquals(Long.valueOf(2000L), plugin.getWithdrawals().get(ARROW));

		plugin.getLedger().apply(Attribution.kill(npc(41, CALLISTO, "Callisto"), spent(45), 0));
		plugin.rebuildPlan();

		assertEquals(CALLISTO, plugin.getPlanSubject().getNpcId());
		assertEquals(Long.valueOf(4500L), plugin.getWithdrawals().get(ARROW));
	}

	@Test
	public void theTripSizeAndMarginAreTheOnesTheUserSet()
	{
		GunnarsToolsPlugin plugin = plugin();
		config.withTripKills(250).withSafetyMargin(20);

		plugin.getLedger().apply(Attribution.kill(npc(40, SPINDEL, "Spindel"), spent(20), 0));
		plugin.rebuildPlan();

		assertEquals(1, plugin.getPlan().size());
		assertEquals(250, plugin.getPlan().get(0).getTargetMonsters());
		assertEquals(20, plugin.getPlan().get(0).getSafetyMarginPercent());
		assertEquals(6000L, plugin.getPlan().get(0).getBring());
	}

	@Test
	public void changingTheDialsRebuildsTheAnswer()
	{
		GunnarsToolsPlugin plugin = plugin();
		config.withTripKills(100).withSafetyMargin(0);

		plugin.getLedger().apply(Attribution.kill(npc(40, SPINDEL, "Spindel"), spent(20), 0));
		plugin.rebuildPlan();
		assertEquals(2000L, plugin.getPlan().get(0).getBring());

		config.withTripKills(300);
		plugin.onConfigChanged(configChanged(GunnarsToolsConfig.GROUP));

		assertEquals(6000L, plugin.getPlan().get(0).getBring());
	}

	@Test
	public void somebodyElsesSettingsPanelDoesNotRebuildAnything()
	{
		// Every plugin's config changes arrive on the same bus. Rebuilding on all of
		// them would be a sort per keystroke in an unrelated settings panel.
		GunnarsToolsPlugin plugin = plugin();
		config.withTripKills(100).withSafetyMargin(0);

		plugin.getLedger().apply(Attribution.kill(npc(40, SPINDEL, "Spindel"), spent(20), 0));
		plugin.rebuildPlan();

		config.withTripKills(300);
		plugin.onConfigChanged(configChanged("someoneelsesplugin"));

		assertEquals("a stale plan is the correct answer to somebody else's change",
			2000L, plugin.getPlan().get(0).getBring());
	}

	// --- what a tick does to the answer ---------------------------------------

	@Test
	public void aTickThatEndedInAKillProducesANewAnswer()
	{
		GunnarsToolsPlugin plugin = plugin();
		config.withTripKills(100).withSafetyMargin(0);
		FoughtNpc target = npc(40, SPINDEL, "Spindel");

		plugin.getAttribution().interacting(target);
		plugin.tickEnded(AmmoDelta.EMPTY);
		plugin.getAttribution().damagedByMe(target);
		plugin.getAttribution().npcDied(target);

		assertTrue("the kill is what makes the answer", plugin.tickEnded(spent(ARROW, 20)));

		assertEquals("Spindel", plugin.getPlanSubject().getNpcName());
		assertEquals(Long.valueOf(2000L), plugin.getWithdrawals().get(ARROW));
	}

	@Test
	public void aTickWithNothingButAStrayDeathLeavesTheAnswerAlone()
	{
		// Rebuilding on every tick would sort every sample series sixty times a
		// minute, and neither an abandoned fight nor an unattributed death moves a
		// column the projection reads.
		GunnarsToolsPlugin plugin = plugin();
		config.withTripKills(100).withSafetyMargin(0);
		FoughtNpc target = npc(40, SPINDEL, "Spindel");
		FoughtNpc bystander = npc(41, SPINDEL, "Spindel");

		plugin.getAttribution().interacting(target);
		plugin.tickEnded(AmmoDelta.EMPTY);
		plugin.getAttribution().damagedByMe(target);
		plugin.getAttribution().damagedByMe(bystander);
		plugin.getAttribution().npcDied(bystander);

		assertFalse("somebody else's kill is not a new answer",
			plugin.tickEnded(spent(ARROW, 5)));
		assertTrue(plugin.getPlan().isEmpty());
	}

	@Test
	public void aBarrageTickIsStillAKillEvenThoughItDoesNotEndOnOne()
	{
		// The verdicts come back as a kill followed by its co-victims' unattributed
		// deaths, so a flag assigned from the last one rather than accumulated
		// across them would leave the answer stale exactly when it changed most.
		GunnarsToolsPlugin plugin = plugin();
		config.withTripKills(120).withSafetyMargin(0);
		FoughtNpc target = npc(40, SPINDEL, "Spindel");

		plugin.getAttribution().interacting(target);
		plugin.tickEnded(AmmoDelta.EMPTY);
		plugin.getAttribution().damagedByMe(target);
		plugin.getAttribution().damagedByMe(npc(41, SPINDEL, "Spindel"));
		plugin.getAttribution().damagedByMe(npc(42, SPINDEL, "Spindel"));
		plugin.getAttribution().npcDied(target);
		plugin.getAttribution().npcDied(npc(41, SPINDEL, "Spindel"));
		plugin.getAttribution().npcDied(npc(42, SPINDEL, "Spindel"));

		assertTrue(plugin.tickEnded(spent(ARROW, 12)));

		assertEquals(3, plugin.getPlanSubject().getMonstersPriced());
		assertEquals("four arrows a monster over a hundred and twenty of them",
			Long.valueOf(480L), plugin.getWithdrawals().get(ARROW));
	}

	@Test
	public void aBarrageThatAlsoKillsSomethingElsePlansForTheMonsterTheTripIsFor()
	{
		// The arithmetic done by hand before any of it was written down. Four
		// barrages of four runes; each one kills the Spindel being fought and two
		// skeletons standing in it. Sixteen runes bought four Spindels, so a
		// Spindel costs four runes and a hundred of them costs four hundred.
		//
		// Counting the skeletons as co-victims makes the divisor twelve, the rate
		// 1.333 and the answer 134 — a third of the trip, in the one direction this
		// plugin exists to refuse. The divisor has to be in the same unit as the
		// number it is eventually divided into, and that unit is Spindels.
		GunnarsToolsPlugin plugin = plugin();
		config.withTripKills(100).withSafetyMargin(0);

		for (int cast = 0; cast < 4; cast++)
		{
			final int index = 40 + cast * 3;
			FoughtNpc target = npc(index, SPINDEL, "Spindel");
			plugin.getAttribution().interacting(target);
			plugin.tickEnded(AmmoDelta.EMPTY);

			plugin.getAttribution().damagedByMe(target);
			plugin.getAttribution().damagedByMe(npc(index + 1, SKELETON, "Skeleton"));
			plugin.getAttribution().damagedByMe(npc(index + 2, SKELETON, "Skeleton"));
			plugin.getAttribution().npcDied(target);
			plugin.getAttribution().npcDied(npc(index + 1, SKELETON, "Skeleton"));
			plugin.getAttribution().npcDied(npc(index + 2, SKELETON, "Skeleton"));

			assertTrue(plugin.tickEnded(spent(ARROW, 4)));
		}

		NpcAmmoRecord subject = plugin.getPlanSubject();
		assertEquals(SPINDEL, subject.getNpcId());
		assertEquals(4, subject.getKills());
		assertEquals("eight skeletons died, and not one of them was a Spindel",
			0, subject.getPricedCoVictims());
		assertEquals(4, subject.getMonstersPriced());
		assertEquals("the skeletons' own record counts them, and prices nothing",
			8, plugin.getLedger().get(SKELETON).getUnattributedDeaths());
		assertEquals(Long.valueOf(400L), plugin.getWithdrawals().get(ARROW));
	}

	@Test
	public void aTickWithNoKillInItLeavesTheCachedPlanObjectsWhereTheyWere()
	{
		// The guard that only rebuilds on a kill or a change of subject is a
		// performance one, and a performance guard is invisible to a test that only
		// reads values: rebuild unconditionally and the numbers come out identical,
		// sixty times a minute, having sorted every sample series to get there. So
		// what is asserted is the identity of the cached objects — a rebuild
		// replaces them, and a tick that moved nothing the answer depends on is not
		// allowed to.
		GunnarsToolsPlugin plugin = plugin();
		config.withTripKills(100).withSafetyMargin(0);
		FoughtNpc target = npc(40, SPINDEL, "Spindel");

		plugin.getAttribution().interacting(target);
		plugin.tickEnded(AmmoDelta.EMPTY);
		plugin.getAttribution().damagedByMe(target);
		plugin.getAttribution().npcDied(target);
		assertTrue(plugin.tickEnded(spent(ARROW, 20)));

		// Engaging the next monster is a change of subject — from "the Spindel you
		// last killed" to "the Spindel you are fighting" — so it is allowed to
		// rebuild, and the objects are captured on the far side of it. Capturing
		// before would be asserting that a target change does nothing, which is the
		// opposite of what this plugin is now for.
		FoughtNpc next = npc(41, SPINDEL, "Spindel");
		plugin.getAttribution().interacting(next);
		plugin.tickEnded(AmmoDelta.EMPTY);

		final List<TripPlan> planned = plugin.getPlan();
		final Map<Integer, Long> withdrawals = plugin.getWithdrawals();
		assertEquals(Long.valueOf(2000L), withdrawals.get(ARROW));

		// An unattributed death and nothing else — the shape the comment at the
		// guard names as not worth a rebuild. The subject does not move: the player
		// is still fighting the same monster they were.
		FoughtNpc bystander = npc(42, SPINDEL, "Spindel");
		plugin.getAttribution().damagedByMe(next);
		plugin.getAttribution().damagedByMe(bystander);
		plugin.getAttribution().npcDied(bystander);
		assertFalse(plugin.tickEnded(spent(ARROW, 4)));

		assertSame("the plan was not rebuilt", planned, plugin.getPlan());
		assertSame("nor was the bank lookup", withdrawals, plugin.getWithdrawals());
	}

	@Test
	public void engagingADifferentMonsterRepointsThePlanWithoutAKill()
	{
		// The other half of the guard above, and the fix for the complaint that
		// started this work: the plugin used to be able to say nothing at all about
		// a monster until it had watched one die. Clicking on one is enough.
		GunnarsToolsPlugin plugin = plugin();
		config.withTripKills(100).withSafetyMargin(0);

		plugin.getLedger().apply(Attribution.kill(npc(40, SPINDEL, "Spindel"), spent(20), 0));
		plugin.rebuildPlan();
		assertEquals(SPINDEL, plugin.getAdvice().getTarget().getNpcId());
		assertEquals(PlanTarget.Source.LAST_KILL, plugin.getAdvice().getTarget().getSource());

		plugin.getAttribution().interacting(npc(41, CALLISTO, "Callisto"));
		assertFalse("no kill happened", plugin.tickEnded(AmmoDelta.EMPTY));

		assertEquals("the plan follows what is in front of the player",
			CALLISTO, plugin.getAdvice().getTarget().getNpcId());
		assertEquals(PlanTarget.Source.FIGHTING, plugin.getAdvice().getTarget().getSource());
	}

	@Test
	public void theTicksMeasuredConsumptionIsWhatReachesTheAttribution()
	{
		// The wiring line the whole plugin rests on. A tick that handed the
		// attribution an empty delta instead of the meter's would measure nothing,
		// silently, forever — every record would read zero arrows per kill and the
		// answer would be "bring none".
		//
		// The meter is substituted because its real filter asks the client's item
		// cache whether an id is stackable, and there is no client here. Everything
		// else is the plugin's own path: onGameTick, its meter, its attribution.
		GunnarsToolsPlugin plugin = plugin();
		plugin.meter = new ConsumptionMeter(itemId -> true);
		FoughtNpc target = npc(40, SPINDEL, "Spindel");

		plugin.meter.containerChanged(InventoryID.INV, new Item[]{new Item(ARROW, 900)});
		plugin.meter.tickEnded();
		assertTrue("a baseline, so the tick does not try to prime off a client",
			plugin.meter.hasBaseline());

		plugin.getAttribution().interacting(target);
		plugin.onGameTick(new GameTick());
		plugin.getAttribution().damagedByMe(target);

		plugin.meter.containerChanged(InventoryID.INV, new Item[]{new Item(ARROW, 880)});
		plugin.onGameTick(new GameTick());

		assertEquals("twenty arrows left the stack and twenty reached the fight",
			20L, plugin.getAttribution().getWindow().consumedOf(ARROW));
	}

	@Test
	public void theBankOverlayCannotEditThePluginsOwnLookup()
	{
		GunnarsToolsPlugin plugin = plugin();
		config.withTripKills(100).withSafetyMargin(0);
		plugin.getLedger().apply(Attribution.kill(npc(40, SPINDEL, "Spindel"), spent(20), 0));
		plugin.rebuildPlan();

		try
		{
			plugin.getWithdrawals().put(ARROW, 1L);
			org.junit.Assert.fail("an overlay must not be able to rewrite the plan");
		}
		catch (UnsupportedOperationException expected)
		{
			assertEquals(Long.valueOf(2000L), plugin.getWithdrawals().get(ARROW));
		}
	}

	private static AmmoDelta spent(int itemId, long quantity)
	{
		return new AmmoDelta(Collections.singletonMap(itemId, quantity), Collections.emptyMap());
	}

	private static ConfigChanged configChanged(String group)
	{
		ConfigChanged event = new ConfigChanged();
		event.setGroup(group);
		event.setKey(GunnarsToolsConfig.TRIP_KILLS);
		return event;
	}
}
