package com.matthewmariner.gunnarstools;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * The dev client's entry point — {@code ./gradlew run} launches this
 * {@code main} on {@code sourceSets.test.runtimeClasspath}, per
 * {@code build.gradle}'s {@code pluginMainClass}.
 *
 * <p>Despite the name, this is not a JUnit test: it has no {@code @Test}
 * method and Gradle's test-class detection does not pick it up as one (it
 * looks for JUnit markers, not for the filename pattern). The name mirrors
 * lively-cities' {@code LivelyCitiesPluginTest}, which mirrors the official
 * {@code runelite/example-plugin} template this convention comes from.
 */
public class GunnarsToolsPluginTest
{
	// loadBuiltin's varargs parameter is Class<? extends Plugin>..., so passing a
	// single Class literal still triggers "generic array created for a varargs
	// parameter" at the call site — the same unavoidable unchecked warning
	// lively-cities suppresses in its own builtinPlugins() once it has more than
	// one plugin to pass. Suppressed here rather than left as build noise for a
	// warning nothing here can actually fix.
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GunnarsToolsPlugin.class);
		RuneLite.main(args);
	}
}
