package com.matthewmariner.gunnarstools;

import net.runelite.client.ui.overlay.Overlay;

/**
 * Where an overlay is registered and unregistered.
 *
 * <p><b>Why an interface and not {@code OverlayManager} itself.</b> Its only
 * constructor is <i>private</i>, and takes seven collaborators — verified with
 * {@code javap -p} against the 1.12.38 client jar this project pins — so it can
 * be neither constructed nor subclassed from here, and there is no mocking
 * framework on this classpath.
 *
 * <p>This said "package-private" and cited plain {@code javap} until a review
 * pointed out that plain {@code javap} cannot tell the two apart: it prints
 * public and protected members only, so a private constructor and a
 * package-private one both show up as nothing at all. The observation the old
 * wording actually rested on was "no public constructor is listed", which rules
 * out neither. {@code -p} prints it and settles it. {@code ../lively-cities}
 * had this right on the same class one repository over, which is where the
 * pattern came from in the first place.
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
