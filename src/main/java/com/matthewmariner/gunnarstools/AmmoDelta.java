package com.matthewmariner.gunnarstools;

import java.util.Collections;
import java.util.Map;

/**
 * What one game tick did to the player's stackable holdings: quantities lost,
 * and quantities gained, per item id.
 *
 * <p><b>Losses and gains are two fields, and they are never netted into one.</b>
 * That is the single most important decision in this class and it is not
 * fastidiousness. A gain in an ammunition stack has two indistinguishable
 * causes at this layer:
 *
 * <ul>
 *   <li>arrows that missed, fell on the floor and were picked back up — in which
 *       case they were never really consumed, and netting them off is exactly
 *       right;</li>
 *   <li>arrows, bolts, runes or ether that the monster <em>dropped</em> — in
 *       which case netting them off understates what the next trip needs, and
 *       understating is the dangerous direction. The plugin exists so its owner
 *       can walk into the Wilderness with enough ammunition and no more;
 *       telling him he needs fewer arrows than he does ends the trip early,
 *       which is the failure the tool is supposed to prevent.</li>
 * </ul>
 *
 * <p>Nothing available to a plugin distinguishes those two cases from a
 * container diff. Ground items carry no ownership a client can read, and
 * ammunition that lands under another player's kill looks identical to
 * ammunition that landed under yours. So this type refuses to guess: it carries
 * both directions, {@link NpcAmmoRecord} keeps them in separate columns, and
 * the number it publishes as "consumed per kill" is the <em>gross</em> figure —
 * the safe direction — with the recovered volume sitting next to it as a
 * disclosed contaminant a later milestone can reason about. See
 * {@link NpcAmmoRecord#getRecovered()}.
 *
 * <p>Quantities are {@code long} throughout. A single stack of coins reaches
 * {@link Integer#MAX_VALUE}, and this class sums a quantity across three
 * containers, so an {@code int} accumulator has a real overflow case rather
 * than a theoretical one. Ammunition never gets close, but coins are stackable
 * and therefore metered.
 */
public final class AmmoDelta
{
	/** Nothing changed. Returned for every tick that had no container event. */
	public static final AmmoDelta EMPTY = new AmmoDelta(Collections.emptyMap(), Collections.emptyMap());

	private final Map<Integer, Long> consumed;
	private final Map<Integer, Long> gained;

	AmmoDelta(Map<Integer, Long> consumed, Map<Integer, Long> gained)
	{
		this.consumed = Collections.unmodifiableMap(consumed);
		this.gained = Collections.unmodifiableMap(gained);
	}

	/** Item id to quantity lost. Values are positive; absent means unchanged. */
	public Map<Integer, Long> getConsumed()
	{
		return consumed;
	}

	/** Item id to quantity gained. Values are positive; absent means unchanged. */
	public Map<Integer, Long> getGained()
	{
		return gained;
	}

	public boolean isEmpty()
	{
		return consumed.isEmpty() && gained.isEmpty();
	}

	@Override
	public String toString()
	{
		return "AmmoDelta(consumed=" + consumed + ", gained=" + gained + ")";
	}
}
