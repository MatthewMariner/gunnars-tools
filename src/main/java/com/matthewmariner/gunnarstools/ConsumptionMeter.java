package com.matthewmariner.gunnarstools;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import net.runelite.api.Item;
import net.runelite.api.gameval.InventoryID;

/**
 * Measures what the player spent, by differencing the total quantity of every
 * stackable item they are carrying against what they were carrying at the end
 * of the previous game tick.
 *
 * <p>Three decisions in here are load-bearing.
 *
 * <p><b>1. Quantity, not presence.</b> Every map in this class is keyed by item
 * id and valued by quantity, and the diff is arithmetic on the quantities.
 * {@code ItemContainer.getItems()} hands back {@code Item[]} where each element
 * carries <em>both</em> an id and a quantity, and a design that reads only the
 * ids sees "adamant arrow: still present" across a shot from 950 to 949 and
 * across a shot from 950 to 0 alike. There is no version of this plugin that
 * works without quantities.
 *
 * <p><b>2. The containers are summed, not watched separately.</b> Inventory
 * ({@link InventoryID#INV}), equipment ({@link InventoryID#WORN}) and the
 * quiver ({@link InventoryID#DIZANAS_QUIVER_AMMO}) are folded into one
 * multiset before anything is differenced. Equipping 500 arrows is a loss of
 * 500 from the inventory and a gain of 500 in the ammo slot; watching either
 * container on its own reports half of a move as a fact about consumption.
 * Summing makes a move between tracked containers arithmetically invisible,
 * which is what it deserves to be — the arrows are still on the player, in the
 * same trip, available to fire.
 *
 * <p><b>3. The diff happens at the tick boundary, and it compares levels rather
 * than accumulating increments.</b> RuneLite fires one
 * {@code ItemContainerChanged} per container, so the two halves of an equip
 * arrive as two events; differencing on each event would record a spurious
 * loss and then a spurious gain even though the sum never moved. So each event
 * only <em>overwrites this container's snapshot</em>, and the subtraction runs
 * once, on {@code GameTick}, against the previous tick's sum.
 *
 * <p>The "levels, not increments" half of that is what makes it robust rather
 * than merely tidy. Each snapshot is the container's complete contents, so if a
 * container event ever landed on the far side of a tick boundary from the event
 * that logically accompanied it, the only consequence is that the change is
 * reported one tick later than it happened. It cannot be double counted and it
 * cannot be lost, because the next diff still compares "everything I hold now"
 * against "everything I held then". A design that added up per-event
 * differences would have the event ordering as a correctness dependency; this
 * one has it as a latency dependency, and one tick of latency does not change
 * any answer this plugin gives.
 *
 * <p>Not tracked, and deliberately: the bank, the deposit box, and every other
 * container. Depositing arrows is a real decrement of the inventory that is
 * plainly not consumption, and the structural defence against recording it is
 * that {@link KillAttribution} throws away any delta that arrives while the
 * player is not engaged with a monster. Adding the bank to the sum would defeat
 * that by making the withdrawal invisible instead — and would then make a genuine
 * mid-trip restock invisible too.
 */
public final class ConsumptionMeter
{
	/**
	 * The containers whose contents are summed.
	 *
	 * <p>{@link InventoryID#DIZANAS_QUIVER_AMMO} is here because a quiver holds
	 * a second ammunition stack that a matching weapon fires from, and a plugin
	 * that only watched the ammo slot would report zero arrows per kill for
	 * anybody wearing one — a silent zero, which is the worst failure this
	 * plugin has available. Including it is free if that turns out not to be the
	 * container the game decrements: a container that never changes contributes
	 * a constant to the sum and therefore nothing to any diff. <b>This one is
	 * reasoned from the API rather than observed, and wants confirming in
	 * game</b>; see the README's verification list.
	 */
	static final Set<Integer> TRACKED_CONTAINERS = Collections.unmodifiableSet(
		new HashSet<>(java.util.Arrays.asList(
			InventoryID.INV,
			InventoryID.WORN,
			InventoryID.DIZANAS_QUIVER_AMMO)));

	private final IntPredicate consumable;

	/** Container id to its most recent contents, already filtered. */
	private final Map<Integer, Map<Integer, Long>> snapshots = new HashMap<>();

	/**
	 * The summed contents as of the last tick that produced a diff, or null when
	 * there is nothing to compare against — before the first tick after login,
	 * and after {@link #invalidateBaseline()}.
	 */
	private Map<Integer, Long> baseline;

	/**
	 * {@link #baseline} wrapped for {@link #getHoldings()}, rebuilt only when the
	 * baseline is.
	 *
	 * <p>A second field for one wrapper looks like premature tidiness and is not.
	 * The bank highlight asks for this once per visible item per frame, and a full
	 * bank tab is around three hundred and fifty items — wrapping on each call
	 * would be a few hundred throwaway objects a frame for a map that changes once
	 * a tick at most. {@code AGENTS.md} is explicit that per-frame work stays
	 * minimal. Both fields are assigned in {@link #publishBaseline}, and nowhere
	 * else, so they cannot drift apart.
	 */
	private Map<Integer, Long> holdings = Collections.emptyMap();

	/** Whether any tracked container changed since the last {@link #tickEnded()}. */
	private boolean dirty;

	public ConsumptionMeter(IntPredicate consumable)
	{
		this.consumable = consumable;
	}

	/**
	 * Records a container's new contents. Untracked containers are ignored.
	 *
	 * @return true if the container was one we track, purely so a caller (and a
	 * test) can tell "ignored" from "recorded" without reaching inside
	 */
	public boolean containerChanged(int containerId, Item[] items)
	{
		if (!TRACKED_CONTAINERS.contains(containerId))
		{
			return false;
		}

		Map<Integer, Long> contents = new LinkedHashMap<>();
		if (items != null)
		{
			for (Item item : items)
			{
				if (item == null)
				{
					continue;
				}
				final int id = item.getId();
				final int quantity = item.getQuantity();

				// An empty slot comes back as id -1, quantity 0, and both halves
				// of that are worth refusing separately. A negative id is not an
				// item to ask the composition cache about — the filter below is a
				// cache lookup in production, and asking it about slot contents
				// that do not exist is a question with no good answer. And a
				// quantity of zero or less is not a holding: added to the sum, a
				// negative would make the total *smaller* than the arrows the
				// player is carrying, so the next real reading would come back as
				// ammunition found.
				if (id < 0 || quantity <= 0)
				{
					continue;
				}
				if (!consumable.test(id))
				{
					continue;
				}
				contents.merge(id, (long) quantity, Long::sum);
			}
		}

		snapshots.put(containerId, contents);
		dirty = true;
		return true;
	}

	/**
	 * Closes the tick: sums the snapshots, subtracts the previous sum, and makes
	 * the new sum the baseline.
	 *
	 * @return what changed, or {@link AmmoDelta#EMPTY} if nothing did — including
	 * the first call after a baseline reset, which establishes the baseline and
	 * reports nothing, because "everything appeared at once" is what a login
	 * looks like and none of it was consumed
	 */
	public AmmoDelta tickEnded()
	{
		if (!dirty)
		{
			// Nothing changed, so there is nothing to subtract. Skipping the sum
			// here is also why this class does not cost anything on the 599 ticks
			// out of 600 where the player's holdings sat still.
			return AmmoDelta.EMPTY;
		}
		dirty = false;

		final Map<Integer, Long> combined = combine();

		if (baseline == null)
		{
			publishBaseline(combined);
			return AmmoDelta.EMPTY;
		}

		final Map<Integer, Long> consumed = new LinkedHashMap<>();
		final Map<Integer, Long> gained = new LinkedHashMap<>();

		// Union of both key sets: an id that vanished entirely is absent from
		// `combined`, and an id that appeared is absent from `baseline`. Walking
		// only one side loses one of those two cases, and "the stack ran out" is
		// precisely the case that matters most.
		final Set<Integer> ids = new java.util.LinkedHashSet<>(baseline.keySet());
		ids.addAll(combined.keySet());

		for (int id : ids)
		{
			final long before = baseline.getOrDefault(id, 0L);
			final long after = combined.getOrDefault(id, 0L);
			if (after < before)
			{
				consumed.put(id, before - after);
			}
			else if (after > before)
			{
				gained.put(id, after - before);
			}
		}

		publishBaseline(combined);

		if (consumed.isEmpty() && gained.isEmpty())
		{
			return AmmoDelta.EMPTY;
		}
		return new AmmoDelta(consumed, gained);
	}

	/**
	 * Forgets the baseline and every snapshot, so the next tick that sees a
	 * container event re-establishes the baseline and reports no change.
	 *
	 * <p>For login, world hop, region load and the local player's own death.
	 * Every one of those replaces the containers wholesale, and differencing
	 * across the replacement would report the entire inventory as consumed and
	 * then the entire inventory as gained. Neither is a fact about ammunition.
	 */
	public void invalidateBaseline()
	{
		snapshots.clear();
		publishBaseline(null);
		dirty = false;
	}

	/**
	 * The only place {@link #baseline} is assigned, so its published view cannot
	 * describe a state it was never in.
	 */
	private void publishBaseline(Map<Integer, Long> combined)
	{
		baseline = combined;
		holdings = combined == null
			? Collections.emptyMap()
			: Collections.unmodifiableMap(combined);
	}

	/** Symmetric with construction: leaves nothing behind for {@code shutDown()}. */
	public void clear()
	{
		invalidateBaseline();
	}

	/** Package-private for the lifecycle test. */
	boolean hasBaseline()
	{
		return baseline != null;
	}

	/**
	 * Everything the player is holding of a metered item, as of the last tick that
	 * produced a diff.
	 *
	 * <p>This is the baseline under a different name, and it is already exactly the
	 * right number: it sums inventory, worn equipment and the quiver, filtered to
	 * the items this plugin meters. The bank highlight subtracts it to turn "what
	 * the trip needs" into "what is left to withdraw", which is the question
	 * somebody standing at a bank is actually asking.
	 *
	 * <p>An unmodifiable view rather than a copy, built once when the baseline is
	 * and not on each call — see {@link #holdings}. The map behind it is replaced
	 * wholesale on each diff and never mutated afterwards, so a caller holding this
	 * sees a consistent tick rather than a half-written one.
	 *
	 * @return empty before the first tick after a login, and after
	 * {@link #invalidateBaseline()} — which is the correct answer in both cases,
	 * since the plugin genuinely does not know what is being carried
	 */
	public Map<Integer, Long> getHoldings()
	{
		return holdings;
	}

	private Map<Integer, Long> combine()
	{
		final Map<Integer, Long> combined = new LinkedHashMap<>();
		for (Map<Integer, Long> contents : snapshots.values())
		{
			contents.forEach((id, qty) -> combined.merge(id, qty, Long::sum));
		}
		return combined;
	}
}
