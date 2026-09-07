package com.matthewmariner.gunnarstools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The slang OSRS players actually type instead of a monster's name, and the one
 * distinctive word of the real name it stands for.
 *
 * <p>He searched "abby demon" for Abyssal demon and got nothing. "abby" alone
 * already works — {@link MonsterIndex}'s near tier is one edit from "abyssal" in
 * both names it could mean — but a *second* word he typed correctly made it
 * worse: "abby demon" measured whole against "abyssal de" is nowhere near close
 * enough, and forgiving misspellings was never going to forgive a different word.
 * Wilderness and Slayer bosses are routinely known by something other than their
 * name — this is the finite list of what that something is.
 *
 * <h2>This is a table, and {@link Ammunition} argued at length against one</h2>
 *
 * <p>That argument does not transfer, and the reason it does not is the whole
 * justification for this file existing. {@link Ammunition#isConsumable} replaced
 * a table of item ids with {@code stackable}, a property the game already tracks
 * for reasons of its own — the table would have been a lossy cache of something
 * computable, checked into a repository to save one lookup, and wrong the week
 * Jagex added an ammunition type it did not have an entry for. There is no
 * equivalent property here. Nothing about "Abyssal demon" as data — its name, its
 * hitpoints, its combat stats, anything {@link NpcSource} can read out of the
 * client's own cache — implies that a player might call it "abby", any more than
 * a dictionary derives that "cop" is short for "police officer" from the word
 * "officer" itself. A nickname is a fact about how a community talks, not a fact
 * about the monster, and the only way to know a fact like that is for someone who
 * has seen it used to write it down. Finite and slow-moving — Jagex adds a boss
 * every year or two, not a new slang term — but not computable at any speed.
 *
 * <h2>Small on purpose, and checked before it grew</h2>
 *
 * <p>A wrong entry here is worse than a missing one: it sends a player to a
 * different monster while looking exactly as confident as a right answer, and
 * confidence is the one thing {@link MonsterIndex} is built never to fake — see
 * its class javadoc on ambiguity shown rather than resolved silently. So every
 * entry below is one this plugin's author was sure of, and several plausible ones
 * were dropped rather than guessed at: see the development log for the ones that
 * did not make it and why, including several that turned out to need no entry at
 * all because {@link MonsterIndex}'s existing prefix, substring and near tiers
 * already resolve the bare nickname on their own — an alias earns its place only
 * when something in the query, not just the nickname alone, is what the plain
 * tiers cannot get past.
 *
 * <h2>A key cannot be a prefix of an ordinary NPC's name</h2>
 *
 * <p>"sara" and "arma" are not in the table below, and it is not because nobody
 * thought of them — players genuinely say both, for Saradomin's and Armadyl's
 * God Wars bosses. They cannot be entries, because {@link MonsterIndex#add}
 * indexes every named NPC the client's cache holds, not just combat monsters,
 * and both strings are literal prefixes of an entirely ordinary one: Sara
 * <em>domin</em> priest, Arma<em>dyl</em> guard. The zero-match gate that
 * unlocks this table at all — see {@link MonsterIndex#matching} — is a single
 * pass/fail test run once, before this table is even consulted, and against
 * either name {@link MonsterIndex.Tier#PREFIX} answers it first: a generic
 * priest or guard, not a miss the alias table ever gets a turn to fix. That is
 * worse than the eleven candidates the development log describes dropping for
 * being dead code — those were harmless, because the raw query already found
 * the <em>right</em> monster before an alias was needed. "sara" and "arma"
 * would be live entries that silently never fire, masking a wrong answer
 * behind a table that looks like it covers the case.
 *
 * <p>Nothing is actually lost by leaving them out: {@link MonsterIndex.Tier
 * #CONTAINS} already finds Commander Zilyana from "zily" and {@link
 * MonsterIndex.Tier#PREFIX} already finds Kree'arra from "kree", through the
 * ordinary tiers, with this table never involved. The lesson generalises past
 * these two gods, which is why it is written here rather than as a comment by
 * the two missing rows: a candidate key is only viable if no ordinary NPC's
 * name in the cache starts with it, and that is a property of the whole
 * cache checked at the time, not a one-off fact about "sara" and "arma" —
 * the next person proposing an entry has to check the same thing against
 * whatever names the cache holds then, not just assume a short faction
 * nickname is safe because these two turned out not to be.
 *
 * <h2>A token, never the full name</h2>
 *
 * <p>"abby" maps to "abyssal", not to "abyssal demon" or "abyssal sire" — mapping
 * to the *whole* name would mean silently picking which Abyssal monster the
 * player meant, which is exactly the wrong this plugin's ambiguity rule exists to
 * prevent, reached from a different door. Handing back one distinctive word and
 * letting {@link MonsterIndex}'s ordinary tiers do the rest keeps that choice
 * where it belongs: with whichever of the resulting rows the player clicks.
 */
final class MonsterAliases
{
	/**
	 * Slang, lower case, to the token of the real name it means — also lower
	 * case, since {@link #expand} only ever sees an already-{@link
	 * MonsterIndex#normalise}d query. Insertion order is kept for no reason the
	 * lookup cares about; it is what makes a dump of this map read in the order
	 * it was authored, which is the order the development log discusses it in.
	 */
	private static final Map<String, String> ALIASES = build();

	private MonsterAliases()
	{
	}

	private static Map<String, String> build()
	{
		final Map<String, String> table = new LinkedHashMap<>();

		// Abyssal demon *and* Abyssal Sire both answer to "abby" — showing both is
		// the point, not a gap. The report this file exists for: "abby" alone already
		// found them through the near tier; "abby demon" did not, because a second
		// word he typed correctly made the whole-string edit distance worse instead
		// of better.
		table.put("abby", "abyssal");

		// "dks" alone finds nothing by any of MonsterIndex's existing tiers — it is
		// not a near miss of "dagannoth" by any budget this project forgives, and it
		// is not a substring of it either. Resolves to the Kings, the ordinary
		// Dagannoth and the spawn together, which is correct: it is Krystilia's
		// umbrella task name, not one monster.
		table.put("dks", "dagannoth");

		// Kalphite Queen, plus whatever else in the Kalphite Lair the client's cache
		// answers to "kalphite" — an umbrella, same as "dks" above, and for the same
		// reason: nothing here decides which Kalphite the player meant.
		table.put("kq", "kalphite");

		// The real name is "King Black Dragon", and "black" alone is far too common a
		// word across the cache to use as the token — Black dragon, Black demon and
		// Black knight all contain it. "king black" is still a token that really
		// occurs at the front of the real name, just two words instead of one; the
		// existing prefix tier does not care how many spaces are in what it is
		// comparing.
		table.put("kbd", "king black");

		// The Bandos general at God Wars. "bandos" names the god's whole faction, not
		// this specific boss, which is exactly why it is not findable by any property
		// of the NPC itself — the same argument as the class javadoc, restated for
		// the entry most likely to look surprising on first read.
		table.put("bandos", "graardor");

		// The Zamorak boss at God Wars — "zammy" is the god's nickname, not the
		// NPC's, same shape as "bandos" above. Armadyl's and Saradomin's own
		// faction nicknames, "arma" and "sara", are deliberately not entries here —
		// see the class javadoc's "A key cannot be a prefix of an ordinary NPC's
		// name" section for why a table entry for either one could never fire.
		table.put("zammy", "tsutsaroth");

		// One of the reworked Wilderness bosses. "venny" is one edit short of a near
		// match to "venenatis" under this project's own budget (a five-letter query
		// gets one edit, and this one needs more than that to reach the real name's
		// prefix), so the near tier does not already catch it the way it catches
		// "abby".
		table.put("venny", "venenatis");

		return Collections.unmodifiableMap(table);
	}

	/**
	 * Rewrites every token in {@code query} that this table has an entry for,
	 * leaving everything else untouched.
	 *
	 * <p>Token-level rather than whole-string, and that is what lets "abby demon"
	 * become "abyssal demon" rather than requiring an entry for every sentence a
	 * player might type. {@link MonsterIndex} decides on its own whether to call
	 * this at all — see its {@code matching} — so nothing here needs to know that
	 * it is only ever consulted once the plain tiers have already failed.
	 *
	 * @param query already {@link MonsterIndex#normalise}d: lower case, trimmed,
	 *              and single-spaced. A token is matched only whole — "abbyssal"
	 *              is left alone even though it starts with "abby" — because a
	 *              substring match here would rewrite words that happen to
	 *              contain a nickname rather than words that <em>are</em> one.
	 * @return the rewritten query, or {@code query} itself, unchanged, if nothing
	 * in it was an alias — which a caller can tell apart from "rewritten to
	 * exactly what it already was" (impossible; nothing maps to itself) with a
	 * plain {@code equals} check
	 */
	static String expand(String query)
	{
		if (query.isEmpty())
		{
			return query;
		}

		final String[] tokens = query.split(" ");
		boolean rewritten = false;
		final StringBuilder out = new StringBuilder(query.length());
		for (int i = 0; i < tokens.length; i++)
		{
			final String alias = ALIASES.get(tokens[i]);
			if (i > 0)
			{
				out.append(' ');
			}
			if (alias != null)
			{
				out.append(alias);
				rewritten = true;
			}
			else
			{
				out.append(tokens[i]);
			}
		}
		return rewritten ? out.toString() : query;
	}
}
