package com.matthewmariner.gunnarstools;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Item;
import net.runelite.api.gameval.InventoryID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The measurement half, exercised without a client.
 *
 * <p>{@code net.runelite.api.Item} is a final class with a public
 * {@code (id, quantity)} constructor, so a container's contents can be built
 * literally rather than mocked — which means these tests are asserting on the
 * same type the game hands the plugin, not on a stand-in that could disagree
 * with it.
 *
 * <p>Item ids here are arbitrary small numbers, chosen so a failure message
 * reads as arithmetic rather than as a game trivia question. The meter has no
 * opinion about which ids are ammunition; that is {@link Ammunition}'s job, and
 * it arrives here as the injected predicate.
 */
public class ConsumptionMeterTest
{
	private static final int ARROW = 11;
	private static final int RUNE = 22;
	private static final int PLATEBODY = 33;

	/**
	 * Everything is meterable except the one id standing in for a worn item.
	 *
	 * <p>The predicate throws for a non-positive id rather than answering,
	 * because in production it is a lookup in the client's item cache and asking
	 * it about the contents of a slot that holds nothing is a question with no
	 * good answer. That makes the meter's own "skip the empty slots" guard
	 * provable here instead of merely plausible.
	 */
	private static ConsumptionMeter meter()
	{
		return new ConsumptionMeter(id ->
		{
			if (id <= 0)
			{
				throw new IllegalArgumentException("asked the item cache about id " + id);
			}
			return id != PLATEBODY;
		});
	}

	private static Item[] items(int... idsAndQuantities)
	{
		Item[] out = new Item[idsAndQuantities.length / 2];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = new Item(idsAndQuantities[i * 2], idsAndQuantities[i * 2 + 1]);
		}
		return out;
	}

	// --- the baseline ---------------------------------------------------------

	@Test
	public void theFirstTickEstablishesTheBaselineAndReportsNothing()
	{
		ConsumptionMeter meter = meter();

		meter.containerChanged(InventoryID.WORN, items(ARROW, 950));

		assertTrue("everything appearing at once is a login, not a purchase",
			meter.tickEnded().isEmpty());
		assertTrue(meter.hasBaseline());
	}

	@Test
	public void aTickWithNoContainerEventProducesNothing()
	{
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.WORN, items(ARROW, 950));
		meter.tickEnded();

		assertTrue(meter.tickEnded().isEmpty());
		assertTrue(meter.tickEnded().isEmpty());
	}

	// --- quantity, not presence ----------------------------------------------

	@Test
	public void aStackThatShrinksReportsHowMuchItShrankBy()
	{
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.WORN, items(ARROW, 950));
		meter.tickEnded();

		meter.containerChanged(InventoryID.WORN, items(ARROW, 949));
		AmmoDelta delta = meter.tickEnded();

		assertEquals("one arrow, not one 'arrows changed'",
			Long.valueOf(1L), delta.getConsumed().get(ARROW));
		assertTrue(delta.getGained().isEmpty());
	}

	@Test
	public void aStackThatEmptiesReportsTheWholeStack()
	{
		// The case an id-only design gets most wrong: 950 to 0 and 950 to 949
		// are the same event to anything that only looks at which ids are present.
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.WORN, items(ARROW, 950));
		meter.tickEnded();

		meter.containerChanged(InventoryID.WORN, items());
		AmmoDelta delta = meter.tickEnded();

		assertEquals(Long.valueOf(950L), delta.getConsumed().get(ARROW));
	}

	@Test
	public void twoStacksMovingInOneTickAreBothReported()
	{
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.INV, items(ARROW, 100, RUNE, 1000));
		meter.tickEnded();

		meter.containerChanged(InventoryID.INV, items(ARROW, 97, RUNE, 994));
		AmmoDelta delta = meter.tickEnded();

		assertEquals(Long.valueOf(3L), delta.getConsumed().get(ARROW));
		assertEquals(Long.valueOf(6L), delta.getConsumed().get(RUNE));
	}

	@Test
	public void aStackThatGrowsIsReportedAsGainedAndNeverAsConsumed()
	{
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.WORN, items(ARROW, 940));
		meter.tickEnded();

		meter.containerChanged(InventoryID.WORN, items(ARROW, 952));
		AmmoDelta delta = meter.tickEnded();

		assertEquals(Long.valueOf(12L), delta.getGained().get(ARROW));
		assertTrue(delta.getConsumed().isEmpty());
	}

	// --- the containers are summed -------------------------------------------

	@Test
	public void equippingAStackWithinOneTickIsNotAConsumptionAndNotAGain()
	{
		// The reason the diff waits for the tick boundary. RuneLite fires one
		// event per container, so an equip arrives as two events; anything that
		// subtracted on each event would record 500 spent and then 500 found.
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.INV, items(ARROW, 500));
		meter.containerChanged(InventoryID.WORN, items());
		meter.tickEnded();

		meter.containerChanged(InventoryID.INV, items());
		meter.containerChanged(InventoryID.WORN, items(ARROW, 500));
		AmmoDelta delta = meter.tickEnded();

		assertTrue("the arrows are still on the player: " + delta, delta.isEmpty());
	}

	@Test
	public void aStackSplitAcrossContainersIsMeteredAsOne()
	{
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.INV, items(ARROW, 300));
		meter.containerChanged(InventoryID.WORN, items(ARROW, 200));
		meter.tickEnded();

		// One arrow leaves the equipped stack. The inventory stack is untouched
		// and must not be double counted into the answer.
		meter.containerChanged(InventoryID.WORN, items(ARROW, 199));
		AmmoDelta delta = meter.tickEnded();

		assertEquals(Long.valueOf(1L), delta.getConsumed().get(ARROW));
	}

	@Test
	public void theQuiverIsPartOfTheSum()
	{
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.DIZANAS_QUIVER_AMMO, items(ARROW, 400));
		meter.tickEnded();

		meter.containerChanged(InventoryID.DIZANAS_QUIVER_AMMO, items(ARROW, 396));
		AmmoDelta delta = meter.tickEnded();

		assertEquals("a quiver user must not read as zero arrows per kill",
			Long.valueOf(4L), delta.getConsumed().get(ARROW));
	}

	@Test
	public void theTrackedContainersAreTheThreeOnThePlayer()
	{
		assertEquals(new HashSet<>(java.util.Arrays.asList(
				InventoryID.INV, InventoryID.WORN, InventoryID.DIZANAS_QUIVER_AMMO)),
			ConsumptionMeter.TRACKED_CONTAINERS);
	}

	@Test
	public void theBankIsNotMetered()
	{
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.INV, items(ARROW, 100));
		meter.tickEnded();

		assertFalse("an untracked container must report that it was ignored",
			meter.containerChanged(InventoryID.BANK, items(ARROW, 50000)));

		AmmoDelta delta = meter.tickEnded();
		assertTrue("opening the bank is not fifty thousand arrows found: " + delta, delta.isEmpty());
	}

	// --- the filter ----------------------------------------------------------

	@Test
	public void anItemTheFilterRejectsNeverEntersTheSum()
	{
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.WORN, items(PLATEBODY, 1, ARROW, 950));
		meter.tickEnded();

		// Swapping armour mid-trip is not consumption, and must not appear.
		meter.containerChanged(InventoryID.WORN, items(ARROW, 950));
		AmmoDelta delta = meter.tickEnded();

		assertTrue("a swapped platebody is not ammunition: " + delta, delta.isEmpty());
	}

	@Test
	public void emptySlotsAndZeroQuantitiesAreSkipped()
	{
		ConsumptionMeter meter = meter();

		// How RuneLite renders an inventory with one filled slot: id -1 and
		// quantity 0 everywhere else.
		meter.containerChanged(InventoryID.INV, items(-1, 0, ARROW, 950, -1, 0));
		meter.tickEnded();

		meter.containerChanged(InventoryID.INV, items(-1, 0, ARROW, 949, -1, 0));
		AmmoDelta delta = meter.tickEnded();

		assertEquals(Collections.singleton(ARROW), delta.getConsumed().keySet());
		assertEquals(Long.valueOf(1L), delta.getConsumed().get(ARROW));
	}

	@Test
	public void aNegativeQuantityIsTreatedAsAnEmptySlotRatherThanAsACredit()
	{
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.INV, items(ARROW, 100));
		meter.tickEnded();

		// Added to the sum, -1 would make the total read as 99 fewer than nothing
		// and turn the next honest reading into ammunition found.
		meter.containerChanged(InventoryID.INV, items(ARROW, -1));
		AmmoDelta delta = meter.tickEnded();

		assertEquals("the whole stack is gone, and it is 100 rather than 101",
			Long.valueOf(100L), delta.getConsumed().get(ARROW));
		assertTrue(delta.getGained().isEmpty());
	}

	@Test
	public void aNullContainerIsAnEmptyOneRatherThanACrash()
	{
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.INV, items(ARROW, 950));
		meter.tickEnded();

		meter.containerChanged(InventoryID.INV, null);
		AmmoDelta delta = meter.tickEnded();

		assertEquals(Long.valueOf(950L), delta.getConsumed().get(ARROW));
	}

	// --- invalidation --------------------------------------------------------

	@Test
	public void invalidatingTheBaselineSuppressesTheDiffAcrossIt()
	{
		// A hop, a region load, or the player's own death. Differencing across one
		// of those reports the whole inventory as consumed.
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.INV, items(ARROW, 950, RUNE, 1000));
		meter.tickEnded();

		meter.invalidateBaseline();
		assertFalse(meter.hasBaseline());

		meter.containerChanged(InventoryID.INV, items());
		AmmoDelta delta = meter.tickEnded();

		assertTrue("an emptied inventory across a reset is not 1,950 things spent: " + delta,
			delta.isEmpty());
		assertTrue("and the empty state is now the baseline", meter.hasBaseline());
	}

	@Test
	public void aTickThatSawNothingDoesNotBaselineAnEmptyInventory()
	{
		// A loading screen is several ticks long and no container event arrives
		// during it. A tick that saw nothing must leave the baseline unset rather
		// than record "the player is carrying nothing" — otherwise the first real
		// reading afterwards reads as the whole kit being found.
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.INV, items(ARROW, 950));
		meter.tickEnded();

		meter.invalidateBaseline();
		assertTrue(meter.tickEnded().isEmpty());
		assertFalse("nothing was seen, so nothing is known", meter.hasBaseline());

		meter.containerChanged(InventoryID.INV, items(ARROW, 950));
		AmmoDelta delta = meter.tickEnded();

		assertTrue("arriving on a new world is not 950 arrows found: " + delta, delta.isEmpty());
	}

	@Test
	public void invalidatingMidTickClearsTheDirtyFlagFromEarlierInTheSameTick()
	{
		// A container event lands (dirty = true), and then, before tickEnded()
		// ever runs, something replaces the whole scene and invalidates the
		// baseline — a world hop mid-tick. If invalidateBaseline() left the
		// stale dirty flag set, the next tick — the loading screen itself,
		// where no container event arrives at all — would sail past the
		// "nothing changed" guard and baseline the cleared, empty snapshots as
		// though an empty inventory had genuinely been observed.
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.INV, items(ARROW, 950));
		meter.tickEnded();

		meter.containerChanged(InventoryID.INV, items(ARROW, 900));
		meter.invalidateBaseline();

		assertTrue("a tick with no events afterwards must not baseline anything",
			meter.tickEnded().isEmpty());
		assertFalse("nothing was seen this tick, so nothing should be known yet",
			meter.hasBaseline());

		// The kit reappears once the loading screen ends.
		meter.containerChanged(InventoryID.INV, items(ARROW, 950));
		AmmoDelta delta = meter.tickEnded();

		assertTrue("the real inventory arriving must not read as 950 arrows found: " + delta,
			delta.isEmpty());
	}

	@Test
	public void anIdThatVanishedEntirelyCanStillBeReportedAsAGainLater()
	{
		// The stack empties first, which drops the id out of the baseline
		// entirely rather than leaving it recorded at zero. Picking some back
		// up off the floor afterwards has to be judged against the union of
		// both sides' id spaces — walking only the (now id-less) baseline
		// would silently lose the pickup.
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.INV, items(ARROW, 2));
		meter.tickEnded();

		meter.containerChanged(InventoryID.INV, items());
		AmmoDelta fired = meter.tickEnded();
		assertEquals(Long.valueOf(2L), fired.getConsumed().get(ARROW));

		meter.containerChanged(InventoryID.INV, items(ARROW, 2));
		AmmoDelta pickedBackUp = meter.tickEnded();

		assertEquals("arrows off the floor after the stack ran dry must still count as a gain",
			Long.valueOf(2L), pickedBackUp.getGained().get(ARROW));
	}

	@Test
	public void aFreshMeterIsNotBaselinedByTicksAlone()
	{
		ConsumptionMeter meter = meter();

		meter.tickEnded();
		meter.tickEnded();
		assertFalse(meter.hasBaseline());

		meter.containerChanged(InventoryID.INV, items(ARROW, 950));
		assertTrue("logging in is not 950 arrows found", meter.tickEnded().isEmpty());
	}

	@Test
	public void invalidationForgetsTheContainersThemselvesAndNotJustTheirSum()
	{
		// The point of forgetting: a container that changed while the plugin was
		// not looking must not be differenced against its pre-invalidation
		// snapshot. Keeping the snapshots would carry the equipped stack across a
		// hop, and the first honest reading of it afterwards would report the
		// difference as ammunition spent.
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.INV, items(ARROW, 100));
		meter.containerChanged(InventoryID.WORN, items(ARROW, 50));
		meter.tickEnded();

		meter.invalidateBaseline();

		// On the other side, only the inventory reports in at first.
		meter.containerChanged(InventoryID.INV, items(ARROW, 100));
		meter.tickEnded();

		// Then the equipped stack reports in, and it is empty — unequipped during
		// the load, or never there at all on this character.
		meter.containerChanged(InventoryID.WORN, items());
		AmmoDelta delta = meter.tickEnded();

		assertTrue("the 50 from before the reset are not 50 arrows fired: " + delta,
			delta.isEmpty());
	}

	@Test
	public void meteringResumesAfterTheBaselineIsRebuilt()
	{
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.INV, items(ARROW, 950));
		meter.tickEnded();
		meter.invalidateBaseline();

		meter.containerChanged(InventoryID.INV, items(ARROW, 40));
		meter.tickEnded();

		meter.containerChanged(InventoryID.INV, items(ARROW, 38));
		AmmoDelta delta = meter.tickEnded();

		assertEquals("the new baseline is 40, not the 950 from before the reset",
			Long.valueOf(2L), delta.getConsumed().get(ARROW));
	}

	@Test
	public void clearLeavesNothingBehind()
	{
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.INV, items(ARROW, 950));
		meter.tickEnded();

		meter.clear();

		assertFalse(meter.hasBaseline());
		assertTrue(meter.tickEnded().isEmpty());
	}

	// --- the invariant the whole design rests on ------------------------------

	@Test
	public void aDelayedContainerEventShiftsTheTickButNotTheTotal()
	{
		// Snapshots are levels, not increments, so an event that lands a tick late
		// costs a tick of latency and nothing else. This is what makes the event
		// ordering within a tick a non-issue rather than a correctness dependency.
		ConsumptionMeter prompt = meter();
		ConsumptionMeter delayed = meter();

		prompt.containerChanged(InventoryID.INV, items(ARROW, 100));
		delayed.containerChanged(InventoryID.INV, items(ARROW, 100));
		prompt.tickEnded();
		delayed.tickEnded();

		long promptTotal = 0;
		long delayedTotal = 0;

		// Three shots, reported one tick apart on the two meters.
		int[] quantities = {99, 98, 97};
		for (int i = 0; i < quantities.length; i++)
		{
			prompt.containerChanged(InventoryID.INV, items(ARROW, quantities[i]));
			promptTotal += prompt.tickEnded().getConsumed().getOrDefault(ARROW, 0L);

			delayedTotal += delayed.tickEnded().getConsumed().getOrDefault(ARROW, 0L);
			delayed.containerChanged(InventoryID.INV, items(ARROW, quantities[i]));
		}
		delayedTotal += delayed.tickEnded().getConsumed().getOrDefault(ARROW, 0L);

		assertEquals(3L, promptTotal);
		assertEquals("late is late, never lost and never doubled", 3L, delayedTotal);
	}

	@Test
	public void theSumIsWiderThanAnIntCanHold()
	{
		// Coins are stackable and therefore metered, and one stack reaches
		// Integer.MAX_VALUE. Two containers holding one each overflow an int
		// accumulator; the arithmetic here is long throughout.
		final int coins = 995;
		ConsumptionMeter meter = new ConsumptionMeter(id -> true);
		meter.containerChanged(InventoryID.INV, items(coins, Integer.MAX_VALUE));
		meter.containerChanged(InventoryID.WORN, items(coins, Integer.MAX_VALUE));
		meter.tickEnded();

		meter.containerChanged(InventoryID.WORN, items());
		AmmoDelta delta = meter.tickEnded();

		assertEquals(Long.valueOf((long) Integer.MAX_VALUE), delta.getConsumed().get(coins));
	}

	@Test
	public void theDeltaIsNotEditableFromOutside()
	{
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.INV, items(ARROW, 100));
		meter.tickEnded();
		meter.containerChanged(InventoryID.INV, items(ARROW, 99));
		AmmoDelta delta = meter.tickEnded();

		try
		{
			delta.getConsumed().put(ARROW, 9999L);
			org.junit.Assert.fail("a delta a caller can edit is not a measurement");
		}
		catch (UnsupportedOperationException expected)
		{
			// what an unmodifiable map does
		}
	}

	@Test
	public void theEmptyDeltaIsEmptyInBothDirections()
	{
		Set<Integer> none = Collections.emptySet();
		assertTrue(AmmoDelta.EMPTY.isEmpty());
		assertEquals(none, AmmoDelta.EMPTY.getConsumed().keySet());
		assertEquals(none, AmmoDelta.EMPTY.getGained().keySet());
	}

	// --- what is being carried -------------------------------------------------

	@Test
	public void holdingsAreTheSumAcrossEveryTrackedContainer()
	{
		// The number the bank highlight subtracts. Reading one container would tell
		// a player to withdraw the arrows already in their quiver.
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.INV, items(ARROW, 100));
		meter.containerChanged(InventoryID.WORN, items(ARROW, 50));
		meter.containerChanged(InventoryID.DIZANAS_QUIVER_AMMO, items(ARROW, 900));
		meter.tickEnded();

		assertEquals(Long.valueOf(1050L), meter.getHoldings().get(ARROW));
	}

	@Test
	public void holdingsAreEmptyUntilTheContainersHaveBeenRead()
	{
		// The honest answer: the plugin genuinely does not know what is being
		// carried. Reporting zero would be the same value with a different meaning,
		// and the highlight would tell the player to withdraw the whole trip twice.
		ConsumptionMeter meter = meter();

		assertTrue(meter.getHoldings().isEmpty());

		meter.containerChanged(InventoryID.INV, items(ARROW, 100));
		meter.tickEnded();
		assertFalse(meter.getHoldings().isEmpty());

		meter.invalidateBaseline();
		assertTrue("and a login puts it back", meter.getHoldings().isEmpty());
	}

	@Test
	public void holdingsFollowTheStackDown()
	{
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.INV, items(ARROW, 100));
		meter.tickEnded();
		meter.containerChanged(InventoryID.INV, items(ARROW, 60));
		meter.tickEnded();

		assertEquals(Long.valueOf(60L), meter.getHoldings().get(ARROW));
	}

	@Test
	public void holdingsCannotBeEditedByWhoeverReadsThem()
	{
		ConsumptionMeter meter = meter();
		meter.containerChanged(InventoryID.INV, items(ARROW, 100));
		meter.tickEnded();

		try
		{
			meter.getHoldings().put(ARROW, 1L);
			org.junit.Assert.fail("an overlay must not be able to rewrite the meter");
		}
		catch (UnsupportedOperationException expected)
		{
			assertEquals(Long.valueOf(100L), meter.getHoldings().get(ARROW));
		}
	}
}
