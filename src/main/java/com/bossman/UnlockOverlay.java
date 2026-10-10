/*
 * Copyright (c) 2019, CodePanter <https://github.com/codepanter> (Another Bronzeman Mode)
 * Copyright (c) 2026, Erik Rahm (Bossman Mode)
 * All rights reserved. BSD-2-Clause — see LICENSE.
 */
package com.bossman;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageCapture;
import net.runelite.client.util.ImageUploadStyle;

/** The "item unlocked" banner at the top of the screen, captioned with the source boss and KC. */
public class UnlockOverlay extends Overlay
{
	private static final Logger log = LoggerFactory.getLogger(UnlockOverlay.class);

	private static final long DISPLAY_MS = 5000;

	private static class Pending
	{
		final int itemId;
		final String caption;

		Pending(int itemId, String caption)
		{
			this.itemId = itemId;
			this.caption = caption;
		}
	}

	private final Client client;
	private final BossmanModePlugin plugin;
	private final Deque<Pending> queue = new ArrayDeque<>();
	private Pending current;
	private long displayTime;
	private int displayY;
	private boolean screenshotUnlock;
	private boolean includeFrame;

	@Inject private ItemManager itemManager;
	@Inject private ImageCapture imageCapture;
	@Inject private ClientUI clientUi;
	@Inject private ScheduledExecutorService executor;
	@Inject private DrawManager drawManager;

	@Inject
	public UnlockOverlay(Client client, BossmanModePlugin plugin)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_CENTER);
	}

	public void addItemUnlock(int itemId, String caption)
	{
		synchronized (queue)
		{
			queue.add(new Pending(itemId, caption));
		}
	}

	public void updateScreenshotUnlock(boolean doScreenshotUnlock, boolean doIncludeFrame)
	{
		screenshotUnlock = doScreenshotUnlock;
		includeFrame = doIncludeFrame;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (client.getGameState() != GameState.LOGGED_IN || plugin.getUnlockImage() == null)
		{
			return null;
		}
		if (current == null)
		{
			synchronized (queue)
			{
				current = queue.poll();
			}
			if (current == null)
			{
				return null;
			}
			displayTime = System.currentTimeMillis();
			displayY = -20;
			return null;
		}

		BufferedImage banner = plugin.getUnlockImage();
		graphics.drawImage(banner, -62, displayY, null);
		graphics.drawImage(itemManager.getImage(current.itemId, 1, false), -50, displayY + 7, null);

		if (current.caption != null && !current.caption.isEmpty())
		{
			Font old = graphics.getFont();
			graphics.setFont(old.deriveFont(Font.BOLD, 11f));
			FontMetrics fm = graphics.getFontMetrics();
			int w = fm.stringWidth(current.caption);
			int x = -62 + (banner.getWidth() - w) / 2;
			int y = displayY + banner.getHeight() + 12;
			graphics.setColor(new Color(0, 0, 0, 170));
			graphics.fillRoundRect(x - 5, y - fm.getAscent() - 2, w + 10, fm.getHeight() + 4, 6, 6);
			graphics.setColor(new Color(255, 200, 80));
			graphics.drawString(current.caption, x, y);
			graphics.setFont(old);
		}

		if (displayY < 10)
		{
			displayY++;
		}
		if (System.currentTimeMillis() > displayTime + DISPLAY_MS)
		{
			if (screenshotUnlock)
			{
				String itemName = itemManager.getItemComposition(current.itemId).getMembersName();
				takeScreenshot("ItemUnlocked " + itemName + " ");
			}
			current = null;
		}
		return null;
	}

	private void takeScreenshot(String fileName)
	{
		Consumer<Image> imageCallback = (img) -> executor.submit(() -> takeScreenshot(fileName, img));
		drawManager.requestNextFrameListener(imageCallback);
	}

	private void takeScreenshot(String fileName, Image image)
	{
		BufferedImage screenshot = includeFrame
			? new BufferedImage(clientUi.getWidth(), clientUi.getHeight(), BufferedImage.TYPE_INT_ARGB)
			: new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
		Graphics graphics = screenshot.getGraphics();
		int gameOffsetX = 0;
		int gameOffsetY = 0;
		if (includeFrame)
		{
			try
			{
				SwingUtilities.invokeAndWait(() -> clientUi.paint(graphics));
			}
			catch (InterruptedException | InvocationTargetException e)
			{
				log.warn("unable to paint client UI on screenshot", e);
			}
			final Point canvasOffset = clientUi.getCanvasOffset();
			gameOffsetX = (int) canvasOffset.getX();
			gameOffsetY = (int) canvasOffset.getY();
		}
		graphics.drawImage(image, gameOffsetX, gameOffsetY, null);
		imageCapture.takeScreenshot(screenshot, fileName, "Bossman Unlocks", false, ImageUploadStyle.NEITHER);
	}
}
