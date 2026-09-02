package com.matthewmariner.gunnarstools;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Proves the one lifecycle promise this skeleton makes: {@code startUp()} and
 * {@code shutDown()} are symmetric, so whatever the estimator feature
 * registers later has a clean pair to hang off from day one — the same
 * invariant lively-cities pins as "shutdown leaves zero registered".
 *
 * <p>Constructed directly rather than through Guice: {@code startUp()} and
 * {@code shutDown()} are {@code protected}, which same-package test code can
 * call without a subclass, and this plugin has no {@code @Inject} fields yet
 * for Guice to need to satisfy. That is also why this test is real rather than
 * a placeholder — flip either method to a no-op, or drop the {@code active =}
 * assignment from one of them, and {@code isActive()} reports the wrong thing
 * and this goes red.
 */
public class GunnarsToolsPluginLifecycleTest
{
	@Test
	public void startUpAndShutDownAreSymmetric()
	{
		GunnarsToolsPlugin plugin = new GunnarsToolsPlugin();

		assertFalse("must not be active before startUp", plugin.isActive());

		plugin.startUp();
		assertTrue("must be active after startUp", plugin.isActive());

		plugin.shutDown();
		assertFalse("shutDown must leave it inactive again", plugin.isActive());
	}

	@Test
	public void startUpIsIdempotentAndShutDownStillClears()
	{
		GunnarsToolsPlugin plugin = new GunnarsToolsPlugin();

		plugin.startUp();
		plugin.startUp();
		assertTrue(plugin.isActive());

		plugin.shutDown();
		assertFalse("one shutDown clears it regardless of how many startUps preceded it",
			plugin.isActive());
	}
}
