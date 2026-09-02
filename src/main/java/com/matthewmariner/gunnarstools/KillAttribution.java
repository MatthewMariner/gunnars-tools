package com.matthewmariner.gunnarstools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Decides which monster a tick's ammunition belongs to, and which deaths were
 * the player's kills.
 *
 * <p>This is the correctness decision the rest of the plugin hangs off, so the
 * reasoning is written out rather than implied.
 *
 * <h2>A despawn is not a death</h2>
 *
 * <p>{@code NpcDespawned} fires whenever an NPC leaves the client's scene, which
 * happens for a monster that died, a monster that wandered out of range, a
 * monster that was there when the region unloaded, and every monster on the old
 * world when the player hops. Treating despawn as death would count the entire
 * Wilderness as kills. It is used here only to <em>forget</em> things: the
 * damage evidence for that scene index, and the open window if the despawned
 * monster owned it.
 *
 * <h2>What {@code ActorDeath} actually means</h2>
 *
 * <p>RuneLite posts {@code ActorDeath} from the actor's combat-info hook, when a
 * health-bar update arrives reporting zero health, once per life. That is a
 * precise statement and it is worth being precise about what it does and does
 * not imply:
 *
 * <ul>
 *   <li>It fires for <em>any</em> actor whose health bar the client is
 *       maintaining, not only the player's target. In multi-combat Wilderness —
 *       which is where this plugin is used — other people's kills land in the
 *       same event stream as yours.</li>
 *   <li>It does not fire for a monster the client never received a zero-health
 *       update for. That is rare and is not compensated for here; see
 *       {@link Attribution.Kind#ABANDONED} for how it surfaces instead of being
 *       guessed at.</li>
 *   <li>It fires for the local player's own death too. Handled separately, and
 *       it has to be: dying drops the inventory, and the resulting container
 *       diff is the largest false "consumption" available anywhere in the
 *       game.</li>
 * </ul>
 *
 * <h2>The two conditions for "my kill"</h2>
 *
 * <p>A death becomes a kill only if <b>both</b> hold:
 *
 * <ol>
 *   <li><b>The player damaged it.</b> Proven by a hitsplat on that NPC for which
 *       {@code Hitsplat.isMine()} is true. That flag is the game's own
 *       statement about whose damage a hitsplat is — it covers the blue
 *       zero of a miss as well as real damage, so a fight in which every shot
 *       missed still registers. It does not cover poison or venom ticks, which
 *       the game colours as neither party's; that costs nothing here, because
 *       the evidence is kept for the whole of the NPC's life and anything the
 *       player poisoned they first hit.</li>
 *   <li><b>It was the monster the player was fighting</b> — the owner of the
 *       currently open consumption window. Without this, an area spell that
 *       damages four monsters would credit four kills against one window, three
 *       of them with no ammunition in them, and drag the per-kill average
 *       toward zero. Those deaths are still counted, as
 *       {@link Attribution.Kind#UNATTRIBUTED_DEATH}, so the loss is visible.</li>
 * </ol>
 *
 * <p>Condition 1 without condition 2 is a real kill this class refuses to price.
 * Condition 2 without condition 1 is somebody else finishing a monster the
 * player had merely clicked on. Neither is a sample.
 *
 * <h2>Everything is decided at the tick boundary</h2>
 *
 * <p>Hitsplats, deaths, despawns and interaction changes all arrive while the
 * client processes one tick's packets, and their order within that tick is not
 * something a plugin may rely on. A one-shot kill in particular can deliver the
 * killing hitsplat and the death in either order. So none of the event handlers
 * on this class decide anything: they append to buffers, and
 * {@link #tickEnded(AmmoDelta)} resolves the whole tick in one fixed order —
 * the player's own death first, then this tick's consumption, then deaths, then
 * despawns, then interaction changes. Almost no ordering within a tick is
 * load-bearing, which removes an entire class of intermittent, unreproducible
 * miscount — with one exception, below.
 *
 * <p><b>The exception:</b> {@link #damagedByMe(FoughtNpc)} decides at event
 * time, not at the tick boundary, which monster's hitsplat opens a window
 * when none is open — whichever one the method sees first. If two of the
 * player's own hitsplats land on different monsters this tick and no window
 * is open yet, arrival order within the tick picks the winner, and that
 * choice is not revisited when the tick resolves. This is reachable when a
 * multi-target attack is the first action after a kill (the window closed
 * with the previous kill), or when the plugin is enabled while a
 * multi-target fight is already under way. On a Slayer task the two monsters
 * usually share an id, so the record is identical whichever one wins; only a
 * cross-id area attack under those conditions puts the cost on the wrong
 * record.
 *
 * <p>Two consequences of that order are deliberate. Consumption is added
 * <em>before</em> deaths resolve, so the shot that killed the monster is
 * counted against the monster it killed. And an interaction change is applied
 * <em>after</em>, so a monster that dies on the same tick the player clicks the
 * next one still owns the window when its death is judged.
 *
 * <p>The price of applying interaction changes last is one attack's worth of
 * ammunition at each target switch: if the player clicks a new monster and the
 * game fires at it on that same tick, that shot is charged to the previous
 * target. It is one shot, only at a switch, and in the case this plugin is
 * built for — a slayer task, the same monster over and over — the two monsters
 * share an id and the error is exactly zero. The alternative ordering loses a
 * whole kill instead of one shot, which is the worse trade.
 *
 * <h2>Engagement is sticky</h2>
 *
 * <p>{@code InteractingChanged} reports the target going to null constantly —
 * between attacks, when a monster dies, when the player is walking. Clearing
 * the window on a null target would drop the ammunition spent on a fight in the
 * gaps within that fight. So null is ignored, and the window changes hands only
 * when the player engages a <em>different</em> monster, or when the current one
 * dies or despawns.
 */
public final class KillAttribution
{
	/**
	 * The monster the open window belongs to, or null when the player is not
	 * fighting anything the plugin knows about. Null is also the guard that
	 * keeps banking, eating and dropping out of the record: a delta arriving
	 * with no owner is discarded.
	 */
	private FoughtNpc owner;

	/** Ammunition spent since the window opened. */
	private final AmmoTally window = new AmmoTally();

	/**
	 * Scene indices of NPCs the player has landed a hitsplat on. Entries are
	 * removed on death and on despawn, which is what keeps index reuse from
	 * crediting a fresh NPC with the previous occupant's evidence.
	 */
	private final Set<Integer> damagedByMe = new LinkedHashSet<>();

	private final List<FoughtNpc> deathsThisTick = new ArrayList<>();
	private final Set<Integer> despawnsThisTick = new LinkedHashSet<>();

	private FoughtNpc pendingEngagement;
	private boolean localPlayerDied;

	/**
	 * The player started interacting with a monster.
	 *
	 * <p>A null target is ignored rather than treated as disengagement — see the
	 * class javadoc on stickiness.
	 */
	public void interacting(@Nullable FoughtNpc npc)
	{
		if (npc == null)
		{
			return;
		}
		pendingEngagement = npc;
	}

	/**
	 * A hitsplat the game attributes to the player landed on this monster.
	 *
	 * <p>Also opens a window if none is open. The player's interaction target is
	 * the primary way a window opens, but it is not the only one — a monster
	 * attacked by an autocast the interaction never registered, or one engaged
	 * before the plugin was enabled, would otherwise have its whole fight
	 * discarded. Only when nothing is open, so a multi-target spell cannot pull
	 * the window away from the monster the player is actually on.
	 */
	public void damagedByMe(FoughtNpc npc)
	{
		damagedByMe.add(npc.getIndex());
		if (owner == null && pendingEngagement == null)
		{
			pendingEngagement = npc;
		}
	}

	/** An NPC's health bar reached zero. Judged at the tick boundary, not here. */
	public void npcDied(FoughtNpc npc)
	{
		deathsThisTick.add(npc);
	}

	/** An NPC left the scene. Not a death; see the class javadoc. */
	public void npcDespawned(int npcIndex)
	{
		despawnsThisTick.add(npcIndex);
	}

	/**
	 * The local player died. Everything in flight is void — the container diff
	 * for this tick is an inventory hitting the floor, not ammunition spent.
	 */
	public void localPlayerDied()
	{
		localPlayerDied = true;
	}

	/**
	 * Resolves one game tick.
	 *
	 * @param delta what the containers did this tick, from
	 *              {@link ConsumptionMeter#tickEnded()}
	 * @return every verdict the tick produced, in the order they were decided.
	 * Usually empty.
	 */
	public List<Attribution> tickEnded(AmmoDelta delta)
	{
		final List<Attribution> out = new ArrayList<>();

		if (localPlayerDied)
		{
			// Deliberately before everything else and deliberately total. The
			// tick the player dies on carries a container diff the size of their
			// whole kit; there is no part of it worth keeping, and there is no
			// monster left to attribute it to either.
			reset();
			return out;
		}

		// 1. This tick's consumption, charged to whoever owns the window. No
		//    owner means the player was not fighting, which is what banking,
		//    eating, dropping and looting all look like from here.
		if (owner != null)
		{
			window.add(delta);
		}

		// 2. Deaths.
		for (FoughtNpc dead : deathsThisTick)
		{
			final boolean iDamagedIt = damagedByMe.remove(dead.getIndex());
			final boolean itWasMyTarget = owner != null && owner.getIndex() == dead.getIndex();

			if (iDamagedIt && itWasMyTarget)
			{
				out.add(Attribution.kill(dead, window.copy()));
				window.clear();
				owner = null;
			}
			else if (iDamagedIt)
			{
				out.add(Attribution.unattributedDeath(dead));
			}
			// Neither: a monster somebody else killed, which in multi-combat
			// Wilderness is most of the deaths this handler will ever see.
		}
		deathsThisTick.clear();

		// 3. Despawns.
		for (int index : despawnsThisTick)
		{
			final boolean iDamagedIt = damagedByMe.remove(index);
			if (owner != null && owner.getIndex() == index)
			{
				closeWindow(out, iDamagedIt);
			}
			if (pendingEngagement != null && pendingEngagement.getIndex() == index)
			{
				// A monster that left the scene this tick must not be handed the
				// window a few lines below. The index is about to be reused, and a
				// window owned by a departed NPC is a window the next occupant of
				// that slot cannot take over, because ownership is compared by
				// index and the two would match.
				pendingEngagement = null;
			}
		}
		despawnsThisTick.clear();

		// 4. Interaction changes, last, so a death earlier in this same tick was
		//    judged against the target the player had when the tick began.
		if (pendingEngagement != null)
		{
			if (owner == null || owner.getIndex() != pendingEngagement.getIndex())
			{
				// Switching targets abandons whatever the previous one cost. The
				// monster is still alive; the player simply stopped shooting it.
				closeWindow(out, owner != null && damagedByMe.contains(owner.getIndex()));
				owner = pendingEngagement;
			}
			pendingEngagement = null;
		}

		return out;
	}

	/**
	 * Closes the open window without a kill.
	 *
	 * @param damaged whether the player had landed a hitsplat on the owner. The
	 *                ammunition is only banked as {@link Attribution.Kind#ABANDONED}
	 *                if they had: a window opened by talking to a banker or by
	 *                clicking a monster that was then killed by somebody else
	 *                contains no ammunition spent on it, and recording it would
	 *                put loot and food into a monster's record.
	 */
	private void closeWindow(List<Attribution> out, boolean damaged)
	{
		if (owner != null && damaged && !window.isEmpty())
		{
			out.add(Attribution.abandoned(owner, window.copy()));
		}
		window.clear();
		owner = null;
	}

	/**
	 * Drops every piece of state. Used for the player's own death, for game state
	 * changes that replace the scene, and by {@code shutDown()}.
	 */
	public void reset()
	{
		owner = null;
		window.clear();
		damagedByMe.clear();
		deathsThisTick.clear();
		despawnsThisTick.clear();
		pendingEngagement = null;
		localPlayerDied = false;
	}

	/** Null when no window is open. Package-private: tests and the lifecycle check. */
	@Nullable
	FoughtNpc getOwner()
	{
		return owner;
	}

	/** The open window's contents. Package-private, for the same two readers. */
	AmmoTally getWindow()
	{
		return window;
	}

	/** Package-private, so the lifecycle test can prove the evidence set empties. */
	int getDamageEvidenceCount()
	{
		return damagedByMe.size();
	}
}
