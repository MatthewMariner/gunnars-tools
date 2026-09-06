package com.matthewmariner.gunnarstools;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import net.runelite.client.plugins.PluginDescriptor;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The two places this plugin describes itself, held to saying the same thing.
 *
 * <p><b>There are two copies of this metadata and they are read by different
 * readers.</b> {@code runelite-plugin.properties} is what the Plugin Hub's packager
 * reads to build the listing on the website; the {@link PluginDescriptor} annotation is
 * what the client reads to draw the row in the in-client plugin panel. Nothing in either
 * project compares them, so they are free to drift, and when they drift the plugin
 * describes itself two different ways in two public places at once.
 *
 * <p><b>This is not hypothetical.</b> The properties file said "Estimates how much
 * ammunition a Wilderness Slayer trip needs by measuring your <i>actual</i> consumption
 * per kill" while the annotation said "Estimates ammunition needed for a Wilderness
 * Slayer trip by measuring your <i>own</i> consumption per kill" — different sentences,
 * neither wrong, discovered on the submission checklist rather than by anything that
 * runs. The submission runbook lists this drift as having already happened in two
 * repositories in this family. A checklist a human walks is not a guard; this is.
 *
 * <p><b>Read with {@link Properties}, deliberately</b>, rather than by splitting lines on
 * {@code '='}. That is how the hub's own packager reads the file, so a value that this
 * test agrees with is a value the packager will agree with too — including the escaping
 * rules, which a hand-rolled split gets wrong in exactly the cases nobody thinks to try.
 *
 * <p>File I/O in a test source set is what {@code TestCountTest} already does here and
 * what the hub's rules permit; the rule is about {@code src/main}.
 */
public class ListingMetadataTest
{
	/**
	 * The annotation the client reads. Fetched through the class rather than retyped, so
	 * this test cannot agree with a copy of the string that has itself gone stale.
	 */
	private static PluginDescriptor descriptor()
	{
		PluginDescriptor descriptor =
			GunnarsToolsPlugin.class.getAnnotation(PluginDescriptor.class);
		assertNotNull("GunnarsToolsPlugin has lost its @PluginDescriptor", descriptor);
		return descriptor;
	}

	private static Properties listing() throws IOException
	{
		File file = new File("runelite-plugin.properties");
		assertTrue("runelite-plugin.properties is missing — the hub cannot package "
			+ "a plugin without it", file.isFile());

		Properties properties = new Properties();
		try (InputStream in = new FileInputStream(file))
		{
			properties.load(in);
		}
		return properties;
	}

	@Test
	public void theListingDescriptionIsTheOneTheClientShows() throws IOException
	{
		assertEquals("the hub listing and the in-client panel must say the same thing",
			descriptor().description(), listing().getProperty("description"));
	}

	@Test
	public void theListingNameIsTheOneTheClientShows() throws IOException
	{
		assertEquals(descriptor().name(), listing().getProperty("displayName"));
	}

	@Test
	public void theListingTagsAreTheOnesTheClientSearches() throws IOException
	{
		// Joined the way the properties file spells a list. A tag present in one place
		// and not the other is a search that finds the plugin in the client and not on
		// the website, or the reverse — see the deliberate absence of "ether" in both.
		assertEquals(String.join(",", descriptor().tags()), listing().getProperty("tags"));
	}

	@Test
	public void theListingNamesTheClassThatIsActuallyThePlugin() throws IOException
	{
		assertEquals(GunnarsToolsPlugin.class.getName(), listing().getProperty("plugins"));
	}

	@Test
	public void noFieldTheHubRefusesToPackageIsLeftAtItsTemplateValue() throws IOException
	{
		Properties listing = listing();

		// The example-plugin template ships these three with placeholder text, and a
		// placeholder is a packager failure rather than a style problem. `version` is
		// the one field that is correctly left empty — the hub falls back to the commit.
		for (String field : new String[]{"displayName", "author", "description"})
		{
			String value = listing.getProperty(field);
			assertNotNull(field + " is missing", value);
			assertFalse(field + " is empty", value.trim().isEmpty());
			assertFalse(field + " still holds the template's placeholder",
				value.toLowerCase().contains("example")
					|| value.toLowerCase().contains("your name")
					|| value.toLowerCase().contains("todo"));
		}

		assertEquals("build must be `standard` — the hub substitutes its own build.gradle",
			"standard", listing.getProperty("build"));
	}
}
