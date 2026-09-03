package com.matthewmariner.gunnarstools;

import java.util.ArrayList;
import java.util.List;
import net.runelite.client.ui.overlay.Overlay;
import static org.junit.Assert.assertNotNull;

/**
 * An {@link OverlayRegistry} that remembers what was registered.
 *
 * <p>Registered rather than counted, so "shutDown removes the same overlays
 * startUp added" is answerable rather than "the same number of calls happened".
 * An overlay left in the manager draws forever, over a ledger that shutdown has
 * just emptied.
 */
final class RecordingOverlays implements OverlayRegistry
{
	private final List<Overlay> added = new ArrayList<>();
	private final List<Overlay> removed = new ArrayList<>();

	@Override
	public void add(Overlay overlay)
	{
		assertNotNull("the plugin must never register a null overlay", overlay);
		added.add(overlay);
	}

	@Override
	public void remove(Overlay overlay)
	{
		assertNotNull("the plugin must never unregister a null overlay", overlay);
		removed.add(overlay);
	}

	/** @return what is registered now: everything added and not removed */
	List<Overlay> live()
	{
		final List<Overlay> out = new ArrayList<>(added);
		out.removeAll(removed);
		return out;
	}
}
