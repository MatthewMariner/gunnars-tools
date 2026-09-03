package com.matthewmariner.gunnarstools;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.ui.overlay.components.TextComponent;
import net.runelite.client.util.QuantityFormatter;

/**
 * Marks the ammunition the trip needs, in the bank, with the quantity to
 * withdraw.
 *
 * <h2>The supported way to draw on a bank item</h2>
 *
 * <p>{@link WidgetItemOverlay} with {@link #showOnBank()}, which is RuneLite's
 * own mechanism for exactly this and not an interface this plugin goes looking
 * for. The base class asks {@code OverlayManager.getWidgetItems()} for the items
 * currently laid out and calls {@link #renderItemOverlay} once per visible one,
 * having already clipped to the container; {@code showOnBank()} is a
 * {@code drawAfterLayer} on the two bank item layers, so the drawing lands above
 * the icon and below nothing that matters.
 *
 * <p>Three plugins shipped with the client do the same thing the same way —
 * {@code ItemIdentificationOverlay} (which calls {@code showOnInventory()} and
 * {@code showOnBank()} together, the closest match to this), {@code
 * InventoryTagsOverlay} and {@code ItemChargeOverlay}. Verified against the
 * pinned 1.12.38 client jar rather than remembered.
 *
 * <p>Nothing here touches a menu entry, a click zone or a hidden component, so
 * none of {@code AGENTS.md}'s interface or menu restrictions are in play: it
 * draws a number over an item the player is already looking at.
 *
 * <h2>What it draws and what it decides</h2>
 *
 * <p>It decides nothing. The map of item id to quantity is
 * {@link TripPlanner#withdrawals}, computed at the kill that changed it and
 * unit-tested without a client; this class looks the id up and formats a number.
 *
 * <p>The one thing it reads off the widget is {@link WidgetItem#getQuantity()},
 * the amount actually banked, and it uses it for the colour only: green when the
 * bank holds enough for the trip, red when it does not. That is a comparison of
 * two measured quantities, and it answers the question a player standing at the
 * bank is about to ask anyway.
 */
class BankWithdrawalOverlay extends WidgetItemOverlay
{
	private static final Color ENOUGH = new Color(0x4C, 0xAF, 0x50);
	private static final Color SHORT = new Color(0xF4, 0x43, 0x36);

	private final GunnarsToolsPlugin plugin;
	private final GunnarsToolsConfig config;

	@Inject
	BankWithdrawalOverlay(GunnarsToolsPlugin plugin, GunnarsToolsConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		showOnBank();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem itemWidget)
	{
		if (!config.highlightBank())
		{
			return;
		}

		final Map<Integer, Long> withdrawals = plugin.getWithdrawals();
		final Long needed = withdrawals.get(itemId);
		if (needed == null || needed <= 0L)
		{
			return;
		}

		final Rectangle bounds = itemWidget.getCanvasBounds();
		final boolean enough = itemWidget.getQuantity() >= needed;
		final Color colour = enough ? ENOUGH : SHORT;

		graphics.setColor(colour);
		graphics.draw(bounds);

		final TextComponent quantity = new TextComponent();
		// quantityToRSDecimalStack takes an int and the plan is a long that
		// saturates rather than wrapping, so clamp for display. A number this large
		// is nonsense either way; rendering it as a negative would be nonsense that
		// looks deliberate.
		quantity.setText(QuantityFormatter.quantityToRSDecimalStack(
			(int) Math.min(needed, Integer.MAX_VALUE)));
		quantity.setColor(colour);
		quantity.setPosition(new Point(bounds.x, bounds.y + bounds.height));
		quantity.render(graphics);
	}
}
