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
 * decision rather than an omission: a plugin that writes files is reviewed by
 * hand at the Plugin Hub instead of automatically, and the measurement approach
 * is not settled enough yet to be worth spending that on. It also means the
 * numbers are only as good as the session that produced them, which is honest
 * for a first milestone — a per-kill figure from four kills is not one to plan a
 * trip on, and the sample count sits next to every figure so nobody has to
 * guess.
 *
 * <p>Insertion-ordered, so a dump reads in the order monsters were fought.
 */
public final class AmmoLedger
{
	private final Map<Integer, NpcAmmoRecord> records = new LinkedHashMap<>();

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
				record.recordKill(attribution.getTally());
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
	}
}
