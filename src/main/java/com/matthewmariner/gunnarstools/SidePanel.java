package com.matthewmariner.gunnarstools;

import java.util.List;

/**
 * The monster lookup's place in RuneLite's sidebar: put it there, take it away,
 * tell it what to draw.
 *
 * <p>Behind an interface for the same reason as {@link OverlayRegistry} one file
 * over — a {@code ClientToolbar} cannot be constructed from a test and there is no
 * mocking framework on this classpath — and for one more that matters as much:
 * the thing on the other side of it is Swing. A lifecycle test that had to build a
 * real {@code PluginPanel} would be a lifecycle test that needs a windowing
 * system, and the promise being kept here — {@code shutDown()} leaves nothing
 * registered — is exactly the promise that must hold on a machine with no display.
 *
 * <h2>Why the answer is pushed rather than pulled</h2>
 *
 * <p>Two reasons, and the second is not a preference. The first is that Swing does
 * not repaint on somebody else's news: the plan is rebuilt on the client thread
 * when a kill lands, a setting changes or the target moves, and a panel that only
 * redrew when it was clicked would show the previous monster's numbers under the
 * current monster's name.
 *
 * <p>The second is that <b>the summary cannot be built on the Swing thread at
 * all</b>. Turning an item id into "Rune arrow" goes through
 * {@code Client.getItemDefinition}, which throws {@code IllegalStateException} off
 * the client thread — an assertion in a development client and a real exception in
 * a shipped one, verified in the 1.12.38 injected client's bytecode. So the lines
 * are composed where the client can be read and handed over finished, and the
 * panel's only job is to draw them.
 */
interface SidePanel
{
	/** Adds the lookup to the sidebar. Idempotent — RuneLite's toolbar tolerates a repeat. */
	void show();

	/** Takes it away again. What {@code shutDown()} and switching the setting off call. */
	void hide();

	/**
	 * Something the panel draws has moved: redraw it.
	 *
	 * <p>Called from the client thread, so an implementation that touches Swing has
	 * to hop to the event dispatch thread itself rather than making every caller
	 * remember to.
	 *
	 * @param answer the finished summary from {@link LookupSummary#of}, already
	 *               resolved against the item cache
	 */
	void refresh(List<LookupSummary.Line> answer);
}
