package com.matthewmariner.gunnarstools;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Collections;
import net.runelite.api.widgets.WidgetItem;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The parts of the two overlays that can be reached with no game running: their
 * switches, the lookup that decides whether an item is marked at all, and the
 * colour that says whether the bank holds enough.
 *
 * <p>An overlay is mostly drawing, and drawing wants a client. But three
 * decisions in these two classes are not drawing — "is this feature on", "is
 * this one of the items the trip needs", and "does the bank have enough" — and
 * all three are the sort of thing that fails silently. A switch that does
 * nothing is a setting the user cannot turn off; a lookup that matches
 * everything paints the whole bank.
 *
 * <p>The two colour tests render onto a {@link BufferedImage} and read the pixel
 * back. That is a real assertion about what a player sees rather than about
 * which branch ran, and the corner of the highlight rectangle is a deterministic
 * place to look: {@code Graphics2D.draw} on an unantialiased image puts the
 * outline exactly on the bounds.
 */
public class OverlaySwitchTest
{
	private static final int ARROW = 11;
	private static final int BOLT = 9144;
	private static final int SPINDEL = 5265;

	private static final int GREEN = 0xFF4CAF50;
	private static final int RED = 0xFFF44336;

	private final FakeConfig config = new FakeConfig();

	/** A plugin whose trip wants 2,000 arrows and nothing else. */
	private GunnarsToolsPlugin planned()
	{
		GunnarsToolsPlugin plugin = new GunnarsToolsPlugin();
		plugin.config = config;
		plugin.overlayRegistry = new RecordingOverlays();
		plugin.tripPanelOverlay = new TripPanelOverlay(plugin, config, null);
		plugin.bankWithdrawalOverlay = new BankWithdrawalOverlay(plugin, config);

		config.withTripKills(100).withSafetyMargin(0);

		AmmoTally window = new AmmoTally();
		window.add(new AmmoDelta(Collections.singletonMap(ARROW, 20L), Collections.emptyMap()));
		plugin.getLedger().apply(Attribution.kill(
			new FoughtNpc(40, SPINDEL, "Spindel", new int[]{130, 130, 130, 200, 1, 130}), window, 0));
		plugin.rebuildPlan();

		return plugin;
	}

	// --- the panel ------------------------------------------------------------

	@Test
	public void thePanelDrawsNothingWhenItsSwitchIsOff()
	{
		// Null graphics on purpose. With the switch honoured nothing is touched; the
		// moment it is not, the very next line reads an item name off an item
		// manager this test has not got.
		GunnarsToolsPlugin plugin = planned();
		config.withShowOverlay(false);

		assertNull(plugin.tripPanelOverlay.render(null));
	}

	@Test
	public void thePanelDrawsNothingBeforeTheFirstKill()
	{
		GunnarsToolsPlugin plugin = new GunnarsToolsPlugin();
		plugin.config = config.withShowOverlay(true);

		assertNull(new TripPanelOverlay(plugin, config, null).render(null));
	}

	// --- the bank highlight ---------------------------------------------------

	@Test
	public void theBankHighlightDrawsNothingWhenItsSwitchIsOff()
	{
		GunnarsToolsPlugin plugin = planned();
		config.withHighlightBank(false);

		plugin.bankWithdrawalOverlay.renderItemOverlay(null, ARROW, null);
	}

	@Test
	public void theBankHighlightIgnoresAnItemTheTripDoesNotNeed()
	{
		// Most of what is in a bank. Marking it would paint every slot.
		GunnarsToolsPlugin plugin = planned();

		plugin.bankWithdrawalOverlay.renderItemOverlay(null, BOLT, null);
	}

	@Test
	public void anItemTheBankHasEnoughOfIsMarkedGreen()
	{
		assertEquals(GREEN, cornerColourFor(2000));
	}

	@Test
	public void anItemTheBankIsOneShortOfIsMarkedRed()
	{
		// One below what the trip needs, not zero. The comparison is the whole
		// decision, and a boundary is where a comparison goes wrong.
		assertEquals(RED, cornerColourFor(1999));
	}

	@Test
	public void anItemTheBankHasExactlyEnoughOfIsMarkedGreen()
	{
		assertEquals(GREEN, cornerColourFor(2000));
		assertEquals(GREEN, cornerColourFor(2001));
	}

	/**
	 * Renders the highlight over a bank slot holding {@code banked} arrows and
	 * returns the colour of the top-left corner of the box it drew.
	 */
	private int cornerColourFor(int banked)
	{
		GunnarsToolsPlugin plugin = planned();
		assertEquals("the trip wants two thousand, so the boundary is there",
			Long.valueOf(2000L), plugin.getWithdrawals().get(ARROW));

		BufferedImage canvas = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = canvas.createGraphics();
		Rectangle bounds = new Rectangle(4, 4, 32, 32);

		plugin.bankWithdrawalOverlay.renderItemOverlay(graphics, ARROW,
			new WidgetItem(ARROW, banked, bounds, null, null));
		graphics.dispose();

		return canvas.getRGB(bounds.x, bounds.y);
	}
}
