package com.matthewmariner.gunnarstools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * An {@link NpcSource} a test fills in by hand, including the ways the real one
 * fails.
 *
 * <p>The failures are the point. The client streams its cache, so the first read
 * of the NPC group routinely comes back with nothing and fixes itself a moment
 * later — {@link #withoutIds()} and {@link #withoutDefinitions()} are those two
 * states, and they are what make "an empty answer is never latched as a permanent
 * one" an assertion rather than a comment.
 *
 * <p>{@link #reads()} counts every definition handed out, so "the sweep is spread
 * over ticks rather than done in one" can be checked by counting rather than by
 * reading the source.
 */
final class FakeNpcSource implements NpcSource
{
	private final Map<Integer, FoughtNpc> monsters = new LinkedHashMap<>();

	private boolean idsAvailable = true;
	private boolean definitionsAvailable = true;
	private int reads;

	/** Adds a monster with real stats, so {@link FoughtNpc#hasStats()} is true. */
	FakeNpcSource with(int npcId, String name, int hitpoints)
	{
		monsters.put(npcId, new FoughtNpc(FoughtNpc.NO_INDEX, npcId, name,
			new int[]{70, 70, 70, hitpoints, 1, 70}));
		return this;
	}

	/**
	 * Adds a monster whose stats the cache never filled in — the
	 * {@code {1,1,1,1,1,1}} default {@link FoughtNpc} exists to recognise.
	 */
	FakeNpcSource withoutStats(int npcId, String name)
	{
		monsters.put(npcId, new FoughtNpc(FoughtNpc.NO_INDEX, npcId, name, null));
		return this;
	}

	/** Adds an id the archive lists and has no definition for. The commonest case in a real cache. */
	FakeNpcSource unnamed(int npcId)
	{
		monsters.put(npcId, new FoughtNpc(FoughtNpc.NO_INDEX, npcId, "null", null));
		return this;
	}

	/** The archive's index has not been read: {@code getFileIds} returns null. */
	FakeNpcSource withoutIds()
	{
		idsAvailable = false;
		return this;
	}

	/** The group has not landed: every definition reads as the unnamed placeholder. */
	FakeNpcSource withoutDefinitions()
	{
		definitionsAvailable = false;
		return this;
	}

	/** Whatever was missing has arrived. */
	FakeNpcSource nowAvailable()
	{
		idsAvailable = true;
		definitionsAvailable = true;
		return this;
	}

	@Override
	@Nullable
	public int[] npcIds()
	{
		if (!idsAvailable)
		{
			return null;
		}
		final List<Integer> ids = new ArrayList<>(monsters.keySet());
		final int[] out = new int[ids.size()];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = ids.get(i);
		}
		return out;
	}

	@Override
	@Nullable
	public FoughtNpc npc(int npcId)
	{
		reads++;
		if (!definitionsAvailable)
		{
			// What the client actually produces for a group it has not fetched yet: a
			// composition with its defaults, whose name is the "null" placeholder.
			return new FoughtNpc(FoughtNpc.NO_INDEX, npcId, "null", null);
		}
		return monsters.get(npcId);
	}

	/** How many definitions have been handed out since this source was built. */
	int reads()
	{
		return reads;
	}
}
