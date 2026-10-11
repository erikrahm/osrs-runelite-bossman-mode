/*
 * Bossman Mode: dims locked items in the inventory, bank and equipment and marks free (tool/access) items.
 * BSD-2-Clause, see LICENSE.
 */
package com.bossman;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

@Singleton   // LockedBankEdgeOverlay draws with this same instance
public class LockedItemOverlay extends WidgetItemOverlay
{
	private static final Color DIM = new Color(0, 0, 0, 150);
	private static final Color LOCK = new Color(220, 60, 60, 230);
	private static final Color TOOL = new Color(90, 200, 120, 230);
	private static final Color ACCESS = new Color(90, 160, 230, 230);
	private static final Color BOND = new Color(230, 200, 90, 230);

	private final BossmanModePlugin plugin;
	private final BossmanModeConfig config;
	private final ItemManager itemManager;

	@Inject
	public LockedItemOverlay(BossmanModePlugin plugin, BossmanModeConfig config, ItemManager itemManager)
	{
		this.plugin = plugin;
		this.config = config;
		this.itemManager = itemManager;
		showOnInventory();
		showOnBank();
		showOnEquipment();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		Rectangle b = widgetItem.getCanvasBounds();
		if (b != null)
		{
			drawMarks(graphics, itemId, b);
		}
	}

	/** Dim + lock marker on a locked item, or the free-item marker; shared with {@link LockedBankEdgeOverlay}. */
	void drawMarks(Graphics2D graphics, int itemId, Rectangle b)
	{
		if (itemId <= 1 || itemId == ItemID.BANK_FILLER)
		{
			return;
		}
		LadderRules rules = plugin.getRules();
		if (rules.isAllowed(itemId))
		{
			if (config.markFree())
			{
				FreeKind kind = rules.freeKind(itemId);
				if (kind != null)
				{
					graphics.setColor(markerColor(kind));
					graphics.fillRect(b.x + b.width - 5, b.y + b.height - 5, 4, 4);
				}
			}
			return;
		}
		if (!config.dimLocked())
		{
			return;
		}
		if (!isPlaceholder(itemId))
		{
			// the game already greys a placeholder out; it only needs the lock marker
			graphics.setColor(DIM);
			graphics.fillRect(b.x, b.y, b.width, b.height);
		}
		// small padlock-ish marker in the bottom-right corner, clear of the stack count drawn top-left
		graphics.setColor(LOCK);
		int lx = b.x + b.width - 9;
		int ly = b.y + b.height - 8;
		graphics.fillRect(lx, ly, 7, 6);
		graphics.drawRect(lx + 1, ly - 3, 4, 4);
	}

	private static Color markerColor(FreeKind kind)
	{
		switch (kind)
		{
			case TOOL:
				return TOOL;
			case BOND:
				return BOND;
			default:
				return ACCESS;   // access gear and slayer tools: both only get you to (or through) a boss
		}
	}

	private boolean isPlaceholder(int itemId)
	{
		ItemComposition comp = itemManager.getItemComposition(itemId);
		return comp != null && comp.getPlaceholderTemplateId() != -1;
	}
}
