/*
 * Copyright (c) 2019, CodePanter <https://github.com/codepanter> (Another Bronzeman Mode)
 * Copyright (c) 2026, Erik Rahm (Bossman Mode)
 * All rights reserved. BSD-2-Clause, see LICENSE.
 */
package com.bossman;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageCapture;

/**
 * The "item unlocked" banner at the top of the screen, captioned with the source boss and KC. Items unlocked by the
 * same loot (one chest, one kill) share a banner: "+N more" rather than a five-second banner each.
 */
public class UnlockOverlay extends Overlay
{
	private static final long DISPLAY_MS = 5000;
	/** a frame gap longer than this (a loading screen, the client minimised) doesn't count as the banner being seen */
	private static final long MAX_FRAME_GAP_MS = 100;
	/** the banner slides down this far when it appears */
	private static final int SLIDE = 20;
	private static final int CAPTION_GAP = 4;
	private static final Color CAPTION_BG = new Color(0, 0, 0, 170);
	private static final Color CAPTION_FG = new Color(255, 200, 80);

	private static class Banner
	{
		final int itemId;
		final String caption;
		final int tick;
		int more;

		Banner(int itemId, String caption, int tick)
		{
			this.itemId = itemId;
			this.caption = caption;
			this.tick = tick;
		}

		String text()
		{
			String c = caption == null ? "" : caption;
			return more == 0 ? c : c + (c.isEmpty() ? "" : " ") + "(+" + more + " more)";
		}
	}

	private final Client client;
	private final BossmanModePlugin plugin;
	private final Deque<Banner> queue = new ArrayDeque<>();
	private Banner current;
	/** how long the current banner has actually been on screen */
	private long shownMs;
	private long lastFrame;
	private boolean firstFrame;
	private int slide;
	private boolean screenshotUnlock;
	private boolean includeFrame;
	private Font captionFont;

	@Inject private ItemManager itemManager;
	@Inject private ImageCapture imageCapture;

	@Inject
	public UnlockOverlay(Client client, BossmanModePlugin plugin)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_CENTER);
		setLayer(OverlayLayer.ABOVE_WIDGETS);   // over raid reward chests and the like
	}

	/** Queue a banner; an unlock from the same loot as the last one queued joins it. Client thread. */
	public void addItemUnlock(int itemId, String caption)
	{
		int tick = client.getTickCount();
		synchronized (queue)
		{
			Banner last = queue.peekLast();
			if (last != null && last.tick == tick && Objects.equals(last.caption, caption))
			{
				last.more++;
				return;
			}
			queue.add(new Banner(itemId, caption, tick));
		}
	}

	/** Drop the banner showing and anything queued (shutdown, logout, world hop). */
	public void clear()
	{
		synchronized (queue)
		{
			queue.clear();
			current = null;
		}
	}

	public void updateScreenshotUnlock(boolean doScreenshotUnlock, boolean doIncludeFrame)
	{
		screenshotUnlock = doScreenshotUnlock;
		includeFrame = doIncludeFrame;
	}

	/** Save a screenshot of the next frame to the "Bossman Unlocks" folder. */
	public void screenshot(String fileName)
	{
		imageCapture.takeScreenshot("Bossman Unlocks", fileName, includeFrame, false, false);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		BufferedImage banner = plugin.getUnlockImage();
		long now = System.currentTimeMillis();
		if (client.getGameState() != GameState.LOGGED_IN || banner == null)
		{
			lastFrame = 0;   // time away isn't time shown
			return null;
		}
		Banner b;
		synchronized (queue)
		{
			if (current == null)
			{
				current = queue.poll();
				if (current == null)
				{
					return null;
				}
				shownMs = 0;
				slide = -SLIDE;
				firstFrame = true;
				lastFrame = 0;
			}
			b = current;
		}

		if (captionFont == null)
		{
			captionFont = graphics.getFont().deriveFont(Font.BOLD, 11f);
		}
		FontMetrics fm = graphics.getFontMetrics(captionFont);
		String text = b.text();
		int w = Math.max(banner.getWidth(), text.isEmpty() ? 0 : fm.stringWidth(text) + 10);
		int h = banner.getHeight() + (text.isEmpty() ? 0 : CAPTION_GAP + fm.getHeight() + 4);
		if (firstFrame)
		{
			// the overlay is placed using last frame's size: report the size once before drawing, so the banner
			// appears centred instead of hanging off to one side for a frame
			firstFrame = false;
			return new Dimension(w, h);
		}

		int bx = (w - banner.getWidth()) / 2;
		graphics.drawImage(banner, bx, slide, null);
		graphics.drawImage(itemManager.getImage(b.itemId, 1, false), bx + 12, slide + 7, null);
		if (!text.isEmpty())
		{
			Font old = graphics.getFont();
			graphics.setFont(captionFont);
			int tw = fm.stringWidth(text);
			int x = (w - tw) / 2;
			int y = slide + banner.getHeight() + CAPTION_GAP + fm.getAscent() + 2;
			graphics.setColor(CAPTION_BG);
			graphics.fillRoundRect(x - 5, y - fm.getAscent() - 2, tw + 10, fm.getHeight() + 4, 6, 6);
			graphics.setColor(CAPTION_FG);
			graphics.drawString(text, x, y);
			graphics.setFont(old);
		}

		if (slide < 0)
		{
			slide++;
		}
		if (lastFrame != 0)
		{
			shownMs += Math.min(now - lastFrame, MAX_FRAME_GAP_MS);
		}
		lastFrame = now;
		if (shownMs > DISPLAY_MS)
		{
			if (screenshotUnlock)
			{
				screenshot("ItemUnlocked " + itemManager.getItemComposition(b.itemId).getMembersName() + " ");
			}
			synchronized (queue)
			{
				if (current == b)
				{
					current = null;
				}
			}
		}
		return new Dimension(w, h);
	}
}
