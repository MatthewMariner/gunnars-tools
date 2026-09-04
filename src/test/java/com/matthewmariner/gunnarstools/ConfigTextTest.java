package com.matthewmariner.gunnarstools;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * The parsing every string this plugin keeps in the user's profile goes through.
 *
 * <p>Worth its own file because the value is not one this plugin controls. It is
 * written by one version and read by another, synchronised between machines, and
 * editable by hand. The promise is that nothing in here ever throws and that no
 * malformed field can be mistaken for a well-formed different one.
 */
public class ConfigTextTest
{
	@Test
	public void aNameWithASeparatorInItCannotEatTheNextField()
	{
		// The whole reason sanitising exists. "Sea, snake" written raw makes the
		// next field's parser read "snake" as a hitpoints value.
		assertEquals("Sea  snake", ConfigText.sanitise("Sea, snake"));
		assertEquals("a b c d", ConfigText.sanitise("a;b|c:d"));
	}

	@Test
	public void aNameThatIsNothingButSeparatorsBecomesAQuestionMark()
	{
		// Not the empty string. An empty field is indistinguishable from a
		// truncated write, and a monster called "" would read as one.
		assertEquals("?", ConfigText.sanitise(";;;"));
		assertEquals("?", ConfigText.sanitise("   "));
		assertEquals("?", ConfigText.sanitise(""));
		assertEquals("?", ConfigText.sanitise(null));
	}

	@Test
	public void aLongNameIsCutRatherThanRefused()
	{
		final StringBuilder tooLong = new StringBuilder();
		for (int i = 0; i < 200; i++)
		{
			tooLong.append('x');
		}

		assertEquals(ConfigText.MAX_NAME_LENGTH, ConfigText.sanitise(tooLong.toString()).length());
	}

	@Test
	public void aTrailingSeparatorProducesATrailingEmptyPart()
	{
		// String.split drops these, and dropping them is what would let a truncated
		// write read as a complete archive with a different last field.
		assertEquals(Arrays.asList("1", "a", ""), ConfigText.split("1;a;", ';'));
		assertEquals(Arrays.asList("", ""), ConfigText.split(";", ';'));
		assertEquals(Arrays.asList(""), ConfigText.split("", ';'));
	}

	@Test
	public void aFieldWithNoSeparatorIsOnePart()
	{
		assertEquals(Arrays.asList("Spindel"), ConfigText.split("Spindel", ';'));
	}

	@Test
	public void anUnparseableNumberFallsBackRatherThanThrowing()
	{
		assertEquals(-7, ConfigText.parseInt("not a number", -7));
		assertEquals(-7, ConfigText.parseInt("", -7));
		assertEquals(-7, ConfigText.parseInt("99999999999999999999", -7));
		assertEquals(0L, ConfigText.parseLong("nonsense"));
	}

	@Test
	public void surroundingSpaceIsToleratedOnNumbers()
	{
		// A hand-edited profile is the case this is for.
		assertEquals(5265, ConfigText.parseInt("  5265 ", -1));
		assertEquals(42L, ConfigText.parseLong(" 42"));
	}
}
