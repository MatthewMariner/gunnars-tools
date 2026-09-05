package com.matthewmariner.gunnarstools;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link GunnarsToolsConfig} whose values a test can set, and the
 * {@link ConfigStore} that writes back into it.
 *
 * <p>Every method on the real interface is a {@code default}, so this starts out
 * as the shipped defaults and only diverges where a test says so — which means a
 * test that forgets to set something gets the value a user would get rather than
 * a zero.
 *
 * <p><b>It is both halves on purpose.</b> In production a write goes through
 * {@code ConfigManager} and comes back out of the config proxy, so the plugin
 * reads what it wrote. A fake that only recorded writes would let a bug where the
 * plugin writes one key and reads another pass every test in the suite. Writes
 * are also kept in {@link #writes()} in order, because "the archive was saved
 * after this kill" is a claim worth asserting directly.
 */
final class FakeConfig implements GunnarsToolsConfig, ConfigStore
{
	private int tripKills = GunnarsToolsConfig.super.tripKills();
	private int safetyMarginPercent = GunnarsToolsConfig.super.safetyMarginPercent();
	private String planFor = GunnarsToolsConfig.super.planFor();
	private boolean estimateBeforeMeasuring = GunnarsToolsConfig.super.estimateBeforeMeasuring();
	private boolean rememberBetweenSessions = GunnarsToolsConfig.super.rememberBetweenSessions();
	private boolean subtractCarried = GunnarsToolsConfig.super.subtractCarried();
	private boolean showOverlay = GunnarsToolsConfig.super.showOverlay();
	private boolean highlightBank = GunnarsToolsConfig.super.highlightBank();
	private boolean showLookup = GunnarsToolsConfig.super.showLookup();
	private String pinnedTarget = GunnarsToolsConfig.super.pinnedTarget();
	private String archive = GunnarsToolsConfig.super.archive();

	private final List<String> writes = new ArrayList<>();

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
	public String planFor()
	{
		return planFor;
	}

	@Override
	public boolean estimateBeforeMeasuring()
	{
		return estimateBeforeMeasuring;
	}

	@Override
	public boolean rememberBetweenSessions()
	{
		return rememberBetweenSessions;
	}

	@Override
	public boolean subtractCarried()
	{
		return subtractCarried;
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

	@Override
	public boolean showLookup()
	{
		return showLookup;
	}

	@Override
	public String pinnedTarget()
	{
		return pinnedTarget;
	}

	@Override
	public String archive()
	{
		return archive;
	}

	/**
	 * The {@link ConfigStore} half. An unrecognised key throws rather than being
	 * ignored: a plugin writing to a key nothing reads is the exact bug this class
	 * exists to make impossible, and a silent no-op would hide it.
	 */
	@Override
	public void write(String key, String value)
	{
		writes.add(key + "=" + value);
		switch (key)
		{
			case GunnarsToolsConfig.ARCHIVE:
				archive = value;
				break;
			case GunnarsToolsConfig.PINNED_TARGET:
				pinnedTarget = value;
				break;
			case GunnarsToolsConfig.PLAN_FOR:
				planFor = value;
				break;
			default:
				throw new IllegalArgumentException("nothing reads " + key);
		}
	}

	/** Every write, newest last, as {@code key=value}. */
	List<String> writes()
	{
		return writes;
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

	FakeConfig withPlanFor(String name)
	{
		this.planFor = name;
		return this;
	}

	FakeConfig withEstimateBeforeMeasuring(boolean estimate)
	{
		this.estimateBeforeMeasuring = estimate;
		return this;
	}

	FakeConfig withRememberBetweenSessions(boolean remember)
	{
		this.rememberBetweenSessions = remember;
		return this;
	}

	FakeConfig withSubtractCarried(boolean subtract)
	{
		this.subtractCarried = subtract;
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

	FakeConfig withShowLookup(boolean show)
	{
		this.showLookup = show;
		return this;
	}

	FakeConfig withPinnedTarget(String serialised)
	{
		this.pinnedTarget = serialised;
		return this;
	}

	FakeConfig withArchive(String serialised)
	{
		this.archive = serialised;
		return this;
	}
}
