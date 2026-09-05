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
	String PLAN_FOR = "planFor";
	String ESTIMATE_BEFORE_MEASURING = "estimateBeforeMeasuring";
	String REMEMBER_BETWEEN_SESSIONS = "rememberBetweenSessions";
	String SUBTRACT_CARRIED = "subtractCarried";
	String SHOW_OVERLAY = "showOverlay";
	String HIGHLIGHT_BANK = "highlightBank";
	String SHOW_LOOKUP = "showLookup";

	/**
	 * The two keys the plugin writes for itself rather than for the user.
	 *
	 * <p>Hidden rather than absent, because they are the plugin's own saved state
	 * and {@code AGENTS.md} points at the config store for exactly that: it lives
	 * in the user's profile beside their settings, and uninstalling the plugin
	 * takes it with them. Neither is a file, which is the other half of the same
	 * rule.
	 */
	String PINNED_TARGET = "pinnedTarget";

	String ARCHIVE = "archive";

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

	/**
	 * Which monster the plan is about, when the player wants to say rather than be
	 * inferred at.
	 *
	 * <p>The setting that makes this plugin usable before a trip instead of only
	 * after one. Left empty it changes nothing: the plan follows what you are
	 * fighting, and falls back to what you last killed. Typed in, it overrides both
	 * — which is the state a player is in at a bank, where there is no monster to
	 * infer from and the whole question is what to pack.
	 *
	 * <p>It is a free text field rather than a dropdown, and the reason has changed
	 * since it was written. It used to say that a list would have to come from
	 * somewhere and that every candidate source was one this plugin refuses — a
	 * bundled monster table cannot resolve nineteen of Krystilia's thirty-six tasks
	 * (see {@link FoughtNpc}), and a list of what you have measured excludes the
	 * monster you are about to fight for the first time. Both halves of that are
	 * still true. What was missing was a third source that is neither: the game's
	 * own cache, read at runtime by {@link MonsterCatalogue}, which is not bundled,
	 * not stale, and knows every monster rather than only the ones you have killed.
	 *
	 * <p>So there is a list now, and it lives in the sidebar rather than in this
	 * field — because the interesting names are the ambiguous ones and a settings
	 * control has nowhere to show a choice. This field still resolves a name
	 * outright when exactly one monster answers to it, and reports
	 * {@link TripAdvice.Waiting#AMBIGUOUS_MONSTER} when several do.
	 *
	 * <p>A name that matches nothing is reported on the panel rather than ignored.
	 * Falling back silently would plan for a different monster under the name the
	 * player chose.
	 */
	@ConfigItem(
		keyName = PLAN_FOR,
		name = "Plan for",
		description = "The monster to plan for, by name. Leave it empty to follow what you are "
			+ "fighting. Find one in the sidebar lookup, or shift-right-click a monster and "
			+ "choose \"Plan trip\".",
		position = 3
	)
	default String planFor()
	{
		return "";
	}

	/**
	 * Whether to answer at all before this monster has been measured.
	 *
	 * <p>On, the panel will scale a rate you measured on something else onto the
	 * monster in front of you and label it an estimate. Off, it says nothing until
	 * it has watched a kill. The distinction is worth a setting because it is the
	 * one number in this plugin that rests on an assumption — that your damage per
	 * shot is the same against both monsters — and a player who wants only
	 * measurements should be able to have only measurements.
	 *
	 * <p>Default on. A blank panel at a bank is what made the first version of this
	 * plugin unusable.
	 */
	@ConfigItem(
		keyName = ESTIMATE_BEFORE_MEASURING,
		name = "Estimate before measuring",
		description = "Show an estimate for a monster you have not killed yet, scaled from one you "
			+ "have. Always labelled as an estimate.",
		position = 4
	)
	default boolean estimateBeforeMeasuring()
	{
		return true;
	}

	/**
	 * Whether a session's totals survive into the next one.
	 *
	 * <p>On, a compact summary — monster, setup, monsters priced, gross quantities
	 * — is kept in your RuneLite profile, so the plugin has something to say the
	 * next time you stand at a bank. It is never republished as a measurement; see
	 * {@link AmmoArchive}.
	 *
	 * <p>Turning it off also forgets what is already stored, which makes this the
	 * plugin's reset as well as its switch. There is deliberately no second control
	 * for that: two ways to clear the same data is how one of them ends up not
	 * clearing all of it.
	 */
	@ConfigItem(
		keyName = REMEMBER_BETWEEN_SESSIONS,
		name = "Remember between sessions",
		description = "Keep a summary of what each monster cost, so there is an answer at the bank. "
			+ "Turning this off forgets what is stored.",
		position = 5
	)
	default boolean rememberBetweenSessions()
	{
		return true;
	}

	/**
	 * Whether the bank highlight counts what the player is already carrying.
	 *
	 * <p>On, the number over a bank slot is what to <em>withdraw</em>; off, it is
	 * what the trip needs in total. This is a real decision made standing at a bank
	 * — half a trip's arrows are usually already in the inventory — and the version
	 * without it is a documented shortcoming of the highlight rather than a design
	 * choice.
	 *
	 * <p>It never lowers what the trip needs. The panel keeps showing the total; the
	 * subtraction happens only where the question is "how many more".
	 */
	@ConfigItem(
		keyName = SUBTRACT_CARRIED,
		name = "Subtract what you carry",
		description = "The bank highlight shows what is left to withdraw rather than what the trip "
			+ "needs in total.",
		position = 6
	)
	default boolean subtractCarried()
	{
		return true;
	}

	@ConfigItem(
		keyName = SHOW_OVERLAY,
		name = "Show the trip panel",
		description = "Draws what to bring for the monster you are planning for, and what it is "
			+ "waiting for when it cannot say yet.",
		position = 7
	)
	default boolean showOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = HIGHLIGHT_BANK,
		name = "Highlight in the bank",
		description = "Marks the ammunition you need in the bank with the quantity to withdraw.",
		position = 8
	)
	default boolean highlightBank()
	{
		return true;
	}

	/**
	 * Whether the monster lookup appears in RuneLite's sidebar.
	 *
	 * <p>Default on, and it is the setting least likely to want turning off. The
	 * complaint this plugin's lookup was built for was not "I disagree with the
	 * numbers", it was "I don't know how to test this, I'm lost" — and the answer to
	 * that has to be visible without being told about it. A sidebar button that
	 * appears the moment the plugin is enabled is the only surface here that
	 * announces itself; the panel behind it is also the only place an umbrella name
	 * like "spider" can be turned into one specific monster.
	 *
	 * <p>Off, the sidebar button goes away and the game's monster list stops being
	 * read, so the "Plan for" field goes back to matching only monsters this plugin
	 * has already seen. That is the previous behaviour rather than a broken one, and
	 * it is why this is a switch rather than a warning.
	 */
	@ConfigItem(
		keyName = SHOW_LOOKUP,
		name = "Show the monster lookup",
		description = "Adds a sidebar panel for finding a monster by name, so a trip can be planned "
			+ "at a bank without one on screen.",
		position = 9
	)
	default boolean showLookup()
	{
		return true;
	}

	/**
	 * The pinned monster's identity, written by the "Plan trip" menu entry.
	 *
	 * <p>The companion to {@link #planFor()}, and hidden because it is machinery
	 * rather than a setting: {@code npcId,name,hitpoints}, so a monster pinned in
	 * the Wilderness can still be planned for at a bank where nothing of it is on
	 * screen. It is only consulted when its name matches what is in the visible
	 * field, so clearing that field is enough to clear the pin.
	 */
	@ConfigItem(
		keyName = PINNED_TARGET,
		name = "",
		description = "",
		hidden = true
	)
	default String pinnedTarget()
	{
		return "";
	}

	/**
	 * The archive, serialised. Hidden for the same reason, and never edited by hand
	 * — though it survives being edited by hand; see {@link AmmoArchive}.
	 */
	@ConfigItem(
		keyName = ARCHIVE,
		name = "",
		description = "",
		hidden = true
	)
	default String archive()
	{
		return "";
	}
}
