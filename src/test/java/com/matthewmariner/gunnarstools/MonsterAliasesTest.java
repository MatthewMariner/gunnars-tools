package com.matthewmariner.gunnarstools;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * The slang table on its own, with no {@link MonsterIndex} behind it: does a
 * known token get replaced, does an unknown one survive untouched, and is a
 * nickname matched whole rather than as a substring.
 */
public class MonsterAliasesTest
{
	@Test
	public void aKnownTokenIsReplacedByTheRealNamesToken()
	{
		assertEquals("abyssal demon", MonsterAliases.expand("abby demon"));
	}

	/** The rewrite is per token, so a query with no slang in it at all is untouched. */
	@Test
	public void aQueryWithNoSlangComesBackUnchanged()
	{
		assertEquals("venenatis", MonsterAliases.expand("venenatis"));
		assertEquals("dagannoth rex", MonsterAliases.expand("dagannoth rex"));
	}

	/**
	 * Every aliased token is rewritten, not only the first one found — a query is
	 * not expected to carry more than one nickname, but nothing here assumes that.
	 */
	@Test
	public void everyAliasedTokenIsRewritten()
	{
		assertEquals("abyssal king black", MonsterAliases.expand("abby kbd"));
	}

	/**
	 * A token is matched whole. "abby" must not fire against a word that merely
	 * starts with it — that would rewrite a name rather than a nickname, which is
	 * the failure {@link MonsterIndex}'s own tiers already avoid by comparing
	 * whole strings rather than characters.
	 */
	@Test
	public void anAliasFiresOnAWholeTokenOnlyNeverAsASubstring()
	{
		assertEquals("abbyssal", MonsterAliases.expand("abbyssal"));
		assertEquals("corporation", MonsterAliases.expand("corporation"));
	}

	/**
	 * {@link MonsterIndex#normalise} always lower-cases before this is called, so
	 * this is what the table actually receives — pinned here rather than assumed,
	 * since a table keyed one way and probed another is the exact failure
	 * {@link MonsterIndex#add}'s sanitising note already warns about, reached from
	 * a different door.
	 */
	@Test
	public void theTableIsKeyedInLowerCase()
	{
		assertEquals("abyssal", MonsterAliases.expand("abby"));
	}

	@Test
	public void anEmptyQueryStaysEmpty()
	{
		assertEquals("", MonsterAliases.expand(""));
	}

	@Test
	public void everyTableEntryResolvesItsKnownNickname()
	{
		assertEquals("dagannoth", MonsterAliases.expand("dks"));
		assertEquals("kalphite", MonsterAliases.expand("kq"));
		assertEquals("king black", MonsterAliases.expand("kbd"));
		assertEquals("graardor", MonsterAliases.expand("bandos"));
		assertEquals("tsutsaroth", MonsterAliases.expand("zammy"));
		assertEquals("venenatis", MonsterAliases.expand("venny"));
	}

	/**
	 * "sara" and "arma" are deliberately not in the table — see
	 * {@link MonsterAliases}'s class javadoc, "A key cannot be a prefix of an
	 * ordinary NPC's name". Pinned here as a negative so a future entry for
	 * either one is a deliberate, reviewed change to this test, not a silent
	 * regression back into a key that can never fire.
	 */
	@Test
	public void saraAndArmaAreNotAliasesBecauseTheyArePrefixesOfOrdinaryNames()
	{
		assertEquals("sara", MonsterAliases.expand("sara"));
		assertEquals("arma", MonsterAliases.expand("arma"));
	}
}
