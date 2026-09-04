package com.matthewmariner.gunnarstools;

import java.util.Collections;
import net.runelite.api.Item;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.client.events.ConfigChanged;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The wiring between a kill, the user's RuneLite profile, and the answer waiting
 * at the bank next time.
 *
 * <p>{@code AmmoArchiveTest} owns the format and the parsing. What is here is
 * everything that only exists once the plugin is holding both ends: that a kill
 * actually writes, that the write goes to the key the plugin reads back, that
 * startup restores it, that the persistence switch is also the reset, and that
 * writing a config value from inside a config handler cannot loop.
 *
 * <p>The plugin is built by hand rather than through Guice, exactly as the
 * lifecycle test builds it: {@code startUp()} and {@code shutDown()} are
 * {@code protected} and same-package test code can call them, and neither touches
 * the injected client.
 */
public class GunnarsToolsPersistenceTest
{
	private static final int ARROW = 892;
	private static final int SPINDEL = 5265;
	private static final int VENENATIS = 6610;

	private static final int WEAPON_SLOT = EquipmentInventorySlot.WEAPON.getSlotIdx();
	private static final int AMMO_SLOT = EquipmentInventorySlot.AMMO.getSlotIdx();

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

	private static FoughtNpc npc(int index, int id, String name, int hitpoints)
	{
		return new FoughtNpc(index, id, name, new int[]{130, 130, 130, hitpoints, 1, 130});
	}

	private static AmmoDelta spent(long arrows)
	{
		return new AmmoDelta(Collections.singletonMap(ARROW, arrows), Collections.emptyMap());
	}

	private static AmmoTally tally(long arrows)
	{
		AmmoTally out = new AmmoTally();
		out.add(spent(arrows));
		return out;
	}

	/** One complete fight, resolved through the plugin's own tick path. */
	private static void kill(GunnarsToolsPlugin plugin, FoughtNpc target, long arrows)
	{
		plugin.getAttribution().interacting(target);
		plugin.tickEnded(AmmoDelta.EMPTY);
		plugin.getAttribution().damagedByMe(target);
		plugin.getAttribution().npcDied(target);
		plugin.tickEnded(spent(arrows));
	}

	private static Item[] worn(int weaponId, int ammoId)
	{
		final Item[] items = new Item[Math.max(WEAPON_SLOT, AMMO_SLOT) + 1];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = new Item(-1, 0);
		}
		items[WEAPON_SLOT] = new Item(weaponId, 1);
		items[AMMO_SLOT] = new Item(ammoId, 500);
		return items;
	}

	// --- a kill writes ---------------------------------------------------------

	@Test
	public void everyKillWritesTheArchiveToTheKeyThePluginReadsBack()
	{
		// Not on shutdown. A session that ends in a crash, a disconnect or a client
		// closed from the taskbar never runs a teardown, and those are ordinary ways
		// for a Wilderness trip to end.
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();

		kill(plugin, npc(40, SPINDEL, "Spindel", 200), 25L);

		assertEquals("one write, to the archive key",
			Collections.singletonList(GunnarsToolsConfig.ARCHIVE + "=" + config.archive()),
			config.writes());

		AmmoArchive stored = AmmoArchive.parse(config.archive());
		assertEquals(1, stored.get(SPINDEL).getMonsters());
		assertEquals(Long.valueOf(25L), stored.get(SPINDEL).getConsumed().get(ARROW));
		assertEquals("Spindel", stored.get(SPINDEL).getName());
		assertEquals("with the hitpoints a later estimate needs to scale from",
			200, stored.get(SPINDEL).getHitpoints());
	}

	@Test
	public void aSecondSessionStartsWithTheFirstSessionsAnswerAlreadyInHand()
	{
		// The whole point. Standing at a bank on a fresh task with nothing killed
		// this session, there is a number.
		GunnarsToolsPlugin first = plugin();
		first.startUp();
		kill(first, npc(40, SPINDEL, "Spindel", 200), 25L);
		first.shutDown();

		GunnarsToolsPlugin second = plugin();
		second.startUp();

		assertEquals(1, second.getArchive().size());

		// Nothing measured this session, so it can only be an estimate — and it is
		// labelled as one.
		second.getAttribution().interacting(npc(41, SPINDEL, "Spindel", 200));
		second.tickEnded(AmmoDelta.EMPTY);

		TripAdvice advice = second.getAdvice();
		assertTrue(advice.isEstimated());
		assertFalse("a restored figure is never republished as a measurement",
			advice.isMeasured());
		assertEquals(Long.valueOf(2750L), advice.getWithdrawals().get(ARROW));
	}

	@Test
	public void startUpDoesNotLoadTheArchiveWhenPersistenceIsOff()
	{
		config.withArchive("1;5265,Spindel,200,4,-1,-1,892:100")
			.withRememberBetweenSessions(false);

		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();

		assertTrue(plugin.getArchive().isEmpty());
	}

	@Test
	public void nothingIsWrittenWhenPersistenceIsOff()
	{
		config.withRememberBetweenSessions(false);
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();

		kill(plugin, npc(40, SPINDEL, "Spindel", 200), 25L);

		assertTrue("no write of any kind", config.writes().isEmpty());
		assertTrue(config.archive().isEmpty());
	}

	@Test
	public void turningPersistenceOffForgetsWhatIsAlreadyStored()
	{
		// The setting doubles as the reset, which is what its own description
		// promises. Both halves have to go: the in-memory copy is what the panel
		// reads and the stored string is what the next session reads.
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();
		kill(plugin, npc(40, SPINDEL, "Spindel", 200), 25L);
		assertFalse(config.archive().isEmpty());

		config.withRememberBetweenSessions(false);
		plugin.onConfigChanged(configChanged(GunnarsToolsConfig.REMEMBER_BETWEEN_SESSIONS));

		assertTrue("the copy the panel reads", plugin.getArchive().isEmpty());
		assertTrue("and the copy the next session reads", config.archive().isEmpty());
	}

	@Test
	public void aChangeToTheArchiveKeyDoesNotRebuildAnything()
	{
		// Writing the archive posts the change back on the same bus, synchronously,
		// inside the write that provoked it. A handler that acted on it would run
		// nested, and any future rebuild that wrote anything would not terminate.
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();
		kill(plugin, npc(40, SPINDEL, "Spindel", 200), 25L);

		final TripAdvice before = plugin.getAdvice();
		config.withTripKills(999);
		plugin.onConfigChanged(configChanged(GunnarsToolsConfig.ARCHIVE));

		org.junit.Assert.assertSame("the archive key is not a dial", before, plugin.getAdvice());
	}

	/**
	 * A config change does its work on the client thread, not on the thread it
	 * arrived on.
	 *
	 * <p>{@code ConfigManager.setConfiguration} posts synchronously on whichever
	 * thread called it, which from the settings panel is the Swing EDT — while
	 * every other handler on the plugin, and both overlays, are the client thread.
	 * Choosing a target walks the ledger's map, and the client thread inserts into
	 * that map the first time a new monster is fought, so a handler that did its
	 * work in place would iterate a {@code LinkedHashMap} another thread was
	 * structurally modifying.
	 *
	 * <p>Asserted by counting rather than by racing: a threading bug is not
	 * reproducible in a unit test, so what is pinned is that the whole body goes
	 * through the marshaller and none of it happens beside it.
	 */
	@Test
	public void aConfigChangeIsMarshalledOntoTheClientThread()
	{
		final int[] marshalled = {0};
		final boolean[] ranInside = {false};

		GunnarsToolsPlugin plugin = plugin();
		plugin.clientThread = task ->
		{
			marshalled[0]++;
			ranInside[0] = true;
			task.run();
			ranInside[0] = false;
		};
		plugin.startUp();
		config.withTripKills(250);

		plugin.onConfigChanged(configChanged(GunnarsToolsConfig.TRIP_KILLS));

		assertEquals("the handler marshals exactly once", 1, marshalled[0]);
		assertFalse(ranInside[0]);

		// And nothing was done outside it: with the marshaller declining to run the
		// task at all, the dials must not have taken effect.
		GunnarsToolsPlugin ignored = plugin();
		ignored.clientThread = task ->
		{
		};
		ignored.startUp();
		ignored.getLedger().apply(Attribution.kill(npc(40, SPINDEL, "Spindel", 200), tally(25L), 0));
		ignored.rebuildPlan();
		final TripAdvice before = ignored.getAdvice();

		config.withTripKills(999);
		ignored.onConfigChanged(configChanged(GunnarsToolsConfig.TRIP_KILLS));

		org.junit.Assert.assertSame("every part of the handler is inside the marshaller",
			before, ignored.getAdvice());
	}

	@Test
	public void theArchiveResetIsMarshalledTooRatherThanDoneOnTheWayIn()
	{
		// Clearing the archive from the arriving thread would race the client
		// thread's own write of it: the reset lands, the client thread finishes a
		// kill it had already started, and the old archive is written straight back
		// over the top. Both halves of the reset go through the marshaller.
		GunnarsToolsPlugin plugin = plugin();
		plugin.clientThread = task ->
		{
		};
		plugin.startUp();
		plugin.getLedger().apply(Attribution.kill(npc(40, SPINDEL, "Spindel", 200), tally(25L), 0));
		config.withArchive("1;5265,Spindel,200,4,-1,-1," + ARROW + ":100")
			.withRememberBetweenSessions(false);

		plugin.onConfigChanged(configChanged(GunnarsToolsConfig.REMEMBER_BETWEEN_SESSIONS));

		assertFalse("nothing was erased outside the marshaller", config.archive().isEmpty());
	}

	@Test
	public void shutDownForgetsTheArchiveItLoaded()
	{
		// It is a copy of a config value that startUp reloads. One left populated
		// would be folded into the next session's on top of whatever that one reads.
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();
		kill(plugin, npc(40, SPINDEL, "Spindel", 200), 25L);
		assertFalse(plugin.getArchive().isEmpty());

		plugin.shutDown();

		assertTrue(plugin.getArchive().isEmpty());
		assertFalse("but what was stored stays stored", config.archive().isEmpty());
	}

	@Test
	public void aSpecialAttackKillDoesNotReplaceALongSeriesInTheArchive()
	{
		// The failure that keying by setup would otherwise push into the archive:
		// one kill landing while a different weapon happened to be equipped is the
		// newest reading and by far the worst one.
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();
		plugin.equip(new Loadout(861, ARROW));

		for (int i = 0; i < 20; i++)
		{
			kill(plugin, npc(40 + i, SPINDEL, "Spindel", 200), 25L);
		}
		assertEquals(20, AmmoArchive.parse(config.archive()).get(SPINDEL).getMonsters());

		plugin.equip(new Loadout(1305, ARROW));
		kill(plugin, npc(90, SPINDEL, "Spindel", 200), 1L);

		assertEquals("the twenty-kill reading is the one worth keeping",
			20, AmmoArchive.parse(config.archive()).get(SPINDEL).getMonsters());
		assertEquals(Long.valueOf(500L),
			AmmoArchive.parse(config.archive()).get(SPINDEL).getConsumed().get(ARROW));
	}

	@Test
	public void theFirstKillOfASessionDoesNotWipeTheSessionBefore()
	{
		// The bug this test exists for was silent and total: bestFor only sees the
		// session ledger, so the first kill of session two handed the archive a
		// one-kill record and the archive replaced three hundred monsters with it.
		// The history was gone permanently, and the stored figure could never hold
		// more than whatever the current session had got to — which is the exact
		// failure the intra-session guard was written to prevent, reached through
		// the door it was not watching.
		GunnarsToolsPlugin first = plugin();
		first.startUp();
		first.equip(Loadout.of(worn(861, ARROW)));
		for (int i = 0; i < 30; i++)
		{
			kill(first, npc(40 + i, SPINDEL, "Spindel", 200), 25L);
		}
		assertEquals(30, AmmoArchive.parse(config.archive()).get(SPINDEL).getMonsters());
		first.shutDown();

		GunnarsToolsPlugin second = plugin();
		second.startUp();
		second.equip(Loadout.of(worn(861, ARROW)));
		kill(second, npc(90, SPINDEL, "Spindel", 200), 40L);

		assertEquals("thirty monsters is not replaced by one",
			30, AmmoArchive.parse(config.archive()).get(SPINDEL).getMonsters());
		assertEquals(Long.valueOf(750L),
			AmmoArchive.parse(config.archive()).get(SPINDEL).getConsumed().get(ARROW));
	}

	@Test
	public void aSessionThatOutgrowsTheStoredFigureReplacesIt()
	{
		// The other side of the same guard, and the one that stops it freezing the
		// archive forever. A refusal to downgrade must not become a refusal to
		// update.
		GunnarsToolsPlugin first = plugin();
		first.startUp();
		first.equip(Loadout.of(worn(861, ARROW)));
		kill(first, npc(40, SPINDEL, "Spindel", 200), 25L);
		kill(first, npc(41, SPINDEL, "Spindel", 200), 25L);
		first.shutDown();

		GunnarsToolsPlugin second = plugin();
		second.startUp();
		second.equip(Loadout.of(worn(861, ARROW)));
		kill(second, npc(90, SPINDEL, "Spindel", 200), 40L);
		assertEquals("one is fewer than two, so the stored figure stands",
			2, AmmoArchive.parse(config.archive()).get(SPINDEL).getMonsters());

		kill(second, npc(91, SPINDEL, "Spindel", 200), 40L);

		assertEquals("two is not fewer than two, so the newer reading wins",
			2, AmmoArchive.parse(config.archive()).get(SPINDEL).getMonsters());
		assertEquals(Long.valueOf(80L),
			AmmoArchive.parse(config.archive()).get(SPINDEL).getConsumed().get(ARROW));
	}

	@Test
	public void aChangeOfWeaponReplacesAStoredFigureHoweverWellEvidencedItIs()
	{
		// The exception to the refusal, and the reason it is an exception. Three
		// hundred monsters measured on a shortbow is not a better reading of a
		// crossbow than four monsters measured on the crossbow; it is a reading of
		// something else. The newest reading is the one that describes the player as
		// they are now.
		GunnarsToolsPlugin first = plugin();
		first.startUp();
		first.equip(Loadout.of(worn(861, ARROW)));
		for (int i = 0; i < 30; i++)
		{
			kill(first, npc(40 + i, SPINDEL, "Spindel", 200), 25L);
		}
		first.shutDown();

		GunnarsToolsPlugin second = plugin();
		second.startUp();
		second.equip(Loadout.of(worn(21902, 9144)));
		kill(second, npc(90, SPINDEL, "Spindel", 200), 40L);

		AmmoArchive.Entry stored = AmmoArchive.parse(config.archive()).get(SPINDEL);
		assertEquals(1, stored.getMonsters());
		assertEquals(new Loadout(21902, 9144), stored.getLoadout());
	}

	@Test
	public void aRememberedFigureOnOtherGearDoesNotSuppressThisWeaponsMeasurement()
	{
		// The mirror image of the bug above, in the panel rather than the store.
		// Three hundred remembered shortbow monsters outnumber a fresh fifty-kill
		// crossbow series on raw count, and the comparison used to be on raw count —
		// so the panel showed a shortbow estimate and hid a real measurement of the
		// weapon in the player's hands. That is the averaging failure Loadout exists
		// to prevent, reached from the other side.
		GunnarsToolsPlugin first = plugin();
		first.startUp();
		first.equip(Loadout.of(worn(861, ARROW)));
		for (int i = 0; i < 30; i++)
		{
			kill(first, npc(40 + i, SPINDEL, "Spindel", 200), 25L);
		}
		first.shutDown();

		GunnarsToolsPlugin second = plugin();
		second.startUp();
		second.equip(Loadout.of(worn(21902, 9144)));
		config.withSafetyMargin(0);
		kill(second, npc(90, SPINDEL, "Spindel", 200), 40L);

		assertTrue("one crossbow kill beats thirty shortbow ones about a crossbow",
			second.getAdvice().isMeasured());
		assertEquals(Long.valueOf(4000L), second.getWithdrawals().get(ARROW));
	}

	// --- the pin ---------------------------------------------------------------

	@Test
	public void pinningAMonsterWritesBothHalvesAndRepointsThePlan()
	{
		// The affordance that lets a player plan for a monster they are not
		// fighting, which is the state they are in when the answer matters most.
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();

		plugin.pin(npc(40, VENENATIS, "Venenatis", 850));

		assertEquals("Venenatis", config.planFor());
		assertEquals("6610,Venenatis,850", config.pinnedTarget());
		assertEquals(VENENATIS, plugin.getAdvice().getTarget().getNpcId());
		assertEquals(PlanTarget.Source.PINNED, plugin.getAdvice().getTarget().getSource());
	}

	@Test
	public void theMenuEntryIsCalledWhatTheReadmeSaysItIs()
	{
		// The one part of the pin a player has to find on their own. The README
		// tells them to shift-right-click and choose this exact wording, and the
		// menu is the only place they will look for it.
		assertEquals("Plan trip", GunnarsToolsPlugin.PIN_OPTION);
	}

	@Test
	public void aPinOutranksTheMonsterInFrontOfYou()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();
		plugin.pin(npc(40, VENENATIS, "Venenatis", 850));

		plugin.getAttribution().interacting(npc(41, SPINDEL, "Spindel", 200));
		plugin.tickEnded(AmmoDelta.EMPTY);

		assertEquals("a player who says what they are going out for has said it",
			VENENATIS, plugin.getAdvice().getTarget().getNpcId());
	}

	@Test
	public void aPinSurvivesAShutdownAndAFreshStart()
	{
		GunnarsToolsPlugin first = plugin();
		first.startUp();
		first.pin(npc(40, VENENATIS, "Venenatis", 850));
		first.shutDown();

		GunnarsToolsPlugin second = plugin();
		second.startUp();

		assertEquals(VENENATIS, second.getAdvice().getTarget().getNpcId());
		assertEquals("with the hitpoints an estimate needs",
			850, second.getAdvice().getTarget().getHitpoints());
	}

	@Test
	public void clearingTheVisibleFieldClearsThePin()
	{
		// One control, not two. The hidden companion is only ever consulted when its
		// name matches what is in the visible field.
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();
		plugin.pin(npc(40, VENENATIS, "Venenatis", 850));

		config.withPlanFor("");
		plugin.onConfigChanged(configChanged(GunnarsToolsConfig.PLAN_FOR));

		assertNull("nothing pinned, nothing fought, nothing killed",
			plugin.getAdvice().getTarget());
		assertEquals(TripAdvice.Waiting.A_TARGET, plugin.getAdvice().getWaitingFor());
	}

	@Test
	public void aTypedNameThatMatchesNothingIsReportedRatherThanIgnored()
	{
		// Falling back to the last kill would plan for a different monster under the
		// name the player chose: wrong, and it looks right.
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();
		kill(plugin, npc(40, SPINDEL, "Spindel", 200), 25L);

		config.withPlanFor("Zulrah");
		plugin.onConfigChanged(configChanged(GunnarsToolsConfig.PLAN_FOR));

		assertEquals(TripAdvice.Waiting.UNKNOWN_MONSTER, plugin.getAdvice().getWaitingFor());
		assertNull(plugin.getAdvice().getTarget());
		assertTrue(plugin.getWithdrawals().isEmpty());
	}

	@Test
	public void aTypedNameResolvesOnceTheMonsterHasBeenMeasured()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();
		config.withPlanFor("spindel");
		plugin.onConfigChanged(configChanged(GunnarsToolsConfig.PLAN_FOR));
		assertEquals(TripAdvice.Waiting.UNKNOWN_MONSTER, plugin.getAdvice().getWaitingFor());

		kill(plugin, npc(40, SPINDEL, "Spindel", 200), 25L);

		assertEquals(SPINDEL, plugin.getAdvice().getTarget().getNpcId());
		assertEquals(PlanTarget.Source.PINNED, plugin.getAdvice().getTarget().getSource());
	}

	// --- gear ------------------------------------------------------------------

	@Test
	public void theWornContainerIsWhatDecidesWhichRecordAKillLandsIn()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();

		plugin.equip(Loadout.of(worn(861, ARROW)));
		kill(plugin, npc(40, SPINDEL, "Spindel", 200), 25L);

		plugin.equip(Loadout.of(worn(21902, 9144)));
		kill(plugin, npc(41, SPINDEL, "Spindel", 200), 40L);

		assertEquals(2, plugin.getLedger().size());
		assertEquals(25.0d,
			plugin.getLedger().get(SPINDEL, Loadout.of(worn(861, ARROW)))
				.estimate(ARROW).getPerMonster(), 1e-9d);
	}

	@Test
	public void changingWeaponsDemotesTheAnswerToAnEstimateRatherThanReprintingIt()
	{
		// The honest outcome of a gear change: what was measured is still true and
		// still there, and it is no longer a measurement of the weapon in hand.
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();
		plugin.equip(Loadout.of(worn(861, ARROW)));
		kill(plugin, npc(40, SPINDEL, "Spindel", 200), 25L);
		assertTrue(plugin.getAdvice().isMeasured());

		plugin.equip(Loadout.of(worn(21902, 9144)));

		assertFalse("no measurement exists on the crossbow", plugin.getAdvice().isMeasured());
		assertTrue("but there is still an answer", plugin.getAdvice().isEstimated());
		assertEquals(ProjectedNeed.Basis.LAST_SESSION_OTHER_GEAR,
			plugin.getAdvice().getProjected().get(0).getBasis());
	}

	@Test
	public void anEquipmentEventThatChangesNothingRebuildsNothing()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();
		plugin.equip(Loadout.of(worn(861, ARROW)));
		kill(plugin, npc(40, SPINDEL, "Spindel", 200), 25L);

		final TripAdvice before = plugin.getAdvice();
		plugin.equip(Loadout.of(worn(861, ARROW)));

		org.junit.Assert.assertSame("firing arrows is not a gear change",
			before, plugin.getAdvice());
	}

	// --- the cold start --------------------------------------------------------

	@Test
	public void aMonsterNeverFoughtGetsAnAnswerFromOneThatWas()
	{
		// The complaint that started this work, answered: click a monster you have
		// never killed and there is a number, before anything dies.
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();
		config.withSafetyMargin(0);
		plugin.equip(Loadout.of(worn(861, ARROW)));

		for (int i = 0; i < 10; i++)
		{
			kill(plugin, npc(40 + i, SPINDEL, "Spindel", 200), 25L);
		}

		plugin.getAttribution().interacting(npc(90, VENENATIS, "Venenatis", 850));
		plugin.tickEnded(AmmoDelta.EMPTY);

		TripAdvice advice = plugin.getAdvice();
		assertEquals(VENENATIS, advice.getTarget().getNpcId());
		assertTrue(advice.isEstimated());
		assertNotNull(advice.getWithdrawals().get(ARROW));

		// 250 arrows bought 10 × 200 = 2,000 hitpoints. Venenatis is 850, which is
		// 106.25 arrows and rounds up to 107; a hundred of her is 10,625.
		assertEquals(Long.valueOf(10625L), advice.getWithdrawals().get(ARROW));
	}

	@Test
	public void withEstimatesOffTheColdStartSaysSoRatherThanGoingBlank()
	{
		GunnarsToolsPlugin plugin = plugin();
		plugin.startUp();
		plugin.equip(Loadout.of(worn(861, ARROW)));
		kill(plugin, npc(40, SPINDEL, "Spindel", 200), 25L);

		config.withEstimateBeforeMeasuring(false);
		plugin.onConfigChanged(configChanged(GunnarsToolsConfig.ESTIMATE_BEFORE_MEASURING));

		plugin.getAttribution().interacting(npc(90, VENENATIS, "Venenatis", 850));
		plugin.tickEnded(AmmoDelta.EMPTY);

		assertEquals(TripAdvice.Waiting.ESTIMATES_OFF, plugin.getAdvice().getWaitingFor());
		assertEquals("and still says which monster it is refusing to guess about",
			VENENATIS, plugin.getAdvice().getTarget().getNpcId());
	}

	private static ConfigChanged configChanged(String key)
	{
		ConfigChanged event = new ConfigChanged();
		event.setGroup(GunnarsToolsConfig.GROUP);
		event.setKey(key);
		return event;
	}
}
