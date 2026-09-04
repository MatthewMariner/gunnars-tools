package com.matthewmariner.gunnarstools;

/**
 * Runs a piece of work on the client thread, whichever thread asks.
 *
 * <p><b>Why this exists at all.</b> {@code ConfigManager.setConfiguration} posts
 * {@code ConfigChanged} on the event bus synchronously, on whichever thread
 * called it, and for the settings panel that thread is the Swing EDT. Everything
 * else in this plugin — game ticks, the two overlays — is the client thread. So
 * {@link GunnarsToolsPlugin#onConfigChanged} arrives from a different thread than
 * every other handler on that class.
 *
 * <p>That was tolerable when a config change only read one field and rebuilt one
 * projection off one record, and it stopped being tolerable when it started
 * choosing a target: resolving a typed monster name walks {@link AmmoLedger}'s
 * whole map, and so does picking a monster to scale a rate from. The client
 * thread inserts into that map on the tick a new monster is first fought.
 * Iterating a {@code LinkedHashMap} while another thread structurally modifies it
 * is a {@code ConcurrentModificationException} on a good day and a silently wrong
 * read on a bad one — and the good day is the one where a settings slider takes
 * the plugin down.
 *
 * <p>So the whole handler is marshalled instead of being made thread-safe piece
 * by piece. {@code ClientThread.invoke} runs the work immediately when the caller
 * is already the client thread and defers it to the next tick otherwise, which is
 * exactly the semantics wanted: instant in game, one tick late from the settings
 * panel, and never concurrent.
 *
 * <p>Behind an interface for the same reason as {@link OverlayRegistry} and
 * {@link ConfigStore}: a {@code ClientThread} cannot be constructed without a
 * client, and there is no mocking framework on this classpath. A test supplies
 * {@code Runnable::run} and the work happens inline, which is what the real one
 * does on the thread that matters anyway.
 */
interface ClientThreadRunner
{
	void run(Runnable task);
}
