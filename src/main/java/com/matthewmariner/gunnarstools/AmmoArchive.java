package com.matthewmariner.gunnarstools;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import net.runelite.api.NPCComposition;

/**
 * What previous sessions cost, kept so the plugin can answer a question asked at
 * a bank rather than only one asked mid-fight.
 *
 * <h2>What this is for, and the one thing it is not allowed to do</h2>
 *
 * <p>A Wilderness Slayer trip is packed <em>before</em> it starts. Standing at a
 * bank with a fresh task, the player has killed nothing this session, so
 * everything {@link AmmoLedger} knows is empty and everything
 * {@link ConsumptionMeter} has measured is nothing. An in-memory-only plugin has
 * no answer at exactly the moment the answer is wanted, which is most of why the
 * previous version of this one was unusable.
 *
 * <p>So a compact summary survives, through RuneLite's own configuration store —
 * not a file. That is the mechanism {@code AGENTS.md} points at, and it is the
 * one this project should use: a plugin's saved state belongs in the user's
 * profile alongside their settings, where deleting the plugin takes it with it.
 *
 * <p><b>A restored entry is an estimate and may never be shown as a
 * measurement.</b> That is the rule the rest of this class exists to make easy to
 * keep. Everything here is loaded into {@link ProjectedNeed}, whose whole reason
 * for being a separate type from {@link TripPlan} is that the two cannot be
 * confused at the point of use. The distinction is not pedantry: a measured
 * figure is a claim about kills this session watched, with a sample count and a
 * spread standing behind it, and republishing last month's total under that
 * heading would be claiming evidence this session does not have — possibly from
 * before a gear change, possibly from before a game update. The archive keeps the
 * totals; it does not keep the samples, and without the samples there is no
 * spread and no honest way to call it measured.
 *
 * <h2>The format, and why it is parsed as if it were hostile</h2>
 *
 * <p>One string, versioned, entries separated by semicolons:
 *
 * <pre>1;5265,Spindel,200,37,861,892,11:925;6612,Skeleton,29,12,861,892,11:96</pre>
 *
 * <p>Version first, so a later shape can be introduced by bumping it and
 * returning an empty archive for anything it does not recognise — a setting that
 * silently resets is bad, and a setting that throws on startup is worse.
 *
 * <p>Nothing here throws. The value is a string in a user-editable profile that
 * may have been written by a different version, hand-edited, or truncated, and a
 * {@code startUp()} that dies on a malformed one takes the whole plugin with it.
 * A field that does not parse loses its entry; an entry that does not parse
 * loses itself; neither loses the rest.
 */
public final class AmmoArchive
{
	/** The only version this understands. Bump it when the field order changes. */
	static final String VERSION = "1";

	/**
	 * How many monsters are remembered. Forty is more Wilderness Slayer monsters
	 * than Krystilia's whole task list contains, and it bounds the config value at
	 * a couple of kilobytes — a profile is synchronised, and an unbounded string
	 * in one is somebody else's outage.
	 */
	static final int MAX_ENTRIES = 40;

	private static final char ENTRY_SEPARATOR = ConfigText.ENTRY_SEPARATOR;
	private static final char FIELD_SEPARATOR = ConfigText.FIELD_SEPARATOR;
	private static final char ITEM_SEPARATOR = ConfigText.ITEM_SEPARATOR;
	private static final char QUANTITY_SEPARATOR = ConfigText.QUANTITY_SEPARATOR;

	/**
	 * One monster, as a previous session left it.
	 *
	 * <p>{@link #getMonsters()} is {@link NpcAmmoRecord#getMonstersPriced()} and
	 * not its kill count, for the same reason the live estimate divides by that
	 * one: it is counted in the unit a trip size is counted in. Storing the kill
	 * count instead would quietly reintroduce the threefold overstatement under
	 * area damage that this project has already had to correct once.
	 */
	public static final class Entry
	{
		private final int npcId;
		private final String name;
		private final int hitpoints;
		private final int monsters;
		private final int weaponId;
		private final int ammoId;
		private final Map<Integer, Long> consumed;

		/**
		 * <p>The name is sanitised here rather than at {@link AmmoArchive#format},
		 * so that an entry built by {@link #remember} and the same entry read back
		 * by {@link #parse} carry the same string. They did not: {@code remember}
		 * stored the raw name and {@code parseEntry} stored the sanitised one, so a
		 * monster whose name holds a separator matched by name before a restart and
		 * not after it. One name, decided once.
		 */
		Entry(int npcId, String name, int hitpoints, int monsters, int weaponId, int ammoId,
			Map<Integer, Long> consumed)
		{
			this.npcId = npcId;
			this.name = ConfigText.sanitise(name);
			this.hitpoints = hitpoints;
			this.monsters = monsters;
			this.weaponId = weaponId;
			this.ammoId = ammoId;
			this.consumed = Collections.unmodifiableMap(new LinkedHashMap<>(consumed));
		}

		public int getNpcId()
		{
			return npcId;
		}

		public String getName()
		{
			return name;
		}

		/** Zero when the session that wrote this never resolved them; see {@link PlanTarget}. */
		public int getHitpoints()
		{
			return hitpoints;
		}

		/** Monsters of this id the recorded windows killed. The denominator. */
		public int getMonsters()
		{
			return monsters;
		}

		/** The setup it was measured on, so a changed one can be said out loud. */
		public Loadout getLoadout()
		{
			return new Loadout(weaponId, ammoId);
		}

		/** Item id to gross quantity, across {@link #getMonsters()} monsters. */
		public Map<Integer, Long> getConsumed()
		{
			return consumed;
		}

		@Override
		public String toString()
		{
			return "Entry(" + name + " #" + npcId + ", hp=" + hitpoints
				+ ", monsters=" + monsters + ", consumed=" + consumed + ")";
		}
	}

	/** Insertion-ordered, oldest first, so the cap drops the least recently seen. */
	private final LinkedHashMap<Integer, Entry> entries = new LinkedHashMap<>();

	/**
	 * Folds one session's record in, replacing whatever was remembered about that
	 * monster rather than adding to it.
	 *
	 * <p>Replacing is the only defensible choice. Adding would sum totals from two
	 * different setups into one rate with nothing to say so — which is the exact
	 * error the whole gear-change story exists to prevent — and would also
	 * double-count a session that was saved twice. The newest reading is the one
	 * most likely to describe the player as they are now.
	 *
	 * <p>A record with no measured monsters is not remembered. An entry whose
	 * denominator is zero is not a rate, and keeping it would occupy one of the
	 * forty slots to say nothing.
	 */
	void remember(NpcAmmoRecord record, Loadout loadout)
	{
		if (record.getMonstersPriced() <= 0 || record.getConsumed().isEmpty())
		{
			return;
		}

		final Entry entry = new Entry(record.getNpcId(), record.getNpcName(),
			record.hasStats() ? record.getStat(NPCComposition.STAT_HITPOINTS) : 0,
			record.getMonstersPriced(), loadout.getWeaponId(), loadout.getAmmoId(),
			record.getConsumed());

		// Removed before it is put back so the re-insertion moves it to the end of
		// the iteration order. LinkedHashMap keeps a key's original position on a
		// plain overwrite, which would make "most recently measured" mean "first
		// ever measured" and evict exactly the wrong entries at the cap.
		entries.remove(entry.npcId);
		entries.put(entry.npcId, entry);

		while (entries.size() > MAX_ENTRIES)
		{
			entries.remove(entries.keySet().iterator().next());
		}
	}

	/** @return what a previous session measured about this monster, or null */
	@Nullable
	public Entry get(int npcId)
	{
		return entries.get(npcId);
	}

	/** Least recently measured first. */
	public Collection<Entry> getEntries()
	{
		return Collections.unmodifiableCollection(entries.values());
	}

	public int size()
	{
		return entries.size();
	}

	public boolean isEmpty()
	{
		return entries.isEmpty();
	}

	/** Forgets one monster. The manual reset's other half. */
	void forget(int npcId)
	{
		entries.remove(npcId);
	}

	/** Symmetric with a fresh instance. */
	void clear()
	{
		entries.clear();
	}

	/**
	 * @param serialised whatever came out of the config store, including null for
	 *                   a profile that has never had this key
	 * @return an archive holding every entry that parsed, which for an unreadable
	 * value is an empty one. Never null and never throws — see the class javadoc.
	 */
	static AmmoArchive parse(@Nullable String serialised)
	{
		final AmmoArchive archive = new AmmoArchive();
		if (serialised == null || serialised.isEmpty())
		{
			return archive;
		}

		final List<String> parts = split(serialised, ENTRY_SEPARATOR);
		if (parts.isEmpty() || !VERSION.equals(parts.get(0)))
		{
			return archive;
		}

		for (int i = 1; i < parts.size(); i++)
		{
			final Entry entry = parseEntry(parts.get(i));
			if (entry != null)
			{
				entries(archive).put(entry.npcId, entry);
			}
		}

		while (archive.entries.size() > MAX_ENTRIES)
		{
			archive.entries.remove(archive.entries.keySet().iterator().next());
		}
		return archive;
	}

	private static LinkedHashMap<Integer, Entry> entries(AmmoArchive archive)
	{
		return archive.entries;
	}

	@Nullable
	private static Entry parseEntry(String text)
	{
		final List<String> fields = split(text, FIELD_SEPARATOR);
		// Exactly seven, not at least seven. A hand-edited entry with a stray comma
		// in it parses perfectly well under "at least" — every field after the name
		// shifts along by one, so the hitpoints silently become zero and the weapon
		// and ammunition take their neighbours' values. That is a malformed entry
		// being read as a well-formed different one, which is the one outcome this
		// parser is written to make impossible; losing the entry is the cheaper
		// failure and the only honest one.
		if (fields.size() != 7)
		{
			return null;
		}

		final int npcId = parseInt(fields.get(0), Integer.MIN_VALUE);
		final int hitpoints = parseInt(fields.get(2), 0);
		final int monsters = parseInt(fields.get(3), 0);
		final int weaponId = parseInt(fields.get(4), Loadout.EMPTY_SLOT);
		final int ammoId = parseInt(fields.get(5), Loadout.EMPTY_SLOT);
		if (npcId == Integer.MIN_VALUE || monsters <= 0)
		{
			return null;
		}

		final Map<Integer, Long> consumed = new LinkedHashMap<>();
		for (String pair : split(fields.get(6), ITEM_SEPARATOR))
		{
			final int at = pair.indexOf(QUANTITY_SEPARATOR);
			if (at <= 0)
			{
				continue;
			}
			final int itemId = parseInt(pair.substring(0, at), Integer.MIN_VALUE);
			final long quantity = parseLong(pair.substring(at + 1));

			// A zero or negative quantity is not a cost, and an id of -1 is the empty
			// slot marker rather than an item. Both are dropped rather than rejecting
			// the entry: one bad pair should not lose a monster's other ammunition.
			if (itemId >= 0 && quantity > 0L)
			{
				consumed.put(itemId, quantity);
			}
		}

		if (consumed.isEmpty())
		{
			return null;
		}
		return new Entry(npcId, fields.get(1), Math.max(0, hitpoints), monsters,
			weaponId, ammoId, consumed);
	}

	/** The string to hand back to the config store. Empty archive, empty string. */
	String format()
	{
		if (entries.isEmpty())
		{
			return "";
		}

		final StringBuilder out = new StringBuilder(VERSION);
		for (Entry entry : entries.values())
		{
			out.append(ENTRY_SEPARATOR)
				.append(entry.npcId).append(FIELD_SEPARATOR)
				// Already sanitised by Entry's constructor, which is where it has to
				// happen so that a written name and a read-back one are one string.
				.append(entry.name).append(FIELD_SEPARATOR)
				.append(entry.hitpoints).append(FIELD_SEPARATOR)
				.append(entry.monsters).append(FIELD_SEPARATOR)
				.append(entry.weaponId).append(FIELD_SEPARATOR)
				.append(entry.ammoId).append(FIELD_SEPARATOR);

			boolean first = true;
			for (Map.Entry<Integer, Long> item : entry.consumed.entrySet())
			{
				if (!first)
				{
					out.append(ITEM_SEPARATOR);
				}
				first = false;
				out.append(item.getKey()).append(QUANTITY_SEPARATOR).append(item.getValue());
			}
		}
		return out.toString();
	}

	/**
	 * The sanitising, splitting and number parsing all moved to
	 * {@link ConfigText} when the pinned target became a second string kept in the
	 * same profile. The reasoning moved with them; see that class.
	 */
	private static List<String> split(String text, char separator)
	{
		return ConfigText.split(text, separator);
	}

	private static int parseInt(String text, int fallback)
	{
		return ConfigText.parseInt(text, fallback);
	}

	private static long parseLong(String text)
	{
		return ConfigText.parseLong(text);
	}

	@Override
	public String toString()
	{
		return String.format(Locale.ROOT, "AmmoArchive(%d monster(s))", entries.size());
	}
}
