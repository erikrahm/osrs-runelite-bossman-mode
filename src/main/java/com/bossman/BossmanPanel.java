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
	private final ItemManager itemManager;

	private final JPanel body = new ScrollableBody();
	private final SkillIconManager skillIconManager;
	private final java.util.Map<String, java.awt.image.BufferedImage> icons = new java.util.HashMap<>();
	/** null = follow the current rung; otherwise the rung being browsed */
	private Integer browsing;

	@Inject
	public BossmanPanel(BossmanModePlugin plugin, ItemManager itemManager, SkillIconManager skillIconManager)
	{
		super(false);
		this.plugin = plugin;
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
		BossmanData.Rung current = plugin.currentRung();
		List<BossmanData.Rung> rungs = plugin.getData().orderedRungs();
		if (rungs.isEmpty())
		{
			body.add(label("No ladder data.", MUTED));
			revalidate();
			repaint();
			return;
		}
		int currentIdx = current == null ? rungs.size() - 1 : rungs.indexOf(current);
		int idx = browsing == null ? currentIdx : Math.max(0, Math.min(browsing, rungs.size() - 1));
		BossmanData.Rung r = rungs.get(idx);

		addBlock(header(idx, rungs.size(), current, r));
		body.add(Box.createVerticalStrut(6));
		addBlock(rungCard(r, r == current));
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
		JButton prev = smallButton("<");
		prev.setToolTipText("Previous boss");
		prev.addActionListener(e -> { browsing = idx - 1; refresh(); });
		prev.setEnabled(idx > 0);
		JButton now = smallButton("Current");
		now.setToolTipText("Jump to the boss you're on");
		now.addActionListener(e -> { browsing = null; refresh(); });
		now.setEnabled(shown != current);
		JButton next = smallButton(">");
		next.setToolTipText("Next boss");
		next.addActionListener(e -> { browsing = idx + 1; refresh(); });
		next.setEnabled(idx < total - 1);
		nav.add(prev);
		nav.add(now);
		nav.add(next);
		p.add(nav, BorderLayout.EAST);
		return p;
	}

	private JPanel rungCard(BossmanData.Rung r, boolean isCurrent)
	{
		JPanel p = card();
		boolean complete = plugin.isRungComplete(r);
		JLabel name = new JLabel("Boss " + r.rung + ": " + r.boss);
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(complete ? OK : (isCurrent ? WARN : MUTED));
		name.setToolTipText(complete ? "Complete" : (isCurrent ? "In progress" + (r.isFirstKillOnly() ? " - first kill completes it" : "") : "Locked - finish the bosses before it"));
		name.setAlignmentX(LEFT_ALIGNMENT);
		javax.swing.JPopupMenu bossMenu = new javax.swing.JPopupMenu();
		javax.swing.JMenuItem bossWiki = new javax.swing.JMenuItem("Open Wiki");
		bossWiki.setFont(FontManager.getRunescapeSmallFont());
		bossWiki.addActionListener(e -> net.runelite.client.util.LinkBrowser.browse(
			"https://oldschool.runescape.wiki/w/" + (r.wikiPage != null ? r.wikiPage : r.boss.replace(' ', '_'))));
		bossMenu.add(bossWiki);
		name.setComponentPopupMenu(bossMenu);
		p.add(name);
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


		int kc = plugin.rungKc(r);
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
				if (plugin.isCaDone(t))
				{
					done++;
				}
			}
			JPanel caRow = infoRow("Combat Achievements", done + "/" + r.ca.tasks + (plugin.isCaGating() ? " (gating)" : ""), null, done == r.ca.tasks ? OK : Color.WHITE);
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
				tip.append(plugin.isCaDone(t) ? "[x] " : "[ ] ").append(escape(t.name)).append(" (").append(t.tier).append(")<br>");
			}
			caRow.setToolTipText(tip.append("</html>").toString());
			p.add(caRow);
		}

		int need = 0;
		int have = 0;
		for (BossmanData.GatingItem gi : r.log.gatingItems)
		{
			if (plugin.isMeme(gi) || plugin.isBacklogged(r, gi.item))
			{
				continue;
			}
			need++;
			if (plugin.isLogItemUnlocked(gi))
			{
				have++;
			}
		}
		for (BossmanData.DebtItem d : r.log.debtPetsJars)
		{
			if (!plugin.isDebtItemGating(d) || plugin.isBacklogged(r, d.item))
			{
				continue;
			}
			need++;
			if (plugin.isDebtItemUnlocked(d))
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
		int kc = plugin.rungKc(r);
		boolean any = false;
		for (BossmanData.GatingItem gi : r.log.gatingItems)
		{
			if (plugin.isMeme(gi))
			{
				continue;
			}
			any = true;
			boolean have = plugin.isLogItemUnlocked(gi);
			boolean bl = plugin.isBacklogged(r, gi.item);
			JPanel row = logRow(gi.ids, (have ? "[x] " : "[ ] ") + gi.item, "Drop Rate: " + gi.rate + (bl && !have ? " - in backlog" : ""),
				have ? OK : (bl ? MUTED : Color.WHITE), MUTED);
			attachMenu(row, r, gi.item, gi.ids, have, true);
			p.add(row);
		}
		// gating pets/jars sit with the other gating items
		for (BossmanData.DebtItem d : r.log.debtPetsJars)
		{
			if (!plugin.isDebtItemGating(d))
			{
				continue;
			}
			any = true;
			boolean have = plugin.isDebtItemUnlocked(d);
			boolean bl = plugin.isBacklogged(r, d.item);
			JPanel row = logRow(d.ids, (have ? "[x] " : "[ ] ") + d.item, "Drop Rate: " + d.rate + (bl && !have ? " - in backlog" : ""),
				have ? OK : (bl ? MUTED : Color.WHITE), MUTED);
			attachMenu(row, r, d.item, d.ids, have, true);
			p.add(row);
		}
		// non-gating pets/jars under their own sub-header
		boolean subHeader = false;
		for (BossmanData.DebtItem d : r.log.debtPetsJars)
		{
			if (plugin.isDebtItemGating(d))
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
			boolean have = plugin.isDebtItemUnlocked(d);
			String note = have ? "" : (kc >= d.debtAtKc ? " - in backlog since KC " + d.debtAtKc : " - backlog at KC " + d.debtAtKc);
			JPanel row = logRow(d.ids, (have ? "[x] " : "[ ] ") + d.item, "Drop Rate: " + d.rate + note, have ? OK : MUTED, kc >= d.debtAtKc && !have ? BAD : MUTED);
			attachMenu(row, r, d.item, d.ids, have, false);
			p.add(row);
		}
		if (!any)
		{
			p.add(label("This boss has no collection log; first kill completes it.", MUTED));
		}
		return p;
	}

	/** Items rarer than the configured threshold: shown for completeness, never gating. */
	private JPanel memeSection(BossmanData.Rung r)
	{
		List<BossmanData.GatingItem> memes = new ArrayList<>();
		for (BossmanData.GatingItem gi : r.log.gatingItems)
		{
			if (plugin.isMeme(gi))
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
			boolean have = plugin.isLogItemUnlocked(gi);
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
		int kc = plugin.rungKc(r);
		boolean any = false;
		for (BossmanData.GatingItem gi : r.log.gatingItems)
		{
			if (plugin.isBacklogged(r, gi.item) && !plugin.isLogItemUnlocked(gi))
			{
				any = true;
				JPanel row = logRow(gi.ids, gi.item, "Drop Rate: " + gi.rate + " - moved here by hand", BAD, MUTED);
				attachMenu(row, r, gi.item, gi.ids, false, true);
				p.add(row);
			}
		}
		for (BossmanData.DebtItem d : r.log.debtPetsJars)
		{
			if (plugin.isDebtItemUnlocked(d))
			{
				continue;
			}
			boolean manual = plugin.isBacklogged(r, d.item);
			boolean auto = !plugin.isDebtItemGating(d) && kc >= d.debtAtKc;
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
			p.add(label("Nothing in the backlog for this boss.", MUTED));
		}
		return p;
	}

	/** Right-click on a log row: backlog toggle (gating items) and the wiki link. Pre-plugin drops come in via the collection-log import. */
	private void attachMenu(JPanel row, BossmanData.Rung r, String item, List<Integer> ids, boolean have, boolean backlogable)
	{
		javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
		if (backlogable && !have)
		{
			boolean bl = plugin.isBacklogged(r, item);
			javax.swing.JMenuItem mi = new javax.swing.JMenuItem(bl ? "Remove from Backlog" : "Move to Backlog");
			mi.setFont(FontManager.getRunescapeSmallFont());
			mi.addActionListener(e -> plugin.setBacklogged(r, item, !bl));
			menu.add(mi);
		}
		menu.add(wikiItem(item, ids));
		installMenu(row, menu);
	}

	/** Right-click with only "Open wiki" (unlock / still-locked rows). */
	private void attachWikiMenu(JPanel row, String item, List<Integer> ids)
	{
		javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
		menu.add(wikiItem(item, ids));
		installMenu(row, menu);
	}

	private javax.swing.JMenuItem wikiItem(String item, List<Integer> ids)
	{
		javax.swing.JMenuItem wiki = new javax.swing.JMenuItem("Open Wiki");
		wiki.setFont(FontManager.getRunescapeSmallFont());
		wiki.addActionListener(e ->
		{
			String url;
			if (ids != null && !ids.isEmpty())
			{
				url = "https://oldschool.runescape.wiki/w/Special:Lookup?type=item&id=" + ids.get(0) + "&name=" + urlEncode(item);
			}
			else
			{
				url = "https://oldschool.runescape.wiki/w/" + urlEncode(item.replace(' ', '_'));
			}
			net.runelite.client.util.LinkBrowser.browse(url);
		});
		return wiki;
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
		JLabel t = new JLabel("<html><body style='width:135px'>" + escape(title) + "</body></html>");
		t.setFont(FontManager.getRunescapeSmallFont());
		t.setForeground(titleColor);
		t.setAlignmentX(LEFT_ALIGNMENT);
		text.add(t);
		JLabel sb = new JLabel("<html><body style='width:135px'>" + escape(sub) + "</body></html>");
		sb.setFont(FontManager.getRunescapeSmallFont());
		sb.setForeground(subColor);
		sb.setAlignmentX(LEFT_ALIGNMENT);
		text.add(sb);
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
			p.add(label("Nothing unlocked from this boss yet.", MUTED));
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

	private JLabel wrapLabel(String text, Color color)
	{
		JLabel l = new JLabel("<html><body style='width:150px'>" + escape(text) + "</body></html>");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
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
