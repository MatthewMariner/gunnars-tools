package com.matthewmariner.gunnarstools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The README quotes how many tests this suite has, in two places, and nothing checks that
 * either number is still true. {@code ../entourage} has already drifted three times on the
 * same claim; this repo's numbers happen to be correct right now, which is exactly the
 * moment a guard is cheap to add and easy to verify.
 *
 * <p><b>Why its own file.</b> This is a claim about the whole repo — the docs and the test
 * suite together — not about any one feature area, so it does not belong inside
 * {@code AmmoLedgerTest}, {@code MonsterLookupTest}, or any other class built around a
 * single subject. {@code RegionDataLoaderTest} carries the equivalent guard in
 * {@code ../lively-cities}, but there is no region-data-loader-shaped class here for it to
 * ride along with, so it gets a file of its own.
 *
 * <p><b>Ported from {@code ../lively-cities}'s {@code RegionDataLoaderTest}.</b> Counting
 * lines that trim to exactly {@code @Test} or start with {@code @Test(} is a source scan,
 * not a JUnit runner query, on purpose: asking Gradle's own test task how many tests it ran
 * would only prove this file agrees with the runner, not that the README agrees with either
 * one.
 */
public class TestCountTest
{
	@Test
	public void theTestCountTheDocumentsQuoteIsTheNumberOfTestsThereAre() throws IOException
	{
		int tests = 0;
		List<File> sources = new ArrayList<>();
		collectJavaSources(new File("src/test/java"), sources);
		assertTrue("no test sources found, so this method counted nothing at all",
			sources.size() > 20);

		for (File source : sources)
		{
			for (String line : new String(Files.readAllBytes(source.toPath()),
				StandardCharsets.UTF_8).split("\n"))
			{
				String trimmed = line.trim();
				if (trimmed.equals("@Test") || trimmed.startsWith("@Test("))
				{
					tests++;
				}
			}
		}

		String readme = new String(
			Files.readAllBytes(new File("README.md").toPath()), StandardCharsets.UTF_8);

		assertEquals("the README's badge", 1, count(readme,
			"[![Tests](https://img.shields.io/badge/tests-" + tests + "-brightgreen)]"));
		assertEquals("the README's build command comment",
			1, count(readme, "# runs the " + tests + "-test JUnit suite"));
	}

	private static void collectJavaSources(File dir, List<File> into)
	{
		File[] children = dir.listFiles();
		if (children == null)
		{
			return;
		}
		for (File child : children)
		{
			if (child.isDirectory())
			{
				collectJavaSources(child, into);
			}
			else if (child.getName().endsWith(".java"))
			{
				into.add(child);
			}
		}
	}

	private static int count(String haystack, String needle)
	{
		int found = 0;
		for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1))
		{
			found++;
		}
		return found;
	}
}
