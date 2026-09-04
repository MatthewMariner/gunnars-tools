package com.matthewmariner.gunnarstools;

/**
 * Where the plugin's own saved state is written.
 *
 * <p>Reading goes through {@link GunnarsToolsConfig} like every other setting —
 * RuneLite's config proxy already answers {@code config.archive()} from the
 * profile — so this covers only the direction the proxy has no method for.
 *
 * <p><b>Why an interface rather than {@code ConfigManager} itself.</b> The same
 * reason as {@link OverlayRegistry}, one file over: a {@code ConfigManager} needs
 * a session, a profile and a scheduled executor to exist at all, and there is no
 * mocking framework on this classpath. Two methods behind an interface make "a
 * kill writes the archive, and turning persistence off erases it" assertions
 * rather than readings of the source.
 *
 * <p><b>This is the only writing path, and it must stay the only one.</b>
 * {@code AGENTS.md} confines a plugin's own data to the {@code .runelite}
 * directory or to the config store; this project takes the second, which means
 * uninstalling the plugin takes the data with it and nothing here ever opens a
 * file. A future need for more space is a reason to reconsider the format, not to
 * add a second mechanism beside this one.
 */
interface ConfigStore
{
	/**
	 * @param key   one of {@link GunnarsToolsConfig}'s key constants
	 * @param value the new value. Empty means "back to the default", which for
	 *              every key this writes is the empty string — so an empty write
	 *              and an unset are the same state, and callers do not have to know
	 *              which one the store performs.
	 */
	void write(String key, String value);
}
