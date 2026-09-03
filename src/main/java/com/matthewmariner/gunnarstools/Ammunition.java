package com.matthewmariner.gunnarstools;

/**
 * Decides whether an item id is worth metering at all.
 *
 * <p>The plugin's question is "how much ammunition does N kills cost?", and the
 * answer is measured as a decrement in a container. Every item in the inventory
 * and equipment changes for reasons that are not consumption — armour is
 * swapped, loot arrives, a looting bag fills — so something has to narrow the
 * field before {@link ConsumptionMeter} starts differencing quantities.
 *
 * <p><b>The rule is a property, not a list.</b> There is deliberately no table
 * of arrow, bolt and rune ids here. Arrows, bolts, darts, every rune and
 * revenant ether are stackable; a rune platebody, a shark and a dragon defender
 * are not. Stackability therefore separates the things this plugin meters from
 * almost everything it does not, in a way the game itself already expresses, and
 * a table would need an entry per ammunition type in the game and would be wrong
 * the week Jagex adds one.
 *
 * <p><b>It is an over-approximation, not the category itself,</b> and an earlier
 * version of this javadoc claimed otherwise. {@link #isConsumable} admits every
 * stackable unnoted item, which is more than ammunition: this repository says so
 * two files away, where {@code AmmoDelta} notes that "coins are stackable and
 * therefore metered." The false positives are accepted and disclosed rather than
 * denied — narrowing them means the id table this method exists to avoid.
 *
 * <p><b>What it is <em>not</em> justified by.</b> This used to open by calling
 * "an ammunition stack never occupies a keep slot on death" the premise the whole
 * plugin rests on. That is an unverified claim about the game and it is not what
 * the wiki says: Items Kept on Death ranks candidates in order of value, first by
 * effective Grand Exchange value and then by alchemy value, with no mention of
 * stacks, quantity or unit price and no ammunition row. The sibling
 * {@code ../dangerous-teleport} declines to subtract keep slots on the same
 * unverified ranking — see its {@code RiskAssessment} javadoc — and this file
 * should not have been leaning on the mirror image of it. Nothing in the code
 * changes: stackability is a good filter for "did a quantity move for a reason
 * worth counting" whatever death does with the stack afterwards.
 *
 * <p>Noted items are excluded on top of that. A note is stackable — that is the
 * entire point of a note — but nobody fires one, and a bank trip that withdraws
 * fifteen noted sharks would otherwise read as fifteen units of something
 * consumed. The exclusion is again a property: RuneLite reports
 * {@code getNote() != -1} for an item that <em>is</em> a note (the note's own
 * template id), and {@code -1} for everything else, including unnoted items that
 * merely <em>have</em> a noted form.
 *
 * <p>Both inputs are passed in as primitives rather than as an
 * {@code ItemComposition}, so this decision is testable without a client. The
 * one line that cannot be tested offline — reading the composition out of
 * {@code Client.getItemDefinition} — stays in
 * {@link GunnarsToolsPlugin#isConsumable(int)}, where it is a lookup and
 * nothing else.
 */
public final class Ammunition
{
	private Ammunition()
	{
	}

	/**
	 * The template id RuneLite reports from {@code ItemComposition.getNote()}
	 * when an item is not itself a note. Named rather than inlined because
	 * {@code -1} appears in this codebase for "no such NPC index" too, and the
	 * two have nothing to do with each other.
	 */
	static final int NOT_A_NOTE = -1;

	/**
	 * @param stackable      {@code ItemComposition.isStackable()}
	 * @param noteTemplateId {@code ItemComposition.getNote()} — the note's own
	 *                       template id if this item <em>is</em> a note,
	 *                       {@link #NOT_A_NOTE} otherwise
	 * @return true if a change in this item's quantity is worth attributing to a
	 * kill
	 */
	public static boolean isConsumable(boolean stackable, int noteTemplateId)
	{
		return stackable && noteTemplateId == NOT_A_NOTE;
	}
}
