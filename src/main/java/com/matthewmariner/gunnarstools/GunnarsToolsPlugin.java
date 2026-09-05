package com.matthewmariner.gunnarstools;

import com.google.inject.Provides;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.IndexDataBase;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

/**
 * Gunnar's Tools — measures how much ammunition (arrows, bolts, runes) each kill
 * of a given monster actually costs, so a Wilderness Slayer trip can be packed
 * with enough and no more. Dying to a PKer keeps three items unskulled and none
 * skulled, and a stack of ammunition never occupies one of those slots, so every
 * arrow carried in and not fired is simply given away.
 *
 * <p><b>Measure, do not model.</b> The accuracy-times-damage-times-attack-speed
 * arithmetic that would predict consumption carries error bars wide enough to
 * make the answer useless, and it silently omits whatever the player's gear,
 * prayers and boosts are doing. Watching the ammunition stack go down is
 * automatically correct about all of it.
 *
 * <p>The measurement is {@link ConsumptionMeter} (what was spent),
 * {@link KillAttribution} (who it was spent on, and which deaths were the
 * player's) and {@link AmmoLedger} (the running record per monster and setup).
 * The answer is {@link ConsumptionEstimate} (the figure and its spread),
 * {@link TripPlanner} (what to bring) and {@link TripAdvisor} (which of those the
 * player is actually owed right now), drawn by {@link TripPanelOverlay} and
 * {@link BankWithdrawalOverlay}.
 *
 * <h2>The subject of the plan is chosen, not inherited from the last corpse</h2>
 *
 * <p>Until {@link PlanTarget} existed, this plugin's subject was "whatever you
 * last killed", which meant it had nothing to say until a kill had happened and
 * could never say anything about a monster you had not fought. Both of those are
 * the state a player is in at a bank with a fresh task, which is the exact moment
 * the answer is wanted. Now the subject is a pin, or the monster being fought, or
 * the last kill, in that order, and {@link AmmoArchive} means a previous session's
 * totals are there to answer with — as an estimate, labelled, never republished as
 * a measurement.
 *
 * <h2>And naming the subject is a separate problem from choosing it</h2>
 *
 * <p>{@link PlanTarget} made it possible to plan for a monster you were not
 * killing. It did not make it possible to <em>name</em> one that was not in front
 * of you: the only way to pick a subject was to shift-right-click a monster in the
 * world, so planning a trip required already standing next to the thing being
 * planned for. The question is asked at a bank, where there is nothing to
 * right-click.
 *
 * <p>{@link MonsterCatalogue} reads the game's own NPC list out of the client's
 * cache — a slice per tick, nothing bundled, nothing downloaded —
 * {@link MonsterIndex} makes it searchable by name, and
 * {@link MonsterLookupPanel} puts that in the sidebar. Where a name means several
 * monsters of different sizes, which is nineteen of Krystilia's thirty-six tasks,
 * every one of them is listed with its own hitpoints rather than one being chosen.
 *
 * <p>This class is deliberately thin. Every handler translates a RuneLite event
 * into a call on one of those and decides nothing itself, so that the decisions
 * all live somewhere a test can reach without a game client. The one piece of
 * state it owns is the cached advice below, and the reason it owns it is timing
 * rather than logic.
 */
@Slf4j
@PluginDescriptor(
	name = "Gunnar's Tools",
	description = "Estimates ammunition needed for a Wilderness Slayer trip by measuring your "
		+ "own consumption per kill",
	// No "ether" tag, deliberately. A charged wilderness weapon spends its
	// revenant ether from a charge counter rather than from a container, so
	// nothing this plugin measures can see it — see the README's limitations.
	// A search keyword is a promise, and that is one this cannot keep.
	tags = {"slayer", "wilderness", "ammo", "arrows", "bolts", "runes", "inventory"}
)
public class GunnarsToolsPlugin extends Plugin
{
	/** The menu entry that pins a monster. Client-side only; nothing reaches the server. */
	static final String PIN_OPTION = "Plan trip";

	/**
	 * The NPC archive inside the config index.
	 *
	 * <p>Nine, within index 2, which is what {@code Client.getIndexConfig()}
	 * returns. Both numbers are {@code net.runelite.cache}'s own
	 * {@code ConfigType.NPC} and {@code IndexType.CONFIGS}, checked against the
	 * cache library published alongside the client version this project pins rather
	 * than remembered.
	 */
	private static final int NPC_ARCHIVE = 9;

	@Inject
	Client client;

	@Inject
	GunnarsToolsConfig config;

	@Inject
	ConfigStore configStore;

	@Inject
	ClientThreadRunner clientThread;

	@Inject
	OverlayRegistry overlayRegistry;

	@Inject
	SidePanel sidePanel;

	@Inject
	NpcSource npcSource;

	@Inject
	TripPanelOverlay tripPanelOverlay;

	@Inject
	BankWithdrawalOverlay bankWithdrawalOverlay;

	/**
	 * Not private and not final, and both of those are deliberate.
	 *
	 * <p>The meter's filter reads the item cache off the client, so a meter built
	 * here cannot be fed a real item id with no game running — which would leave
	 * the single most important wiring line in the plugin, the one handing this
	 * tick's consumption to the attribution, provable only by reading it. A test
	 * substitutes a meter with its own filter and drives {@link #onGameTick}
	 * directly. That is the whole reason; nothing in production replaces it.
	 */
	ConsumptionMeter meter = new ConsumptionMeter(this::isConsumable);

	/**
	 * How an item id becomes a name for the sidebar's summary.
	 *
	 * <p>Not private and not final, for exactly the reason {@link #meter} is not:
	 * it reads the item cache off the client, so with no game running it cannot
	 * resolve anything, and the summary would be the one thing the sidebar draws
	 * that no offline test could reach. A test substitutes a function of its own;
	 * nothing in production replaces it.
	 *
	 * <p>It is also why the summary is composed here rather than in the panel.
	 * {@code Client.getItemDefinition} throws off the client thread — see
	 * {@link SidePanel} — and the panel's thread is Swing's.
	 */
	IntFunction<String> itemNames = this::nameOf;

	private final KillAttribution attribution = new KillAttribution();
	private final AmmoLedger ledger = new AmmoLedger();

	/**
	 * The game's own monster list, read a slice per tick so a name can be looked up
	 * without the monster being present.
	 *
	 * <p>This is the half of "choose the subject of the plan" that
	 * {@link PlanTarget} could not supply on its own. Separating the subject from
	 * the measurement made it possible to plan for a monster you were not killing;
	 * it did not make it possible to <em>name</em> one that was not in front of you,
	 * so the only way to choose was still to shift-right-click something in the
	 * world — at a bank, where there is nothing to right-click and the question is
	 * asked. See {@link MonsterCatalogue}.
	 */
	private final MonsterCatalogue catalogue = new MonsterCatalogue();

	/**
	 * What previous sessions left behind. Loaded once at {@link #startUp()} and
	 * rewritten after every kill, so a crash costs one monster's last few kills
	 * rather than the whole session.
	 *
	 * <p>Not final because it is replaced wholesale on load and on teardown, and
	 * replacing it is what makes {@code shutDown()} symmetric with a fresh install
	 * — an archive left populated would be folded into the next session's on top of
	 * whatever that one reloads.
	 */
	private AmmoArchive archive = new AmmoArchive();

	/**
	 * The answer the two overlays draw, recomputed when a kill, a config change, a
	 * change of target or a change of equipment makes it stale — never in a render
	 * loop.
	 *
	 * <p>Cached rather than derived on demand because {@code AGENTS.md} is
	 * explicit that per-frame work stays minimal, and building an answer sorts a
	 * sample array per item.
	 *
	 * <h2>One thread writes this field, and it took two goes to get there</h2>
	 *
	 * <p>This comment used to say that game ticks, config events and overlay
	 * rendering all run on the client thread, so there was nothing to
	 * synchronise. Ticks and rendering do. Config events do not:
	 * {@code ConfigManager.setConfiguration} posts {@code ConfigChanged} on the
	 * event bus synchronously, on whichever thread called it, and for the settings
	 * panel that thread is the Swing EDT.
	 *
	 * <p>The reply to that used to be an argument about safe publication, and it
	 * was correct as far as it went: a {@link TripAdvice} is immutable, every
	 * collection reachable from one is already wrapped by
	 * {@link java.util.Collections}' unmodifiable views, and a thread that sees the
	 * new object is guaranteed by JLS 17.5 to see everything reachable from it. A
	 * reader that has not seen the write yet draws the previous answer for a frame,
	 * which is indistinguishable from ordinary latency.
	 *
	 * <p>What that argument covers is handing the result over. It says nothing
	 * about <em>producing</em> it, and producing it is what grew teeth: choosing a
	 * target now walks {@link AmmoLedger}'s map, which the client thread inserts
	 * into the first time a new monster is fought. So {@link #onConfigChanged} does
	 * its work on the client thread instead — see {@link ClientThreadRunner} — and
	 * this field has one writer again.
	 *
	 * <p>The publication argument is still worth keeping, because it is what makes
	 * a reader on another thread safe at all. Anything later that mutates a
	 * published {@link TripAdvice} in place, or hands one a bare
	 * {@code ArrayList}, loses it without a compiler or a test saying so.
	 */
	private TripAdvice advice = TripAdvice.waitingFor(null, TripAdvice.Waiting.A_TARGET);

	/**
	 * The two things {@link #advice} was built about, kept so a tick can tell
	 * whether anything moved without rebuilding to find out.
	 *
	 * <p>Compared by reference on purpose. {@link KillAttribution} only replaces
	 * its owner when the player engages a different scene index, and the ledger
	 * only replaces a record when a different monster or setup is killed, so
	 * reference identity is exactly "the subject changed" and costs two
	 * comparisons a tick instead of a config read and a name search.
	 */
	private FoughtNpc adviceOwner;
	private NpcAmmoRecord adviceLastKill;

	/**
	 * What is in "Plan for" when it did not resolve to a monster, and null the rest
	 * of the time.
	 *
	 * <p>This is how the settings field points at the surface that can actually
	 * answer it. A config text field cannot draw a list, cannot offer a choice and
	 * cannot show its own validation, so the one thing it can usefully do with a name
	 * it failed on is hand it to the sidebar — which opens with that name already in
	 * the search box and the candidates underneath it. Without this the player is
	 * told to pick one in the side panel and then has to retype, from memory, the
	 * word that has already been established as the one they get wrong.
	 *
	 * <p>Volatile because it is written here on the client thread and read by
	 * {@link MonsterLookupPanel#onActivate()} on Swing's. A {@code String} reference
	 * is safely published by the write; there is nothing reachable from it to see
	 * half of.
	 */
	private volatile String unresolvedName;

	/**
	 * Whether {@link #startUp()} has run and {@link #shutDown()} has not yet
	 * followed it.
	 *
	 * <p>Read by nothing in the plugin itself. It exists so the lifecycle has one
	 * concrete, testable promise — shutdown leaves this in the state a fresh
	 * install would be in — and everything registered alongside it now has to
	 * hold to the same promise.
	 */
	private boolean active;

	@Override
	protected void startUp()
	{
		log.debug("Gunnar's Tools starting");
		overlayRegistry.add(tripPanelOverlay);
		overlayRegistry.add(bankWithdrawalOverlay);

		// Before the first rebuild, so the first thing the panel draws already has
		// last session's numbers in it. This is the whole point of the archive: the
		// answer exists before the first kill, not after it.
		archive = config.rememberBetweenSessions()
			? AmmoArchive.parse(config.archive())
			: new AmmoArchive();
		log.debug("Gunnar's Tools loaded {}", archive);

		// Before the first rebuild for the same reason the archive is: the sidebar
		// button is the only affordance in this plugin that announces itself, and a
		// player who does not know the lookup exists is the player whose complaint
		// produced it.
		if (config.showLookup())
		{
			sidePanel.show();
		}

		// Marshalled, and this line is a bug fix rather than a precaution.
		// PluginManager.startPlugin asserts it is on the Swing event dispatch thread
		// and calls startUp() straight from it — checked against the 1.12.38 client's
		// own bytecode, and true of shutDown() too. Rebuilding the plan now reads the
		// item cache to compose the sidebar's summary, and Client.getItemDefinition
		// throws IllegalStateException off the client thread in a shipped client. It
		// also walks the ledger, which is the same reason onConfigChanged marshals.
		// One tick's delay before the first answer appears, against an exception on
		// every enable.
		clientThread.run(this::rebuildPlan);
		active = true;
	}

	@Override
	protected void shutDown()
	{
		// Removed first and synchronously. An overlay left in the manager keeps
		// drawing, and the lines below are about to empty everything it draws from
		// underneath it.
		overlayRegistry.remove(tripPanelOverlay);
		overlayRegistry.remove(bankWithdrawalOverlay);

		// And the sidebar, unconditionally rather than under the same setting that
		// added it. A player who turns the lookup off and then disables the plugin
		// would otherwise leave a button behind pointing at a panel whose plugin is
		// gone — the teardown has to undo what happened, not what the settings
		// currently say should have.
		sidePanel.hide();

		// The session summary, which the log has carried since M1. Logged on the way
		// out rather than only per kill, because a per-kill line scrolls past and
		// this is the shape a reader actually wants: one line per monster, with the
		// sample count each figure rests on.
		log.debug("Gunnar's Tools stopping with {} record(s) measured", ledger.size());
		ledger.getRecords().forEach(record -> log.debug("  {}", record));

		// One line per thing startUp implicitly brought into being. The meter
		// holds a baseline that would otherwise be compared against a container
		// from a different session; the attribution holds an open window and a set
		// of NPC indices that will mean different NPCs by the time the plugin is
		// switched on again; the ledger holds the session's measurements, which
		// belong to the session; the archive is a copy of a config value that
		// startUp reloads; and the advice is a projection off all of them, so it
		// outlives its own evidence unless it goes too.
		//
		// The archive is deliberately *not* written here. Every kill already wrote
		// it, so there is nothing newer to save, and a teardown that performs I/O is
		// a teardown that can fail.
		meter.clear();
		attribution.reset();
		ledger.clear();
		archive = new AmmoArchive();

		// The monster list goes too. It is a reading of the cache rather than a
		// setting, and a plugin that was switched off may have been switched off
		// across a game update — a stale list would answer confidently about NPC ids
		// that have since moved.
		catalogue.clear();
		clearPlan();
		active = false;
	}

	/**
	 * @return true between a {@link #startUp()} and its matching
	 * {@link #shutDown()}, false otherwise — including before the first
	 * {@code startUp()}. Package-private: {@code GunnarsToolsPluginLifecycleTest}
	 * is the only reader outside this class.
	 */
	boolean isActive()
	{
		return active;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		final ItemContainer container = event.getItemContainer();
		final Item[] items = container == null ? null : container.getItems();
		meter.containerChanged(event.getContainerId(), items);

		if (event.getContainerId() == InventoryID.WORN)
		{
			equip(Loadout.of(items));
		}
	}

	/**
	 * The tick boundary, where everything is decided.
	 *
	 * <p>The order is the one {@link KillAttribution} documents: close the
	 * containers first so this tick's consumption exists, then hand it to the
	 * attribution, which resolves the tick's deaths against it.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!meter.hasBaseline())
		{
			primeContainers();
		}

		scanMonsters();

		if (tickEnded(meter.tickEnded()))
		{
			logAdvice();
		}
	}

	/**
	 * Reads one slice of the game's monster list.
	 *
	 * <p>On the tick because {@code Client.getNpcDefinition} refuses to run
	 * anywhere but the client thread, and a slice at a time because
	 * {@code AGENTS.md} does not permit scanning sixteen thousand of anything in one
	 * go. The whole sweep is four ticks and then this returns immediately forever —
	 * {@link MonsterCatalogue#advance} short-circuits once it is ready, so there is
	 * no flag here to get out of step with it.
	 *
	 * <p>Package-private so a test can drive the sweep to completion without
	 * manufacturing game ticks.
	 */
	void scanMonsters()
	{
		if (!config.showLookup() || catalogue.isReady())
		{
			return;
		}

		if (catalogue.advance(npcSource, MonsterCatalogue.SLICE) == MonsterCatalogue.State.READY)
		{
			log.debug("Gunnar's Tools read {}", catalogue.getIndex());

			// The list arriving can turn a name that matched nothing into a monster.
			// Without this the panel would go on saying "no such monster" about a name
			// it can now resolve, until something else happened to provoke a rebuild.
			rebuildPlan();
		}
		else
		{
			// The progress moved and nothing else did. Four of these a session, which
			// is what keeps a panel opened mid-sweep from showing a percentage frozen
			// at whatever it was when it was last drawn.
			sidePanel.refresh(LookupSummary.of(advice, itemNames));
		}
	}

	/**
	 * Resolves one tick's verdicts into the ledger and, if anything the answer
	 * depends on moved, into a fresh answer.
	 *
	 * <p>Split out from {@link #onGameTick} so it can be run with no client: the
	 * two lines left above it — priming the containers and resolving an item name
	 * for the log — are the only parts of a tick that read the game, and they are
	 * the only parts no offline test can reach.
	 *
	 * @return whether the tick produced a kill, and therefore a new measurement
	 */
	boolean tickEnded(AmmoDelta delta)
	{
		final List<Attribution> attributions = attribution.tickEnded(delta);
		boolean killed = false;
		int killedNpcId = -1;

		for (Attribution result : attributions)
		{
			ledger.apply(result);
			log.debug("{} -> {}", result.getKind(), ledger.get(result.getNpc().getId()));

			// Latched rather than assigned from the last verdict. A barrage resolves
			// as a kill followed by its co-victims' unattributed deaths, so the last
			// verdict of the tick is routinely not the kill. The id is safe to
			// overwrite because a tick produces at most one: KillAttribution fills a
			// single kill slot per tick, and everything else in the list is a death
			// it refused to price.
			if (result.getKind() == Attribution.Kind.KILL)
			{
				killed = true;
				killedNpcId = result.getNpc().getId();
			}
		}

		if (killed)
		{
			remember(killedNpcId);
		}

		// Only a kill or a change of subject changes the answer. An abandoned fight
		// and an unattributed death both move columns nothing published reads, and
		// rebuilding on every tick would sort every sample series sixty times a
		// minute for nothing.
		if (killed || attribution.getOwner() != adviceOwner
			|| ledger.getMostRecentKill() != adviceLastKill)
		{
			rebuildPlan();
		}
		return killed;
	}

	/**
	 * The dials changed, so the answer did.
	 *
	 * <p>Filtered to this plugin's own group. RuneLite posts every plugin's config
	 * changes on the same bus, and rebuilding on all of them would be a sort per
	 * keystroke in somebody else's settings panel.
	 *
	 * <p>The archive key is filtered out on top of that, and it is not an
	 * optimisation. Writing it goes through {@code ConfigManager}, which posts the
	 * change back on this same bus synchronously; a handler that rebuilt on it
	 * would run inside the write that provoked it, and any future rebuild that
	 * wrote anything would not terminate.
	 *
	 * <p><b>This handler arrives on the Swing EDT and does its work on the client
	 * thread.</b> {@code ConfigManager.setConfiguration} posts on whichever thread
	 * called it, and from the settings panel that is the EDT — while every other
	 * handler on this class, and both overlays, are the client thread. Publishing
	 * an immutable answer makes the <em>result</em> safe to hand over, and says
	 * nothing about the reading that produced it: choosing a target walks the
	 * ledger's map, and the client thread inserts into that map the first time a
	 * new monster is fought. So the whole body is marshalled rather than made
	 * thread-safe a field at a time; see {@link ClientThreadRunner}.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!GunnarsToolsConfig.GROUP.equals(event.getGroup())
			|| GunnarsToolsConfig.ARCHIVE.equals(event.getKey()))
		{
			return;
		}

		final boolean forgetEverything =
			GunnarsToolsConfig.REMEMBER_BETWEEN_SESSIONS.equals(event.getKey())
				&& !config.rememberBetweenSessions();

		final boolean lookupToggled = GunnarsToolsConfig.SHOW_LOOKUP.equals(event.getKey());

		clientThread.run(() ->
		{
			if (forgetEverything)
			{
				// The setting doubles as the reset, which is stated in its own
				// description. Both halves are needed: the in-memory copy is what the
				// panel reads, and the stored string is what the next session reads.
				archive = new AmmoArchive();
				configStore.write(GunnarsToolsConfig.ARCHIVE, "");
			}

			if (lookupToggled)
			{
				if (config.showLookup())
				{
					sidePanel.show();
				}
				else
				{
					// Both halves, and the second one is not tidiness. The monster list
					// is what "Plan for" resolves an unfought name through, so a
					// catalogue left behind would keep answering after the surface that
					// explains it had gone — and the setting's own description promises
					// the previous behaviour back, not a half of it.
					sidePanel.hide();
					catalogue.clear();
				}
			}

			rebuildPlan();
		});
	}

	/**
	 * An actor's health bar reached zero.
	 *
	 * <p>Two entirely different events share this one hook. The player's own
	 * death has to be caught here because it is the largest source of false
	 * consumption in the game: everything not kept hits the floor on a single
	 * tick, and a container diff cannot tell that from firing nine hundred arrows
	 * at once.
	 */
	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		final Actor actor = event.getActor();

		if (actor == client.getLocalPlayer())
		{
			// Both halves are needed. The attribution throws away the window and
			// the evidence; the meter forgets its baseline, because comparing a
			// post-respawn inventory against a pre-death one reports the whole kit
			// as consumed, and then reports restocking it as recovered.
			attribution.localPlayerDied();
			meter.invalidateBaseline();
			return;
		}

		if (!(actor instanceof NPC))
		{
			return;
		}

		final NPC npc = (NPC) actor;
		final FoughtNpc dead = FoughtNpc.of(npc.getIndex(), compositionOf(npc));
		if (dead != null)
		{
			attribution.npcDied(dead);
		}
	}

	/**
	 * An NPC left the scene.
	 *
	 * <p><b>Not a death.</b> This fires for a monster that wandered off, one that
	 * was standing in a region the client just unloaded, and every monster on the
	 * old world after a hop. It is only ever used to forget.
	 */
	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		attribution.npcDespawned(event.getNpc().getIndex());
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (event.getSource() != client.getLocalPlayer())
		{
			return;
		}
		final Actor target = event.getTarget();
		if (!(target instanceof NPC))
		{
			// Includes the null the game sends constantly between attacks.
			// KillAttribution ignores it on purpose; see its javadoc on stickiness.
			return;
		}
		final NPC npc = (NPC) target;
		attribution.interacting(FoughtNpc.of(npc.getIndex(), compositionOf(npc)));
	}

	/**
	 * A hitsplat landed. The ones that matter are hitsplats on an NPC that the
	 * game itself marks as the player's — {@code isMine()} is true for every
	 * "your damage" splat including the blue zero of a miss, so a fight in which
	 * nothing connected still proves the player was in it.
	 */
	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (!event.getHitsplat().isMine() || !(event.getActor() instanceof NPC))
		{
			return;
		}
		final NPC npc = (NPC) event.getActor();
		final FoughtNpc hit = FoughtNpc.of(npc.getIndex(), compositionOf(npc));
		if (hit != null)
		{
			attribution.damagedByMe(hit);
		}
	}

	/**
	 * Shift-right-clicking a monster offers to plan the trip for it.
	 *
	 * <p>The one affordance that lets a player choose a monster they are not
	 * fighting, which is the state they are in when the answer matters most — a
	 * fresh task, a monster never killed, and a bank trip to pack for. Reading the
	 * live NPC is also how the estimate gets its hitpoints without a bundled
	 * monster table; see {@link FoughtNpc}.
	 *
	 * <p>Behind shift, because an entry on every monster's menu is clutter on
	 * every monster's menu. {@link MenuAction#RUNELITE} is client-side: clicking it
	 * writes two settings and sends nothing to the server, so none of
	 * {@code AGENTS.md}'s menu restrictions are in play. Nothing existing is
	 * removed or reordered either.
	 */
	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (!client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return;
		}

		for (MenuEntry entry : event.getMenuEntries())
		{
			final NPC npc = entry.getNpc();
			if (npc == null)
			{
				continue;
			}

			final FoughtNpc chosen = FoughtNpc.of(npc.getIndex(), compositionOf(npc));
			if (chosen == null)
			{
				continue;
			}

			// One entry per menu, not one per matching option. A monster with
			// "Attack", "Examine" and a Slayer option would otherwise sprout three
			// identical "Plan trip" lines.
			//
			// Through getMenu() rather than the Client overload of the same name,
			// which 1.12.38 deprecates; the compiler says so with -Xlint:deprecation
			// and this project builds clean without it.
			client.getMenu().createMenuEntry(-1)
				.setOption(PIN_OPTION)
				.setTarget(entry.getTarget())
				.setType(MenuAction.RUNELITE)
				.onClick(clicked -> pin(chosen));
			return;
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			return;
		}

		// Loading, hopping, logging in and losing the connection all replace the
		// containers and the scene wholesale. Every NPC index the attribution
		// holds is about to belong to a different NPC, and the meter's baseline is
		// about to be compared against an inventory rebuilt from scratch.
		meter.invalidateBaseline();
		attribution.reset();
	}

	/**
	 * Pins a monster as the plan's subject, by writing the two settings that hold
	 * it.
	 *
	 * <p>Two rather than one because they answer different questions. The visible
	 * one is the name, so the settings panel shows what is pinned and blanking it
	 * unpins; the hidden one is the identity — id, name and hitpoints — so a
	 * monster pinned in the Wilderness is still plannable at a bank where nothing
	 * of it is on screen, and so a monster that has never been killed still has
	 * hitpoints to scale an estimate onto.
	 *
	 * <p>Package-private and taking a {@link FoughtNpc} rather than an NPC, so the
	 * whole of it is reachable from a test. The six lines above that read the live
	 * client are not, and that is the split every event handler in this class
	 * keeps.
	 */
	void pin(FoughtNpc npc)
	{
		pin(PlanTarget.of(npc, PlanTarget.Source.PINNED));
	}

	/**
	 * The same pin, from a monster that is not in the scene.
	 *
	 * <p>Split out so the sidebar lookup and the right-click menu entry write the
	 * same two settings by the same code rather than by two copies of it. A pin
	 * chosen from a list and a pin chosen from a corpse are the same fact.
	 *
	 * @param pinned null is a no-op, which is how a monster the plugin could not
	 *               read is skipped rather than substituted for
	 */
	void pin(@Nullable PlanTarget pinned)
	{
		if (pinned == null)
		{
			return;
		}

		log.debug("pinning {}", pinned);
		configStore.write(GunnarsToolsConfig.PINNED_TARGET, pinned.format());
		configStore.write(GunnarsToolsConfig.PLAN_FOR, pinned.getName());

		// The writes above post ConfigChanged, which rebuilds — in production. This
		// does not rely on that: a store is free to be asynchronous, and a pin that
		// only took effect on the next kill would look broken in exactly the
		// situation it exists for.
		rebuildPlan();
	}

	/**
	 * Plans for a monster the player picked out of the sidebar lookup.
	 *
	 * <p><b>Marshalled onto the client thread, and that is the whole reason this
	 * method exists rather than the panel calling {@link #pin(PlanTarget)}.</b> A
	 * Swing panel's listeners run on the event dispatch thread; {@code pin} rebuilds
	 * the plan, and rebuilding walks {@link AmmoLedger}'s map, which the client
	 * thread inserts into the first time a new monster is fought. That is exactly
	 * the race {@link #onConfigChanged} was already fixed for — see
	 * {@link ClientThreadRunner} — and a second door into the same map deserved the
	 * same lock rather than a second argument about why it was probably fine.
	 *
	 * <p>Which of the match's ids the plan is filed under is
	 * {@link TripAdvisor#preferMeasured}'s decision, made on the client thread here
	 * where the ledger is safe to read.
	 */
	void planFor(MonsterIndex.Match match)
	{
		clientThread.run(() -> pin(new PlanTarget(
			TripAdvisor.preferMeasured(match.getNpcIds(), ledger, archive, ledger.getEquipped()),
			match.getName(), match.getHitpoints(), PlanTarget.Source.PINNED)));
	}

	/**
	 * Unpins whatever was chosen, so the plan follows what the player is fighting
	 * again.
	 *
	 * <p>Both settings, because {@link GunnarsToolsConfig#pinnedTarget()} is only
	 * consulted when its name matches the visible field — leaving it behind is
	 * harmless but leaves a stale identity in the profile that the next matching
	 * name would silently adopt. Marshalled for the same reason as
	 * {@link #planFor}.
	 */
	void clearPin()
	{
		clientThread.run(() ->
		{
			configStore.write(GunnarsToolsConfig.PLAN_FOR, "");
			configStore.write(GunnarsToolsConfig.PINNED_TARGET, "");
			rebuildPlan();
		});
	}

	/**
	 * Shows the meter the containers the player is already carrying.
	 *
	 * <p>Called from {@link #onGameTick} for as long as the meter has no
	 * baseline, which covers the three occasions it needs to: the plugin being
	 * enabled mid-session, the first tick after a login, and the first tick after
	 * a hop or a region load invalidated the previous baseline. Without it the
	 * meter recovers on its own — a container joins the sum the first time it
	 * changes, and that one-off appearance lands in the disclosed gains column
	 * rather than the consumed one — but not before charging the player's spare
	 * arrows to the recovery column once.
	 *
	 * <p>There is deliberately no flag guarding the retry. Priming is idempotent,
	 * costs three cache reads, and a tick where none of the containers exist yet
	 * has to be retried rather than counted as done. The meter's own baseline is
	 * the condition, which also means {@code shutDown()} clearing the meter is the
	 * whole of the teardown for this — there is no second piece of state to
	 * forget to reset.
	 *
	 * <p>It is also where the worn equipment is read for the first time in a
	 * session, so a plugin enabled mid-trip files its first kill under the setup
	 * actually being worn rather than under "not read yet".
	 *
	 * <p>Along with the composition and hitsplat reads above, this is the part of
	 * the plugin that cannot be exercised offline.
	 */
	private void primeContainers()
	{
		for (int containerId : ConsumptionMeter.TRACKED_CONTAINERS)
		{
			final ItemContainer container = client.getItemContainer(containerId);
			if (container != null)
			{
				meter.containerChanged(containerId, container.getItems());
				if (containerId == InventoryID.WORN)
				{
					equip(Loadout.of(container.getItems()));
				}
			}
		}
	}

	/**
	 * The NPC's composition in its <em>current</em> form.
	 *
	 * <p>{@code getTransformedComposition()} resolves whichever varbit decides
	 * which of several forms an NPC is in, and returns null for an NPC with no
	 * transform to apply — most of them — so the plain composition is the
	 * fallback rather than the exception. Taking the id and the stats from
	 * whichever one of the two this returns is what keeps them describing the
	 * same monster; see {@link FoughtNpc#of(int, NPCComposition)}.
	 */
	@Nullable
	private static NPCComposition compositionOf(NPC npc)
	{
		final NPCComposition transformed = npc.getTransformedComposition();
		return transformed != null ? transformed : npc.getComposition();
	}

	/**
	 * Whether a change in this item's quantity is worth metering.
	 *
	 * <p>The lookup is here and the decision is in {@link Ammunition}, so the
	 * decision has a test and this has only a cache read to get wrong.
	 */
	private boolean isConsumable(int itemId)
	{
		final ItemComposition composition = client.getItemDefinition(itemId);
		return composition != null && Ammunition.isConsumable(composition.isStackable(), composition.getNote());
	}

	/**
	 * The player is wearing something different.
	 *
	 * <p>Nothing is discarded and nothing is reset. The ledger keys its records by
	 * monster and setup, so the kills measured on the previous weapon stay exactly
	 * where they were and are picked up again the moment it is re-equipped — which
	 * is what makes a special attack, or dying and re-gearing, cost nothing here.
	 * See {@link AmmoLedger}.
	 *
	 * <p>Package-private so a test can drive it without an equipment container.
	 */
	void equip(Loadout loadout)
	{
		if (loadout.equals(ledger.getEquipped()))
		{
			return;
		}

		// The ledger is the only place the worn setup is kept. This class used to
		// hold a copy of it and hand the same value to both, which was two fields
		// that could only ever be equal and a way for a later edit to make them
		// disagree — and they are compared against each other every time a record is
		// filed under one and read back under the other.
		log.debug("loadout {} -> {}", ledger.getEquipped(), loadout);
		ledger.equipped(loadout);
		rebuildPlan();
	}

	/**
	 * Folds a monster into the archive and writes it out.
	 *
	 * <p>{@link AmmoLedger#bestFor(int)} rather than the record this kill just
	 * touched, because the archive keeps one entry per monster and the record a
	 * kill touched is the record for whatever was equipped at the instant the
	 * monster died. A special attack landing the killing blow would otherwise
	 * replace a three-hundred-kill entry with a one-kill one.
	 *
	 * <p><b>And the same comparison has to reach across the restart, which is the
	 * half a review found missing.</b> {@code bestFor} only sees this session, so
	 * with the intra-session guard alone the first kill of session two replaced a
	 * three-hundred-monster entry with a one-kill record and the history was gone
	 * for good — the archive could never hold more than the current session had
	 * got to, which is the exact failure the guard was written to prevent, arrived
	 * at through the door it was not watching.
	 *
	 * <p>So a stored entry is only replaced by something at least as well
	 * evidenced. The exception is a change of setup: an entry measured on a
	 * different weapon is not a better reading of the weapon in hand however many
	 * monsters stand behind it, and the newest reading is the one most likely to
	 * describe the player as they are now.
	 */
	private void remember(int npcId)
	{
		if (!config.rememberBetweenSessions())
		{
			return;
		}

		final NpcAmmoRecord best = ledger.bestFor(npcId);
		if (best == null)
		{
			return;
		}

		final AmmoArchive.Entry stored = archive.get(npcId);
		if (stored != null
			&& stored.getMonsters() > best.getMonstersPriced()
			&& !Loadout.knownToDiffer(stored.getLoadout(), best.getLoadout()))
		{
			return;
		}

		archive.remember(best, best.getLoadout());
		configStore.write(GunnarsToolsConfig.ARCHIVE, archive.format());
	}

	/**
	 * Rebuilds the cached answer: who the plan is about, and what it says.
	 *
	 * <p>Package-private rather than private so the plugin tests can drive it
	 * without a game tick. The decisions inside it are all in {@link PlanTarget}
	 * and {@link TripAdvisor}; what is here is the reading of the settings and the
	 * caching.
	 *
	 * <p><b>Client thread only.</b> It walks {@link AmmoLedger}'s map, which the
	 * client thread inserts into, and it resolves item ids to names through the
	 * client's own cache, which refuses to run anywhere else. Every caller either is
	 * the client thread already — the tick, a menu click, an equipment change — or
	 * marshals through {@link ClientThreadRunner}, which is what {@link #startUp()},
	 * {@link #onConfigChanged} and {@link #planFor} do. There is no lock here; there
	 * is a single writer, kept single on purpose.
	 */
	void rebuildPlan()
	{
		advice = computeAdvice();

		// One notification, on every path out of the rebuild. The sidebar draws the
		// same answer and Swing does not repaint on somebody else's news; a return
		// that skipped this would leave the previous monster's numbers under the
		// current monster's name.
		sidePanel.refresh(LookupSummary.of(advice, itemNames));
	}

	/**
	 * The answer, and the two fields recording what it was built about.
	 *
	 * <p>Split from {@link #rebuildPlan()} so that every way of failing to resolve a
	 * name still ends up going through the one notification above. It assigns
	 * {@link #adviceOwner} and {@link #adviceLastKill} rather than returning them,
	 * which is not pure and is deliberate: the three have to move together or the
	 * tick's "did anything change?" comparison starts lying.
	 */
	private TripAdvice computeAdvice()
	{
		final String typed = config.planFor() == null ? "" : config.planFor().trim();
		final MonsterIndex index = catalogue.getIndex();

		PlanTarget pinned = null;
		if (!typed.isEmpty())
		{
			pinned = TripAdvisor.resolvePin(typed, PlanTarget.parse(config.pinnedTarget()),
				attribution.getOwner(), ledger, archive, index);

			if (pinned == null)
			{
				// Deliberately not a fallback. A name that matches nothing is a
				// mistake the player can fix in one edit, and quietly planning for a
				// different monster under the name they chose is the worst of the
				// available behaviours: it is wrong and it looks right. A name that
				// matches several is a different problem with a different fix, and
				// TripAdvisor.whyPinFailed is what tells them apart.
				adviceOwner = attribution.getOwner();
				adviceLastKill = ledger.getMostRecentKill();
				unresolvedName = typed;
				return TripAdvice.waitingFor(null,
					TripAdvisor.whyPinFailed(typed, index, config.showLookup()));
			}
		}

		unresolvedName = null;
		adviceOwner = attribution.getOwner();
		adviceLastKill = ledger.getMostRecentKill();

		final PlanTarget target = PlanTarget.resolve(pinned, adviceOwner, adviceLastKill);
		return TripAdvisor.advise(target, ledger, archive, ledger.getEquipped(), config.tripKills(),
			config.safetyMarginPercent(), config.estimateBeforeMeasuring());
	}

	/**
	 * The claim, in words, once per kill.
	 *
	 * <p>The overlay says the same thing in a corner of the screen; this is the
	 * version that survives in a log somebody can read afterwards, and it is the
	 * one that spells out which denominator and how many samples. It lives here
	 * rather than in {@link #rebuildPlan()} because resolving an item id to a name
	 * is a client read, and keeping every client read at the event edge is what
	 * lets the answer be rebuilt in a test with no game running.
	 */
	private void logAdvice()
	{
		if (!log.isDebugEnabled() || advice.getTarget() == null)
		{
			return;
		}

		final String monster = advice.getTarget().getName();
		if (advice.isMeasured())
		{
			final TripPlan headline = advice.getMeasured().get(0);
			log.debug("{}", headline.describe(monster, nameOf(headline.getItemId())));
		}
		else if (advice.isEstimated())
		{
			final ProjectedNeed headline = advice.getProjected().get(0);
			log.debug("{}", headline.describe(monster, nameOf(headline.getItemId())));
		}
	}

	private String nameOf(int itemId)
	{
		final ItemComposition composition = client.getItemDefinition(itemId);
		return composition == null ? "item " + itemId : composition.getName();
	}

	private void clearPlan()
	{
		adviceOwner = null;
		adviceLastKill = null;
		unresolvedName = null;
		advice = TripAdvice.waitingFor(null, TripAdvice.Waiting.A_TARGET);
	}

	/** Everything the overlays draw. Never null. */
	TripAdvice getAdvice()
	{
		return advice;
	}

	/**
	 * What this session has measured about the monster the plan is about, on the
	 * setup currently worn, or null when it has measured nothing.
	 *
	 * <p>Not the same question as "is the published answer a measurement". A record
	 * can exist and still lose to a better-evidenced remembered figure; see
	 * {@link TripAdvisor}. {@link TripAdvice#isMeasured()} is the one to ask about
	 * what is on screen.
	 */
	@Nullable
	NpcAmmoRecord getPlanSubject()
	{
		final PlanTarget target = advice.getTarget();
		return target == null ? null : ledger.get(target.getNpcId(), ledger.getEquipped());
	}

	/** What to bring, biggest first. Never null; empty when the answer is an estimate. */
	List<TripPlan> getPlan()
	{
		return advice.getMeasured();
	}

	/** Item id to quantity, for {@link BankWithdrawalOverlay}. Gross, never net. */
	Map<Integer, Long> getWithdrawals()
	{
		return advice.getWithdrawals();
	}

	/**
	 * What the player is already carrying of each metered item, for the bank
	 * highlight's subtraction. Empty until the containers have been read once.
	 */
	Map<Integer, Long> getCarried()
	{
		return meter.getHoldings();
	}

	/** The session's measurements. Package-private; nothing outside reads it. */
	AmmoLedger getLedger()
	{
		return ledger;
	}

	/** What previous sessions left. Package-private, for tests. */
	AmmoArchive getArchive()
	{
		return archive;
	}

	/**
	 * The game's own monster list and how far through reading it the plugin is.
	 *
	 * <p>Read by the sidebar lookup on the Swing thread. Safe for the reason
	 * {@link MonsterCatalogue} documents: nothing partial is ever visible and what
	 * is published is immutable.
	 */
	MonsterCatalogue getCatalogue()
	{
		return catalogue;
	}

	/**
	 * The name in "Plan for" that did not resolve, or null when it did.
	 *
	 * <p>Read on the Swing thread by {@link MonsterLookupPanel#onActivate()}, which
	 * puts it in the search box so that opening the sidebar after a failed setting
	 * shows the candidates for what was actually typed. See {@link #unresolvedName}.
	 */
	@Nullable
	String getUnresolvedName()
	{
		return unresolvedName;
	}

	/** Package-private, for {@code GunnarsToolsPluginLifecycleTest}. */
	KillAttribution getAttribution()
	{
		return attribution;
	}

	/** Package-private, for {@code GunnarsToolsPluginLifecycleTest}. */
	ConsumptionMeter getMeter()
	{
		return meter;
	}

	@Provides
	GunnarsToolsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GunnarsToolsConfig.class);
	}

	/**
	 * The plugin's only writing path — see {@link ConfigStore} for why it goes
	 * through an interface rather than straight at {@link ConfigManager}.
	 */
	@Provides
	ConfigStore provideConfigStore(ConfigManager configManager)
	{
		return (key, value) -> configManager.setConfiguration(GunnarsToolsConfig.GROUP, key, value);
	}

	/**
	 * How work that arrived on another thread gets back onto the client's — see
	 * {@link ClientThreadRunner}.
	 */
	@Provides
	ClientThreadRunner provideClientThreadRunner(ClientThread clientThread)
	{
		return clientThread::invoke;
	}

	/**
	 * The lookup's place in the sidebar — see {@link SidePanel}.
	 *
	 * <p>The button is built once and added and removed, rather than rebuilt on
	 * every toggle. {@code ClientToolbar} keys its navigation off the button
	 * instance, so a second one built on the way back in would leave the first
	 * behind.
	 */
	@Provides
	SidePanel provideSidePanel(ClientToolbar clientToolbar, MonsterLookupPanel lookupPanel)
	{
		final NavigationButton button = NavigationButton.builder()
			.tooltip("Gunnar's Tools — monster lookup")
			.icon(ImageUtil.loadImageResource(MonsterLookupPanel.class, "lookup_icon.png"))
			.priority(6)
			.panel(lookupPanel)
			.build();

		return new SidePanel()
		{
			@Override
			public void show()
			{
				clientToolbar.addNavigation(button);
			}

			@Override
			public void hide()
			{
				clientToolbar.removeNavigation(button);
			}

			@Override
			public void refresh(List<LookupSummary.Line> answer)
			{
				lookupPanel.accept(answer);
			}
		};
	}

	/**
	 * Where the monster list is read from — see {@link NpcSource}.
	 *
	 * <p><b>Two client calls and one archive id, and every one of them is checked
	 * against the client this project pins.</b> {@code getIndexConfig()} is index 2
	 * (CONFIGS) and 9 is the NPC archive within it, which is
	 * {@code net.runelite.cache}'s own {@code IndexType.CONFIGS} and
	 * {@code ConfigType.NPC}; and the client's own {@code NPCComposition.get(id)}
	 * bottoms out in {@code loadData(9, id)} on that same archive, so
	 * {@link Client#getNpcDefinition(int)} is not a different route to the data —
	 * it is the same route with the game's own decoder on the end of it, which is
	 * why this plugin ships no decoder of its own.
	 *
	 * <p>{@code getFileIds} returns null rather than an empty array for an archive
	 * id it does not have, and it hands back its <em>internal</em> array rather than
	 * a copy. Both are handled: null is passed straight through as "not yet", and
	 * {@link MonsterCatalogue} clones what it keeps.
	 *
	 * <p>{@code getNpcDefinition} throws off the client thread — an
	 * {@code IllegalStateException} in production, where assertions are disabled —
	 * so everything that calls this runs from the game tick.
	 */
	@Provides
	NpcSource provideNpcSource(Client gameClient)
	{
		return new NpcSource()
		{
			@Override
			public int[] npcIds()
			{
				final IndexDataBase configs = gameClient.getIndexConfig();
				return configs == null ? null : configs.getFileIds(NPC_ARCHIVE);
			}

			@Override
			public FoughtNpc npc(int npcId)
			{
				return FoughtNpc.of(FoughtNpc.NO_INDEX, gameClient.getNpcDefinition(npcId));
			}
		};
	}

	/**
	 * The plugin's only overlay registration path — see {@link OverlayRegistry}
	 * for why it goes through an interface rather than straight at
	 * {@link OverlayManager}.
	 */
	@Provides
	OverlayRegistry provideOverlayRegistry(OverlayManager overlayManager)
	{
		return new OverlayRegistry()
		{
			@Override
			public void add(Overlay overlay)
			{
				overlayManager.add(overlay);
			}

			@Override
			public void remove(Overlay overlay)
			{
				overlayManager.remove(overlay);
			}
		};
	}
}
