package com.matthewmariner.gunnarstools;

import java.util.Collections;
import java.util.List;
import net.runelite.client.events.ConfigChanged;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The whole point of the feature, end to end and with no game client: a monster
 * chosen by name, at a bank, with nothing to right-click.
 *
 * <p>Before this the only way to name the subject of a plan was to shift-right-
 * click a monster in the world, which meant standing next to the thing the plan
 * was about — and "how many arrows should I bring?" is a question asked at a bank.
 * These tests drive the sweep, the resolution and the pin through the plugin's own
 * methods, so what is being checked is the wiring rather than the pieces.
 */
public class MonsterLookupTest
{
	private static final int SPIDER = 3019;
	private static final int GIANT_SPIDER = 59;
	private static final int SPINDEL = 5265;
	private static final int VENENATIS = 6610;
	private static final int ARROW = 892;

	private static final Loadout SHORTBOW = new Loadout(861, ARROW);

	private final FakeConfig config = new FakeConfig();
	private final RecordingSidePanel panel = new RecordingSidePanel();

	private FakeNpcSource source = new FakeNpcSource()
		.with(SPIDER, "Spider", 2)
		.with(GIANT_SPIDER, "Giant spider", 5)
		.with(SPINDEL, "Spindel", 200)
		.with(VENENATIS, "Venenatis", 850)
		.unnamed(9999);

	private GunnarsToolsPlugin plugin()
	{
		final GunnarsToolsPlugin plugin = new GunnarsToolsPlugin();
		plugin.config = config;
		plugin.configStore = config;
		plugin.clientThread = Runnable::run;
		plugin.sidePanel = panel;
		plugin.npcSource = source;
		plugin.itemNames = itemId -> itemId == ARROW ? "Rune arrow" : "item " + itemId;
		plugin.overlayRegistry = new RecordingOverlays();
		plugin.tripPanelOverlay = new TripPanelOverlay(plugin, config, null);
		plugin.bankWithdrawalOverlay = new BankWithdrawalOverlay(plugin, config);
		return plugin;
	}

	/** Runs the sweep to completion, however many slices that takes. */
	private static MonsterIndex read(GunnarsToolsPlugin plugin)
	{
		for (int slice = 0; slice < 20 && !plugin.getCatalogue().isReady(); slice++)
		{
			plugin.scanMonsters();
		}
		return plugin.getCatalogue().getIndex();
	}

	private static ConfigChanged configChanged(String key)
	{
		ConfigChanged event = new ConfigChanged();
		event.setGroup(GunnarsToolsConfig.GROUP);
		event.setKey(key);
		return event;
	}

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

	// --- the sidebar's lifecycle -----------------------------------------------

	@Test
	public void theLookupIsInTheSidebarWhileThePluginIsOn()
	{
		GunnarsToolsPlugin plugin = plugin();

		assertFalse("nothing is registered before startUp", panel.shown());

		plugin.startUp();
		assertTrue("the one affordance that announces itself has to be there", panel.shown());

		plugin.shutDown();
		assertFalse("shutdown leaves nothing registered", panel.shown());
		assertEquals(1, panel.hideCount());
	}

	@Test
	public void theLookupCanBeTurnedOff()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.config = config.withShowLookup(false);

		plugin.startUp();

		assertFalse(panel.shown());
	}

	/**
	 * The teardown undoes what happened, not what the settings currently say should
	 * have happened. A player who turns the lookup off and then disables the plugin
	 * would otherwise leave a button behind.
	 */
	@Test
	public void shutDownTakesTheButtonAwayEvenWhenTheSettingIsAlreadyOff()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();
		assertTrue(panel.shown());

		plugin.config = config.withShowLookup(false);
		plugin.shutDown();

		assertFalse(panel.shown());
	}

	@Test
	public void shutDownForgetsTheMonsterList()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();
		assertNotNull("the list has to be there before the teardown means anything",
			read(plugin));

		plugin.shutDown();

		assertNull("a list read before a game update must not answer after one",
			plugin.getCatalogue().getIndex());
		assertFalse(plugin.getCatalogue().isReady());
	}

	// --- the sweep -------------------------------------------------------------

	@Test
	public void theGameOwnMonsterListIsReadWithoutAnyMonsterBeingPresent()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();

		MonsterIndex index = read(plugin);

		assertNotNull(index);
		assertEquals("four monsters and not the unnamed id beside them", 4, index.size());
		assertEquals(850, index.resolve("Venenatis").get(0).getHitpoints());
	}

	@Test
	public void theSweepStopsWhenTheLookupIsSwitchedOff()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.config = config.withShowLookup(false);
		plugin.startUp();

		plugin.scanMonsters();

		assertEquals("a lookup nobody can see costs nothing to keep", 0, source.reads());
		assertNull(plugin.getCatalogue().getIndex());
	}

	@Test
	public void aSweepThatFindsNothingIsRetriedRatherThanBelieved()
	{
		source = source.withoutIds();
		GunnarsToolsPlugin plugin = plugin();
		plugin.npcSource = source;
		plugin.startUp();

		plugin.scanMonsters();
		assertNull(plugin.getCatalogue().getIndex());

		source.nowAvailable();

		assertNotNull("the client had not brought the group in yet; that is not a failure",
			read(plugin));
	}

	// --- naming a monster you have never met -----------------------------------

	@Test
	public void aNameNothingHasEverBeenMeasuredForNowResolvesToARealMonster()
	{
		// The state a player is in at a bank with a fresh task: nothing killed, nothing
		// remembered, nothing on screen.
		GunnarsToolsPlugin plugin = plugin();
		plugin.config = config.withPlanFor("Venenatis");
		plugin.startUp();

		assertEquals("before the list is read the honest report is about the list",
			TripAdvice.Waiting.MONSTER_LIST, plugin.getAdvice().getWaitingFor());

		read(plugin);

		PlanTarget target = plugin.getAdvice().getTarget();
		assertNotNull(target);
		assertEquals(VENENATIS, target.getNpcId());
		assertEquals("and its own size, off the cache, with nothing bundled", 850,
			target.getHitpoints());
		assertEquals(PlanTarget.Source.PINNED, target.getSource());
	}

	/**
	 * Nineteen of Krystilia's thirty-six tasks are names more than one monster
	 * answers to. A settings field has nowhere to offer a choice, so it says there
	 * is one instead of making it.
	 */
	@Test
	public void anUmbrellaNameIsReportedRatherThanGuessedAt()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.config = config.withPlanFor("Spider");
		plugin.startUp();

		// Two monsters called exactly "Spider", 425 hitpoints apart.
		source.with(SPIDER + 1, "Spider", 850);
		plugin.getCatalogue().clear();
		read(plugin);

		assertNull(plugin.getAdvice().getTarget());
		assertEquals(TripAdvice.Waiting.AMBIGUOUS_MONSTER, plugin.getAdvice().getWaitingFor());
	}

	/**
	 * The report, end to end: he typed "Dagganoth" into the settings field, and what
	 * came back was nothing.
	 *
	 * <p>It still does not resolve to one monster, and it must not: three Kings, the
	 * ordinary Dagannoths and the spawns all answer to what he meant. What changed is
	 * that the field now knows that, and says which of its failures this is.
	 */
	@Test
	public void theMisspeltNameFromTheReportIsAChoiceRatherThanSilence()
	{
		source.with(2265, "Dagannoth Rex", 255)
			.with(2266, "Dagannoth Prime", 255)
			.with(2267, "Dagannoth Supreme", 255)
			.with(2243, "Dagannoth", 70)
			.with(2256, "Dagannoth spawn", 10);

		GunnarsToolsPlugin plugin = plugin();
		plugin.config = config.withPlanFor("Dagganoth");
		plugin.startUp();
		MonsterIndex index = read(plugin);

		assertEquals(TripAdvice.Waiting.AMBIGUOUS_MONSTER, plugin.getAdvice().getWaitingFor());
		assertEquals("and the panel has all five of them to offer, misspelt query and all",
			5, index.search("dagganoth", 20).getTotal());
		assertEquals("with the field's own text carried across to the search box",
			"Dagganoth", plugin.getUnresolvedName());
	}

	@Test
	public void aTypoWithOnlyOneAnswerIsResolvedRatherThanRefused()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.config = config.withPlanFor("Venenatsi");
		plugin.startUp();
		read(plugin);

		assertEquals(VENENATIS, plugin.getAdvice().getTarget().getNpcId());
		assertNull("nothing is left over to carry to the panel", plugin.getUnresolvedName());
	}

	@Test
	public void aNameNothingIsCloseToIsStillAMonsterThatDoesNotExist()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.config = config.withPlanFor("Zulrah");
		plugin.startUp();
		read(plugin);

		assertEquals(TripAdvice.Waiting.UNKNOWN_MONSTER, plugin.getAdvice().getWaitingFor());
	}

	@Test
	public void turningTheLookupOffPutsTheOldBehaviourBackRatherThanHalfOfIt()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.config = config.withPlanFor("Venenatis");
		plugin.startUp();
		read(plugin);
		assertNotNull(plugin.getAdvice().getTarget());

		plugin.config = config.withShowLookup(false);
		plugin.onConfigChanged(configChanged(GunnarsToolsConfig.SHOW_LOOKUP));

		// Not an assertion that this is nice; it is what the setting's own description
		// promises, and it is only true if the catalogue went with the button. The
		// report is UNKNOWN_MONSTER rather than MONSTER_LIST because no list is coming:
		// with the lookup off there is nothing to wait for.
		assertNull(plugin.getCatalogue().getIndex());
		assertEquals(TripAdvice.Waiting.UNKNOWN_MONSTER, plugin.getAdvice().getWaitingFor());
	}

	// --- picking one out of the list -------------------------------------------

	@Test
	public void pickingAMonsterInThePanelPlansForIt()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();
		MonsterIndex index = read(plugin);

		plugin.planFor(index.resolve("Spindel").get(0));

		assertEquals("Spindel", config.planFor());
		assertEquals("5265,Spindel,200", config.pinnedTarget());
		assertEquals(SPINDEL, plugin.getAdvice().getTarget().getNpcId());
		assertEquals(PlanTarget.Source.PINNED, plugin.getAdvice().getTarget().getSource());
	}

	@Test
	public void clearingThePinGoesBackToFollowingTheFight()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();
		MonsterIndex index = read(plugin);
		plugin.planFor(index.resolve("Spindel").get(0));

		plugin.clearPin();

		assertEquals("", config.planFor());
		assertEquals("both halves, so no stale identity is left for the next matching name",
			"", config.pinnedTarget());
		assertEquals(TripAdvice.Waiting.A_TARGET, plugin.getAdvice().getWaitingFor());
	}

	/**
	 * Records are keyed by monster and loadout, and one monster placed in several
	 * regions is several ids. Filing the plan under the wrong one of those hands
	 * back an estimate while the player's own hundred kills sit one id to the left.
	 */
	@Test
	public void aPickPrefersTheIdThePlayerHasActuallyMeasured()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();

		// Three ids, one name, one size — folded into a single row in the panel.
		source.with(700, "Bandit", 60).with(701, "Bandit", 60).with(702, "Bandit", 60);
		plugin.getCatalogue().clear();
		MonsterIndex index = read(plugin);

		plugin.equip(SHORTBOW);
		plugin.getLedger().apply(Attribution.kill(npc(40, 702, "Bandit", 60), spent(20L), 0));

		MonsterIndex.Match bandit = index.resolve("Bandit").get(0);
		assertEquals(3, bandit.getVariants());

		plugin.planFor(bandit);

		assertEquals("the id with the measurement, not the lowest one",
			702, plugin.getAdvice().getTarget().getNpcId());
		assertTrue("which is the whole reason it matters", plugin.getAdvice().isMeasured());
	}

	@Test
	public void aPickWithNoEvidenceAnywhereIsStillDeterministic()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();

		source.with(702, "Bandit", 60).with(700, "Bandit", 60).with(701, "Bandit", 60);
		plugin.getCatalogue().clear();
		MonsterIndex index = read(plugin);

		plugin.planFor(index.resolve("Bandit").get(0));

		assertEquals("the lowest id, whatever order the sweep found them in",
			700, plugin.getAdvice().getTarget().getNpcId());
	}

	// --- the panel is told when the answer moves -------------------------------

	@Test
	public void everyRebuildTellsThePanelWhatToDraw()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();
		int afterStart = panel.refreshes().size();
		assertTrue("startUp builds an answer, so the panel hears about it", afterStart > 0);

		read(plugin);
		plugin.planFor(new MonsterIndex.Match("Spindel", 200,
			Collections.singletonList(SPINDEL), MonsterIndex.Tier.EXACT));

		assertTrue("picking a monster is a change the panel has to hear about",
			panel.refreshes().size() > afterStart);

		List<LookupSummary.Line> drawn = panel.latest();
		assertEquals("Spindel", drawn.get(0).getLeft());
		assertEquals("200 hp", drawn.get(0).getRight());
	}

	@Test
	public void aNameThatFailedToResolveStillReachesThePanel()
	{
		// The path that used to return early. A rebuild that skipped the notification
		// would leave the previous monster's numbers under the new monster's name.
		GunnarsToolsPlugin plugin = plugin();
		plugin.config = config.withPlanFor("Nothing at all");
		plugin.startUp();

		assertEquals(TripAdvice.Waiting.MONSTER_LIST.getHeadline(),
			panel.latest().get(0).getLeft());
	}
}
