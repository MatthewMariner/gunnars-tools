package com.matthewmariner.gunnarstools;

import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;

/**
 * What the player is shooting <em>with</em>: the weapon and the ammunition in the
 * two equipment slots that decide how many shots a monster takes.
 *
 * <h2>Why a measurement needs this attached to it</h2>
 *
 * <p>Everything else in this plugin is careful about <em>which monster</em> a
 * number belongs to and says nothing at all about which player it belongs to.
 * That is fine while there is only one setup in play and quietly wrong the moment
 * there is not. Twenty-five rune arrows per Spindel is a fact about a magic
 * shortbow; the same player on a dragon hunter crossbow is a different number
 * entirely, and averaging the two produces a figure that describes neither.
 *
 * <p>The plugin cannot know whether a bow is better than a crossbow, and does not
 * try — that would be the combat model it refuses. It only has to notice that the
 * thing in your hand is not the thing that was in your hand, which is a
 * comparison of two integers.
 *
 * <h2>Two slots, not fourteen</h2>
 *
 * <p>Ranged strength bonuses come off the whole outfit and prayers and potions
 * come off neither, so no set of slots makes this a complete description of a
 * setup, and pretending otherwise would be the same overreach as the combat
 * model. What the weapon and the ammunition have that the other twelve slots do
 * not is that changing either one changes what is <em>consumed</em>: a different
 * weapon fires at a different speed and accuracy, and a different ammunition is a
 * different item id in the record. Those are the two changes that make an
 * existing series stop being about the same thing, and they are also the two the
 * player is most likely to make mid-session and least likely to remember.
 *
 * <p>Anything narrower — a change of gloves, a brew, an overload — is
 * undetectable here and is left to the manual reset the panel offers. That is
 * disclosed rather than hidden: this class is named for what it covers.
 */
public final class Loadout
{
	/** No weapon, no ammunition, or a container that has not been read yet. */
	static final int EMPTY_SLOT = -1;

	/** What the plugin holds before it has ever seen the equipment container. */
	static final Loadout UNKNOWN = new Loadout(EMPTY_SLOT, EMPTY_SLOT);

	private final int weaponId;
	private final int ammoId;

	Loadout(int weaponId, int ammoId)
	{
		this.weaponId = weaponId;
		this.ammoId = ammoId;
	}

	/**
	 * Reads the two slots out of the worn-equipment container.
	 *
	 * <p>{@code ItemContainer.getItems()} for the equipment container is indexed
	 * by equipment slot, so the two reads are array indexing rather than a search.
	 * A short array is not an error worth throwing over — an empty equipment
	 * container legitimately comes back shorter than the ammunition slot's index —
	 * so a slot past the end reads as empty, which is what it is.
	 *
	 * @param worn the container's contents, or null when there is no container
	 */
	static Loadout of(Item[] worn)
	{
		return new Loadout(slot(worn, EquipmentInventorySlot.WEAPON),
			slot(worn, EquipmentInventorySlot.AMMO));
	}

	private static int slot(Item[] worn, EquipmentInventorySlot slot)
	{
		if (worn == null)
		{
			return EMPTY_SLOT;
		}
		final int index = slot.getSlotIdx();
		if (index < 0 || index >= worn.length)
		{
			return EMPTY_SLOT;
		}
		final Item item = worn[index];
		if (item == null)
		{
			return EMPTY_SLOT;
		}

		// An empty slot comes back as id -1 with quantity 0, and a quantity of zero
		// with a real id is the same emptiness spelled differently. Reading the id
		// alone would make unequipping a bow look like equipping item -1, which is a
		// change, and then re-equipping it look like another one.
		return item.getQuantity() <= 0 ? EMPTY_SLOT : item.getId();
	}

	int getWeaponId()
	{
		return weaponId;
	}

	int getAmmoId()
	{
		return ammoId;
	}

	/**
	 * @return true before the equipment container has ever been read. Distinguished
	 * from "wearing nothing" on purpose: the first reading of a session is not a
	 * change from anything, and treating it as one would announce a gear change to
	 * every player at every login.
	 */
	boolean isUnknown()
	{
		return weaponId == EMPTY_SLOT && ammoId == EMPTY_SLOT;
	}

	/**
	 * Whether two setups are <em>known</em> to be different things.
	 *
	 * <p>Not {@code !a.equals(b)}, and the difference is the whole reason this
	 * method exists rather than being written inline at its two call sites. The
	 * question "has the gear changed" is three-valued, because {@link #UNKNOWN} is
	 * a real state on either side: it is what the plugin holds between
	 * {@code startUp()} and the first game tick, and it is what a remembered entry
	 * holds when the session that wrote it never read its own equipment.
	 *
	 * <p>Plain equality gets both ends wrong at once. Two unknowns compare
	 * <em>equal</em> and claim a match nobody checked. A known setup against an
	 * unknown one compares <em>unequal</em> and announces a gear change that may
	 * not have happened — which a review caught as a wrong "other gear" label on
	 * the very first panel drawn after enabling the plugin, before anything had
	 * read the equipment container.
	 *
	 * <p>So an unknown on either side is neither a match nor a mismatch. Callers
	 * treat that as "say nothing about the gear", which is what it means: the
	 * caveat is something extra said when the plugin knows the setup differs, not
	 * a default.
	 */
	static boolean knownToDiffer(Loadout a, Loadout b)
	{
		return !a.isUnknown() && !b.isUnknown() && !a.equals(b);
	}

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof Loadout))
		{
			return false;
		}
		final Loadout that = (Loadout) other;
		return weaponId == that.weaponId && ammoId == that.ammoId;
	}

	@Override
	public int hashCode()
	{
		return weaponId * 31 + ammoId;
	}

	@Override
	public String toString()
	{
		return "Loadout(weapon=" + weaponId + ", ammo=" + ammoId + ")";
	}
}
