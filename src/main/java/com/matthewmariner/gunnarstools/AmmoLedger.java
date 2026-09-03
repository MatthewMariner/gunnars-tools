package com.matthewmariner.gunnarstools;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Every {@link NpcAmmoRecord} measured this session, by NPC id.
 *
 * <p><b>In memory only.</b> Nothing here touches the disk, and that is a
 * decision rather than an omission: the measurement approach is not settled
 * enough to be worth a file format that would then have to be migrated, and
 * persistence is a slice of its own rather than a line in this one. It also
 * means the numbers are only as good as the session that produced them, which is
 * honest for a first milestone — a per-kill figure from four kills is not one to
 * plan a trip on, and the sample count sits next to every figure so nobody has
 * to guess.
 *
 * <p><b>What this decision is <em>not</em> justified by, any more.</b> It used to
 * be argued here that "a plugin that writes files is reviewed by hand at the
 * Plugin Hub instead of automatically." <b>That is unverified and nothing
 * supports it</b> — not the plugin-hub README, not its tooling, not the RuneLite
 * wiki. The hub's only file-related rule is about <em>location</em> ("only
 * read/write files inside the {@code .runelite} directory", which is in this
 * repository's own {@code AGENTS.md}), and the one thing its README names as
 * requiring a maintainer by hand is dependency verification, not file I/O. The
 * design stands perfectly well on its own; it did not need a review-process
 * consequence invented for it, and an argument resting on a made-up rule is
 * weaker than the same argument resting on nothing.
 *
 * <p>Insertion-ordered, so a dump reads in the order monsters were fought.
 */
public final class AmmoLedger
{
	private final Map<Integer, NpcAmmoRecord> records = new LinkedHashMap<>();

	/**
	 * The record for the monster most recently killed, which is what every display
	 * surface plans for.
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
	 * Applies one verdict from {@link KillAttribution}.
	 *
	 * <p>The switch is exhaustive on purpose: a fourth kind added later without a
	 * branch here would drop verdicts on the floor, which is exactly the silent
	 * loss the three kinds exist to prevent, so it throws instead.
	 */
	public void apply(Attribution attribution)
	{
		final FoughtNpc npc = attribution.getNpc();
		final NpcAmmoRecord record = records.computeIfAbsent(npc.getId(), id -> new NpcAmmoRecord(npc));
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

	@Nullable
	public NpcAmmoRecord get(int npcId)
	{
		return records.get(npcId);
	}

	/** @return the monster to plan a trip for, or null before the first kill */
	@Nullable
	public NpcAmmoRecord getMostRecentKill()
	{
		return mostRecentKill;
	}

	public Collection<NpcAmmoRecord> getRecords()
	{
		return Collections.unmodifiableCollection(records.values());
	}

	public int size()
	{
		return records.size();
	}

	public boolean isEmpty()
	{
		return records.isEmpty();
	}

	/** Symmetric with a fresh instance: what {@code shutDown()} calls. */
	public void clear()
	{
		records.clear();
		mostRecentKill = null;
	}
}
