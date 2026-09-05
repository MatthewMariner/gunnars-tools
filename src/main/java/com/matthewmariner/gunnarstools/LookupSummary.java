package com.matthewmariner.gunnarstools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import javax.annotation.Nullable;

/**
 * The answer, in four lines, for the side panel that chose the monster.
 *
 * <p>The on-screen {@link TripPanelOverlay} is the full reading — the spread, the
 * percentile, the per-kill denominator, the disclosures. This is the short
 * version that appears under the search box, so that picking a monster in the
 * panel <em>answers the question in the panel</em> rather than sending the reader
 * off to find a different surface. The owner's complaint was that he could not
 * work out how to drive this plugin; "you clicked the right thing, now look
 * somewhere else" is that complaint with an extra step.
 *
 * <p>It is a deliberate summary and not a second copy of the overlay. What it
 * keeps is exactly what a bank trip needs — which monster, how big, what to
 * bring, and how much of that is evidence — and it says where the rest is.
 *
 * <p><b>The one thing it may never do is let an estimate read as a
 * measurement.</b> {@link ProjectedNeed} and {@link TripPlan} are separate types
 * precisely so a caller cannot forget which it is holding, and this one says so
 * on its own line and prefixes every estimated quantity with a tilde, the same
 * two defences the overlay uses. A summary that dropped the label to save a line
 * would be the failure this plugin is built around, introduced by a layout
 * decision.
 *
 * <p>Static and taking everything as arguments, so the whole of it runs with no
 * game client and no Swing. Resolving an item id to a name is the one thing that
 * needs the client, so it arrives as a function.
 */
final class LookupSummary
{
	/** How many item rows fit before the summary stops being a summary. */
	private static final int MAX_ITEMS = 2;

	private LookupSummary()
	{
	}

	/**
	 * What clicking a row does, when clicking it does anything.
	 *
	 * <p>Kept here rather than in the panel because it is a decision — "there is a
	 * pin, so there has to be a way out of it" — and a decision inside a Swing
	 * component is one no test can reach. The panel turns it into a mouse listener
	 * and nothing more.
	 */
	enum Action
	{
		/** Text. Most rows. */
		NONE,

		/**
		 * Unpins, so the plan goes back to following what the player is fighting.
		 *
		 * <p>Offered whenever the answer is about a pinned monster, because pinning
		 * is the one thing the lookup does and a surface that can only be driven one
		 * way is half a control. Blanking the "Plan for" setting has always cleared a
		 * pin; that is not discoverable from a panel that never mentions it.
		 */
		CLEAR_PIN
	}

	/** One row: a label, a value, whether it is a caveat, and what clicking it does. */
	static final class Line
	{
		private final String left;
		private final String right;
		private final boolean caveat;
		private final Action action;

		Line(String left, String right, boolean caveat)
		{
			this(left, right, caveat, Action.NONE);
		}

		Line(String left, String right, boolean caveat, Action action)
		{
			this.left = left;
			this.right = right;
			this.caveat = caveat;
			this.action = action;
		}

		/** Never null. {@link Action#NONE} for a row that is only text. */
		Action getAction()
		{
			return action;
		}

		String getLeft()
		{
			return left;
		}

		/** May be empty, which the panel draws as a label spanning the row. */
		String getRight()
		{
			return right;
		}

		/**
		 * Whether this row is a warning, a waiting reason or an estimate label rather
		 * than a number to act on. The panel colours it; a test asserts it is set.
		 */
		boolean isCaveat()
		{
			return caveat;
		}

		@Override
		public String toString()
		{
			return "Line(" + left + " | " + right + (caveat ? " | caveat" : "")
				+ (action == Action.NONE ? "" : " | " + action) + ")";
		}
	}

	/**
	 * @param advice   what {@link TripAdvisor} decided, or null before there is one
	 * @param itemName resolves an item id to its name. In production that is
	 *                 {@code ItemManager::getItemComposition}; in a test it is a
	 *                 lambda.
	 * @return the rows to draw, never null and never empty. Every state has
	 * something to say, including the states that have no answer — an empty panel
	 * and a broken one were the same picture once already in this project, and the
	 * fix is not one that only applies to overlays.
	 */
	static List<Line> of(@Nullable TripAdvice advice, IntFunction<String> itemName)
	{
		final List<Line> lines = new ArrayList<>();
		if (advice == null)
		{
			lines.add(new Line("nothing planned yet", "", true));
			lines.add(new Line("search above and pick a monster", "", false));
			return Collections.unmodifiableList(lines);
		}

		final PlanTarget target = advice.getTarget();
		if (target == null)
		{
			// No monster, but always a reason. The two that come from a name in the
			// settings — a name matching nothing, and a name matching several — are
			// also the two a player cannot get out of without knowing that blanking
			// the field is what clears it, so those get the way out on the row below.
			lines.add(new Line(advice.getWaitingFor().getHeadline(), "", true));
			lines.add(new Line(advice.getWaitingFor().getDetail(), "", false));

			if (advice.getWaitingFor() == TripAdvice.Waiting.UNKNOWN_MONSTER
				|| advice.getWaitingFor() == TripAdvice.Waiting.AMBIGUOUS_MONSTER)
			{
				lines.add(new Line("the name in \"Plan for\"", "clear", false, Action.CLEAR_PIN));
			}
			return Collections.unmodifiableList(lines);
		}

		lines.add(new Line(target.getName(),
			target.hasHitpoints() ? target.getHitpoints() + " hp" : "hp unknown", false));

		if (advice.getWaitingFor() != TripAdvice.Waiting.NOTHING)
		{
			lines.add(new Line(advice.getWaitingFor().getHeadline(), "", true));
			lines.add(new Line(advice.getWaitingFor().getDetail(), "", false));
		}
		else if (advice.isMeasured())
		{
			measured(lines, advice.getMeasured(), itemName);
		}
		else
		{
			estimated(lines, advice.getProjected(), itemName);
		}

		// Last, and on every path that had a monster at all — including the ones with
		// no answer. "I picked the wrong spider and now the panel says it has never
		// been measured" is exactly the moment somebody needs the way back out, and a
		// waiting state is not a reason to withhold it.
		if (target.getSource() == PlanTarget.Source.PINNED)
		{
			lines.add(new Line(target.getSource().getLabel(), "clear", false, Action.CLEAR_PIN));
		}
		return Collections.unmodifiableList(lines);
	}

	private static void measured(List<Line> lines, List<TripPlan> plans, IntFunction<String> itemName)
	{
		final TripPlan first = plans.get(0);
		lines.add(new Line("for " + format(first.getTargetMonsters()) + " kills",
			"+" + first.getSafetyMarginPercent() + "%", false));

		final int rows = Math.min(MAX_ITEMS, plans.size());
		for (int i = 0; i < rows; i++)
		{
			final TripPlan plan = plans.get(i);
			lines.add(new Line(itemName.apply(plan.getItemId()), format(plan.getBring()), false));
		}

		final ConsumptionEstimate estimate = first.getEstimate();
		lines.add(new Line("measured", "n=" + estimate.getAttributedKills()
			+ " " + estimate.getConfidence().getLabel(), false));
	}

	private static void estimated(List<Line> lines, List<ProjectedNeed> needs,
		IntFunction<String> itemName)
	{
		final ProjectedNeed first = needs.get(0);

		// Before the numbers, not after them. A reader who stops at the first figure
		// has to have already been told it is not one.
		lines.add(new Line("estimate", "not measured", true));
		lines.add(new Line("for " + format(first.getTargetMonsters()) + " kills",
			"+" + first.getSafetyMarginPercent() + "%", false));

		final int rows = Math.min(MAX_ITEMS, needs.size());
		for (int i = 0; i < rows; i++)
		{
			final ProjectedNeed need = needs.get(i);
			lines.add(new Line(itemName.apply(need.getItemId()), "~" + format(need.getBring()), false));
		}

		lines.add(new Line(first.getBasis().getLabel(),
			"n=" + first.getSourceMonsters() + " " + first.getConfidence().getLabel(), false));
	}

	private static String format(long quantity)
	{
		return String.format(Locale.ROOT, "%,d", quantity);
	}
}
