package com.matthewmariner.gunnarstools;

import com.google.inject.Provides;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

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
 * <p>Milestone 1 is the measurement and nothing else — no projection, no bank
 * highlighting, no interface. The record lives in memory for the session and
 * reports itself to the debug log as each kill lands. The pieces are
 * {@link ConsumptionMeter} (what was spent), {@link KillAttribution} (who it was
 * spent on, and which deaths were the player's) and {@link AmmoLedger} (the
 * running record per monster).
 *
 * <p>This class is deliberately thin. Every handler translates a RuneLite event
 * into a call on one of those three and decides nothing itself, so that the
 * decisions all live somewhere a test can reach without a game client.
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
	@Inject
	Client client;

	private final ConsumptionMeter meter = new ConsumptionMeter(this::isConsumable);
	private final KillAttribution attribution = new KillAttribution();
	private final AmmoLedger ledger = new AmmoLedger();

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
		active = true;
	}

	@Override
	protected void shutDown()
	{
		// The session summary, which in M1 is the whole of the user interface.
		// Logged on the way out rather than only per kill, because a per-kill line
		// scrolls past and this is the shape a reader actually wants: one line per
		// monster, with the sample count each figure rests on.
		log.debug("Gunnar's Tools stopping with {} monster(s) measured", ledger.size());
		ledger.getRecords().forEach(record -> log.debug("  {}", record));

		// One line per thing startUp implicitly brought into being. The meter
		// holds a baseline that would otherwise be compared against a container
		// from a different session; the attribution holds an open window and a set
		// of NPC indices that will mean different NPCs by the time the plugin is
		// switched on again; the ledger holds the session's measurements, which
		// belong to the session.
		meter.clear();
		attribution.reset();
		ledger.clear();
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
		meter.containerChanged(event.getContainerId(), container == null ? null : container.getItems());
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

		final List<Attribution> attributions = attribution.tickEnded(meter.tickEnded());
		for (Attribution result : attributions)
		{
			ledger.apply(result);
			log.debug("{} -> {}", result.getKind(), ledger.get(result.getNpc().getId()));
		}
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

	/** The session's measurements. Package-private; nothing outside reads it in M1. */
	AmmoLedger getLedger()
	{
		return ledger;
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
}
