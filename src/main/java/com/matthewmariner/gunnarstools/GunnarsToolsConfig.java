package com.matthewmariner.gunnarstools;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

/**
 * The plugin's dials: how big a trip is, how much slack to leave on top, and
 * which of the two surfaces to draw.
 *
 * <p><b>{@code keyName}s are permanent once shipped.</b> RuneLite writes them
 * into the user's profile, so renaming one silently resets that setting for
 * everyone who had it — see {@code AGENTS.md}. Each one is therefore a named
 * constant rather than a string literal in the annotation, for the same reason
 * {@link #GROUP} is: the constant is what a test can pin, and pinning it means a
 * rename cannot happen by accident. Annotation values have to be compile-time
 * constants, and a {@code static final String} is one, so nothing is lost by
 * spelling it this way.
 *
 * <p>The machine names are chosen independently of the labels above them. A
 * label is free to be reworded; a key is not.
 */
@ConfigGroup(GunnarsToolsConfig.GROUP)
public interface GunnarsToolsConfig extends Config
{
	String GROUP = "gunnarstools";

	String TRIP_KILLS = "tripKills";
	String SAFETY_MARGIN = "safetyMarginPct";
	String SHOW_OVERLAY = "showOverlay";
	String HIGHLIGHT_BANK = "highlightBank";

	/**
	 * How many monsters the trip is for.
	 *
	 * <p>The maximum is not a guess about what anybody would type — it is what
	 * keeps a config value out of the arithmetic's overflow range. The ceiling in
	 * {@link TripPlan} saturates rather than wrapping either way, so this is the
	 * outer of two guards rather than the only one.
	 */
	@ConfigItem(
		keyName = TRIP_KILLS,
		name = "Trip size",
		description = "How many of the monster this trip is for. The estimate is multiplied by "
			+ "this.",
		position = 1
	)
	@Range(min = 1, max = 100000)
	default int tripKills()
	{
		return 100;
	}

	/**
	 * Extra to carry on top of the measurement, as a percentage.
	 *
	 * <p>Ten per cent by default, which is a small hedge rather than a
	 * recommendation — the honest input to this dial is the sample count and the
	 * spread the overlay prints next to the figure, and a number measured over
	 * four kills wants a much larger margin than one measured over four hundred.
	 * There is deliberately no automatic widening: see {@link TripPlan}.
	 */
	@ConfigItem(
		keyName = SAFETY_MARGIN,
		name = "Safety margin",
		description = "Extra to carry on top of the measured figure. Widen it when the sample "
			+ "count is small.",
		position = 2
	)
	@Range(min = 0, max = 200)
	@Units(Units.PERCENT)
	default int safetyMarginPercent()
	{
		return 10;
	}

	@ConfigItem(
		keyName = SHOW_OVERLAY,
		name = "Show the trip panel",
		description = "Draws the estimate and what to bring for the monster you last killed.",
		position = 3
	)
	default boolean showOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = HIGHLIGHT_BANK,
		name = "Highlight in the bank",
		description = "Marks the ammunition you need in the bank with the quantity to withdraw.",
		position = 4
	)
	default boolean highlightBank()
	{
		return true;
	}
}
