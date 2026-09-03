package com.matthewmariner.gunnarstools;

import java.util.Arrays;

/**
 * What one item id cost on every attributed kill of one monster, one entry per
 * kill, in the order the kills happened.
 *
 * <h2>Why the individual kills are kept at all</h2>
 *
 * <p>Milestone 1 kept a running total and a kill count, which is enough to
 * publish a mean and nothing else. A mean on its own cannot say how much to
 * trust it: twenty-five arrows per kill measured over two kills and twenty-five
 * measured over two hundred are the same number, and a trip packed off the first
 * one is a guess wearing a decimal point. Keeping the samples is what makes the
 * spread available, and the spread is the honest half of the answer.
 *
 * <h2>Order statistics, not a fitted distribution</h2>
 *
 * <p>{@link #percentile(int)} is <b>nearest rank</b>: sort the observed values,
 * take the one at {@code ceil(percent × n / 100)}. No interpolation, no assumed
 * shape. Every figure this class publishes is therefore a number that actually
 * happened on an actual kill, which is the whole design principle of this plugin
 * applied one level down — <em>measure, do not model.</em>
 *
 * <p>The alternative on offer was a mean and a standard deviation, read as a
 * normal interval. That would have been wrong in a way that matters rather than
 * a way that is merely inelegant. Ammunition per kill is discrete, floored at
 * zero and right-skewed — most kills cost about the same, an unlucky one costs
 * two or three times that, and none can cost less than nothing. A symmetric
 * interval fitted to that reports a lower bound below zero often enough to be
 * embarrassing, and understates the long tail on the side the player actually
 * has to carry.
 *
 * <p>The rank is computed in integer arithmetic, and the reason is worth stating
 * precisely rather than gesturing at, because the obvious version is wrong in a
 * way that is easy to overstate. {@code (int) Math.ceil((percent / 100d) * size)}
 * disagrees with the exact ceiling for real inputs — {@code 0.07d * 100} is
 * {@code 7.000000000000001}, so the seventh percentile of a hundred samples
 * would take rank 8 rather than rank 7, silently and only at some sample counts.
 * It does <em>not</em> disagree at either of the two percentiles this class is
 * asked for today: for 50 and 90 the two forms agree for every sample count up
 * to a million, checked rather than assumed, and the tempting comment claiming
 * otherwise about {@code 0.9d * 20} was simply false — that product is exactly
 * 18.0. So this is a guard against the next percentile somebody asks for, not a
 * bug being fixed. {@code (percent × n + 99) / 100} on longs is the same ceiling
 * with no such case at any percentile.
 *
 * <h2>Growth</h2>
 *
 * <p>A {@code long[]} that doubles, rather than a {@code List<Long>}, because
 * this holds one entry per kill per item id for the whole session and boxing
 * every one of them is three words where one would do. The sorted copy is built
 * on demand and thrown away by the next {@link #add(long)}, so a caller that asks
 * for four percentiles between two kills sorts once.
 */
final class KillSamples
{
	private static final int INITIAL_CAPACITY = 8;

	private long[] values = new long[INITIAL_CAPACITY];
	private int size;
	private long sum;
	private int nonZero;

	/** Dropped on every {@link #add(long)}; rebuilt by the next percentile query. */
	private long[] sorted;

	/**
	 * Records what this item cost on one kill.
	 *
	 * <p>Zero is a real sample and is stored as one. A kill during which the
	 * player fired nothing — somebody else finished the monster, or the fight was
	 * over in the tick the window opened — is evidence about what this monster
	 * costs, and dropping it would quietly raise the mean of everything that is
	 * left.
	 */
	void add(long value)
	{
		if (size == values.length)
		{
			values = Arrays.copyOf(values, values.length * 2);
		}
		values[size++] = value;
		sum += value;
		if (value != 0L)
		{
			nonZero++;
		}
		sorted = null;
	}

	/** How many kills are behind this series. Equal to the record's kill count. */
	int size()
	{
		return size;
	}

	/** Gross quantity across every kill. */
	long sum()
	{
		return sum;
	}

	/**
	 * @return how many of those kills spent any of this item at all. Published
	 * next to the sample count because "spent on 37 of 37 kills" and "spent on 3
	 * of 37 kills" are the difference between a per-kill cost and an occasional
	 * event that happened to land inside a window — a stack moved into the looting
	 * bag mid-fight, say, which the README lists as a known contaminant.
	 */
	int nonZero()
	{
		return nonZero;
	}

	/** One sample, in the order the kills happened. For tests and for nothing else. */
	long at(int index)
	{
		if (index < 0 || index >= size)
		{
			throw new IndexOutOfBoundsException("no sample " + index + " in " + size);
		}
		return values[index];
	}

	/**
	 * The nearest-rank percentile of the observed samples.
	 *
	 * @param percent 0 for the cheapest kill seen, 100 for the dearest. Values
	 *                outside that range are clamped rather than rejected: this is
	 *                a reading of a measurement, and there is no percentile a
	 *                caller could ask for that deserves an exception more than it
	 *                deserves the nearest real sample.
	 * @return a quantity that was actually observed on some kill, or 0 when there
	 * are no samples
	 */
	long percentile(int percent)
	{
		if (size == 0)
		{
			return 0L;
		}

		final long[] ordered = ordered();

		// ceil(percent * size / 100) in exact integer arithmetic; see the class
		// javadoc for why this is not the double multiply it looks like it wants
		// to be.
		long rank = ((long) percent * size + 99L) / 100L;
		if (rank < 1L)
		{
			rank = 1L;
		}
		if (rank > size)
		{
			rank = size;
		}
		return ordered[(int) (rank - 1L)];
	}

	private long[] ordered()
	{
		if (sorted == null)
		{
			sorted = Arrays.copyOf(values, size);
			Arrays.sort(sorted);
		}
		return sorted;
	}
}
