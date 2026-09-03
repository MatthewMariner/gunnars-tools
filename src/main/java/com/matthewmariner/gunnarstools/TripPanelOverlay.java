package com.matthewmariner.gunnarstools;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * The trip panel: what the monster you last killed costs, and what to bring.
 *
 * <p>Deliberately small. This is a utility with one question to answer, and a
 * panel that grows a row per column of the record would be a dashboard nobody
 * reads mid-fight. Three items at most, two lines each, and the header.
 *
 * <p>It decides nothing. Every number on it comes off a {@link TripPlan} that
 * was computed once, at the kill that produced it or at the config change that
 * invalidated it — never here. {@code AGENTS.md} is explicit that overlay work
 * runs every frame and has to stay minimal, and there is a second reason on top
 * of the cost: an overlay cannot be exercised without a running client, so
 * anything decided in one is a decision no test can reach.
 *
 * <p>The one lookup it does make per frame is the item name, out of
 * {@link ItemManager}'s cache. That is what every stock plugin that labels an
 * item does, and holding a copy of the string would mean holding a cache the
 * client already holds.
 */
class TripPanelOverlay extends OverlayPanel
{
	/**
	 * How many item lines fit before the panel stops being glanceable. A monster
	 * with more than three metered items is one whose record has picked up
	 * contamination — loot moved into a looting bag, most likely — and the tail of
	 * that list is not what anybody is reading the panel for. The full set is
	 * still in the ledger and still in the debug log.
	 */
	private static final int MAX_ITEM_LINES = 3;

	private static final Color SOLID_COLOUR = new Color(0x4C, 0xAF, 0x50);
	private static final Color FAIR_COLOUR = new Color(0xCD, 0xDC, 0x39);
	private static final Color THIN_COLOUR = new Color(0xFF, 0x98, 0x00);
	private static final Color ANECDOTAL_COLOUR = new Color(0xF4, 0x43, 0x36);

	private final GunnarsToolsPlugin plugin;
	private final GunnarsToolsConfig config;
	private final ItemManager itemManager;

	@Inject
	TripPanelOverlay(GunnarsToolsPlugin plugin, GunnarsToolsConfig config, ItemManager itemManager)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.TOP_LEFT);
		getPanelComponent().setPreferredSize(new Dimension(180, 0));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showOverlay())
		{
			return null;
		}

		final NpcAmmoRecord subject = plugin.getPlanSubject();
		final List<TripPlan> plans = plugin.getPlan();
		if (subject == null || plans.isEmpty())
		{
			// Nothing measured yet. An empty panel is worse than no panel: it takes
			// up the corner and says nothing.
			return null;
		}

		getPanelComponent().getChildren().clear();

		getPanelComponent().getChildren().add(TitleComponent.builder()
			.text(subject.getNpcName())
			.build());

		final TripPlan first = plans.get(0);
		getPanelComponent().getChildren().add(LineComponent.builder()
			.left("for " + format(first.getTargetMonsters()) + " kills")
			.right("+" + first.getSafetyMarginPercent() + "%")
			.build());

		final int lines = Math.min(MAX_ITEM_LINES, plans.size());
		for (int i = 0; i < lines; i++)
		{
			final TripPlan plan = plans.get(i);
			final ConsumptionEstimate estimate = plan.getEstimate();

			getPanelComponent().getChildren().add(LineComponent.builder()
				.left(nameOf(plan.getItemId()))
				.right(format(plan.getBring()))
				.build());

			// The unit is spelled out because the two denominators are not the same
			// number under area damage, and this plugin has already had to correct a
			// claim that treated them as if they were.
			getPanelComponent().getChildren().add(LineComponent.builder()
				.left(String.format(Locale.ROOT, "  %.1f/%s",
					estimate.getPerMonster(), estimate.isAreaDamageSeen() ? "monster" : "kill"))
				.right("n=" + estimate.getAttributedKills() + " " + estimate.getConfidence().getLabel())
				.rightColor(colourOf(estimate.getConfidence()))
				.build());

			if (estimate.isAreaDamageSeen())
			{
				getPanelComponent().getChildren().add(LineComponent.builder()
					.left("  per kill")
					.right(String.format(Locale.ROOT, "%.1f over %d",
						estimate.getPerAttributedKill(), estimate.getMonstersPriced()))
					.build());
			}

			// The spread, in four observed quantities: cheapest kill, median,
			// ninetieth, dearest. This is the honest half of the confidence signal
			// — the band word says how many kills are behind the figure, and this
			// says how much they disagreed. "24/25/26/26" reads as a monster that
			// costs the same every time; "10/50/90/900" reads as one that does not,
			// and no single number can say that. Dearest item only, so a second and
			// third line stay at two rows each.
			if (i == 0)
			{
				getPanelComponent().getChildren().add(LineComponent.builder()
					.left("  spread")
					.right(estimate.getLowestKill() + "/" + estimate.getMedianKill()
						+ "/" + estimate.getNinetiethKill() + "/" + estimate.getHighestKill())
					.build());
			}

			// Same, and only when it is exactly true — see TripPlan on why it is
			// withheld under area damage rather than converted. It is what the trip
			// costs at the ninetieth-percentile kill, which is not always more than
			// the recommendation: a monster with one ruinous kill in ten has a mean
			// above its own shoulder.
			if (i == 0 && plan.getWorstCase().isPresent())
			{
				getPanelComponent().getChildren().add(LineComponent.builder()
					.left("  at 90th pct")
					.right(format(plan.getWorstCase().getAsLong()))
					.build());
			}

			if (estimate.getKillsWithConsumption() < estimate.getAttributedKills())
			{
				// A cost that only shows up on some kills is usually not a cost. Said
				// rather than filtered, because hiding a line hides a shortfall.
				getPanelComponent().getChildren().add(LineComponent.builder()
					.left("  spent on")
					.right(estimate.getKillsWithConsumption() + " of " + estimate.getAttributedKills())
					.build());
			}
		}

		return super.render(graphics);
	}

	private String nameOf(int itemId)
	{
		final ItemComposition composition = itemManager.getItemComposition(itemId);
		return composition == null ? "Item " + itemId : composition.getName();
	}

	private static String format(long quantity)
	{
		return String.format(Locale.ROOT, "%,d", quantity);
	}

	private static Color colourOf(Confidence confidence)
	{
		switch (confidence)
		{
			case SOLID:
				return SOLID_COLOUR;
			case FAIR:
				return FAIR_COLOUR;
			case THIN:
				return THIN_COLOUR;
			default:
				return ANECDOTAL_COLOUR;
		}
	}
}
