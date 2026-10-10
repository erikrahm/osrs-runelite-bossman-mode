/*
 * Bossman Mode — side panel: the ladder (current rung, log progress, KC, debt board) and recent unlocks.
 * BSD-2-Clause — see LICENSE.
 */
package com.bossman;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
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

		body.add(header(idx, rungs.size(), current, r));
		body.add(Box.createVerticalStrut(6));
		body.add(rungCard(r, r == current));
		body.add(Box.createVerticalStrut(6));
		body.add(logSection(r));
		body.add(Box.createVerticalStrut(6));
		body.add(debtSection(r));
		body.add(Box.createVerticalStrut(10));
		body.add(recentSection());
		revalidate();
		repaint();
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
		JLabel name = new JLabel("Rung " + r.rung + " — " + r.boss);
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(complete ? OK : (isCurrent ? WARN : Color.WHITE));
		p.add(name);
		p.add(label("Combat level " + (r.combatLevel != null ? r.combatLevel : "n/a"), MUTED));
		if (r.gate != null && !r.gate.isEmpty())
		{
			p.add(wrapLabel("Gate: " + r.gate, MUTED));
		}
		String status = complete ? "COMPLETE" : (isCurrent ? "IN PROGRESS" : (r.rung != null && plugin.currentRung() != null && r.rung < plugin.currentRung().rung ? "COMPLETE" : "LOCKED"));
		p.add(label(status + "  ·  " + r.completion, complete ? OK : (isCurrent ? WARN : MUTED)));

		int kc = plugin.rungKc(r);
		String kcText = "KC " + kc + (r.log.expectedKc != null && r.log.expectedKc > 0 ? "  (expected ~" + r.log.expectedKc + ")" : "");
		p.add(label(kcText, Color.WHITE));

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
			bar.setString(have + " / " + r.log.gatingItems.size() + " log items");
			bar.setForeground(have == r.log.gatingItems.size() ? OK : WARN);
			bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
			p.add(Box.createVerticalStrut(4));
			p.add(bar);
		}
		if (r.isFirstKillOnly() || r.log.gatingItems.isEmpty())
		{
			JCheckBox done = new JCheckBox("Mark rung complete by hand", plugin.getLedger().manualComplete.contains(r.boss));
			done.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			done.setForeground(MUTED);
			done.addActionListener(e -> plugin.markRungComplete(r, done.isSelected()));
			p.add(Box.createVerticalStrut(4));
			p.add(done);
		}
		return p;
	}

	private JPanel logSection(BossmanData.Rung r)
	{
		JPanel p = card();
		p.add(sectionTitle("Collection log (gating)"));
		if (r.log.gatingItems.isEmpty())
		{
			p.add(label("No log items gate this rung.", MUTED));
			return p;
		}
		for (BossmanData.GatingItem gi : r.log.gatingItems)
		{
			boolean have = plugin.isLogItemUnlocked(gi);
			JPanel row = new JPanel(new BorderLayout(4, 0));
			row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
			JLabel icon = new JLabel();
			icon.setPreferredSize(new Dimension(36, 32));
			if (!gi.ids.isEmpty())
			{
				AsyncBufferedImage img = itemManager.getImage(gi.ids.get(0));
				img.addTo(icon);
			}
			row.add(icon, BorderLayout.WEST);
			JPanel text = new JPanel(new GridLayout(2, 1));
			text.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			text.add(label((have ? "✓ " : "• ") + gi.item, have ? OK : Color.WHITE));
			text.add(label(gi.rate != null ? gi.rate : "", MUTED));
			row.add(text, BorderLayout.CENTER);
			p.add(row);
		}
		return p;
	}

	private JPanel debtSection(BossmanData.Rung r)
	{
		JPanel p = card();
		p.add(sectionTitle("Debt board"));
		int kc = plugin.rungKc(r);
		boolean any = false;
		for (BossmanData.DebtItem d : r.log.debtPetsJars)
		{
			any = true;
			boolean have = false;
			for (Ledger.Entry en : plugin.getLedger().unlocks.values())
			{
				if (en.name != null && en.name.equalsIgnoreCase(d.item))
				{
					have = true;
					break;
				}
			}
			String state = have ? "✓ obtained" : (kc >= d.debtAtKc ? "DEBT (KC " + kc + " ≥ " + d.debtAtKc + ")" : "pending — debt at KC " + d.debtAtKc);
			p.add(wrapLabel(d.item + "  " + d.rate + "  ·  " + state, have ? OK : (kc >= d.debtAtKc ? BAD : MUTED)));
		}
		for (BossmanData.MemeItem m : r.log.memeItems)
		{
			any = true;
			p.add(wrapLabel(m.item + "  " + m.rate + "  ·  meme grind (optional)", MUTED));
		}
		if (!any)
		{
			p.add(label("Nothing on the board for this rung.", MUTED));
		}
		return p;
	}

	private JPanel recentSection()
	{
		JPanel p = card();
		p.add(sectionTitle("Recent unlocks  ·  " + plugin.getLedger().unlocks.size() + " total"));
		List<Ledger.Entry> recent = new ArrayList<>(plugin.getLedger().recent(10));
		if (recent.isEmpty())
		{
			p.add(label("Nothing unlocked yet. Go hit Brutus.", MUTED));
		}
		SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
		for (Ledger.Entry en : recent)
		{
			String src = en.source + (en.kc != null ? " KC " + en.kc : "");
			p.add(wrapLabel(en.name + "  —  " + src + "  ·  " + fmt.format(new Date(en.time)), Color.WHITE));
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
		return l;
	}

	private JLabel label(String text, Color color)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
		l.setHorizontalAlignment(SwingConstants.LEFT);
		return l;
	}

	private JLabel wrapLabel(String text, Color color)
	{
		JLabel l = new JLabel("<html><body style='width:170px'>" + escape(text) + "</body></html>");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
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
