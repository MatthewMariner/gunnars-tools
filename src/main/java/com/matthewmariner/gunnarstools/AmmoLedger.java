package com.matthewmariner.gunnarstools;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Every {@link NpcAmmoRecord} measured this session, by NPC id <em>and</em> the
 * {@link Loadout} it was measured on.
 *
 * <p><b>In memory only, and that is now half of a story rather than the whole of
 * one.</b> Nothing here touches the disk and nothing here survives a restart.
 * What changed is that the session's answer is no longer the plugin's only
 * answer: {@link AmmoArchive} keeps a compact summary in the user's RuneLite
 * profile so a question asked at a bank has something to answer with, and it is
 * careful never to present what it kept as a measurement. This class stays the
 * measurement — what <em>this</em> session watched happen — and the two are
 * different claims that the type system keeps apart.
 *
 * <p><b>What that decision is <em>not</em> justified by, any more.</b> It used to
 * be argued here that "a plugin that writes files is reviewed by hand at the
 * Plugin Hub instead of automatically." <b>That is unverified and nothing
 * supports it</b> — not the plugin-hub README, not its tooling, not the RuneLite
 * wiki. The hub's only file-related rule is about <em>location</em> ("only
 * read/write files inside the {@code .runelite} directory", which is in this
 * repository's own {@code AGENTS.md}), and the one thing its README names as
 * requiring a maintainer by hand is dependency verification, not file I/O. The
 * archive answers the question that argument was standing in front of, and it
 * does it through the config store rather than through a file, which is the
 * mechanism {@code AGENTS.md} points at for a plugin's own saved state.
 *
 * <h2>Two setups are two records, and no reset is needed anywhere</h2>
 *
 * <p>The key is the pair. Twenty-five rune arrows per Spindel is a fact about a
 * magic shortbow; the same player on a dragon hunter crossbow is a different
 * number, and one series holding both describes neither. Keying by the pair makes
 * that mixture unrepresentable rather than merely discouraged.
 *
 * <p>It is also why this plugin has no "reset my samples after a gear change"
 * setting, which is the obvious thing to reach for and the wrong one. A reset is
 * destructive and it fires on the wrong events: a special attack is a weapon
 * change, and so is dying in the Wilderness, so a plugin that cleared the session
 * on every loadout change would throw away a three-hundred-kill series because
 * somebody speced a Callisto. Keyed records lose nothing — the spec's kill lands
 * in its own one-sample record, the main series is untouched, and swapping back
 * resumes it.
 *
 * <p>{@link #equipped(Loadout)} is how the current setup gets in. It is deliberately
 * a piece of state on the ledger rather than an argument on {@link #apply} —
 * every kill in a tick was fought with whatever is worn at that tick, so the
 * caller would be passing the same value every time and would eventually pass the
 * wrong one.
 *
 * <p>Insertion-ordered, so a dump reads in the order monsters were fought.
 */
public final class AmmoLedger
{
	/**
	 * One monster on one setup.
	 *
	 * <p>A value type rather than a composed integer, because the composition
	 * would have to be reversible for {@link #bestFor(int)} and the arithmetic to
	 * do that with three ids is exactly the kind of thing that is wrong in one
	 * direction only.
	 */
	private static final class Key
	{
		private final int npcId;
		private final Loadout loadout;

		private Key(int npcId, Loadout loadout)
		{
			this.npcId = npcId;
			this.loadout = loadout;
		}

		@Override
		public boolean equals(Object other)
		{
			if (this == other)
			{
				return true;
			}
			if (!(other instanceof Key))
			{
				return false;
			}
			final Key that = (Key) other;
			return npcId == that.npcId && loadout.equals(that.loadout);
		}

		@Override
		public int hashCode()
		{
			return npcId * 31 + loadout.hashCode();
		}
	}

	private final Map<Key, NpcAmmoRecord> records = new LinkedHashMap<>();

	/**
	 * What the player is wearing, as far as anything has told this ledger.
	 *
	 * <p>{@link Loadout#UNKNOWN} until the worn-equipment container has been read
	 * once, which is a real state and not a placeholder: the first reading of a
	 * session is not a change from anything.
	 */
	private Loadout equipped = Loadout.UNKNOWN;

	/**
	 * The record for the monster most recently killed, which is where the plan's
	 * subject falls back to when the player has not chosen one.
	 *
	 * <p>Most recently <em>killed</em>, not most recently fought or most recently
	 * seen. The bank highlight is read while the player is standing at a bank with
	 * nothing in front of them, so "what am I looking at" is not available; the
	 * last thing they actually killed is, and on a Slayer task it is the same
	 * monster all trip. An abandoned fight deliberately does not move it — walking
	 * past something and hitting it once should not repoint the shopping list.
	 */
	private NpcAmmoRecord mostRecentKill;

	/**
	 * Tells the ledger what is worn, so the next kill lands in the right record.
	 *
	 * @param loadout never null; {@link Loadout#UNKNOWN} for "not read yet"
	 */
	public void equipped(Loadout loadout)
	{
		this.equipped = loadout;
	}

	/** What the next kill will be filed under. */
	public Loadout getEquipped()
	{
		return equipped;
	}

	/**
	 * Applies one verdict from {@link KillAttribution}, against the setup
	 * {@link #equipped(Loadout)} last named.
	 *
	 * <p>The switch is exhaustive on purpose: a fourth kind added later without a
	 * branch here would drop verdicts on the floor, which is exactly the silent
	 * loss the three kinds exist to prevent, so it throws instead.
	 */
	public void apply(Attribution attribution)
	{
		final FoughtNpc npc = attribution.getNpc();
		final Loadout loadout = equipped;
		final NpcAmmoRecord record = records.computeIfAbsent(new Key(npc.getId(), loadout),
			key -> new NpcAmmoRecord(npc, loadout));
		record.observeStats(npc);

		switch (attribution.getKind())
		{
			case KILL:
				record.recordKill(attribution.getTally(), attribution.getCoVictims());
				mostRecentKill = record;
				break;
			case ABANDONED:
				record.recordAbandoned(attribution.getTally());
				break;
			case UNATTRIBUTED_DEATH:
				record.recordUnattributedDeath();
				break;
			default:
				throw new IllegalStateException("unhandled attribution kind " + attribution.getKind());
		}
	}

	/**
	 * @return what this session measured about that monster <em>on the setup
	 * currently worn</em>, or null. A player who has just changed weapons has no
	 * measurement for the weapon in their hands, which is the honest answer and is
	 * why the panel falls back to an estimate rather than reprinting the old one.
	 */
	@Nullable
	public NpcAmmoRecord get(int npcId)
	{
		return records.get(new Key(npcId, equipped));
	}

	/** @return the record for one monster on one named setup, or null. */
	@Nullable
	public NpcAmmoRecord get(int npcId, Loadout loadout)
	{
		return records.get(new Key(npcId, loadout));
	}

	/**
	 * The best-evidenced record for a monster, across every setup.
	 *
	 * <p>What {@link AmmoArchive} is fed, and the reason it is fed this rather than
	 * whichever record a kill just touched. The archive keeps one entry per
	 * monster, so a single kill landing while a special-attack weapon happened to
	 * be equipped would otherwise replace a three-hundred-kill entry with a
	 * one-kill one and call it the newer reading.
	 *
	 * <p>Ties go to the later record, which is the newer setup — the same
	 * preference {@link AmmoArchive#remember} states for the same reason.
	 */
	@Nullable
	public NpcAmmoRecord bestFor(int npcId)
	{
		NpcAmmoRecord best = null;
		for (NpcAmmoRecord record : records.values())
		{
			if (record.getNpcId() != npcId)
			{
				continue;
			}
			if (best == null || record.getMonstersPriced() >= best.getMonstersPriced())
			{
				best = record;
			}
		}
		return best;
	}

	/** @return the monster the plan falls back to, or null before the first kill */
	@Nullable
	public NpcAmmoRecord getMostRecentKill()
	{
		return mostRecentKill;
	}

	public Collection<NpcAmmoRecord> getRecords()
	{
		return Collections.unmodifiableCollection(records.values());
	}

	/**
	 * Finds a monster this session has fought, by name.
	 *
	 * <p><b>Deliberately not scoped to the setup worn</b>, which the first version
	 * of this was and which a review correctly called a conflation. "Which monster
	 * does this name mean" is a question about identity; "what has this weapon
	 * measured about it" is a question about evidence, and only the second is what
	 * {@link Loadout} exists to keep apart. Scoping both meant that typing the name
	 * of a monster you had killed forty times an hour ago produced "no such
	 * monster" because you had since changed weapons — for a monster the plugin
	 * demonstrably knew. The evidence question is still asked, one layer up, by
	 * {@link #get(int, Loadout)}.
	 *
	 * <p>Case-insensitive, because the name comes from something the player typed
	 * into a settings field rather than from the game.
	 *
	 * @return any record for a monster of that name, or null. Which of several
	 * setups' records it is does not matter: only the id, the name and the
	 * hitpoints are read off it, and all three are properties of the monster.
	 */
	@Nullable
	public NpcAmmoRecord findByName(String name)
	{
		for (NpcAmmoRecord record : records.values())
		{
			if (record.getNpcName().equalsIgnoreCase(name))
			{
				return record;
			}
		}
		return null;
	}

	public int size()
	{
		return records.size();
	}

	public boolean isEmpty()
	{
		return records.isEmpty();
	}

	/**
	 * Symmetric with a fresh instance: what {@code shutDown()} calls.
	 *
	 * <p>The worn setup goes back to {@link Loadout#UNKNOWN} too. A ledger that
	 * remembered the last session's equipment would file the first kill of the next
	 * one under gear the player may well have changed while the plugin was off.
	 */
	public void clear()
	{
		records.clear();
		mostRecentKill = null;
		equipped = Loadout.UNKNOWN;
	}
}
