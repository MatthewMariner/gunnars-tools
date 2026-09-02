package com.matthewmariner.gunnarstools;

import javax.annotation.Nullable;

/**
 * The three things {@link FoughtNpc} reads out of an {@code NPCComposition}: its
 * id, its name and its stats. Everything else inherits {@link StubNpcComposition}'s
 * throw.
 *
 * <p>The stats array is handed over as-is, including the null and the wrong
 * length, because those are the cases {@code FoughtNpcTest} needs to be able to
 * construct — the whole point of the class under test is that a stats array is
 * not to be trusted on sight.
 */
final class FakeNpcComposition extends StubNpcComposition
{
	private final int id;
	private final String name;
	private final int[] stats;

	FakeNpcComposition(int id, String name, @Nullable int[] stats)
	{
		this.id = id;
		this.name = name;
		this.stats = stats;
	}

	/**
	 * A composition whose stats array is exactly what the cache leaves behind
	 * when nothing filled it in: six ones, not six zeros.
	 */
	static FakeNpcComposition withUnsetStats(int id, String name)
	{
		return new FakeNpcComposition(id, name, new int[]{1, 1, 1, 1, 1, 1});
	}

	@Override
	public int getId()
	{
		return id;
	}

	@Override
	public String getName()
	{
		return name;
	}

	@Override
	public int[] getStats()
	{
		return stats;
	}
}
