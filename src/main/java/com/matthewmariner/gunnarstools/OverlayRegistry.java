package com.matthewmariner.gunnarstools;

import net.runelite.client.ui.overlay.Overlay;

/**
 * Where an overlay is registered and unregistered.
 *
 * <p><b>Why an interface and not {@code OverlayManager} itself.</b> Its only
 * constructor is package-private — verified with {@code javap} against the
 * 1.12.38 client jar this project pins, which lists no public constructor at all
 * — so it can be neither constructed nor subclassed from here, and there is no
 * mocking framework on this classpath.
 *
 * <p>That matters because an overlay left in the manager after {@code shutDown()}
 * keeps drawing, and it would be drawing a trip plan built from a ledger that has
 * just been emptied underneath it. Two methods behind an interface are what make
 * "startUp adds them and shutDown removes the same ones" an assertion instead of
 * a reading of the source. The same pattern, for the same reason, as
 * lively-cities.
 */
interface OverlayRegistry
{
	void add(Overlay overlay);

	void remove(Overlay overlay);
}
