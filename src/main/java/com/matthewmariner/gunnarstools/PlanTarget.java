package com.matthewmariner.gunnarstools;

import java.util.List;
import javax.annotation.Nullable;
import net.runelite.api.NPCComposition;

/**
 * The monster a plan is <em>about</em>, which until this class existed was always
 * and only "the last thing you killed".
 *
 * <p>That was the whole of why the plugin could not answer before a trip. A tool
 * whose subject is chosen by a kill cannot say anything until a kill has
 * happened, and cannot ever say anything about a monster you have not fought.
 * Separating the subject from the measurement is what lets the same panel be
 * driven by a monster you pinned, one you are currently shooting at, or one a
 * previous session remembered — none of which involve killing anything now.
 *
 * <h2>Hitpoints, and the one number this class refuses to publish</h2>
 *
 * <p>{@link #getHitpoints()} is the live NPC's own hitpoints, read off
 * {@code NPCComposition.getStats()} at {@code STAT_HITPOINTS}. It is what makes a
 * cold-start estimate possible without a bundled monster table: the monster in
 * front of you states its own size, so nothing has to resolve the word "spider"
 * to one of the thirty-odd things Krystilia means by it.
 *
 * <p>It is <b>zero when the number cannot be trusted</b>, and there are two ways
 * to get there. The first is {@link FoughtNpc#hasStats()} being false — the cache
 * left the array at its {@code {1,1,1,1,1,1}} default and there is nothing in it.
 * The second is subtler and comes straight out of {@link FoughtNpc}'s own
 * warning: a single {@code 1} inside an otherwise populated array is ambiguous
 * between "the cache left this entry alone" and "it really is 1", and no code
 * downstream may treat it as evidence of either. A hitpoints value of 1 is
 * therefore refused rather than resolved — not because a one-hitpoint monster is
 * impossible, but because dividing a trip's ammunition by it would turn an
 * unfilled cache entry into an estimate two orders of magnitude too large, and
 * doing that silently is the failure this plugin is built to avoid. The panel
 * says the hitpoints did not resolve instead.
 */
public final class PlanTarget
{
	/**
	 * How this monster came to be the subject, in the order the plugin resolves
	 * them. The order is the point: an explicit choice by the player outranks
	 * anything inferred, and something happening now outranks something that
	 * happened earlier.
	 */
	public enum Source
	{
		/** The player pinned it — shift-right-click, or a pin restored from a previous session. */
		PINNED("pinned"),

		/** The monster the open consumption window belongs to: what you are shooting at. */
		FIGHTING("fighting"),

		/** The last monster killed. The only source that existed before target selection. */
		LAST_KILL("last kill");

		private final String label;

		Source(String label)
		{
			this.label = label;
		}

		/** The word the panel shows. Lower case; it sits beside the monster's name. */
		public String getLabel()
		{
			return label;
		}
	}

	/**
	 * The lowest hitpoints value this class will publish. See the class javadoc:
	 * one is the value an unfilled cache entry and a genuine one-hitpoint monster
	 * share, and a divisor is the worst possible place to guess which.
	 */
	static final int MINIMUM_USABLE_HITPOINTS = 2;

	private final int npcId;
	private final String name;
	private final int hitpoints;
	private final Source source;

	/**
	 * <p>The name is {@link ConfigText#sanitise}d here rather than at
	 * {@link #format()}, and that placement is the fix for a real bug rather than
	 * tidiness. Sanitising on the way out alone means the name a pin is
	 * <em>stored</em> under and the name it is <em>matched</em> by are different
	 * strings for any monster whose name holds a separator or runs past forty
	 * characters — so the pin silently stopped matching after a restart, at a bank,
	 * which is the one place it exists to work. One name, decided once, and every
	 * comparison downstream is between two strings that went through the same door.
	 */
	PlanTarget(int npcId, String name, int hitpoints, Source source)
	{
		this.npcId = npcId;
		this.name = ConfigText.sanitise(name);
		this.hitpoints = hitpoints < MINIMUM_USABLE_HITPOINTS ? 0 : hitpoints;
		this.source = source;
	}

	/**
	 * @return a target for a monster the plugin has a live reading of, or null if
	 * it has not — which the caller must skip rather than substitute for, exactly
	 * as with {@link FoughtNpc#of(int, NPCComposition)}
	 */
	@Nullable
	static PlanTarget of(@Nullable FoughtNpc npc, Source source)
	{
		if (npc == null)
		{
			return null;
		}
		return new PlanTarget(npc.getId(), npc.getName(),
			npc.hasStats() ? npc.getStat(NPCComposition.STAT_HITPOINTS) : 0, source);
	}

	/** @return a target for the monster a session record was built from */
	static PlanTarget of(NpcAmmoRecord record, Source source)
	{
		return new PlanTarget(record.getNpcId(), record.getNpcName(),
			record.hasStats() ? record.getStat(NPCComposition.STAT_HITPOINTS) : 0, source);
	}

	/** @return a target for a monster only a previous session ever saw */
	static PlanTarget of(AmmoArchive.Entry entry, Source source)
	{
		return new PlanTarget(entry.getNpcId(), entry.getName(), entry.getHitpoints(), source);
	}

	/**
	 * Picks the plan's subject from everything that could be it.
	 *
	 * <p>The order is the whole of the fix for the plugin being unusable. An
	 * explicit choice by the player outranks anything inferred, because a player
	 * who says "I am going out for Spindels" has stated a fact no amount of
	 * watching can improve on; something happening now outranks something that
	 * happened earlier, because the monster in front of you is the one you are
	 * about to spend arrows on; and only when neither exists does it fall back to
	 * the last kill, which is where this plugin used to start and stop.
	 *
	 * @return null when the player has pinned nothing, is fighting nothing and has
	 * killed nothing — a real state with its own sentence on the panel, and not an
	 * error
	 */
	@Nullable
	static PlanTarget resolve(@Nullable PlanTarget pinned, @Nullable FoughtNpc fighting,
		@Nullable NpcAmmoRecord lastKill)
	{
		if (pinned != null)
		{
			return pinned;
		}
		final PlanTarget engaged = of(fighting, Source.FIGHTING);
		if (engaged != null)
		{
			return engaged;
		}
		return lastKill == null ? null : of(lastKill, Source.LAST_KILL);
	}

	/**
	 * The pin, as one line for the config store: {@code npcId,name,hitpoints}.
	 *
	 * <p>Kept because a pin has to survive the walk back to the bank and the logout
	 * in the middle of it. It carries the name and the hitpoints rather than only
	 * the id on purpose: the whole point of pinning is to plan for a monster you
	 * have never killed, and a monster you have never killed is in no archive to
	 * look the other two fields up in.
	 */
	String format()
	{
		// Already sanitised by the constructor, which is where it has to happen —
		// see there.
		return npcId + "" + ConfigText.FIELD_SEPARATOR + name
			+ ConfigText.FIELD_SEPARATOR + hitpoints;
	}

	/**
	 * @param serialised whatever came out of the config store, including null
	 * @return the pinned target, or null for anything that does not parse. Never
	 * throws — see {@link ConfigText}.
	 */
	@Nullable
	static PlanTarget parse(@Nullable String serialised)
	{
		if (serialised == null || serialised.isEmpty())
		{
			return null;
		}

		final List<String> fields = ConfigText.split(serialised, ConfigText.FIELD_SEPARATOR);
		if (fields.size() < 3)
		{
			return null;
		}

		final int npcId = ConfigText.parseInt(fields.get(0), Integer.MIN_VALUE);
		if (npcId < 0)
		{
			return null;
		}

		final String name = fields.get(1).trim();
		if (name.isEmpty())
		{
			return null;
		}

		return new PlanTarget(npcId, name, Math.max(0, ConfigText.parseInt(fields.get(2), 0)),
			Source.PINNED);
	}

	public int getNpcId()
	{
		return npcId;
	}

	public String getName()
	{
		return name;
	}

	/** Hitpoints, or zero when the reading cannot be trusted. See the class javadoc. */
	public int getHitpoints()
	{
		return hitpoints;
	}

	/**
	 * @return whether an estimate may be extrapolated onto this monster's size.
	 * False is not an error — it is the panel's cue to say the hitpoints did not
	 * resolve, which is a fact worth showing rather than a reason to draw nothing.
	 */
	public boolean hasHitpoints()
	{
		return hitpoints > 0;
	}

	public Source getSource()
	{
		return source;
	}

	/** Whether this is the same monster as {@code other}, ignoring how each was chosen. */
	boolean isSameMonsterAs(@Nullable PlanTarget other)
	{
		return other != null && other.npcId == npcId;
	}

	/**
	 * Value equality over all four fields, including {@link #getSource()}.
	 *
	 * <p>Including the source is what makes this the plugin's rebuild trigger. The
	 * panel prints the word — "pinned" and "last kill" are different claims about
	 * the same monster, and a player who cannot tell which one they are looking at
	 * is back to not being able to trust the number. So the same monster arriving
	 * by a different route is a different target and redraws.
	 */
	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof PlanTarget))
		{
			return false;
		}
		final PlanTarget that = (PlanTarget) other;
		return npcId == that.npcId && hitpoints == that.hitpoints && source == that.source
			&& name.equals(that.name);
	}

	@Override
	public int hashCode()
	{
		int hash = npcId;
		hash = hash * 31 + hitpoints;
		hash = hash * 31 + source.hashCode();
		return hash * 31 + name.hashCode();
	}

	@Override
	public String toString()
	{
		return "PlanTarget(" + name + " #" + npcId
			+ ", hp=" + (hitpoints > 0 ? Integer.toString(hitpoints) : "unresolved")
			+ ", via " + source + ")";
	}
}
