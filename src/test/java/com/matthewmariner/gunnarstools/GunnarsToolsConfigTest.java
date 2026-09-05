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
		assertEquals("planFor", GunnarsToolsConfig.PLAN_FOR);
		assertEquals("estimateBeforeMeasuring", GunnarsToolsConfig.ESTIMATE_BEFORE_MEASURING);
		assertEquals("rememberBetweenSessions", GunnarsToolsConfig.REMEMBER_BETWEEN_SESSIONS);
		assertEquals("subtractCarried", GunnarsToolsConfig.SUBTRACT_CARRIED);
		assertEquals("showOverlay", GunnarsToolsConfig.SHOW_OVERLAY);
		assertEquals("highlightBank", GunnarsToolsConfig.HIGHLIGHT_BANK);
		assertEquals("showLookup", GunnarsToolsConfig.SHOW_LOOKUP);
	}

	@Test
	public void theTwoKeysThePluginWritesForItselfArePermanentAsWell()
	{
		// More so, if anything. A renamed dial resets a setting the user can put
		// back in one click; a renamed archive key silently throws away every
		// monster they have measured, and nothing tells them it happened.
		assertEquals("pinnedTarget", GunnarsToolsConfig.PINNED_TARGET);
		assertEquals("archive", GunnarsToolsConfig.ARCHIVE);
	}

	@Test
	public void noTwoKeysAreTheSameString()
	{
		// Two settings sharing a key is a single value read through two names, which
		// looks like one of them not working.
		java.util.Set<String> keys = new java.util.HashSet<>(java.util.Arrays.asList(
			GunnarsToolsConfig.TRIP_KILLS,
			GunnarsToolsConfig.SAFETY_MARGIN,
			GunnarsToolsConfig.PLAN_FOR,
			GunnarsToolsConfig.ESTIMATE_BEFORE_MEASURING,
			GunnarsToolsConfig.REMEMBER_BETWEEN_SESSIONS,
			GunnarsToolsConfig.SUBTRACT_CARRIED,
			GunnarsToolsConfig.SHOW_OVERLAY,
			GunnarsToolsConfig.HIGHLIGHT_BANK,
			GunnarsToolsConfig.SHOW_LOOKUP,
			GunnarsToolsConfig.PINNED_TARGET,
			GunnarsToolsConfig.ARCHIVE));

		assertEquals(11, keys.size());
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
	public void allThreeSurfacesAreOnByDefault()
	{
		GunnarsToolsConfig config = new Defaults();

		assertTrue(config.showOverlay());
		assertTrue(config.highlightBank());

		// And the lookup most of all. The complaint that produced it was not "I
		// disagree with the numbers", it was "I don't know how to test this, I'm
		// lost" — and a sidebar button that has to be switched on is that complaint
		// with an extra step.
		assertTrue(config.showLookup());
	}

	@Test
	public void thePluginAnswersOutOfTheBoxRatherThanAfterBeingConfigured()
	{
		// All three default on, and all three for the same reason: the complaint
		// that produced them was that the plugin said nothing until it had been
		// used for a while, and a fix that has to be switched on is the same
		// complaint with an extra step.
		GunnarsToolsConfig config = new Defaults();

		assertTrue("an estimate before the first kill is the whole cold start",
			config.estimateBeforeMeasuring());
		assertTrue("and the answer at a bank depends on the archive",
			config.rememberBetweenSessions());
		assertTrue("a bank number that ignores the inventory is a second trip's worth",
			config.subtractCarried());
	}

	@Test
	public void nothingIsPlannedForUntilTheUserSaysSo()
	{
		// Empty means "follow what I am fighting". A default monster name would be a
		// bundled monster table with one row.
		assertEquals("", new Defaults().planFor());
	}

	@Test
	public void thePluginsOwnStateStartsEmpty()
	{
		assertEquals("", new Defaults().pinnedTarget());
		assertEquals("", new Defaults().archive());
	}
}
