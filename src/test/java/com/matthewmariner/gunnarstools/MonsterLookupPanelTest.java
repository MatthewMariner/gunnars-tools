package com.matthewmariner.gunnarstools;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.components.IconTextField;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The one part of the lookup that is Swing, exercised without a windowing system.
 *
 * <p>Every <em>decision</em> the panel draws is somewhere else and already under
 * test — {@link LookupPrompt} for the sentences, {@link MonsterIndex} for the
 * rows, {@link LookupSummary} for the answer. What is left is wiring, and wiring
 * is exactly what a person who "doesn't know how to test this" runs into: a mouse
 * listener attached to the wrong component is invisible to every test above and
 * fatal to the feature.
 *
 * <p>Headless throughout. {@code JPanel} and its children construct fine without a
 * display — it is {@code Frame} and {@code Window} that do not — so this runs on a
 * build machine, which is the only reason it is allowed to exist. Nothing here
 * paints.
 */
public class MonsterLookupPanelTest
{
	private static final int SPIDER = 3019;
	private static final int SPINDEL = 5265;
	private static final int VENENATIS = 6610;

	private final FakeConfig config = new FakeConfig();

	private final FakeNpcSource source = new FakeNpcSource()
		.with(SPIDER, "Spider", 2)
		.with(SPINDEL, "Spindel", 200)
		.with(VENENATIS, "Venenatis", 850);

	private GunnarsToolsPlugin plugin;

	private GunnarsToolsPlugin plugin()
	{
		plugin = new GunnarsToolsPlugin();
		plugin.config = config;
		plugin.configStore = config;
		plugin.clientThread = Runnable::run;
		plugin.sidePanel = new RecordingSidePanel();
		plugin.npcSource = source;
		plugin.itemNames = itemId -> "item " + itemId;
		plugin.overlayRegistry = new RecordingOverlays();
		plugin.tripPanelOverlay = new TripPanelOverlay(plugin, config, null);
		plugin.bankWithdrawalOverlay = new BankWithdrawalOverlay(plugin, config);
		plugin.startUp();
		return plugin;
	}

	private void readTheList()
	{
		for (int slice = 0; slice < 20 && !plugin.getCatalogue().isReady(); slice++)
		{
			plugin.scanMonsters();
		}
	}

	// --- walking the panel, rather than adding seams to it ---------------------

	private static void collect(Container root, List<Component> into)
	{
		for (Component child : root.getComponents())
		{
			into.add(child);
			if (child instanceof Container)
			{
				collect((Container) child, into);
			}
		}
	}

	private static List<String> textOf(Container root)
	{
		final List<Component> all = new ArrayList<>();
		collect(root, all);
		final List<String> out = new ArrayList<>();
		for (Component child : all)
		{
			if (child instanceof JLabel)
			{
				out.add(((JLabel) child).getText());
			}
		}
		return out;
	}

	/**
	 * Types into the search box, on the event dispatch thread.
	 *
	 * <p>Not a nicety: RuneLite's own {@code IconTextField.setText} asserts it is on
	 * that thread, and the test JVM runs with assertions on. Everything a real user
	 * does to this panel arrives on the EDT, so everything here does too.
	 */
	private static void type(Container root, String query)
	{
		onSwing(() ->
		{
			final List<Component> all = new ArrayList<>();
			collect(root, all);
			for (Component child : all)
			{
				if (child instanceof IconTextField)
				{
					((IconTextField) child).setText(query);
					return;
				}
			}
			throw new AssertionError("the panel has no search box");
		});
	}

	/** Presses every component that has a mouse listener and a label reading {@code label}. */
	private static int click(Container root, String label)
	{
		final int[] clicked = {0};
		onSwing(() ->
		{
			final List<Component> all = new ArrayList<>();
			collect(root, all);
			for (Component child : all)
			{
				if (!(child instanceof Container) || child.getMouseListeners().length == 0
					|| !textOf((Container) child).contains(label))
				{
					continue;
				}
				for (MouseListener listener : child.getMouseListeners())
				{
					listener.mousePressed(new MouseEvent(child, MouseEvent.MOUSE_PRESSED,
						0L, 0, 1, 1, 1, false));
				}
				clicked[0]++;
			}
		});
		return clicked[0];
	}

	/** Runs on the event dispatch thread and waits, so a failure inside it is this test's. */
	private static void onSwing(Runnable work)
	{
		try
		{
			SwingUtilities.invokeAndWait(work);
		}
		catch (java.lang.reflect.InvocationTargetException thrown)
		{
			throw thrown.getCause() instanceof AssertionError
				? (AssertionError) thrown.getCause()
				: new AssertionError(thrown.getCause());
		}
		catch (InterruptedException interrupted)
		{
			Thread.currentThread().interrupt();
			throw new AssertionError(interrupted);
		}
	}

	private static void settle()
	{
		onSwing(() ->
		{
		});
	}

	// --- the tests -------------------------------------------------------------

	@Test
	public void itBuildsWithNoGameAndNoDisplay()
	{
		MonsterLookupPanel panel = new MonsterLookupPanel(plugin());

		assertTrue(panel.getComponentCount() > 0);
	}

	@Test
	public void beforeTheListIsReadItSaysWhatItIsWaitingFor()
	{
		MonsterLookupPanel panel = new MonsterLookupPanel(plugin());

		assertTrue(textOf(panel).contains(MonsterCatalogue.State.WAITING.getHeadline()));
	}

	/** The complaint, answered: the panel is working, empty, and says what to do. */
	@Test
	public void onceTheListIsReadTheEmptyPanelNamesTheAction()
	{
		plugin();
		readTheList();
		MonsterLookupPanel panel = new MonsterLookupPanel(plugin);

		List<String> text = textOf(panel);
		assertTrue(text.contains(MonsterCatalogue.State.READY.getHeadline()));
		assertTrue(text.contains(MonsterCatalogue.State.READY.getDetail()));
	}

	@Test
	public void typingANameDrawsOneRowPerMonsterWithItsOwnSize()
	{
		plugin();
		readTheList();
		MonsterLookupPanel panel = new MonsterLookupPanel(plugin);

		type(panel, "spid");

		List<String> text = textOf(panel);
		assertTrue(text.contains("Spider"));
		assertTrue("the size is what tells an umbrella apart", text.contains("2 hp"));
		assertFalse("and a monster that does not match is not drawn", text.contains("Venenatis"));
	}

	@Test
	public void aNameNothingAnswersToSaysSoRatherThanGoingBlank()
	{
		plugin();
		readTheList();
		MonsterLookupPanel panel = new MonsterLookupPanel(plugin);

		type(panel, "venenatsi");

		assertTrue(textOf(panel).contains(LookupPrompt.NO_MATCH));
	}

	/**
	 * The click. Nothing above this can catch a listener attached to the wrong
	 * component, and a list you cannot pick from is the feature not existing.
	 */
	@Test
	public void clickingAMonsterPlansForIt()
	{
		plugin();
		readTheList();
		MonsterLookupPanel panel = new MonsterLookupPanel(plugin);
		type(panel, "venenatis");

		assertEquals("exactly one row to press", 1, click(panel, "Venenatis"));

		assertEquals("Venenatis", config.planFor());
		assertEquals(VENENATIS, plugin.getAdvice().getTarget().getNpcId());
	}

	@Test
	public void theAnswerItIsHandedIsTheAnswerItDraws()
	{
		plugin();
		MonsterLookupPanel panel = new MonsterLookupPanel(plugin);

		panel.accept(Collections.singletonList(
			new LookupSummary.Line("Venenatis", "850 hp", false)));
		settle();

		List<String> text = textOf(panel);
		assertTrue(text.contains("Venenatis"));
		assertTrue(text.contains("850 hp"));
	}

	@Test
	public void theWayOutOfAPinIsSomethingYouCanPress()
	{
		plugin();
		readTheList();
		MonsterLookupPanel panel = new MonsterLookupPanel(plugin);
		type(panel, "spindel");
		click(panel, "Spindel");
		assertEquals("Spindel", config.planFor());

		panel.accept(LookupSummary.of(plugin.getAdvice(), itemId -> "item " + itemId));
		settle();

		assertEquals(1, click(panel, PlanTarget.Source.PINNED.getLabel()));
		assertEquals("", config.planFor());
	}
}
