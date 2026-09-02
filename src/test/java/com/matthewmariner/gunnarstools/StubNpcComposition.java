package com.matthewmariner.gunnarstools;

/**
 * Every abstract method of {@link net.runelite.api.NPCComposition} — and of the
 * {@code ParamHolder} it extends — throwing.
 *
 * <p>Mechanically transcribed from {@code javap net.runelite.api.NPCComposition}
 * and {@code javap net.runelite.api.ParamHolder} against the 1.12.38 API jar and
 * then checked in. No reflection, no mocking framework, nothing generated at
 * runtime — the same approach lively-cities takes, for the same reason.
 *
 * <p>The point is what it makes impossible. {@link FakeNpcComposition}
 * implements the three accessors {@link FoughtNpc} actually reads; the rest
 * throw, so a later revision that quietly started calling {@code transform()}
 * or {@code getCombatLevel()} would fail loudly in a test rather than silently
 * read a zero from a mock's default.
 *
 * <p>One method per line on purpose. This is a lookup table, not code to read.
 */
class StubNpcComposition implements net.runelite.api.NPCComposition
{
	static UnsupportedOperationException unsupported(String method)
	{
		return new UnsupportedOperationException(
			"StubNpcComposition does not implement NPCComposition." + method
				+ "(..) — nothing in this plugin has needed it");
	}

	// --- net.runelite.api.NPCComposition ---
	@Override public java.lang.String getName() { throw unsupported("getName"); }
	@Override public int[] getModels() { throw unsupported("getModels"); }
	@Override public int[] getChatheadModels() { throw unsupported("getChatheadModels"); }
	@Override public net.runelite.api.EntityOps getOps() { throw unsupported("getOps"); }
	@Override public java.lang.String[] getActions() { throw unsupported("getActions"); }
	@Override public boolean isInteractible() { throw unsupported("isInteractible"); }
	@Override public boolean isMinimapVisible() { throw unsupported("isMinimapVisible"); }
	@Override public int getId() { throw unsupported("getId"); }
	@Override public int getCombatLevel() { throw unsupported("getCombatLevel"); }
	@Override public int[] getConfigs() { throw unsupported("getConfigs"); }
	@Override public net.runelite.api.NPCComposition transform() { throw unsupported("transform"); }
	@Override public int getSize() { throw unsupported("getSize"); }
	@Override public boolean isFollower() { throw unsupported("isFollower"); }
	@Override public short[] getColorToReplace() { throw unsupported("getColorToReplace"); }
	@Override public short[] getColorToReplaceWith() { throw unsupported("getColorToReplaceWith"); }
	@Override public int getWidthScale() { throw unsupported("getWidthScale"); }
	@Override public int getHeightScale() { throw unsupported("getHeightScale"); }
	@Override public int getFootprintSize() { throw unsupported("getFootprintSize"); }
	@Override public int[] getStats() { throw unsupported("getStats"); }

	// --- net.runelite.api.ParamHolder ---
	@Override public net.runelite.api.IterableHashTable<net.runelite.api.Node> getParams() { throw unsupported("getParams"); }
	@Override public int getIntValue(int a0) { throw unsupported("getIntValue"); }
	@Override public void setValue(int a0, int a1) { throw unsupported("setValue"); }
	@Override public java.lang.String getStringValue(int a0) { throw unsupported("getStringValue"); }
	@Override public void setValue(int a0, java.lang.String a1) { throw unsupported("setValue"); }
	@Override public long getLongValue(int a0) { throw unsupported("getLongValue"); }
	@Override public void setValue(int a0, long a1) { throw unsupported("setValue"); }
}
