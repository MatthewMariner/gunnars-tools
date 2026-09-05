package com.matthewmariner.gunnarstools;

import javax.annotation.Nullable;

/**
 * Reads the game's whole NPC list into a {@link MonsterIndex}, a slice per game
 * tick.
 *
 * <p>{@code AGENTS.md} is explicit that a plugin does not scan everything every
 * tick, and the sixteen-thousand-odd definitions in the cache are exactly the
 * shape of work it is warning about. They are also read once per session and
 * never again, so the answer is not to skip the scan but to spread it: a fixed
 * slice per tick, a handful of ticks, and nothing at all afterwards.
 *
 * <h2>An empty answer is "not yet", not "never"</h2>
 *
 * <p>The client streams its cache. The group holding the NPC definitions is
 * fetched the first time something asks for it, and until it lands every read of
 * it comes back with nothing — {@link NpcSource#npcIds()} null, or every
 * {@link NpcSource#npc(int)} unnamed. Both are ordinary and both fix themselves a
 * moment later.
 *
 * <p>So <b>no failure is ever latched</b>. A sweep that produced no ids goes back
 * to {@link State#WAITING} and is retried; a sweep that ran the whole list and
 * found no names is thrown away and started again rather than published as an
 * empty index. The only way out of here is a sweep that actually found monsters,
 * which is the only state in which the panel has anything true to say.
 *
 * <h2>What is published, and when</h2>
 *
 * <p>Nothing partial is ever visible. The index being filled is private until
 * the last id has been read; {@link #getIndex()} returns null until then and the
 * sealed, immutable index afterwards. The field is {@code volatile} because it is
 * written on the client thread and read on the Swing thread — see
 * {@link MonsterIndex}'s javadoc for why sealing is what makes that safe rather
 * than merely likely.
 */
final class MonsterCatalogue
{
	/**
	 * How many NPC definitions are read per game tick.
	 *
	 * <p>Four thousand covers the archive in four ticks, which is under three
	 * seconds — short enough that a player who opens the panel and starts typing
	 * has an answer before they finish the word, and small enough per tick that
	 * the work is bounded rather than a stall. It is a constant rather than a
	 * setting because there is no version of this a user could usefully tune.
	 */
	static final int SLICE = 4096;

	/**
	 * Where the sweep is, and what the panel says while it is there.
	 *
	 * <p>Each value carries its own two lines, exactly as {@link TripAdvice.Waiting}
	 * does and for the same reason: a sentence composed inside a Swing panel is a
	 * sentence no test can read, and this project has already had the argument
	 * about an empty surface being indistinguishable from a broken one.
	 */
	enum State
	{
		/**
		 * Nothing has been read yet. Either the plugin has just started or the
		 * client has not brought the cache's NPC group in.
		 */
		WAITING("monster list not read yet", "log in and give it a moment"),

		/** Part-way through. The panel shows {@link MonsterCatalogue#getPercent()} beside this. */
		SCANNING("reading the game's monster list", "no download — this is your own cache"),

		/**
		 * Done. The detail line is the one that had to teach: the owner's complaint
		 * was that he could not work out how to drive the plugin at all, so the
		 * state in which it is ready and empty names the action that fills it in
		 * rather than merely being blank.
		 */
		READY("type a monster's name", "then pick it to plan the trip");

		private final String headline;
		private final String detail;

		State(String headline, String detail)
		{
			this.headline = headline;
			this.detail = detail;
		}

		String getHeadline()
		{
			return headline;
		}

		String getDetail()
		{
			return detail;
		}
	}

	/** Written on the client thread, read on the Swing thread. See the class javadoc. */
	private volatile MonsterIndex published;

	private volatile State state = State.WAITING;

	/** Progress, as a count of ids read out of {@link #ids}. Volatile for the same reason. */
	private volatile int cursor;

	private volatile int total;

	/** The id list for the sweep in progress, and the index being filled from it. */
	private int[] ids;

	private MonsterIndex building;

	/**
	 * Reads the next slice.
	 *
	 * <p>Called from the game tick, on the client thread, which is the only thread
	 * {@code Client.getNpcDefinition} will run on. Returns immediately once the
	 * sweep has finished, so the caller does not have to remember whether it has.
	 *
	 * @param source where the monsters come from
	 * @param slice  how many to read this time. Clamped to at least one, so a
	 *               miscalculated slice size stalls nothing.
	 * @return the state after this slice
	 */
	State advance(NpcSource source, int slice)
	{
		if (state == State.READY)
		{
			return state;
		}

		if (ids == null)
		{
			final int[] found = source.npcIds();
			if (found == null || found.length == 0)
			{
				// The archive's index has not been read in yet. Retryable, and
				// deliberately not recorded as anything worse — see the class javadoc.
				state = State.WAITING;
				return state;
			}

			// Copied rather than held. The client's getFileIds hands back its own
			// internal array, and a reference kept across ticks is a reference to
			// something the client is free to replace underneath us.
			ids = found.clone();
			total = ids.length;
			cursor = 0;
			building = new MonsterIndex();
		}

		state = State.SCANNING;

		final int end = Math.min(ids.length, cursor + Math.max(1, slice));
		for (int i = cursor; i < end; i++)
		{
			building.add(source.npc(ids[i]));
		}
		cursor = end;

		if (cursor < ids.length)
		{
			return state;
		}

		if (building.isEmpty())
		{
			// The whole list read and not one monster named: the group was not
			// resident and every read came back empty. Start over rather than
			// publishing an index that would answer "no such monster" to every
			// question forever.
			restart();
			return state;
		}

		published = building.seal();
		ids = null;
		building = null;
		state = State.READY;
		return state;
	}

	/** Throws away the sweep in progress and leaves it to be started again. */
	private void restart()
	{
		ids = null;
		building = null;
		cursor = 0;
		total = 0;
		state = State.WAITING;
	}

	/**
	 * @return the finished index, or null while there is not one. Never a partial
	 * one; see the class javadoc.
	 */
	@Nullable
	MonsterIndex getIndex()
	{
		return published;
	}

	State getState()
	{
		return state;
	}

	boolean isReady()
	{
		return state == State.READY;
	}

	/**
	 * How far through the sweep, as a whole percentage.
	 *
	 * <p>Zero before it starts and a hundred once it is done, so the panel does not
	 * have to special-case either end. Integer arithmetic on a long, because
	 * {@code cursor * 100} on an id list this size is nowhere near overflowing but
	 * the next person to change the slice size should not have to check.
	 */
	int getPercent()
	{
		if (state == State.READY)
		{
			return 100;
		}
		final int seen = cursor;
		final int all = total;
		return all <= 0 ? 0 : (int) ((long) seen * 100L / all);
	}

	/**
	 * Back to the state a fresh instance is in: what {@code shutDown()} calls.
	 *
	 * <p>The index goes with it rather than being kept as a cache across a disable
	 * and re-enable. A cache is a promise that what it holds is still true, and a
	 * plugin that was switched off may have been switched off across a game update.
	 */
	void clear()
	{
		published = null;
		restart();
	}

	@Override
	public String toString()
	{
		return "MonsterCatalogue(" + state + " " + getPercent() + "%, " + published + ")";
	}
}
