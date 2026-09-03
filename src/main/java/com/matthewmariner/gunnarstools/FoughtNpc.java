package com.matthewmariner.gunnarstools;

import java.util.Arrays;
import javax.annotation.Nullable;
import net.runelite.api.NPCComposition;

/**
 * A snapshot of the monster the player is fighting: its scene index, its id, its
 * name, and its six combat stats read straight off the live NPC.
 *
 * <p><b>Why the stats come from the live NPC and not from a bundled table.</b>
 * Nineteen of Krystilia's thirty-six Wilderness Slayer tasks are umbrellas.
 * "Spider" is Venenatis and Spindel and every giant spider in the game;
 * "bear" is Callisto and Artio and ordinary bears; "skeleton" is Vet'ion and
 * Calvar'ion and the skeletons in the Wilderness. Hitpoints across one of those
 * umbrellas differ by <b>two and a half orders of magnitude</b>: the spider task
 * spans the plain Spider at 2 and Venenatis at 850, a factor of 425, and even
 * starting from a giant spider's 5 it is 170. A generated lookup keyed on the
 * task name has to pick one of those. Asking the NPC that is standing in front
 * of the player cannot make that mistake, because there is no name to resolve:
 * the monster is already picked.
 *
 * <p><b>The anecdote this argument used to carry was itself the error it warns
 * about.</b> It said a draft of that lookup resolved "spider" to "a giant spider
 * with <b>two</b> hitpoints". No giant spider has two hitpoints — the wiki gives
 * the three variants 5, 32 and 50 — and the number 2 belongs to two different
 * columns nearby: it is the weakest giant spider's <em>combat level</em>, and it
 * is the <em>hitpoints</em> of the plain Spider, a different monster whose own
 * combat level is 1. Reading a stat off the wrong row of a table, or off the
 * right row of the wrong table, is exactly the failure mode named in the
 * paragraph above, and it survived in the paragraph making the case against it.
 * It is left written down here rather than silently fixed, because "the argument
 * was right and the evidence for it was mis-read" is the thing worth
 * remembering.
 *
 * <p><b>The stats array defaults to {@code {1,1,1,1,1,1}}, not to zeros.</b>
 * This is the trap the whole class exists to handle. A stat the cache never
 * filled in reads as 1, so {@code stats != null} does not mean "populated" and
 * neither does any non-zero test — every element of the default passes both.
 * {@link #hasStats()} therefore tests for the default <em>pattern</em>: all six
 * equal to one.
 *
 * <p>That predicate cannot distinguish the default from a genuine
 * all-ones NPC, and it does not pretend to. It does not need to: an NPC with one
 * hitpoint is not a slayer task, and this plugin only ever asks about monsters
 * it watched somebody kill. Where it matters is the other direction — a monster
 * whose composition genuinely carries {@code attack=1} inside an otherwise real
 * array still reports {@link #hasStats()} true, because the array as a whole is
 * not the default. Any individual 1 in a populated array remains ambiguous
 * between "the cache left it alone" and "it really is 1", and no code here or
 * downstream may treat a single 1 as evidence of either.
 */
public final class FoughtNpc
{
	/** How many stats {@code NPCComposition.getStats()} promises. */
	static final int STAT_COUNT = 6;

	private final int index;
	private final int id;
	private final String name;

	/** Never null and always {@link #STAT_COUNT} long; see {@link #hasStats()}. */
	private final int[] stats;

	private final boolean statsPopulated;

	public FoughtNpc(int index, int id, String name, @Nullable int[] stats)
	{
		this.index = index;
		this.id = id;
		this.name = name;
		this.stats = normalise(stats);
		this.statsPopulated = !isDefaultStats(this.stats);
	}

	/**
	 * Builds a record from a composition already resolved to the NPC's current
	 * form.
	 *
	 * <p>The id is taken from the composition rather than from
	 * {@code NPC.getId()} on purpose. A transforming NPC — anything whose form
	 * depends on a varbit, which includes several Wilderness demi-bosses — has an
	 * id and a stats array per form, and reading the id from one object and the
	 * stats from another is a way to file Calvar'ion's hitpoints under Vet'ion's
	 * id. One object, one form, both facts.
	 *
	 * @return null if the composition is null, which the caller must skip rather
	 * than substitute for
	 */
	@Nullable
	public static FoughtNpc of(int index, @Nullable NPCComposition composition)
	{
		if (composition == null)
		{
			return null;
		}
		return new FoughtNpc(index, composition.getId(), composition.getName(), composition.getStats());
	}

	/**
	 * The NPC's index in the scene. This — not the id — is what identifies "the
	 * one I am fighting", because a Wilderness spider spawn is a dozen NPCs
	 * sharing one id.
	 *
	 * <p>Indices are reused when an NPC despawns and another takes its slot, so
	 * every consumer of this value has to drop what it knows about an index on
	 * {@code NpcDespawned}. {@link KillAttribution} does.
	 */
	public int getIndex()
	{
		return index;
	}

	public int getId()
	{
		return id;
	}

	public String getName()
	{
		return name;
	}

	/**
	 * @return whether the stats array is anything other than the
	 * {@code {1,1,1,1,1,1}} the cache leaves behind when nothing filled it in.
	 * Read the class javadoc before using this as a proxy for anything narrower.
	 */
	public boolean hasStats()
	{
		return statsPopulated;
	}

	/** A copy, so a caller cannot edit the record's own array. */
	public int[] getStats()
	{
		return stats.clone();
	}

	/**
	 * @param statIndex one of {@code NPCComposition.STAT_ATTACK},
	 *                  {@code STAT_DEFENCE}, {@code STAT_STRENGTH},
	 *                  {@code STAT_HITPOINTS}, {@code STAT_RANGED},
	 *                  {@code STAT_MAGIC}
	 */
	public int getStat(int statIndex)
	{
		return stats[statIndex];
	}

	/**
	 * Pads or truncates whatever the composition handed over to
	 * {@link #STAT_COUNT} entries, and substitutes the documented default for a
	 * null array.
	 *
	 * <p>{@code getStats()} is specified as {@code int[6]} and in practice is,
	 * but this class stores the array for milestones that will index into it by
	 * the {@code STAT_*} constants, and an out-of-range read there would be an
	 * exception in a plugin rather than a wrong number in a test. Substituting
	 * the all-ones default is not a fabrication: it is exactly the value
	 * {@link #hasStats()} reports as "nothing was filled in".
	 */
	private static int[] normalise(@Nullable int[] raw)
	{
		final int[] out = new int[STAT_COUNT];
		Arrays.fill(out, 1);
		if (raw != null)
		{
			System.arraycopy(raw, 0, out, 0, Math.min(raw.length, STAT_COUNT));
		}
		return out;
	}

	private static boolean isDefaultStats(int[] stats)
	{
		for (int stat : stats)
		{
			if (stat != 1)
			{
				return false;
			}
		}
		return true;
	}

	@Override
	public String toString()
	{
		return "FoughtNpc(index=" + index + ", id=" + id + ", name=" + name
			+ ", stats=" + (statsPopulated ? Arrays.toString(stats) : "unpopulated") + ")";
	}
}
