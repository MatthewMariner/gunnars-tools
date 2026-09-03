package com.matthewmariner.gunnarstools;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins {@link GunnarsToolsConfig}'s group and every key under it.
 *
 * <p>A config group name and a key name are hub-visible contracts the moment
 * anyone saves a setting: renaming either silently resets that setting for every
 * user, with no error and no migration path (see {@code AGENTS.md}). These are
 * deliberately not tautologies about a constant compared to itself in the
 * abstract — each fails the instant somebody edits the constant without meaning
 * to, which is the one moment this guard has a job to do. The constants are what
 * the {@code @ConfigItem} annotations are written in terms of, so a key cannot
 * drift from the thing being pinned.
 *
 * <p>The defaults are pinned too, and through a real implementation of the
 * interface rather than through reflection, which {@code AGENTS.md} forbids. A
 * default is not a contract in the way a key is — changing one is visible to a
 * user rather than silent — but a trip size that quietly became zero would turn
 * every answer into "bring nothing".
 */
public class GunnarsToolsConfigTest
{
	/** Every method has a {@code default}, so this is the shipped configuration. */
	private static final class Defaults implements GunnarsToolsConfig
	{
	}

	@Test
	public void theConfigGroupIsThePermanentOne()
	{
		assertEquals("gunnarstools", GunnarsToolsConfig.GROUP);
	}

	@Test
	public void theConfigKeysArePermanentToo()
	{
		assertEquals("tripKills", GunnarsToolsConfig.TRIP_KILLS);
		assertEquals("safetyMarginPct", GunnarsToolsConfig.SAFETY_MARGIN);
		assertEquals("showOverlay", GunnarsToolsConfig.SHOW_OVERLAY);
		assertEquals("highlightBank", GunnarsToolsConfig.HIGHLIGHT_BANK);
	}

	@Test
	public void aTripIsAHundredMonstersUntilTheUserSaysOtherwise()
	{
		// A trip size of zero would turn every answer into "bring nothing".
		assertEquals(100, new Defaults().tripKills());
	}

	@Test
	public void theDefaultMarginIsASmallHedgeRatherThanAnOpinion()
	{
		assertEquals(10, new Defaults().safetyMarginPercent());
	}

	@Test
	public void bothSurfacesAreOnByDefault()
	{
		GunnarsToolsConfig config = new Defaults();

		assertTrue(config.showOverlay());
		assertTrue(config.highlightBank());
	}
}
