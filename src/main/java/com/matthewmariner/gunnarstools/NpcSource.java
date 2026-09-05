package com.matthewmariner.gunnarstools;

import javax.annotation.Nullable;

/**
 * Where the monster list comes from: the game's own cache, one NPC at a time.
 *
 * <p>Behind an interface for the same reason as {@link OverlayRegistry},
 * {@link ConfigStore} and {@link ClientThreadRunner} — a {@code Client} cannot be
 * constructed without a game and there is no mocking framework on this classpath.
 * The production implementation is six lines in {@link GunnarsToolsPlugin}; every
 * decision about what to do with what comes back is in {@link MonsterCatalogue}
 * and {@link MonsterIndex}, where a test can reach it.
 *
 * <p><b>Both methods may return null, and neither null is an error.</b> The
 * client streams the cache: the group holding the NPC definitions is fetched on
 * first use, and until it arrives every read of it comes back empty. A caller
 * that treats the first empty answer as "this client has no monsters" latches a
 * permanent failure on a condition that resolves itself a second later. See
 * {@link MonsterCatalogue#advance}.
 */
interface NpcSource
{
	/**
	 * Every NPC id the cache has a definition for.
	 *
	 * <p>In production this is {@code getIndexConfig().getFileIds(9)} — index 2 is
	 * CONFIGS and archive 9 is NPC, both confirmed against
	 * {@code net.runelite.cache}'s own {@code IndexType} and {@code ConfigType} for
	 * the pinned client version.
	 *
	 * @return the ids, or null when the archive's index has not been read yet.
	 * <b>The array must not be modified</b> — the client's own
	 * {@code getFileIds} hands back its internal array rather than a copy
	 * (verified against the 1.12.38 injected client: the method body is a bounds
	 * check and an {@code aaload}), so writing to it would corrupt the client's
	 * idea of what the cache contains.
	 */
	@Nullable
	int[] npcIds();

	/**
	 * @param npcId one of {@link #npcIds()}
	 * @return the monster's name and stats, or null if it could not be read. The
	 * scene index on the result is {@link FoughtNpc#NO_INDEX}: nothing here is in
	 * the scene.
	 */
	@Nullable
	FoughtNpc npc(int npcId);
}
