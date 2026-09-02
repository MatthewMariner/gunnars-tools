package com.matthewmariner.gunnarstools;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * Pins {@link GunnarsToolsConfig#GROUP}.
 *
 * <p>The config group name is a hub-visible contract the moment anyone saves
 * a setting under it: renaming it silently resets every saved setting for
 * every user, with no error and no migration path (see {@code AGENTS.md}).
 * This test is deliberately not a tautology about a constant compared to
 * itself in the abstract — it fails the instant somebody edits
 * {@code GROUP} without meaning to, which is the one moment this guard has a
 * job to do.
 */
public class GunnarsToolsConfigTest
{
	@Test
	public void theConfigGroupIsThePermanentOne()
	{
		assertEquals("gunnarstools", GunnarsToolsConfig.GROUP);
	}
}
