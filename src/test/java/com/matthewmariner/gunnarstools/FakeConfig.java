package com.matthewmariner.gunnarstools;

/**
 * A {@link GunnarsToolsConfig} whose values a test can set.
 *
 * <p>Every method on the real interface is a {@code default}, so this starts out
 * as the shipped defaults and only diverges where a test says so — which means a
 * test that forgets to set something gets the value a user would get rather than
 * a zero.
 */
final class FakeConfig implements GunnarsToolsConfig
{
	private int tripKills = GunnarsToolsConfig.super.tripKills();
	private int safetyMarginPercent = GunnarsToolsConfig.super.safetyMarginPercent();
	private boolean showOverlay = GunnarsToolsConfig.super.showOverlay();
	private boolean highlightBank = GunnarsToolsConfig.super.highlightBank();

	@Override
	public int tripKills()
	{
		return tripKills;
	}

	@Override
	public int safetyMarginPercent()
	{
		return safetyMarginPercent;
	}

	@Override
	public boolean showOverlay()
	{
		return showOverlay;
	}

	@Override
	public boolean highlightBank()
	{
		return highlightBank;
	}

	FakeConfig withTripKills(int kills)
	{
		this.tripKills = kills;
		return this;
	}

	FakeConfig withSafetyMargin(int percent)
	{
		this.safetyMarginPercent = percent;
		return this;
	}

	FakeConfig withShowOverlay(boolean show)
	{
		this.showOverlay = show;
		return this;
	}

	FakeConfig withHighlightBank(boolean highlight)
	{
		this.highlightBank = highlight;
		return this;
	}
}
