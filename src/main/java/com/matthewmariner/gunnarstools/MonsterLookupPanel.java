package com.matthewmariner.gunnarstools;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;

/**
 * The sidebar: type a monster's name, pick it, see what the trip costs.
 *
 * <p>The surface the plugin was missing. Everything before it needed the monster
 * to be present — shift-right-click it in the world and choose "Plan trip" —
 * which meant the plugin could only be driven while standing next to the thing
 * the plan was about. The question it answers is asked at a bank.
 *
 * <p>A sidebar button rather than a chatbox prompt or a second settings field, and
 * the reason is the complaint rather than the taste. The owner's words were "I
 * honestly don't know how to test this… right now I'm lost", and a keyboard
 * shortcut nobody has been told about is that complaint with a hotkey. A button
 * that appears in the toolbar the moment the plugin is enabled is the only
 * affordance here that announces itself, and it is also the only one with room to
 * show <em>several</em> monsters at once — which is the whole point, because
 * nineteen of Krystilia's thirty-six tasks are names more than one monster answers
 * to.
 *
 * <h2>It decides nothing</h2>
 *
 * <p>Every sentence on it comes out of {@link LookupPrompt} or
 * {@link MonsterCatalogue.State}, every row out of {@link MonsterIndex}, and the
 * summary at the bottom out of {@link LookupSummary} — all of them static,
 * offline and under test. What is left here is layout and mouse handling, which is
 * the part no test can reach without a windowing system, and keeping it to that is
 * deliberate.
 *
 * <p>Two threads meet here and neither is allowed to wander. Reading the catalogue
 * and searching the index happen on Swing's thread, which is safe because a
 * published {@link MonsterIndex} is sealed and immutable. Choosing a monster hands
 * straight back to the client thread through
 * {@link GunnarsToolsPlugin#planFor}, because acting on the choice walks the
 * ledger. And the summary arrives already composed, because resolving an item name
 * needs the client — see {@link SidePanel}.
 */
class MonsterLookupPanel extends PluginPanel
{
	/**
	 * The most monsters listed at once.
	 *
	 * <p>Twenty is more than any real umbrella name produces and few enough to read
	 * without scrolling past the summary. Anything cut is counted rather than
	 * dropped silently — see {@link LookupPrompt#truncation}.
	 */
	private static final int MAX_ROWS = 20;

	private final GunnarsToolsPlugin plugin;

	private final IconTextField search = new IconTextField();
	private final JPanel results = new JPanel();
	private final JPanel summary = new JPanel();

	/** The last summary the client thread handed over. Only ever read on Swing's. */
	private volatile List<LookupSummary.Line> answer = Collections.emptyList();

	@Inject
	MonsterLookupPanel(GunnarsToolsPlugin plugin)
	{
		super(true);
		this.plugin = plugin;

		setBorder(new EmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout(0, 8));

		search.setIcon(IconTextField.Icon.SEARCH);
		search.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 30));
		search.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		search.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		search.addClearListener(this::redraw);
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent event)
			{
				redraw();
			}

			@Override
			public void removeUpdate(DocumentEvent event)
			{
				redraw();
			}

			@Override
			public void changedUpdate(DocumentEvent event)
			{
				redraw();
			}
		});

		results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));
		results.setBackground(ColorScheme.DARK_GRAY_COLOR);

		summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
		summary.setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(search, BorderLayout.NORTH);
		add(results, BorderLayout.CENTER);
		add(summary, BorderLayout.SOUTH);

		redraw();
	}

	/**
	 * Opened from the toolbar: carry over whatever the settings field could not
	 * resolve, redraw, and put the caret in the search box.
	 *
	 * <p>The focus is not a flourish. The panel opens because somebody wants to
	 * type a name into it, and a search box that has to be clicked first is one more
	 * step between "I'm lost" and an answer.
	 *
	 * <p><b>The carry-over is this panel's half of "one front door".</b> The owner
	 * typed a monster's name into the settings field, because that is where you go
	 * when you open a plugin's settings, and a text field there cannot draw a list or
	 * offer a choice. What it can do is hand the name over: a player who is told to
	 * pick one in the side panel arrives with that name already searched, rather than
	 * having to retype from memory the word already established as the one they get
	 * wrong. Only into an empty box — whatever is being typed here now outranks
	 * anything a setting had to say.
	 */
	@Override
	public void onActivate()
	{
		final String carried = plugin.getUnresolvedName();
		if (carried != null && !carried.isEmpty() && search.getText().trim().isEmpty())
		{
			// Fires the document listener, which redraws; the call below is what makes
			// that an implementation detail rather than something to rely on.
			search.setText(carried);
		}

		redraw();
		search.requestFocusInWindow();
	}

	/** Called from the client thread; see {@link SidePanel#refresh}. */
	void accept(List<LookupSummary.Line> lines)
	{
		answer = lines;
		SwingUtilities.invokeLater(this::redraw);
	}

	/**
	 * Rebuilds both halves from scratch.
	 *
	 * <p>Wholesale rather than incrementally, because a search result list is a
	 * handful of rows and reconciling two lists is where a stale row comes from.
	 */
	private void redraw()
	{
		final MonsterCatalogue catalogue = plugin.getCatalogue();
		final MonsterIndex index = catalogue.getIndex();
		final String query = search.getText();

		final MonsterIndex.Results found = index == null
			? new MonsterIndex.Results(Collections.emptyList(), 0)
			: index.search(query, MAX_ROWS);

		results.removeAll();

		for (LookupSummary.Line line : LookupPrompt.of(catalogue.getState(), catalogue.getPercent(),
			query, found.getTotal()))
		{
			results.add(row(line));
		}

		final LookupSummary.Line aliased = LookupPrompt.aliasNotice(found);
		if (aliased != null)
		{
			results.add(row(aliased));
		}

		final LookupSummary.Line truncated = LookupPrompt.truncation(found);
		if (truncated != null)
		{
			results.add(row(truncated));
		}

		for (MonsterIndex.Match match : found.getMatches())
		{
			results.add(monsterRow(match));
		}

		summary.removeAll();
		for (LookupSummary.Line line : answer)
		{
			summary.add(row(line));
		}

		revalidate();
		repaint();
	}

	/**
	 * A two-column line: label on the left, value on the right, and a mouse
	 * listener when {@link LookupSummary.Line#getAction()} says the row does
	 * something.
	 */
	private JPanel row(LookupSummary.Line line)
	{
		final JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(2, 0, 2, 0));

		// Matched by name rather than "not NONE", so an action added later is inert
		// here until somebody wires it up, rather than silently unpinning.
		final boolean clickable = line.getAction() == LookupSummary.Action.CLEAR_PIN;
		final Color colour = line.isCaveat() ? ColorScheme.PROGRESS_INPROGRESS_COLOR
			: clickable ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR;

		final JLabel left = new JLabel(line.getLeft());
		left.setFont(FontManager.getRunescapeSmallFont());
		left.setForeground(line.isCaveat()
			? ColorScheme.PROGRESS_INPROGRESS_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		panel.add(left, BorderLayout.WEST);

		if (!line.getRight().isEmpty())
		{
			final JLabel right = new JLabel(line.getRight());
			right.setFont(FontManager.getRunescapeSmallFont());
			right.setForeground(colour);
			panel.add(right, BorderLayout.EAST);
		}

		if (clickable)
		{
			panel.setCursor(new Cursor(Cursor.HAND_CURSOR));
			panel.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent event)
				{
					plugin.clearPin();
				}
			});
		}

		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
		return panel;
	}

	/**
	 * One monster, clickable.
	 *
	 * <p>The hitpoints sit on the right of every row because they are what tells
	 * the umbrella apart: "Spider 2 hp" over "Venenatis 850 hp" is the whole
	 * ambiguity, on screen, in the two numbers that differ by a factor of 425. A
	 * monster whose hitpoints did not resolve says so rather than showing a number
	 * it does not have.
	 *
	 * <p>The variant count is on the row for the same reason. Several NPC ids
	 * sharing one name and one size are folded into one row so the list stays
	 * readable, and folding without saying so would be hiding something.
	 */
	private JPanel monsterRow(MonsterIndex.Match match)
	{
		final JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			new EmptyBorder(2, 0, 2, 0), new EmptyBorder(4, 6, 4, 6)));
		panel.setCursor(new Cursor(Cursor.HAND_CURSOR));

		final JLabel name = new JLabel(match.getName());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(ColorScheme.TEXT_COLOR);
		panel.add(name, BorderLayout.WEST);

		final JLabel size = new JLabel(match.hasHitpoints()
			? match.getHitpoints() + " hp"
			: "hp unknown");
		size.setFont(FontManager.getRunescapeSmallFont());
		size.setForeground(match.hasHitpoints()
			? ColorScheme.LIGHT_GRAY_COLOR : ColorScheme.PROGRESS_INPROGRESS_COLOR);
		panel.add(size, BorderLayout.EAST);

		if (match.getVariants() > 1)
		{
			final JLabel variants = new JLabel(match.getVariants() + " share this name and size");
			variants.setFont(FontManager.getRunescapeSmallFont());
			variants.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			panel.add(variants, BorderLayout.SOUTH);
		}

		panel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent event)
			{
				plugin.planFor(match);
			}

			@Override
			public void mouseEntered(MouseEvent event)
			{
				paint(panel, ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent event)
			{
				paint(panel, ColorScheme.DARKER_GRAY_COLOR);
			}
		});

		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
		return panel;
	}

	/** The row and its labels, so the hover covers the whole strip rather than its margins. */
	private static void paint(JPanel row, Color colour)
	{
		row.setBackground(colour);
		for (Component child : row.getComponents())
		{
			child.setBackground(colour);
		}
	}
}
