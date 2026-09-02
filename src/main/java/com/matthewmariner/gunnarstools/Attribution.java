package com.matthewmariner.gunnarstools;

import javax.annotation.Nullable;

/**
 * One verdict from {@link KillAttribution}, resolved at a tick boundary.
 *
 * <p>Three outcomes, and the two that are not {@link Kind#KILL} exist because
 * throwing them away silently is how a measurement tool starts lying. Every
 * arrow the player fires is either attributed to a kill, or accounted for
 * somewhere a reader can see it.
 */
public final class Attribution
{
	public enum Kind
	{
		/**
		 * A monster the player was fighting, and had damaged, died. Its window of
		 * consumption closes and becomes one sample.
		 */
		KILL,

		/**
		 * The player was fighting this monster, had damaged it, and then stopped —
		 * it despawned without dying, or they switched targets. The ammunition
		 * spent on it is real and is recorded, but it is <em>not</em> a sample:
		 * folding it into the per-kill mean would raise the numerator without
		 * raising the denominator and quietly inflate every estimate.
		 *
		 * <p>This bucket doubles as the detector for the one failure mode kill
		 * attribution cannot rule out on its own. {@code ActorDeath} is fired from
		 * a health-bar update reaching zero; a monster that dies without the
		 * client ever receiving that update produces a despawn and no death. If
		 * the abandoned count for a monster is large and its kill count is small,
		 * that is what happened, and it is visible rather than folded into the
		 * average as an expensive kill.
		 */
		ABANDONED,

		/**
		 * A monster the player had damaged died, but it was not the monster they
		 * were fighting at the time — the other half of a barrage, or a target
		 * they had already switched away from. The kill is real and the player's
		 * ammunition is genuinely on it, but there is no honest way to say how
		 * much: one window cannot be split between two monsters without inventing
		 * the split. Counted, never averaged.
		 */
		UNATTRIBUTED_DEATH
	}

	private final Kind kind;
	private final FoughtNpc npc;
	private final AmmoTally tally;

	private Attribution(Kind kind, FoughtNpc npc, @Nullable AmmoTally tally)
	{
		this.kind = kind;
		this.npc = npc;
		this.tally = tally;
	}

	static Attribution kill(FoughtNpc npc, AmmoTally tally)
	{
		return new Attribution(Kind.KILL, npc, tally);
	}

	static Attribution abandoned(FoughtNpc npc, AmmoTally tally)
	{
		return new Attribution(Kind.ABANDONED, npc, tally);
	}

	static Attribution unattributedDeath(FoughtNpc npc)
	{
		return new Attribution(Kind.UNATTRIBUTED_DEATH, npc, null);
	}

	public Kind getKind()
	{
		return kind;
	}

	public FoughtNpc getNpc()
	{
		return npc;
	}

	/** Null for {@link Kind#UNATTRIBUTED_DEATH}, which by definition has no window. */
	@Nullable
	public AmmoTally getTally()
	{
		return tally;
	}

	@Override
	public String toString()
	{
		return "Attribution(" + kind + ", " + npc + ", " + tally + ")";
	}
}
