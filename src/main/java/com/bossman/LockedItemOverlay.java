/*
 * Bossman Mode — dims locked items in the inventory, bank and equipment and marks free (tool/access) items.
 * BSD-2-Clause — see LICENSE.
 */
package com.bossman;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

public class LockedItemOverlay extends WidgetItemOverlay
{
	private static final Color DIM = new Color(0, 0, 0, 150);
	private static final Color LOCK = new Color(220, 60, 60, 230);
	private static final Color TOOL = new Color(90, 200, 120, 230);
	private static final Color ACCESS = new Color(90, 160, 230, 230);

	private final BossmanModePlugin plugin;
	private final BossmanModeConfig config;

	@Inject
	public LockedItemOverlay(BossmanModePlugin plugin, BossmanModeConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		showOnInventory();
		showOnBank();
		showOnEquipment();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		if (itemId <= 1)
		{
			return;
		}
		Rectangle b = widgetItem.getCanvasBounds();
		if (b == null)
		{
			return;
		}
		LadderRules rules = plugin.getRules();
		if (rules.isAllowed(itemId))
		{
			if (config.markFree())
			{
				String kind = rules.freeKind(itemId);
				if (kind != null)
				{
					graphics.setColor("tool".equals(kind) ? TOOL : ACCESS);
					graphics.fillRect(b.x + b.width - 5, b.y + b.height - 5, 4, 4);
				}
			}
			return;
		}
		if (!config.dimLocked())
		{
			return;
		}
		graphics.setColor(DIM);
		graphics.fillRect(b.x, b.y, b.width, b.height);
		// small padlock-ish marker in the bottom-right corner, clear of the stack count drawn top-left
		graphics.setColor(LOCK);
		int lx = b.x + b.width - 9;
		int ly = b.y + b.height - 8;
		graphics.fillRect(lx, ly, 7, 6);
		graphics.drawRect(lx + 1, ly - 3, 4, 4);
	}
}
