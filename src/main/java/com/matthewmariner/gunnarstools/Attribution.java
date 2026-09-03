package com.matthewmariner.gunnarstools;

import javax.annotation.Nullable;

/**
 * One verdict from {@link KillAttribution}, resolved at a tick boundary.
 *
 * <p>Three outcomes, and the two that are not {@link Kind#KILL} exist because
 * throwing them away silently is how a measurement tool starts lying. Every
 * arrow the player fires is either attributed to a kill, or accounted for
 * somewhere a reader can see it.
 *
 * <p><b>With one exception, named here rather than left to be discovered.</b> A
 * shot that leaves on the tick the player clicks a new monster while no window
 * is open — the tick after a kill — is discarded by {@link KillAttribution}
 * rather than filed under any of the three: the tick's consumption is charged
 * before the click becomes an engagement, and a delta with no owner is dropped.
 * It is one shot per target switch, it understates, and whether it happens at
 * all depends on a tick-ordering question that needs a running client to
 * settle. See {@link KillAttribution} on the two costs of applying interaction
 * changes last, and the README's "wanted from a real client".
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
	private final int coVictims;

	private Attribution(Kind kind, FoughtNpc npc, @Nullable AmmoTally tally, int coVictims)
	{
		this.kind = kind;
		this.npc = npc;
		this.tally = tally;
		this.coVictims = coVictims;
	}

	/**
	 * There is deliberately no two-argument overload defaulting {@code coVictims}
	 * to zero. It had no production caller and around fifty test call sites, which
	 * is the worst possible distribution: every one of those tests was exercising
	 * the co-victim path with the denominator silently zeroed, so the suite would
	 * have gone on passing with the count wired to nothing. The same standing
	 * invitation was closed one layer down at
	 * {@link NpcAmmoRecord#recordKill(AmmoTally, int)} for the same reason, and
	 * closing it there while leaving it open here defended nothing.
	 *
	 * @param coVictims how many <em>other monsters of this same id</em> died
	 *                  inside the window this kill closes. The id is half the
	 *                  definition: the count is a divisor under this record's
	 *                  ammunition and the number it is divided into is a trip of
	 *                  this monster, so a different species caught by the same
	 *                  barrage is a real death that belongs in a different unit.
	 *                  Attached to the kill rather than to the co-victims'
	 *                  own verdicts because the ammunition is on this record and
	 *                  the correction has to land where the numerator is. See
	 *                  {@link Kind#UNATTRIBUTED_DEATH}, which is what those
	 *                  co-victims are also reported as, {@link KillAttribution}
	 *                  for how they are counted, and {@link ConsumptionEstimate}
	 *                  for what the two counts are each good for.
	 */
	static Attribution kill(FoughtNpc npc, AmmoTally tally, int coVictims)
	{
		return new Attribution(Kind.KILL, npc, tally, coVictims);
	}

	static Attribution abandoned(FoughtNpc npc, AmmoTally tally)
	{
		return new Attribution(Kind.ABANDONED, npc, tally, 0);
	}

	static Attribution unattributedDeath(FoughtNpc npc)
	{
		return new Attribution(Kind.UNATTRIBUTED_DEATH, npc, null, 0);
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

	/**
	 * @return other monsters killed by the same window. Always 0 for the two kinds
	 * that are not {@link Kind#KILL}: an abandoned window's co-victims are not
	 * priced by anything, since its ammunition is kept out of the average, and an
	 * unattributed death is itself somebody else's co-victim.
	 */
	public int getCoVictims()
	{
		return coVictims;
	}

	@Override
	public String toString()
	{
		return "Attribution(" + kind + ", " + npc + ", " + tally
			+ (coVictims > 0 ? ", coVictims=" + coVictims : "") + ")";
	}
}
