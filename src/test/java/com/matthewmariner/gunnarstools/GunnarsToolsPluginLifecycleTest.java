package com.matthewmariner.gunnarstools;

import net.runelite.api.Item;
import net.runelite.api.gameval.InventoryID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The lifecycle promise: {@code shutDown()} leaves the plugin in the state a
 * fresh install would be in, for every piece of state {@code startUp()}
 * implicitly brings into being.
 *
 * <p>Constructed directly rather than through Guice. {@code startUp()} and
 * {@code shutDown()} are {@code protected}, which same-package test code can
 * call without a subclass, and neither of them touches the injected client —
 * which is a deliberate constraint on the plugin rather than a happy accident,
 * because it is what lets the whole lifecycle be held to account with no game
 * running.
 *
 * <p>The three collaborators are loaded up with real state before shutdown here
 * on purpose. A teardown test that shuts down an empty plugin passes whether or
 * not the teardown does anything at all, which is the shape of fake test this
 * repository is trying not to accumulate.
 */
public class GunnarsToolsPluginLifecycleTest
{
	private static final int ARROW = 11;
	private static final int SPINDEL = 5265;

	@Test
	public void startUpAndShutDownAreSymmetric()
	{
		GunnarsToolsPlugin plugin = new GunnarsToolsPlugin();

		assertFalse("must not be active before startUp", plugin.isActive());

		plugin.startUp();
		assertTrue("must be active after startUp", plugin.isActive());

		plugin.shutDown();
		assertFalse("shutDown must leave it inactive again", plugin.isActive());
	}

	@Test
	public void startUpIsIdempotentAndShutDownStillClears()
	{
		GunnarsToolsPlugin plugin = new GunnarsToolsPlugin();

		plugin.startUp();
		plugin.startUp();
		assertTrue(plugin.isActive());

		plugin.shutDown();
		assertFalse("one shutDown clears it regardless of how many startUps preceded it",
			plugin.isActive());
	}

	@Test
	public void shutDownEmptiesTheLedger()
	{
		GunnarsToolsPlugin plugin = new GunnarsToolsPlugin();
		plugin.startUp();

		plugin.getLedger().apply(Attribution.kill(spindel(40), spent(30)));
		assertEquals("the state has to be there before the teardown means anything",
			1, plugin.getLedger().size());

		plugin.shutDown();

		assertTrue("a session's measurements belong to the session",
			plugin.getLedger().isEmpty());
	}

	@Test
	public void shutDownClosesAnOpenFight()
	{
		GunnarsToolsPlugin plugin = new GunnarsToolsPlugin();
		plugin.startUp();

		KillAttribution attribution = plugin.getAttribution();
		FoughtNpc target = spindel(40);
		attribution.interacting(target);
		attribution.tickEnded(AmmoDelta.EMPTY);
		attribution.damagedByMe(target);
		attribution.tickEnded(new AmmoDelta(
			java.util.Collections.singletonMap(ARROW, 12L), java.util.Collections.emptyMap()));

		assertEquals(target, attribution.getOwner());
		assertFalse(attribution.getWindow().isEmpty());
		assertEquals(1, attribution.getDamageEvidenceCount());

		plugin.shutDown();

		assertNull("no monster is being fought by a plugin that is off",
			attribution.getOwner());
		assertTrue(attribution.getWindow().isEmpty());
		assertEquals("and no NPC index survives to mean a different NPC later",
			0, attribution.getDamageEvidenceCount());
	}

	@Test
	public void shutDownDropsTheContainerBaseline()
	{
		GunnarsToolsPlugin plugin = new GunnarsToolsPlugin();
		plugin.startUp();

		ConsumptionMeter meter = plugin.getMeter();

		// Empty slots on purpose. The plugin's own filter reads the item cache off
		// the client, which no offline test has, and the meter skips an empty slot
		// before it asks — so this establishes a real baseline through the plugin's
		// real meter without needing a game. What is under test here is the
		// teardown, not the filter; AmmunitionTest owns that.
		meter.containerChanged(InventoryID.WORN, new Item[]{new Item(-1, 0), new Item(-1, 0)});
		meter.tickEnded();
		assertTrue("the baseline has to exist before clearing it means anything",
			meter.hasBaseline());

		plugin.shutDown();

		assertFalse("a baseline from one session must not be differenced against the next",
			meter.hasBaseline());
	}

	private static FoughtNpc spindel(int index)
	{
		return new FoughtNpc(index, SPINDEL, "Spindel", new int[]{130, 130, 130, 200, 1, 130});
	}

	private static AmmoTally spent(long arrows)
	{
		AmmoTally tally = new AmmoTally();
		tally.add(new AmmoDelta(
			java.util.Collections.singletonMap(ARROW, arrows), java.util.Collections.emptyMap()));
		return tally;
	}
}
