package com.matthewmariner.gunnarstools;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * Gunnar's Tools — estimates how much ammunition (arrows, runes, revenant
 * ether) a Wilderness Slayer trip needs for N kills of a given monster, by
 * measuring the player's own consumption per kill rather than modelling it
 * from wiki data, so they carry no more than necessary into the Wilderness.
 *
 * <p><b>This class is a skeleton.</b> It carries no feature yet — no event
 * subscriptions, no overlay, no config beyond an empty group — because the
 * estimation approach is still being validated separately from this scaffold.
 * What is here is the shape everything else will hang off: the plugin
 * descriptor with real hub metadata, the config wiring, and a lifecycle that
 * is symmetric on purpose (see {@link #isActive()}), so that whatever this
 * plugin registers once the feature lands — an overlay, an event subscriber's
 * internal state, a scheduled task — has a {@code startUp()}/{@code shutDown()}
 * pair to hang off from day one rather than bolting teardown on afterwards.
 */
@Slf4j
@PluginDescriptor(
	name = "Gunnar's Tools",
	description = "Estimates ammunition needed for a Wilderness Slayer trip by measuring your "
		+ "own consumption per kill",
	tags = {"slayer", "wilderness", "ammo", "arrows", "runes", "ether", "inventory"}
)
public class GunnarsToolsPlugin extends Plugin
{
	/**
	 * Whether {@link #startUp()} has run and {@link #shutDown()} has not yet
	 * followed it.
	 *
	 * <p>Nothing in this skeleton reads it yet. It exists so the lifecycle has
	 * one concrete, testable promise from the first commit: shutdown leaves the
	 * plugin in the same state a fresh install would be in. Once a real feature
	 * lands — an overlay, a per-trip counter, a scheduled poll — its own
	 * teardown belongs here too, guarded the same way this flag already is.
	 */
	private boolean active;

	@Override
	protected void startUp()
	{
		log.debug("Gunnar's Tools starting");
		active = true;
	}

	@Override
	protected void shutDown()
	{
		log.debug("Gunnar's Tools stopping");
		active = false;
	}

	/**
	 * @return true between a {@link #startUp()} and its matching
	 * {@link #shutDown()}, false otherwise — including before the first
	 * {@code startUp()}. Package-private: {@code GunnarsToolsPluginLifecycleTest}
	 * is the only reader outside this class.
	 */
	boolean isActive()
	{
		return active;
	}

	@Provides
	GunnarsToolsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GunnarsToolsConfig.class);
	}
}
