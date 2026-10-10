/*
 * Bossman Mode — side panel: the ladder (current rung, log progress, KC, debt board) and recent unlocks.
 * BSD-2-Clause — see LICENSE.
 */
package com.bossman;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

public class BossmanPanel extends PluginPanel
{
	private static final Color OK = new Color(90, 200, 120);
	private static final Color WARN = new Color(255, 200, 80);
	private static final Color BAD = new Color(220, 90, 90);
	private static final Color MUTED = new Color(160, 160, 160);

	private final BossmanModePlugin plugin;
	private final ItemManager itemManager;

	private final JPanel body = new JPanel();
	/** null = follow the current rung; otherwise the rung being browsed */
	private Integer browsing;

	@Inject
	public BossmanPanel(BossmanModePlugin plugin, ItemManager itemManager)
	{
		super(false);
		this.plugin = plugin;
		this.itemManager = itemManager;
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		add(wrapScroll(body), BorderLayout.CENTER);
		refresh();
	}

	private JPanel wrapScroll(JPanel inner)
	{
		JPanel outer = new JPanel(new BorderLayout());
		outer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(inner);
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
			// ledger may be mid-write on the client thread; the next refresh will catch up
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
		addBlock(debtSection(r));
		body.add(Box.createVerticalStrut(10));
		addBlock(recentSection());
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
		JLabel title = new JLabel("Bossman ladder");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		p.add(title, BorderLayout.WEST);

		JPanel nav = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
		nav.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JButton prev = smallButton("<");
		prev.addActionListener(e -> { browsing = idx - 1; refresh(); });
		prev.setEnabled(idx > 0);
		JButton now = smallButton("now");
		now.addActionListener(e -> { browsing = null; refresh(); });
		now.setEnabled(shown != current);
		JButton next = smallButton(">");
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
		name.setForeground(complete ? OK : (isCurrent ? WARN : Color.WHITE));
		name.setAlignmentX(LEFT_ALIGNMENT);
		p.add(name);
		p.add(label("Combat level " + (r.combatLevel != null ? r.combatLevel : "n/a"), MUTED));
		if (r.gate != null && !r.gate.isEmpty())
		{
			p.add(wrapLabel("Gate: " + r.gate, MUTED));
		}
		String status = complete ? "COMPLETE" : (isCurrent ? "IN PROGRESS" : (r.rung != null && plugin.currentRung() != null && r.rung < plugin.currentRung().rung ? "COMPLETE" : "LOCKED"));
		p.add(wrapLabel(status + " - " + r.completion, complete ? OK : (isCurrent ? WARN : MUTED)));

		int kc = plugin.rungKc(r);
		String kcText = "KC " + kc + (r.log.expectedKc != null && r.log.expectedKc > 0 ? "  (expected ~" + r.log.expectedKc + ")" : "");
		p.add(wrapLabel(kcText, Color.WHITE));

		if (!r.log.gatingItems.isEmpty())
		{
			int have = 0;
			for (BossmanData.GatingItem gi : r.log.gatingItems)
			{
				if (plugin.isLogItemUnlocked(gi))
				{
					have++;
				}
			}
			JProgressBar bar = new JProgressBar(0, r.log.gatingItems.size());
			bar.setValue(have);
			bar.setStringPainted(true);
			bar.setString(have + "/" + r.log.gatingItems.size() + " gating items");
			bar.setForeground(have == r.log.gatingItems.size() ? OK : WARN);
			bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
			bar.setAlignmentX(LEFT_ALIGNMENT);
			p.add(Box.createVerticalStrut(4));
			p.add(bar);
		}
		if (r.isFirstKillOnly() || r.log.gatingItems.isEmpty())
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

	private JPanel logSection(BossmanData.Rung r)
	{
		JPanel p = card();
		p.add(sectionTitle("Collection log"));
		int kc = plugin.rungKc(r);
		boolean any = false;
		for (BossmanData.GatingItem gi : r.log.gatingItems)
		{
			any = true;
			boolean have = plugin.isLogItemUnlocked(gi);
			p.add(logRow(gi.ids, (have ? "[x] " : "[ ] ") + gi.item, gi.rate, have ? OK : Color.WHITE, MUTED));
		}
		for (BossmanData.DebtItem d : r.log.debtPetsJars)
		{
			any = true;
			boolean have = isUnlocked(d.ids, d.item);
			String note = have ? "obtained" : (kc >= d.debtAtKc ? "DEBT since KC " + d.debtAtKc : "not gating - debt-eligible at KC " + d.debtAtKc);
			p.add(logRow(d.ids, (have ? "[x] " : "[ ] ") + d.item, d.rate + " - " + note, have ? OK : MUTED, kc >= d.debtAtKc && !have ? BAD : MUTED));
		}
		for (BossmanData.MemeItem m : r.log.memeItems)
		{
			any = true;
			boolean have = isUnlocked(m.ids, m.item);
			p.add(logRow(m.ids, (have ? "[x] " : "[ ] ") + m.item, m.rate + " - meme grind, optional", have ? OK : MUTED, MUTED));
		}
		if (!any)
		{
			p.add(label("This boss has no collection log; first kill completes it.", MUTED));
		}
		return p;
	}

	/** Items that are owed: pets/jars past their debt KC and not yet obtained. Memes are listed in the log, not here. */
	private JPanel debtSection(BossmanData.Rung r)
	{
		JPanel p = card();
		p.add(sectionTitle("Debt board"));
		int kc = plugin.rungKc(r);
		boolean any = false;
		for (BossmanData.DebtItem d : r.log.debtPetsJars)
		{
			if (kc >= d.debtAtKc && !isUnlocked(d.ids, d.item))
			{
				any = true;
				p.add(wrapLabel(d.item + " " + d.rate + " - owed since KC " + d.debtAtKc, BAD));
			}
		}
		if (!any)
		{
			p.add(label("Nothing owed for this boss.", MUTED));
		}
		return p;
	}

	private boolean isUnlocked(List<Integer> ids, String name)
	{
		for (int id : ids)
		{
			if (plugin.getLedger().isUnlocked(id))
			{
				return true;
			}
		}
		for (Ledger.Entry en : plugin.getLedger().unlocks.values())
		{
			if (en.name != null && en.name.equalsIgnoreCase(name))
			{
				return true;
			}
		}
		return false;
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
		text.add(label(title, titleColor));
		text.add(wrapLabel(sub, subColor));
		row.add(text, BorderLayout.CENTER);
		Dimension pref = row.getPreferredSize();
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(36, pref.height)));
		return row;
	}

	private JPanel recentSection()
	{
		JPanel p = card();
		List<Ledger.Entry> all = new ArrayList<>();
		for (Ledger.Entry en : plugin.getLedger().unlocks.values())
		{
			if (!Ledger.KIND_DEFAULT.equals(en.kind))
			{
				all.add(en); // bonds etc. are housekeeping, not unlocks
			}
		}
		p.add(sectionTitle("Recent unlocks (" + all.size() + " total)"));
		List<Ledger.Entry> recent = new ArrayList<>();
		for (int i = all.size() - 1; i >= 0 && recent.size() < 10; i--)
		{
			recent.add(all.get(i));
		}
		if (recent.isEmpty())
		{
			p.add(label("Nothing unlocked yet. Go hit Brutus.", MUTED));
		}
		SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
		for (Ledger.Entry en : recent)
		{
			String src = en.source + (en.kc != null ? " KC " + en.kc : "");
			p.add(wrapLabel(en.name + " - " + src + " - " + fmt.format(new Date(en.time)), Color.WHITE));
		}
		return p;
	}

	// ------------------------------------------------------------------ widgets

	private JPanel card()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		return p;
	}

	private JLabel sectionTitle(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(WARN);
		l.setAlignmentX(LEFT_ALIGNMENT);
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
		JLabel l = new JLabel("<html><body style='width:160px'>" + escape(text) + "</body></html>");
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
