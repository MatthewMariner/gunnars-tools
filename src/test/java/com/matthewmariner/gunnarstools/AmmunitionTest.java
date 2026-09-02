package com.matthewmariner.gunnarstools;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Which items are worth metering at all.
 *
 * <p>Four combinations, all four asserted, because both halves of the rule are
 * doing work: dropping the stackable test lets a swapped platebody read as an
 * item consumed, and dropping the note test lets a bank withdrawal of fifteen
 * noted sharks read as fifteen units of something spent.
 */
public class AmmunitionTest
{
	@Test
	public void aStackableUnnotedItemIsMetered()
	{
		assertTrue("arrows, bolts, darts, runes, ether: the whole point",
			Ammunition.isConsumable(true, Ammunition.NOT_A_NOTE));
	}

	@Test
	public void anUnstackableItemIsNot()
	{
		assertFalse("a platebody cannot be fired at anything",
			Ammunition.isConsumable(false, Ammunition.NOT_A_NOTE));
	}

	@Test
	public void aNoteIsNotMeteredEvenThoughItStacks()
	{
		// 799 is the note template the game reports through getNote(). Any value
		// other than -1 means "this item is a note", which is what matters here.
		assertFalse(Ammunition.isConsumable(true, 799));
	}

	@Test
	public void anUnstackableNoteIsRefusedByBothHalves()
	{
		assertFalse(Ammunition.isConsumable(false, 799));
	}

	@Test
	public void notANoteIsMinusOneRatherThanZero()
	{
		// Zero is a real item id and would be a real template id. The sentinel the
		// game uses is -1, and a rule written against 0 would meter every note in
		// the game.
		assertFalse(Ammunition.isConsumable(true, 0));
		assertTrue(Ammunition.isConsumable(true, -1));
	}
}
