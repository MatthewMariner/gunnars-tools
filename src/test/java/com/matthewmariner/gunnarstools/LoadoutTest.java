package com.matthewmariner.gunnarstools;

import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The two equipment slots that decide whether an existing series of measurements
 * is still about the same thing.
 *
 * <p>Small class, and every one of its guards is a silent failure if it goes: a
 * quantity of zero read as an item makes unequipping look like a change and
 * re-equipping look like another; a short array throws inside an event handler; a
 * first reading treated as a change announces a gear change to every player at
 * every login.
 */
public class LoadoutTest
{
	private static final int SHORTBOW = 861;
	private static final int CROSSBOW = 21902;
	private static final int RUNE_ARROW = 892;
	private static final int DIAMOND_BOLT = 21946;

	private static final int WEAPON = EquipmentInventorySlot.WEAPON.getSlotIdx();
	private static final int AMMO = EquipmentInventorySlot.AMMO.getSlotIdx();

	/** An equipment container long enough for both slots, with everything else empty. */
	private static Item[] worn(int weaponId, int weaponQuantity, int ammoId, int ammoQuantity)
	{
		final Item[] items = new Item[Math.max(WEAPON, AMMO) + 1];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = new Item(-1, 0);
		}
		items[WEAPON] = new Item(weaponId, weaponQuantity);
		items[AMMO] = new Item(ammoId, ammoQuantity);
		return items;
	}

	@Test
	public void itReadsTheTwoSlotsThatMatter()
	{
		Loadout loadout = Loadout.of(worn(SHORTBOW, 1, RUNE_ARROW, 950));

		assertEquals(SHORTBOW, loadout.getWeaponId());
		assertEquals(RUNE_ARROW, loadout.getAmmoId());
		assertFalse(loadout.isUnknown());
	}

	@Test
	public void aSlotHoldingNothingIsEmptyHoweverItIsSpelled()
	{
		// Two spellings of the same emptiness. Reading the id alone would make the
		// second one a change to "item 892 with none of it", and then a change back.
		assertEquals(Loadout.EMPTY_SLOT, Loadout.of(worn(SHORTBOW, 1, -1, 0)).getAmmoId());
		assertEquals("a real id at quantity zero is still an empty slot",
			Loadout.EMPTY_SLOT, Loadout.of(worn(SHORTBOW, 1, RUNE_ARROW, 0)).getAmmoId());
		assertEquals(Loadout.EMPTY_SLOT, Loadout.of(worn(SHORTBOW, 1, RUNE_ARROW, -3)).getAmmoId());
	}

	@Test
	public void aContainerTooShortToHoldTheAmmoSlotIsNotAnError()
	{
		// An empty equipment container legitimately comes back shorter than the
		// ammunition slot's index. Throwing inside an ItemContainerChanged handler
		// would take the plugin down for wearing nothing.
		Loadout loadout = Loadout.of(new Item[]{new Item(SHORTBOW, 1)});

		assertEquals(Loadout.EMPTY_SLOT, loadout.getAmmoId());
	}

	@Test
	public void aNullSlotIsEmptyRatherThanAnException()
	{
		final Item[] items = new Item[Math.max(WEAPON, AMMO) + 1];
		items[WEAPON] = new Item(SHORTBOW, 1);

		assertEquals(SHORTBOW, Loadout.of(items).getWeaponId());
		assertEquals(Loadout.EMPTY_SLOT, Loadout.of(items).getAmmoId());
	}

	@Test
	public void noContainerAtAllReadsAsUnknown()
	{
		assertTrue(Loadout.of(null).isUnknown());
		assertEquals(Loadout.UNKNOWN, Loadout.of(null));
	}

	@Test
	public void wearingNothingIsIndistinguishableFromNotHavingLookedYet()
	{
		// Deliberate, and the reason the plugin only calls the first reading of a
		// session "not a change". There is no third state to encode: an empty
		// equipment container and an unread one contain the same information.
		assertTrue(Loadout.of(worn(-1, 0, -1, 0)).isUnknown());
	}

	@Test
	public void changingEitherSlotIsADifferentLoadout()
	{
		Loadout bow = Loadout.of(worn(SHORTBOW, 1, RUNE_ARROW, 950));

		assertNotEquals("a different weapon fires at a different speed",
			bow, Loadout.of(worn(CROSSBOW, 1, RUNE_ARROW, 950)));
		assertNotEquals("a different ammunition is a different item id in the record",
			bow, Loadout.of(worn(SHORTBOW, 1, DIAMOND_BOLT, 950)));
	}

	@Test
	public void firingArrowsIsNotAGearChange()
	{
		// The quantity is read only to tell empty from occupied. If it were part of
		// the identity, every shot would open a new record.
		assertEquals(Loadout.of(worn(SHORTBOW, 1, RUNE_ARROW, 950)),
			Loadout.of(worn(SHORTBOW, 1, RUNE_ARROW, 12)));
	}

	@Test
	public void equalLoadoutsAgreeOnTheirHashes()
	{
		// It is a map key in AmmoLedger. Equality without a matching hash would put
		// two identical setups in two records and halve every series.
		assertEquals(Loadout.of(worn(SHORTBOW, 1, RUNE_ARROW, 950)).hashCode(),
			Loadout.of(worn(SHORTBOW, 1, RUNE_ARROW, 12)).hashCode());
	}

	// --- has the gear changed, which is a three-valued question ----------------

	@Test
	public void twoKnownDifferentSetupsAreKnownToDiffer()
	{
		assertTrue(Loadout.knownToDiffer(
			Loadout.of(worn(SHORTBOW, 1, RUNE_ARROW, 950)),
			Loadout.of(worn(CROSSBOW, 1, DIAMOND_BOLT, 950))));
	}

	@Test
	public void twoKnownIdenticalSetupsAreNot()
	{
		assertFalse(Loadout.knownToDiffer(
			Loadout.of(worn(SHORTBOW, 1, RUNE_ARROW, 950)),
			Loadout.of(worn(SHORTBOW, 1, RUNE_ARROW, 12))));
	}

	@Test
	public void anUnknownSetupIsNeitherAMatchNorAMismatch()
	{
		// The half that plain equality gets wrong in the direction that shows on
		// screen. A remembered figure compared against a worn setup nobody has read
		// yet is not evidence of a gear change, and a review caught exactly that as
		// a wrong "other gear" label on the first panel drawn after enabling the
		// plugin — before the first game tick had read the equipment container.
		Loadout bow = Loadout.of(worn(SHORTBOW, 1, RUNE_ARROW, 950));

		assertFalse("nothing has been read, so nothing is known to have changed",
			Loadout.knownToDiffer(bow, Loadout.UNKNOWN));
		assertFalse(Loadout.knownToDiffer(Loadout.UNKNOWN, bow));
	}

	@Test
	public void twoUnknownsAreNotAMatchEither()
	{
		// The other half, which plain equality gets wrong in the quieter direction:
		// two unknowns compare equal and would claim a match nobody checked. The
		// answer is the same "say nothing" as above rather than "yes".
		assertFalse(Loadout.knownToDiffer(Loadout.UNKNOWN, Loadout.UNKNOWN));
	}

	@Test
	public void itIsNotEqualToThingsThatAreNotLoadouts()
	{
		Loadout bow = Loadout.of(worn(SHORTBOW, 1, RUNE_ARROW, 950));

		assertFalse(bow.equals(null));
		assertFalse(bow.equals("Loadout(weapon=861, ammo=892)"));
		assertTrue(bow.equals(bow));
	}
}
