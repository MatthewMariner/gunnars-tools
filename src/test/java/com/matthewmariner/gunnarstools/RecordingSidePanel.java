package com.matthewmariner.gunnarstools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertNotNull;

/**
 * A {@link SidePanel} that remembers whether it is in the sidebar and what it was
 * last told to draw.
 *
 * <p>The same shape as {@link RecordingOverlays} and for the same reason: a
 * lifecycle promise is only a promise if a test can ask whether it was kept, and
 * the real implementation on the other side of this interface is a Swing panel and
 * a {@code ClientToolbar}, neither of which exists on a build machine with no
 * display.
 *
 * <p>{@link #shown()} counts adds and removes rather than latching a boolean,
 * because "shutDown removes it" and "shutDown removes it twice" are different
 * facts and only one of them is what {@code ClientToolbar} is being asked for.
 */
final class RecordingSidePanel implements SidePanel
{
	private int shows;
	private int hides;

	private final List<List<LookupSummary.Line>> refreshes = new ArrayList<>();

	@Override
	public void show()
	{
		shows++;
	}

	@Override
	public void hide()
	{
		hides++;
	}

	@Override
	public void refresh(List<LookupSummary.Line> answer)
	{
		assertNotNull("the plugin must never hand the panel a null answer", answer);
		refreshes.add(answer);
	}

	/** True when it has been added more often than it has been taken away. */
	boolean shown()
	{
		return shows > hides;
	}

	int showCount()
	{
		return shows;
	}

	int hideCount()
	{
		return hides;
	}

	/** Every answer pushed, oldest first. */
	List<List<LookupSummary.Line>> refreshes()
	{
		return Collections.unmodifiableList(refreshes);
	}

	/** The answer the panel would be drawing now, or empty if it was never told one. */
	List<LookupSummary.Line> latest()
	{
		return refreshes.isEmpty()
			? Collections.emptyList()
			: refreshes.get(refreshes.size() - 1);
	}
}
