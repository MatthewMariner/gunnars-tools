package com.matthewmariner.gunnarstools;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;

/**
 * The plugin's dials. Empty for now, deliberately.
 *
 * <p>This is scaffolding, built ahead of the feature it will configure — the
 * measured-consumption ammo estimator is still being designed. Adding
 * {@code @ConfigItem}s here now would mean guessing that shape (a target kill
 * count? a per-monster buffer percentage? a warning threshold?) before the
 * design lands, and every guess becomes a permanent hub-visible key the moment
 * a user saves it.
 *
 * <p><b>{@code keyName}s are permanent once shipped.</b> RuneLite writes them
 * into the user's profile, so renaming one silently resets that setting for
 * everyone who had it — see {@code AGENTS.md}. When items are added here, each
 * {@code keyName} should be a machine name chosen independently of its label,
 * exactly as {@link #GROUP} already is independent of the "Gunnar's Tools"
 * display name above it.
 */
@ConfigGroup(GunnarsToolsConfig.GROUP)
public interface GunnarsToolsConfig extends Config
{
	String GROUP = "gunnarstools";
}
