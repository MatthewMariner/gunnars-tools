package com.matthewmariner.gunnarstools;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Reading and writing the small strings this plugin keeps in the user's RuneLite
 * profile, on the assumption that whatever comes back is hostile.
 *
 * <p>Extracted from {@link AmmoArchive}, which had all of it privately, when the
 * pinned target became a second thing stored the same way. Two strings in the
 * same profile, written by the same plugin and exposed to the same hand-editing,
 * should not have two different ideas about what a malformed field means.
 *
 * <p><b>Nothing here throws.</b> The values are strings in a user-editable
 * profile that may have been written by a different version of this plugin,
 * hand-edited, truncated, or synchronised from another machine mid-write. A
 * {@code startUp()} that dies on one of them takes the whole plugin with it, and
 * a plugin that will not start is a worse outcome than a setting that resets.
 */
final class ConfigText
{
	/** Between entries in a list. */
	static final char ENTRY_SEPARATOR = ';';

	/** Between fields of one entry. */
	static final char FIELD_SEPARATOR = ',';

	/** Between item/quantity pairs. */
	static final char ITEM_SEPARATOR = '|';

	/** Between an item and its quantity. */
	static final char QUANTITY_SEPARATOR = ':';

	/** Everything the separators would make ambiguous if it appeared in a name. */
	private static final String RESERVED = ";,|:";

	/** Longest monster name kept. Names are for the panel, not for identity. */
	static final int MAX_NAME_LENGTH = 40;

	private ConfigText()
	{
	}

	/**
	 * Makes a monster's name safe to sit between separators.
	 *
	 * <p>No OSRS monster is called {@code Sea, snake|3}, and that is precisely why
	 * this is here rather than left to chance: the assumption is unverifiable from
	 * outside the game, it costs one pass over a short string to stop depending on,
	 * and the failure it prevents is a name that eats the next field's meaning and
	 * files a monster's ammunition under its hitpoints.
	 *
	 * @return a non-empty string with no separator in it, at most
	 * {@link #MAX_NAME_LENGTH} characters. {@code "?"} for null, blank, or a name
	 * that was nothing but separators.
	 */
	static String sanitise(@Nullable String name)
	{
		if (name == null)
		{
			return "?";
		}

		final StringBuilder out = new StringBuilder(Math.min(name.length(), MAX_NAME_LENGTH));
		for (int i = 0; i < name.length() && out.length() < MAX_NAME_LENGTH; i++)
		{
			final char c = name.charAt(i);
			out.append(RESERVED.indexOf(c) >= 0 ? ' ' : c);
		}

		final String trimmed = out.toString().trim();
		return trimmed.isEmpty() ? "?" : trimmed;
	}

	/**
	 * {@code String.split} with no regex and no surprises about trailing empties.
	 *
	 * <p>The separators here are single characters, and compiling a pattern for
	 * each of forty entries to avoid writing eight lines is the wrong trade. The
	 * trailing-empty behaviour is the load-bearing half: {@code "1;"} has to come
	 * back as two parts, so a truncated write loses one entry rather than being
	 * read as a well-formed archive with a different last field.
	 */
	static List<String> split(String text, char separator)
	{
		final List<String> out = new ArrayList<>();
		int start = 0;
		for (int i = 0; i < text.length(); i++)
		{
			if (text.charAt(i) == separator)
			{
				out.add(text.substring(start, i));
				start = i + 1;
			}
		}
		out.add(text.substring(start));
		return out;
	}

	static int parseInt(String text, int fallback)
	{
		try
		{
			return Integer.parseInt(text.trim());
		}
		catch (NumberFormatException malformed)
		{
			return fallback;
		}
	}

	static long parseLong(String text)
	{
		try
		{
			return Long.parseLong(text.trim());
		}
		catch (NumberFormatException malformed)
		{
			return 0L;
		}
	}
}
