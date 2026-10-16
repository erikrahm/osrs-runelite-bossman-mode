/*
 * Bossman Mode: side panel: the ladder (current boss, log progress, KC, backlog) and per-boss unlocks.
 * BSD-2-Clause, see LICENSE.
 */
package com.bossman;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

public class BossmanPanel extends PluginPanel
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BossmanPanel.class);
	private static final Color OK = new Color(90, 200, 120);
	private static final Color WARN = new Color(255, 200, 80);
	private static final Color BAD = new Color(220, 90, 90);
	private static final Color MUTED = new Color(160, 160, 160);
	private static final Color TRACK = new Color(38, 38, 38);
	private static final int MAX_LOCKED_SHOWN = 40;

	private final BossmanModePlugin plugin;
	private final LadderRules rules;
	private final ItemManager itemManager;

	private final JPanel body = new ScrollableBody();
	private final SkillIconManager skillIconManager;
	private final java.util.Map<String, java.awt.image.BufferedImage> icons = new java.util.HashMap<>();
	/** null = follow the current rung; otherwise the rung being browsed */
	private Integer browsing;
	/** The current boss at the last refresh; once it's complete we stay on it instead of auto-paging. */
	private BossmanData.Rung lastCurrent;
	/** the ledger shown at the last refresh: a new one (login, account switch) starts over at the current boss */
	private Ledger lastLedger;
	/** the rung shown at the last refresh; showing another one scrolls back to the top */
	private int lastShownIdx = -1;
	/** a refresh was asked for while the panel was hidden (or a menu was open); done when it can be */
	private boolean dirty;
	/** the boss picker's list is open */
	private boolean pickerOpen;
	/** retries a refresh that a menu or the picker held up */
	private final javax.swing.Timer retry = new javax.swing.Timer(500, e -> refreshIfDirty());
	/** rungs complete as of the last refresh, for the picker's cell colours */
	private java.util.Set<BossmanData.Rung> completeRungs = java.util.Collections.emptySet();
	private javax.swing.JScrollPane scrollPane;
	/** item icons, held here so a rebuild doesn't refetch them (the shared item-image cache is small) */
	private final java.util.Map<Integer, AsyncBufferedImage> images = new java.util.HashMap<>();

	@Inject
	public BossmanPanel(BossmanModePlugin plugin, ItemManager itemManager, SkillIconManager skillIconManager)
	{
		super(false);
		this.plugin = plugin;
		this.rules = plugin.getRules();
		this.itemManager = itemManager;
		this.skillIconManager = skillIconManager;
		for (String n : new String[]{"quest", "diary", "combat", "wilderness"})
		{
			try
			{
				java.awt.image.BufferedImage img = net.runelite.client.util.ImageUtil.loadImageResource(BossmanPanel.class, "/bossman/" + n + "_icon.png");
				icons.put(n, net.runelite.client.util.ImageUtil.resizeImage(img, 14, 14));
			}
			catch (Exception ignored)
			{
			}
		}
		retry.setRepeats(false);
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		add(wrapScroll(body), BorderLayout.CENTER);
		refresh();
	}

	/** A vertical stack that always fits the panel's width, so nothing is clipped on the right. */
	private static class ScrollableBody extends JPanel implements javax.swing.Scrollable
	{
		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return visibleRect.height;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	private JPanel wrapScroll(JPanel inner)
	{
		JPanel outer = new JPanel(new BorderLayout());
		outer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(inner);
		scrollPane = scroll;
		scroll.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		outer.add(scroll, BorderLayout.CENTER);
		return outer;
	}

	/** Rebuild the panel. Swing thread. Skipped (and done on next show) while the panel is hidden or a menu is open. */
	public void refresh()
	{
		if (!isShowing() || pickerOpen || javax.swing.MenuSelectionManager.defaultManager().getSelectedPath().length > 0)
		{
			dirty = true;
			if (isShowing())
			{
				// a menu is open: rebuilding now would close it under the player's cursor; try again shortly
				retry.restart();
			}
			return;
		}
		dirty = false;
		try
		{
			refreshInner();
		}
		catch (Exception e)
		{
			log.warn("panel refresh failed (browsing={})", browsing, e);
			body.removeAll();
			body.add(label("Panel error - see the RuneLite log.", BAD));
			revalidate();
			repaint();
		}
	}

	private void refreshIfDirty()
	{
		if (dirty)
		{
			refresh();
		}
	}

	@Override
	public void onActivate()
	{
		refreshIfDirty();
	}

	private void refreshInner()
	{
		body.removeAll();
		Ledger ledger = plugin.getLedger();
		if (ledger != lastLedger)
		{
			// login or account switch: follow the current boss, and don't read the jump from the empty
			// placeholder ledger (rung 1) to the real one as "a boss was just completed"
			lastLedger = ledger;
			lastCurrent = null;
			browsing = null;
		}
		if (ledger.getFile() == null && ledger.getSetAside() == null)
		{
			body.add(wrapped("Log in to load your Bossman ledger.", MUTED, 160));
			revalidate();
			repaint();
			return;
		}
		BossmanData.Rung current = rules.currentRung();
		List<BossmanData.Rung> rungs = plugin.getData().browsableRungs();
		if (rungs.isEmpty())
		{
			body.add(label("No ladder data.", MUTED));
			revalidate();
			repaint();
			return;
		}
		// the floating rungs sit after the ladder's last boss, which stays "current" once the ladder is done
		int currentIdx = current == null ? plugin.getData().orderedRungs().size() - 1 : rungs.indexOf(current);
		java.util.Set<BossmanData.Rung> complete = new java.util.HashSet<>();
		for (BossmanData.Rung rr : rungs)
		{
			if (rules.isRungComplete(rr))
			{
				complete.add(rr);
			}
		}
		completeRungs = complete;
		if (browsing == null && lastCurrent != null && lastCurrent != current && complete.contains(lastCurrent))
		{
			// the boss just got completed: let the name go green and the bar fill; "Current" jumps to the next one
			browsing = rungs.indexOf(lastCurrent);
		}
		lastCurrent = current;
		int idx = browsing == null ? currentIdx : Math.max(0, Math.min(browsing, rungs.size() - 1));
		BossmanData.Rung r = rungs.get(idx);

		addBlock(header(idx, rungs.size(), current, r));
		body.add(Box.createVerticalStrut(6));
		addBlock(rungCard(r, r == current, rungs, idx, currentIdx));
		body.add(Box.createVerticalStrut(6));
		addBlock(logSection(r));
		body.add(Box.createVerticalStrut(6));
		JPanel memes = memeSection(r);
		if (memes != null)
		{
			addBlock(memes);
			body.add(Box.createVerticalStrut(6));
		}
		addBlock(backlogSection(r));
		body.add(Box.createVerticalStrut(10));
		addBlock(unlocksSection(r));
		body.add(Box.createVerticalGlue());
		revalidate();
		repaint();
		if (idx != lastShownIdx && scrollPane != null)
		{
			lastShownIdx = idx;
			javax.swing.SwingUtilities.invokeLater(() -> scrollPane.getVerticalScrollBar().setValue(0));
		}
	}

	/** Left-align and stop BoxLayout from stretching a block vertically. */
	private void addBlock(JPanel block)
	{
		block.setAlignmentX(LEFT_ALIGNMENT);
		Dimension pref = block.getPreferredSize();
		block.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
		body.add(block);
	}

	private JPanel header(int idx, int total, BossmanData.Rung current, BossmanData.Rung shown)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel title = new JLabel("Bossman");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		p.add(title, BorderLayout.WEST);

		JPanel nav = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
		nav.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JButton prev = arrowButton("<");
		prev.setToolTipText("Previous boss");
		prev.addActionListener(e -> { browsing = idx - 1; refresh(); });
		prev.setEnabled(idx > 0);
		JButton now = smallButton("Current");
		now.setToolTipText("Jump to the boss you're on");
		now.addActionListener(e -> { browsing = null; refresh(); });
		now.setEnabled(shown != current);
		JButton next = arrowButton(">");
		next.setToolTipText("Next boss");
		next.addActionListener(e -> { browsing = idx + 1; refresh(); });
		next.setEnabled(idx < total - 1);
		// the arrows use a different font than "Current"; give all three the same height and both arrows the same width
		int h = Math.max(prev.getPreferredSize().height, now.getPreferredSize().height);
		int aw = Math.max(prev.getPreferredSize().width, next.getPreferredSize().width);
		prev.setPreferredSize(new Dimension(aw, h));
		next.setPreferredSize(new Dimension(aw, h));
		now.setPreferredSize(new Dimension(now.getPreferredSize().width, h));
		nav.add(prev);
		nav.add(now);
		nav.add(next);
		p.add(nav, BorderLayout.EAST);
		return p;
	}

	/** Width available for a boss name in the picker's list (panel minus card padding and the list's scrollbar). */
	private static final int TITLE_WIDTH = 165;

	private static String hex(Color c)
	{
		return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}

	/** Truncate with "..." so it fits in the given pixel width. */
	private static String fit(String text, java.awt.FontMetrics fm, int width)
	{
		if (fm.stringWidth(text) <= width)
		{
			return text;
		}
		String dots = "...";
		int n = text.length();
		while (n > 0 && fm.stringWidth(text.substring(0, n).trim() + dots) > width)
		{
			n--;
		}
		return text.substring(0, n).trim() + dots;
	}

	/** The boss title doubles as the picker: it reads "Boss N: Name" in the state colour, and opens a list of every boss. */
	private javax.swing.JComboBox<BossmanData.Rung> bossTitle(BossmanData.Rung r, boolean isCurrent, boolean complete,
		List<BossmanData.Rung> rungs, int idx, int currentIdx)
	{
		javax.swing.JComboBox<BossmanData.Rung> box = new javax.swing.JComboBox<>(rungs.toArray(new BossmanData.Rung[0]));
		box.setFont(FontManager.getRunescapeBoldFont());
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setForeground(Color.WHITE);
		box.setBorder(null);
		box.setFocusable(false);
		box.setMaximumRowCount(16);
		box.setAlignmentX(LEFT_ALIGNMENT);
		box.setToolTipText(complete ? "Complete" : (isCurrent ? "In progress" + (r.isFirstKillOnly() ? " - first kill completes it" : "")
			: (r.skilling ? "Skilling boss - tick \"Do this boss now\" to take it on before the ladder's next"
			: (!r.blocking ? "Side boss - doesn't hold up the ladder" : "Locked - finish the bosses before it"))));
		final Color titleColor = complete ? OK : (isCurrent ? WARN : MUTED);
		box.addPopupMenuListener(new javax.swing.event.PopupMenuListener()
		{
			@Override
			public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e)
			{
				pickerOpen = true;
			}

			@Override
			public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e)
			{
				pickerOpen = false;
			}

			@Override
			public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e)
			{
				pickerOpen = false;
			}
		});
		box.setRenderer(new javax.swing.DefaultListCellRenderer()
		{
			@Override
			public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
			{
				JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				BossmanData.Rung rr = (BossmanData.Rung) value;
				// list cells come with their index (the model is the rung list in order); the closed box passes -1
				// side bosses can sit incomplete before the current one, so ask each rung rather than going by position
				Color c = index == -1 ? titleColor : (completeRungs.contains(rr) ? OK : (index == currentIdx ? WARN : MUTED));
				l.setFont(FontManager.getRunescapeBoldFont());
				// the closed box has the arrow button on its right; list cells have the scrollbar
				int boxW = box.getWidth() > 0 ? box.getWidth() : TITLE_WIDTH + 30;
				int avail = index == -1 ? boxW - 30 : TITLE_WIDTH;
				String text = fit(rr.label(), l.getFontMetrics(l.getFont()), avail);
				// colour via HTML so the look-and-feel can't repaint the closed box white
				l.setText("<html><font color='" + hex(c) + "'>" + escape(text) + "</font></html>");
				l.setOpaque(true);
				l.setBackground(index != -1 && isSelected ? ColorScheme.DARK_GRAY_COLOR : ColorScheme.DARKER_GRAY_COLOR);
				l.setBorder(BorderFactory.createEmptyBorder(1, index == -1 ? 0 : 4, 1, 0));
				if (index != -1)
				{
					// keep the popup no wider than the box, whatever the longest boss name is
					l.setPreferredSize(new Dimension(TITLE_WIDTH, l.getPreferredSize().height));
				}
				return l;
			}
		});
		box.setSelectedIndex(idx);
		box.addActionListener(e ->
		{
			int sel = box.getSelectedIndex();
			if (sel >= 0 && sel != idx)
			{
				browsing = (sel == currentIdx) ? null : sel;
				pickerOpen = false;   // the pick closes the list
				refresh();
			}
		});
		// right-click opens the boss's wiki page; left-click opens the list
		javax.swing.JPopupMenu bossMenu = new javax.swing.JPopupMenu();
		javax.swing.JMenuItem bossWiki = new javax.swing.JMenuItem("Open Wiki");
		bossWiki.setFont(FontManager.getRunescapeSmallFont());
		bossWiki.addActionListener(e -> net.runelite.client.util.LinkBrowser.browse(
			bossWikiUrl(r)));
		bossMenu.add(bossWiki);
		box.setComponentPopupMenu(bossMenu);
		Dimension pref = box.getPreferredSize();
		box.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
		return box;
	}

	private JPanel rungCard(BossmanData.Rung r, boolean isCurrent, List<BossmanData.Rung> rungs, int idx, int currentIdx)
	{
		JPanel p = card();
		boolean complete = rules.isRungComplete(r);
		p.add(bossTitle(r, isCurrent, complete, rungs, idx, currentIdx));
		p.add(Box.createVerticalStrut(6));

		p.add(infoRow("Combat Level", r.combatLevel != null ? String.valueOf(r.combatLevel) : "n/a", null, Color.WHITE));
		if (r.gates == null || r.gates.isEmpty())
		{
			p.add(infoRow("Gate", (r.gate == null || r.gate.isEmpty()) ? "none" : r.gate, null, Color.WHITE));
		}
		else
		{
			boolean first = true;
			for (BossmanData.Gate g : r.gates)
			{
				p.add(gateRow(first ? "Gate" : null, g));
				first = false;
			}
		}

		int kc = rules.rungKc(r);
		boolean hasExpected = r.log.expectedKc != null && r.log.expectedKc > 0;
		String kcText = hasExpected ? kc + "/~" + r.log.expectedKc : String.valueOf(kc);
		// past the expected KC without the log done = dry
		Color kcColor = (hasExpected && kc > r.log.expectedKc && !complete) ? BAD : Color.WHITE;
		JPanel kcRow = infoRow("Kill Count", kcText, null, kcColor);
		if (hasExpected)
		{
			kcRow.setToolTipText("<html>~" + r.log.expectedKc + " is the expected kill count to finish this boss's log<br>(red = past it, going dry)</html>");
		}
		p.add(kcRow);
		// rungs fed by several sources (the three Moons and their chest, the Barrows brothers): each one's own count
		java.util.Map<String, Integer> bySource = new java.util.LinkedHashMap<>();
		for (java.util.Map.Entry<String, Integer> en : rules.kcBySource(r).entrySet())
		{
			if (plugin.getData().canonicalSource(en.getKey()) != null)
			{
				bySource.put(en.getKey(), en.getValue());   // receipt-rule names (The Gauntlet, Inferno) aren't counted
			}
		}
		if (bySource.size() > 1)
		{
			for (java.util.Map.Entry<String, Integer> en : bySource.entrySet())
			{
				p.add(infoRow("", en.getKey() + ": " + en.getValue(), null, MUTED));
			}
		}

		if (r.ca != null && r.ca.tasks > 0)
		{
			int done = 0;
			for (BossmanData.CaTask t : r.ca.list)
			{
				if (rules.isCaDone(t))
				{
					done++;
				}
			}
			Color caColor = done == r.ca.tasks ? OK : (rules.isCaGating() ? WARN : Color.WHITE);
			JPanel caRow = infoRow("Combat Achievements", done + "/" + r.ca.tasks + (rules.isCaGating() ? " (gating)" : ""), null, caColor);
			javax.swing.JPopupMenu caMenu = new javax.swing.JPopupMenu();
			javax.swing.JMenuItem caWiki = new javax.swing.JMenuItem("Open Wiki (Combat Achievements)");
			caWiki.setFont(FontManager.getRunescapeSmallFont());
			caWiki.addActionListener(e -> net.runelite.client.util.LinkBrowser.browse(
				bossWikiUrl(r) + "#Combat_Achievements"));
			caMenu.add(caWiki);
			installMenu(caRow, caMenu);
			StringBuilder tip = new StringBuilder("<html>");
			for (BossmanData.CaTask t : r.ca.list)
			{
				tip.append(rules.isCaDone(t) ? "[x] " : "[ ] ").append(escape(t.name)).append(" (").append(t.tier).append(")<br>");
			}
			caRow.setToolTipText(tip.append("</html>").toString());
			p.add(caRow);
		}

		LadderRules.Progress progress = rules.progress(r);
		int need = progress.need;
		int have = progress.have;
		if (need > 0)
		{
			JProgressBar bar = new JProgressBar(0, need);
			bar.setValue(have);
			bar.setStringPainted(true);
			bar.setString(have + "/" + need + (rules.isCaGating() && r.ca != null && !r.ca.list.isEmpty() ? " Items & Tasks" : " Gating Items"));
			bar.setForeground(have == need ? OK : WARN);
			bar.setBackground(TRACK);                 // visible track so an empty bar still reads as a bar
			bar.setBorderPainted(true);
			bar.setBorder(BorderFactory.createLineBorder(new Color(70, 70, 70)));
			bar.setUI(new javax.swing.plaf.basic.BasicProgressBarUI()
			{
				@Override
				protected Color getSelectionBackground()
				{
					return Color.WHITE;   // text over the empty track
				}

				@Override
				protected Color getSelectionForeground()
				{
					return Color.BLACK;   // text over the filled part
				}

				@Override
				protected void paintDeterminate(java.awt.Graphics g, javax.swing.JComponent c)
				{
					super.paintDeterminate(g, c);
					if (progressBar.getValue() == 0)
					{
						// a sliver at zero so the fill colour is visible at a glance
						java.awt.Insets b = progressBar.getInsets();
						g.setColor(progressBar.getForeground());
						g.fillRect(b.left, b.top, 3, progressBar.getHeight() - b.top - b.bottom);
					}
				}
			});
			bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
			bar.setAlignmentX(LEFT_ALIGNMENT);
			p.add(Box.createVerticalStrut(5));
			p.add(bar);
		}
		if (r.isFirstKillOnly() || need == 0)
		{
			JCheckBox done = new JCheckBox("Mark this boss complete by hand", rules.isManuallyComplete(r));
			done.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			done.setForeground(MUTED);
			done.setAlignmentX(LEFT_ALIGNMENT);
			done.setFont(FontManager.getRunescapeSmallFont());
			done.addActionListener(e -> plugin.markRungComplete(r, done.isSelected()));
			p.add(Box.createVerticalStrut(4));
			p.add(done);
		}
		if (r.skilling && !complete)
		{
			JCheckBox now = new JCheckBox("Do this boss now", rules.focusedRung() == r);
			now.setToolTipText("Make it your current boss ahead of the ladder, until its log is done");
			now.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			now.setForeground(MUTED);
			now.setAlignmentX(LEFT_ALIGNMENT);
			now.setFont(FontManager.getRunescapeSmallFont());
			now.addActionListener(e ->
			{
				browsing = null;
				plugin.setFocus(now.isSelected() ? r : null);
			});
			p.add(Box.createVerticalStrut(4));
			p.add(now);
		}
		return p;
	}

	/** One gate requirement: key (first line only), icon by gate type, text. */
	private JPanel gateRow(String key, BossmanData.Gate g)
	{
		java.awt.image.BufferedImage icon = null;
		switch (g.type == null ? "" : g.type)
		{
			case "quest":
				icon = icons.get("quest");
				break;
			case "diary":
				icon = icons.get("diary");
				break;
			case "combat":
				icon = icons.get("combat");
				break;
			case "wilderness":
				icon = icons.get("wilderness");
				break;
			case "skill":
				icon = skillIcon(g.skill);
				break;
			case "task":
				icon = skillIcon("Slayer");
				break;
			case "item":
				// item icons are async; use a label the ItemManager fills in
				return itemGateRow(key, g);
			default:
				break;
		}
		return infoRow(key != null ? key : "", g.text, icon, Color.WHITE);
	}

	private JPanel itemGateRow(String key, BossmanData.Gate g)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);
		JLabel k = new JLabel(key != null ? key + ": " : "");
		k.setFont(FontManager.getRunescapeSmallFont());
		k.setForeground(MUTED);
		row.add(k);
		JLabel i = new JLabel();
		i.setPreferredSize(new Dimension(18, 16));
		i.setMaximumSize(new Dimension(18, 16));
		if (g.itemIds != null && !g.itemIds.isEmpty())
		{
			AsyncBufferedImage img = image(g.itemIds.get(0));
			// onLoaded runs on the client thread: hop to Swing to touch the label
			img.onLoaded(() -> javax.swing.SwingUtilities.invokeLater(() ->
				i.setIcon(new javax.swing.ImageIcon(net.runelite.client.util.ImageUtil.resizeImage(img, 16, 16)))));
		}
		i.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 3));
		row.add(i);
		JLabel v = new JLabel("<html><body style='width:105px'>" + escape(g.text) + "</body></html>");
		v.setFont(FontManager.getRunescapeSmallFont());
		v.setForeground(Color.WHITE);
		row.add(v);
		row.add(Box.createHorizontalGlue());
		Dimension pref = row.getPreferredSize();
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
		return row;
	}

	private AsyncBufferedImage image(int itemId)
	{
		return images.computeIfAbsent(itemId, itemManager::getImage);
	}

	private java.awt.image.BufferedImage skillIcon(String name)
	{
		if (name == null)
		{
			return null;
		}
		try
		{
			Skill sk = Skill.valueOf(name.toUpperCase(java.util.Locale.ROOT));
			return skillIconManager.getSkillImage(sk, true);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/** "Key: value" line with an optional icon in front of the value. */
	private JPanel infoRow(String key, String value, java.awt.image.BufferedImage icon, Color valueColor)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);
		JLabel k = new JLabel(key.isEmpty() ? "" : key + ": ");
		k.setFont(FontManager.getRunescapeSmallFont());
		k.setForeground(MUTED);
		row.add(k);
		if (key.isEmpty())
		{
			row.add(Box.createHorizontalStrut(30));
		}
		if (icon != null)
		{
			JLabel i = new JLabel(new javax.swing.ImageIcon(icon));
			i.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 3));
			row.add(i);
		}
		JLabel v = new JLabel("<html><body style='width:" + (icon != null ? 105 : 125) + "px'>" + escape(value) + "</body></html>");
		v.setFont(FontManager.getRunescapeSmallFont());
		v.setForeground(valueColor);
		row.add(v);
		row.add(Box.createHorizontalGlue());
		Dimension pref = row.getPreferredSize();
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
		return row;
	}

	private JPanel logSection(BossmanData.Rung r)
	{
		JPanel p = card();
		p.add(sectionTitle("Collection Log"));
		int kc = rules.rungKc(r);
		boolean any = false;
		for (BossmanData.GatingItem gi : r.log.gatingItems)
		{
			if (rules.isMeme(gi))
			{
				continue;
			}
			any = true;
			boolean have = rules.isLogItemUnlocked(gi);
			boolean bl = rules.isBacklogged(r, gi.item);
			JPanel row = logRow(gi.ids, (have ? "[x] " : "[ ] ") + gi.item, "Drop Rate: " + gi.rate + (bl && !have ? " - in backlog" : ""),
				have ? OK : (bl ? MUTED : Color.WHITE), MUTED);
			attachMenu(row, r, gi.item, gi.ids, have, true);
			p.add(row);
		}
		// gating pets/jars sit with the other gating items
		for (BossmanData.DebtItem d : r.log.debtPetsJars)
		{
			if (!rules.isDebtItemGating(d))
			{
				continue;
			}
			any = true;
			boolean have = rules.isDebtItemUnlocked(d);
			boolean bl = rules.isBacklogged(r, d.item);
			JPanel row = logRow(d.ids, (have ? "[x] " : "[ ] ") + d.item, "Drop Rate: " + d.rate + (bl && !have ? " - in backlog" : ""),
				have ? OK : (bl ? MUTED : Color.WHITE), MUTED);
			attachMenu(row, r, d.item, d.ids, have, true);
			p.add(row);
		}
		if (!any)
		{
			p.add(wrapped(r.log.gatingItems.isEmpty()
				? "This boss has no collection log; the first kill completes it."
				: "Nothing here gates this boss (all optional); the first kill completes it.", MUTED, 160));
		}
		return p;
	}

	/** Items that never gate: pets, jars and log items rarer than the meme threshold, or optional in the data (mode-locked rewards). */
	private JPanel memeSection(BossmanData.Rung r)
	{
		List<BossmanData.GatingItem> memes = new ArrayList<>();
		for (BossmanData.GatingItem gi : r.log.gatingItems)
		{
			if (rules.isMeme(gi))
			{
				memes.add(gi);
			}
		}
		List<BossmanData.DebtItem> memePets = new ArrayList<>();
		for (BossmanData.DebtItem d : r.log.debtPetsJars)
		{
			if (rules.isMeme(d))
			{
				memePets.add(d);
			}
		}
		if (memes.isEmpty() && memePets.isEmpty())
		{
			return null;
		}
		JPanel p = card();
		p.add(sectionTitle("Optional Items"));
		for (BossmanData.GatingItem gi : memes)
		{
			boolean have = rules.isLogItemUnlocked(gi);
			String why = gi.optional && gi.note != null ? gi.note : "meme grind";
			JPanel row = logRow(gi.ids, (have ? "[x] " : "[ ] ") + gi.item, "Drop Rate: " + gi.rate + " - " + why, have ? OK : MUTED, MUTED);
			attachMenu(row, r, gi.item, gi.ids, have, false);
			p.add(row);
		}
		for (BossmanData.DebtItem d : memePets)
		{
			boolean have = rules.isDebtItemUnlocked(d);
			JPanel row = logRow(d.ids, (have ? "[x] " : "[ ] ") + d.item, "Drop Rate: " + d.rate + " - meme grind", have ? OK : MUTED, MUTED);
			attachMenu(row, r, d.item, d.ids, have, false);
			p.add(row);
		}
		return p;
	}

	/** The backlog: pets/jars whose option is off, plus anything moved here by hand. */
	private JPanel backlogSection(BossmanData.Rung r)
	{
		JPanel p = card();
		p.add(sectionTitle("Backlog"));
		boolean any = false;
		for (BossmanData.GatingItem gi : r.log.gatingItems)
		{
			if (rules.isBacklogged(r, gi.item) && !rules.isLogItemUnlocked(gi))
			{
				any = true;
				JPanel row = logRow(gi.ids, gi.item, "Drop Rate: " + gi.rate + " - moved here by hand", BAD, MUTED);
				attachMenu(row, r, gi.item, gi.ids, false, true);
				p.add(row);
			}
		}
		for (BossmanData.DebtItem d : r.log.debtPetsJars)
		{
			if (rules.isDebtItemUnlocked(d) || rules.isMeme(d))
			{
				continue;
			}
			// the option wins while it's off; a hand-backlogged entry stays in the ledger for when it's back on
			boolean auto = rules.isAutoBacklogged(d);
			if (!auto && !rules.isBacklogged(r, d.item))
			{
				continue;
			}
			any = true;
			boolean owed = rules.isDebtOwed(r, d);
			String note = !auto ? " - moved here by hand"
				: d.debtAtKc == null ? "" : (owed ? " - overdue since KC " : " - expected by KC ") + d.debtAtKc;
			JPanel row = logRow(d.ids, d.item, "Drop Rate: " + d.rate + note, !auto || owed ? BAD : Color.WHITE, MUTED);
			if (auto)
			{
				row.setToolTipText("Backlogged because " + (rules.isJar(d) ? "jars" : "pets") + " don't gate the ladder (setting)");
				attachWikiMenu(row, d.item, d.ids);
			}
			else
			{
				attachMenu(row, r, d.item, d.ids, false, true);
			}
			p.add(row);
		}
		if (!any)
		{
			p.add(wrapped("Nothing in the backlog for this boss.", MUTED, 160));
		}
		return p;
	}

	/** Client-property keys that describe a row to the shared context menu. */
	private static final String ROW_ITEM = "bm.item";
	private static final String ROW_IDS = "bm.ids";
	private static final String ROW_RUNG = "bm.rung";
	private static final String ROW_BACKLOGABLE = "bm.backlogable";
	/** set on unlock rows: the item id a "Re-lock" entry takes back */
	private static final String ROW_RELOCK = "bm.relock";

	/**
	 * One popup menu for every row. Swing hands show() the component that was clicked; we find the row it
	 * belongs to and build the entries for it on the spot. Saves allocating a menu per row per rebuild.
	 */
	private final javax.swing.JPopupMenu rowMenu = new javax.swing.JPopupMenu()
	{
		@Override
		public void show(java.awt.Component invoker, int x, int y)
		{
			java.awt.Component c = invoker;
			while (c != null && !(c instanceof JPanel && ((JPanel) c).getClientProperty(ROW_ITEM) != null))
			{
				c = c.getParent();
			}
			if (c == null)
			{
				return;
			}
			JPanel row = (JPanel) c;
			String item = (String) row.getClientProperty(ROW_ITEM);
			@SuppressWarnings("unchecked")
			List<Integer> ids = (List<Integer>) row.getClientProperty(ROW_IDS);
			BossmanData.Rung r = (BossmanData.Rung) row.getClientProperty(ROW_RUNG);
			removeAll();
			// Open Wiki is always the first entry, whatever else the row offers
			javax.swing.JMenuItem wiki = new javax.swing.JMenuItem("Open Wiki");
			wiki.setFont(FontManager.getRunescapeSmallFont());
			wiki.addActionListener(e -> net.runelite.client.util.LinkBrowser.browse(itemWikiUrl(item, ids)));
			add(wiki);
			if (r != null && Boolean.TRUE.equals(row.getClientProperty(ROW_BACKLOGABLE)))
			{
				boolean bl = rules.isBacklogged(r, item);
				javax.swing.JMenuItem mi = new javax.swing.JMenuItem(bl ? "Remove from Backlog" : "Move to Backlog");
				mi.setFont(FontManager.getRunescapeSmallFont());
				mi.addActionListener(e -> plugin.setBacklogged(r, item, !bl));
				add(mi);
			}
			Integer relockId = (Integer) row.getClientProperty(ROW_RELOCK);
			if (relockId != null)
			{
				javax.swing.JMenuItem relock = new javax.swing.JMenuItem("Re-lock");
				relock.setFont(FontManager.getRunescapeSmallFont());
				relock.addActionListener(e -> plugin.relock(relockId));
				add(relock);
			}
			super.show(invoker, x, y);
		}
	};

	/** The wiki link plus the backlog toggle (only while the item is still locked and gating). */
	private void attachMenu(JPanel row, BossmanData.Rung r, String item, List<Integer> ids, boolean have, boolean backlogable)
	{
		row.putClientProperty(ROW_RUNG, r);
		row.putClientProperty(ROW_BACKLOGABLE, backlogable && !have);
		attachWikiMenu(row, item, ids);
	}

	/** Right-click with only "Open Wiki" (unlock / still-locked rows). */
	private void attachWikiMenu(JPanel row, String item, List<Integer> ids)
	{
		row.putClientProperty(ROW_ITEM, item);
		row.putClientProperty(ROW_IDS, ids);
		installMenu(row, rowMenu);
	}

	private static String bossWikiUrl(BossmanData.Rung r)
	{
		return "https://oldschool.runescape.wiki/w/" + (r.wikiPage != null ? r.wikiPage : urlEncode(r.boss.replace(' ', '_')));
	}

	private static String itemWikiUrl(String item, List<Integer> ids)
	{
		if (ids != null && !ids.isEmpty())
		{
			return "https://oldschool.runescape.wiki/w/Special:Lookup?type=item&id=" + ids.get(0) + "&name=" + urlEncode(item);
		}
		return "https://oldschool.runescape.wiki/w/" + urlEncode(item.replace(' ', '_'));
	}

	private static String urlEncode(String s)
	{
		try
		{
			return java.net.URLEncoder.encode(s, "UTF-8");
		}
		catch (java.io.UnsupportedEncodingException e)
		{
			return s;
		}
	}

	/** Make the whole row (and its labels) open the menu on right-click. */
	private void installMenu(JPanel row, javax.swing.JPopupMenu menu)
	{
		row.setComponentPopupMenu(menu);
		for (java.awt.Component c : row.getComponents())
		{
			if (c instanceof javax.swing.JComponent)
			{
				((javax.swing.JComponent) c).setInheritsPopupMenu(true);
				if (c instanceof java.awt.Container)
				{
					for (java.awt.Component cc : ((java.awt.Container) c).getComponents())
					{
						if (cc instanceof javax.swing.JComponent)
						{
							((javax.swing.JComponent) cc).setInheritsPopupMenu(true);
						}
					}
				}
			}
		}
	}

	/** Wrapped text is laid out at this width and never reflowed wider, so long names wrap instead of clipping. */
	private static final int LOG_TEXT_WIDTH = 125;

	private JLabel wrapped(String text, Color color)
	{
		return wrapped(text, color, LOG_TEXT_WIDTH);
	}

	private JLabel wrapped(String text, Color color, int width)
	{
		JLabel l = new JLabel("<html><body style='width:" + width + "px'>" + escape(text) + "</body></html>");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
		l.setAlignmentX(LEFT_ALIGNMENT);
		l.setMaximumSize(l.getPreferredSize());
		return l;
	}

	private JPanel logRow(List<Integer> ids, String title, String sub, Color titleColor, Color subColor)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(36, 32));
		if (ids != null && !ids.isEmpty())
		{
			image(ids.get(0)).addTo(icon);
		}
		row.add(icon, BorderLayout.WEST);
		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		text.add(wrapped(title, titleColor));
		text.add(wrapped(sub, subColor));
		row.add(text, BorderLayout.CENTER);
		row.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		Dimension pref = row.getPreferredSize();
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(36, pref.height)));
		return row;
	}

	/** What this boss has unlocked for you, then what it still could. */
	private JPanel unlocksSection(BossmanData.Rung r)
	{
		JPanel p = card();
		java.util.Set<String> sources = new java.util.HashSet<>();
		for (String src : r.sources)
		{
			sources.add(src.toLowerCase(java.util.Locale.ROOT));
		}
		// collection-log items have their own section; keep them out of this one
		java.util.Set<Integer> logIds = new java.util.HashSet<>();
		for (BossmanData.GatingItem gi : r.log.gatingItems)
		{
			logIds.addAll(gi.ids);
		}
		for (BossmanData.DebtItem d : r.log.debtPetsJars)
		{
			logIds.addAll(d.ids);
		}
		for (BossmanData.Drop d : r.drops)
		{
			if (d.inLog)
			{
				logIds.addAll(d.ids);
			}
		}
		// unlocked by one of this boss's sources
		List<Ledger.Entry> mine = new ArrayList<>();
		java.util.Set<Integer> mineIds = new java.util.HashSet<>();
		for (Ledger.Entry en : plugin.getLedger().unlocks.values())
		{
			if (en.source != null && sources.contains(en.source.toLowerCase(java.util.Locale.ROOT)) && !logIds.contains(en.itemId))
			{
				mine.add(en);
				mineIds.add(en.itemId);
			}
		}
		int possible = 0;
		int unlockedHere = 0;
		int unlockedElsewhere = 0;
		List<BossmanData.Drop> locked = new ArrayList<>();
		for (BossmanData.Drop d : r.drops)
		{
			if (d.inLog)
			{
				continue; // already listed in the collection log section
			}
			possible++;
			if (d.ids.stream().anyMatch(mineIds::contains))
			{
				unlockedHere++;
			}
			else if (d.ids.stream().anyMatch(plugin.getLedger()::isUnlocked))
			{
				unlockedElsewhere++;
			}
			else
			{
				locked.add(d);
			}
		}
		// count every drop that's unlocked, wherever it came from, so the two numbers add up to the drop table
		p.add(sectionTitle("Unlocks (" + (unlockedHere + unlockedElsewhere) + "/" + possible + ")"));
		if (mine.isEmpty())
		{
			p.add(wrapped("Nothing unlocked from this boss yet.", MUTED, 160));
		}
		// same order as the drop table: commonest first (items without a known rate go last, in unlock order)
		mine.sort((a, b) -> Integer.compare(dropIndex(r, a.itemId), dropIndex(r, b.itemId)));
		for (Ledger.Entry en : mine)
		{
			String rate = rateFor(r, en.itemId);
			JPanel row = logRow(java.util.Collections.singletonList(en.itemId), "[x] " + en.name,
				(rate != null ? "Drop Rate: " + rate + " - " : "") + "KC " + (en.kc != null ? en.kc : "?"), OK, MUTED);
			row.putClientProperty(ROW_RELOCK, en.itemId);
			attachWikiMenu(row, en.name, java.util.Collections.singletonList(en.itemId));
			p.add(row);
		}
		if (unlockedElsewhere > 0)
		{
			p.add(wrapped(unlockedElsewhere + " more already unlocked from other sources.", MUTED, 160));
		}
		if (!locked.isEmpty())
		{
			p.add(Box.createVerticalStrut(6));
			p.add(subTitle("Still Locked (" + locked.size() + ")"));
			p.add(Box.createVerticalStrut(4));
			int shown = 0;
			for (BossmanData.Drop d : locked)
			{
				if (shown++ >= MAX_LOCKED_SHOWN)
				{
					p.add(label("... and " + (locked.size() - MAX_LOCKED_SHOWN) + " more", MUTED));
					break;
				}
				JPanel row = logRow(d.ids, "[ ] " + d.item, "Drop Rate: " + d.rate, Color.WHITE, MUTED);
				attachWikiMenu(row, d.item, d.ids);
				p.add(row);
			}
		}
		return p;
	}

	private int dropIndex(BossmanData.Rung r, int itemId)
	{
		for (int i = 0; i < r.drops.size(); i++)
		{
			if (r.drops.get(i).ids.contains(itemId))
			{
				return i;
			}
		}
		return Integer.MAX_VALUE;
	}

	private String rateFor(BossmanData.Rung r, int itemId)
	{
		for (BossmanData.Drop d : r.drops)
		{
			if (d.ids.contains(itemId))
			{
				return d.rate;
			}
		}
		return null;
	}

	private JPanel card()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		return p;
	}

	private JLabel subTitle(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeBoldFont());
		l.setForeground(Color.WHITE);
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

	private JLabel sectionTitle(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeBoldFont());
		l.setForeground(WARN);
		l.setAlignmentX(LEFT_ALIGNMENT);
		l.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		return l;
	}

	private JLabel label(String text, Color color)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
		l.setHorizontalAlignment(SwingConstants.LEFT);
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

	/** A drawn chevron, so the arrows match at any font and grey out when the button is disabled. */
	private static class ChevronIcon implements javax.swing.Icon
	{
		private final boolean left;

		ChevronIcon(boolean left)
		{
			this.left = left;
		}

		@Override
		public int getIconWidth()
		{
			return 8;
		}

		@Override
		public int getIconHeight()
		{
			return 10;
		}

		@Override
		public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y)
		{
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
			g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setStroke(new java.awt.BasicStroke(1.8f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
			g2.setColor(c.isEnabled() ? Color.WHITE : MUTED);
			int tip = left ? x + 2 : x + 6;
			int tail = left ? x + 6 : x + 2;
			g2.drawLine(tail, y + 1, tip, y + 5);
			g2.drawLine(tip, y + 5, tail, y + 9);
			g2.dispose();
		}
	}

	private JButton arrowButton(String text)
	{
		JButton b = smallButton("");
		b.setIcon(new ChevronIcon("<".equals(text)));
		b.setDisabledIcon(b.getIcon());
		b.setMargin(new java.awt.Insets(1, 7, 1, 7));
		return b;
	}

	private JButton smallButton(String text)
	{
		JButton b = new JButton(text);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setMargin(new java.awt.Insets(1, 6, 1, 6));
		b.setFocusPainted(false);
		return b;
	}

	private static String escape(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
