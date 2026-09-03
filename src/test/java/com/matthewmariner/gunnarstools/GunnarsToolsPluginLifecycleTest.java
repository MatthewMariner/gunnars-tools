package com.matthewmariner.gunnarstools;

import java.util.Arrays;
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
 * <p>The collaborators are loaded up with real state before shutdown here
 * on purpose. A teardown test that shuts down an empty plugin passes whether or
 * not the teardown does anything at all, which is the shape of fake test this
 * repository is trying not to accumulate.
 */
public class GunnarsToolsPluginLifecycleTest
{
	private static final int ARROW = 11;
	private static final int SPINDEL = 5265;

	private final RecordingOverlays overlays = new RecordingOverlays();

	/**
	 * A plugin with the two injected collaborators {@code startUp()} needs.
	 *
	 * <p>The overlays are built with null client-side dependencies, which is safe
	 * and is checked rather than assumed: {@code Overlay}'s constructors only
	 * initialise their own fields and store the plugin, and
	 * {@code WidgetItemOverlay}'s adds a draw hook to a list of its own — verified
	 * against the pinned 1.12.38 jar. Nothing here renders, so nothing here
	 * dereferences them.
	 */
	private GunnarsToolsPlugin plugin()
	{
		final GunnarsToolsPlugin plugin = new GunnarsToolsPlugin();
		plugin.config = new FakeConfig();
		plugin.overlayRegistry = overlays;
		plugin.tripPanelOverlay = new TripPanelOverlay(plugin, plugin.config, null);
		plugin.bankWithdrawalOverlay = new BankWithdrawalOverlay(plugin, plugin.config);
		return plugin;
	}

	@Test
	public void startUpAndShutDownAreSymmetric()
	{
		GunnarsToolsPlugin plugin = plugin();

		assertFalse("must not be active before startUp", plugin.isActive());

		plugin.startUp();
		assertTrue("must be active after startUp", plugin.isActive());

		plugin.shutDown();
		assertFalse("shutDown must leave it inactive again", plugin.isActive());
	}

	@Test
	public void startUpIsIdempotentAndShutDownStillClears()
	{
		GunnarsToolsPlugin plugin = plugin();

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
		GunnarsToolsPlugin plugin = plugin();
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
		GunnarsToolsPlugin plugin = plugin();
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
		GunnarsToolsPlugin plugin = plugin();
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

	/**
	 * Both overlays are registered on startup and both are gone after shutdown.
	 *
	 * <p>An overlay left in the manager keeps drawing, and what it would be drawing
	 * is a trip plan built from a ledger the same {@code shutDown()} has just
	 * emptied. Asserted on identity rather than on a count, so removing the panel
	 * twice and leaving the bank highlight behind cannot pass.
	 */
	@Test
	public void bothOverlaysAreRegisteredOnStartUpAndGoneAfterShutDown()
	{
		GunnarsToolsPlugin plugin = plugin();

		assertTrue("nothing is registered before startUp", overlays.live().isEmpty());

		plugin.startUp();
		assertEquals("the panel and the bank highlight, and nothing else",
			Arrays.asList(plugin.tripPanelOverlay, plugin.bankWithdrawalOverlay), overlays.live());

		plugin.shutDown();
		assertTrue("shutdown must leave nothing registered", overlays.live().isEmpty());
	}

	/**
	 * The cached projection does not outlive the measurements it was derived from.
	 *
	 * <p>It is a separate field from the ledger, so clearing the ledger does not
	 * clear it, and a plan left behind would be drawn by an overlay against a
	 * monster the plugin no longer has a record of.
	 */
	@Test
	public void shutDownDropsTheCachedPlan()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();

		plugin.getLedger().apply(Attribution.kill(spindel(40), spent(30)));
		plugin.rebuildPlan();

		assertEquals("the plan has to be there before the teardown means anything",
			1, plugin.getPlan().size());
		assertEquals(SPINDEL, plugin.getPlanSubject().getNpcId());
		assertFalse(plugin.getWithdrawals().isEmpty());

		plugin.shutDown();

		assertTrue("a projection must not outlive its evidence", plugin.getPlan().isEmpty());
		assertTrue(plugin.getWithdrawals().isEmpty());
		assertNull(plugin.getPlanSubject());
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
