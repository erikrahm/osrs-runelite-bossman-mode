/*
 * Bossman Mode — side panel: the ladder (current boss, log progress, KC, backlog) and per-boss unlocks.
 * BSD-2-Clause — see LICENSE.
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
	/** Index of the current boss at the last refresh; when it moves forward we stay on the finished boss instead of auto-paging. */
	private Integer lastCurrentIdx;

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
		scroll.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		outer.add(scroll, BorderLayout.CENTER);
		return outer;
	}

	public void refresh()
	{
		try
		{
			refreshInner();
		}
		catch (Exception e)
		{
			log.warn("panel refresh failed (browsing={})", browsing, e);
		}
	}

	private void refreshInner()
	{
		body.removeAll();
		BossmanData.Rung current = rules.currentRung();
		List<BossmanData.Rung> rungs = plugin.getData().orderedRungs();
		if (rungs.isEmpty())
		{
			body.add(label("No ladder data.", MUTED));
			revalidate();
			repaint();
			return;
		}
		int currentIdx = current == null ? rungs.size() - 1 : rungs.indexOf(current);
		if (browsing == null && lastCurrentIdx != null && currentIdx > lastCurrentIdx)
		{
			// the boss just got completed: let the name go green and the bar fill; "Current" jumps to the next one
			browsing = lastCurrentIdx;
		}
		lastCurrentIdx = currentIdx;
		int idx = browsing == null ? currentIdx : Math.max(0, Math.min(browsing, rungs.size() - 1));
		BossmanData.Rung r = rungs.get(idx);

		addBlock(header(idx, rungs.size(), current, r));
		body.add(Box.createVerticalStrut(6));
		addBlock(rungCard(r, r == current, rungs, idx, current));
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
	}

	/** Left-align and stop BoxLayout from stretching a block vertically. */
	private void addBlock(JPanel block)
	{
		block.setAlignmentX(LEFT_ALIGNMENT);
		Dimension pref = block.getPreferredSize();
		block.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
		body.add(block);
	}

	// ------------------------------------------------------------------ sections

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

	/** Dropdown of every boss, coloured by state, for jumping straight to one. */
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
		List<BossmanData.Rung> rungs, int idx, BossmanData.Rung current)
	{
		javax.swing.JComboBox<BossmanData.Rung> box = new javax.swing.JComboBox<>(rungs.toArray(new BossmanData.Rung[0]));
		box.setFont(FontManager.getRunescapeBoldFont());
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setForeground(Color.WHITE);
		box.setBorder(null);
		box.setFocusable(false);
		box.setMaximumRowCount(16);
		box.setAlignmentX(LEFT_ALIGNMENT);
		box.setToolTipText(complete ? "Complete" : (isCurrent ? "In progress" + (r.isFirstKillOnly() ? " - first kill completes it" : "") : "Locked - finish the bosses before it"));
		final int currentIdx = current == null ? rungs.size() - 1 : rungs.indexOf(current);
		final Color titleColor = complete ? OK : (isCurrent ? WARN : MUTED);
		box.setRenderer(new javax.swing.DefaultListCellRenderer()
		{
			@Override
			public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
			{
				JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				BossmanData.Rung rr = (BossmanData.Rung) value;
				// list cells come with their index (the model is the rung list in order); the closed box passes -1
				Color c = index == -1 ? titleColor : (index < currentIdx || current == null ? OK : (index == currentIdx ? WARN : MUTED));
				l.setFont(FontManager.getRunescapeBoldFont());
				// the closed box has the arrow button on its right; list cells have the scrollbar
				int boxW = box.getWidth() > 0 ? box.getWidth() : TITLE_WIDTH + 30;
				int avail = index == -1 ? boxW - 30 : TITLE_WIDTH;
				String text = fit("Boss " + rr.rung + ": " + rr.boss, l.getFontMetrics(l.getFont()), avail);
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
				refresh();
			}
		});
		// right-click still opens the boss's wiki page; left-click opens the list
		javax.swing.JPopupMenu bossMenu = new javax.swing.JPopupMenu();
		javax.swing.JMenuItem bossWiki = new javax.swing.JMenuItem("Open Wiki");
		bossWiki.setFont(FontManager.getRunescapeSmallFont());
		bossWiki.addActionListener(e -> net.runelite.client.util.LinkBrowser.browse(
			"https://oldschool.runescape.wiki/w/" + (r.wikiPage != null ? r.wikiPage : r.boss.replace(' ', '_'))));
		bossMenu.add(bossWiki);
		box.setComponentPopupMenu(bossMenu);
		Dimension pref = box.getPreferredSize();
		box.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
		return box;
	}

	private JPanel rungCard(BossmanData.Rung r, boolean isCurrent, List<BossmanData.Rung> rungs, int idx, BossmanData.Rung current)
	{
		JPanel p = card();
		boolean complete = rules.isRungComplete(r);
		p.add(bossTitle(r, isCurrent, complete, rungs, idx, current));
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
			kcRow.setToolTipText("~" + r.log.expectedKc + " is the expected kill count to finish this boss's log (red = past it, going dry)");
		}
		p.add(kcRow);

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
			JPanel caRow = infoRow("Combat Achievements", done + "/" + r.ca.tasks + (rules.isCaGating() ? " (gating)" : ""), null, done == r.ca.tasks ? OK : Color.WHITE);
			javax.swing.JPopupMenu caMenu = new javax.swing.JPopupMenu();
			javax.swing.JMenuItem caWiki = new javax.swing.JMenuItem("Open Wiki (Combat Achievements)");
			caWiki.setFont(FontManager.getRunescapeSmallFont());
			caWiki.addActionListener(e -> net.runelite.client.util.LinkBrowser.browse(
				"https://oldschool.runescape.wiki/w/" + (r.wikiPage != null ? r.wikiPage : r.boss.replace(' ', '_')) + "#Combat_Achievements"));
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

		int need = 0;
		int have = 0;
		for (BossmanData.GatingItem gi : r.log.gatingItems)
		{
			if (rules.isMeme(gi) || rules.isBacklogged(r, gi.item))
			{
				continue;
			}
			need++;
			if (rules.isLogItemUnlocked(gi))
			{
				have++;
			}
		}
		for (BossmanData.DebtItem d : r.log.debtPetsJars)
		{
			if (!rules.isDebtItemGating(d) || rules.isBacklogged(r, d.item))
			{
				continue;
			}
			need++;
			if (rules.isDebtItemUnlocked(d))
			{
				have++;
			}
		}
		if (need > 0)
		{
			JProgressBar bar = new JProgressBar(0, need);
			bar.setValue(have);
			bar.setStringPainted(true);
			bar.setString(have + "/" + need + " Gating Items");
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
			JCheckBox done = new JCheckBox("Mark this boss complete by hand", plugin.getLedger().manualComplete.contains(r.boss));
			done.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			done.setForeground(MUTED);
			done.setAlignmentX(LEFT_ALIGNMENT);
			done.setFont(FontManager.getRunescapeSmallFont());
			done.addActionListener(e -> plugin.markRungComplete(r, done.isSelected()));
			p.add(Box.createVerticalStrut(4));
			p.add(done);
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
			AsyncBufferedImage img = itemManager.getImage(g.itemIds.get(0));
			img.onLoaded(() -> i.setIcon(new javax.swing.ImageIcon(net.runelite.client.util.ImageUtil.resizeImage(img, 16, 16))));
			i.setIcon(new javax.swing.ImageIcon(net.runelite.client.util.ImageUtil.resizeImage(img, 16, 16)));
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
		// non-gating pets/jars under their own sub-header
		boolean subHeader = false;
		for (BossmanData.DebtItem d : r.log.debtPetsJars)
		{
			if (rules.isDebtItemGating(d))
			{
				continue;
			}
			if (!subHeader)
			{
				subHeader = true;
				any = true;
				p.add(Box.createVerticalStrut(6));
				p.add(subTitle("Not Gating"));
				p.add(Box.createVerticalStrut(4));
			}
			boolean have = rules.isDebtItemUnlocked(d);
			String note = have ? "" : (kc >= d.debtAtKc ? " - in backlog since KC " + d.debtAtKc : " - backlog at KC " + d.debtAtKc);
			JPanel row = logRow(d.ids, (have ? "[x] " : "[ ] ") + d.item, "Drop Rate: " + d.rate + note, have ? OK : MUTED, kc >= d.debtAtKc && !have ? BAD : MUTED);
			attachMenu(row, r, d.item, d.ids, have, false);
			p.add(row);
		}
		if (!any)
		{
			p.add(wrapped("This boss has no collection log; first kill completes it.", MUTED, 160));
		}
		return p;
	}

	/** Items rarer than the configured threshold: shown for completeness, never gating. */
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
		if (memes.isEmpty())
		{
			return null;
		}
		JPanel p = card();
		p.add(sectionTitle("Optional Meme Items"));
		for (BossmanData.GatingItem gi : memes)
		{
			boolean have = rules.isLogItemUnlocked(gi);
			JPanel row = logRow(gi.ids, (have ? "[x] " : "[ ] ") + gi.item, "Drop Rate: " + gi.rate, have ? OK : MUTED, MUTED);
			attachMenu(row, r, gi.item, gi.ids, have, false);
			p.add(row);
		}
		return p;
	}

	/** The backlog: pets/jars past their KC that haven't dropped, plus anything moved here by hand. */
	private JPanel backlogSection(BossmanData.Rung r)
	{
		JPanel p = card();
		p.add(sectionTitle("Backlog"));
		int kc = rules.rungKc(r);
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
			if (rules.isDebtItemUnlocked(d))
			{
				continue;
			}
			boolean manual = rules.isBacklogged(r, d.item);
			boolean auto = !rules.isDebtItemGating(d) && kc >= d.debtAtKc;
			if (manual || auto)
			{
				any = true;
				JPanel row = logRow(d.ids, d.item, "Drop Rate: " + d.rate + (manual ? " - moved here by hand" : " - since KC " + d.debtAtKc), BAD, MUTED);
				if (manual)
				{
					attachMenu(row, r, d.item, d.ids, false, true);
				}
				else
				{
					attachWikiMenu(row, d.item, d.ids);
				}
				p.add(row);
			}
		}
		if (!any)
		{
			p.add(wrapped("Nothing in the backlog for this boss.", MUTED, 160));
		}
		return p;
	}

	// ------------------------------------------------------------------ row context menu

	/** Client-property keys that describe a row to the shared context menu. */
	private static final String ROW_ITEM = "bm.item";
	private static final String ROW_IDS = "bm.ids";
	private static final String ROW_RUNG = "bm.rung";
	private static final String ROW_BACKLOGABLE = "bm.backlogable";

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
			if (r != null && Boolean.TRUE.equals(row.getClientProperty(ROW_BACKLOGABLE)))
			{
				boolean bl = rules.isBacklogged(r, item);
				javax.swing.JMenuItem mi = new javax.swing.JMenuItem(bl ? "Remove from Backlog" : "Move to Backlog");
				mi.setFont(FontManager.getRunescapeSmallFont());
				mi.addActionListener(e -> plugin.setBacklogged(r, item, !bl));
				add(mi);
			}
			javax.swing.JMenuItem wiki = new javax.swing.JMenuItem("Open Wiki");
			wiki.setFont(FontManager.getRunescapeSmallFont());
			wiki.addActionListener(e -> net.runelite.client.util.LinkBrowser.browse(itemWikiUrl(item, ids)));
			add(wiki);
			super.show(invoker, x, y);
		}
	};

	/** Backlog toggle (only while the item is still locked and gating) plus the wiki link. */
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
			AsyncBufferedImage img = itemManager.getImage(ids.get(0));
			img.addTo(icon);
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
		List<BossmanData.Drop> locked = new ArrayList<>();
		for (BossmanData.Drop d : r.drops)
		{
			if (d.inLog)
			{
				continue; // already listed in the collection log section
			}
			possible++;
			boolean have = false;
			for (int id : d.ids)
			{
				if (mineIds.contains(id) || plugin.getLedger().isUnlocked(id))
				{
					have = true;
					break;
				}
			}
			if (!have)
			{
				locked.add(d);
			}
		}
		p.add(sectionTitle("Unlocks (" + mine.size() + "/" + possible + ")"));
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
			attachWikiMenu(row, en.name, java.util.Collections.singletonList(en.itemId));
			p.add(row);
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

	// ------------------------------------------------------------------ widgets

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
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
