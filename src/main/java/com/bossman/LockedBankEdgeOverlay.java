/*
 * Bossman Mode — lock marks for bank items cut off at the top of the bank's scroll area.
 * BSD-2-Clause — see LICENSE.
 */
package com.bossman;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * RuneLite hands item overlays the bank items it drew, but leaves out a row cut off at the top of the scroll area
 * (rows cut off at the bottom are included). Without this those items look unlocked while half in view. Drawn once
 * per frame after the bank, with the same marks as {@link LockedItemOverlay}, clipped to the scroll area.
 */
public class LockedBankEdgeOverlay extends Overlay
{
	private final Client client;
	private final LockedItemOverlay marks;

	@Inject
	public LockedBankEdgeOverlay(Client client, LockedItemOverlay marks)
	{
		this.client = client;
		this.marks = marks;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		drawAfterInterface(InterfaceID.BANKMAIN);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (items == null || items.isHidden())
		{
			return null;
		}
		Widget[] children = items.getDynamicChildren();
		if (children == null)
		{
			return null;
		}
		Rectangle view = items.getBounds();
		Shape clip = graphics.getClip();
		graphics.clip(view);
		for (Widget w : children)
		{
			Rectangle b = w.getBounds();
			if (w.getItemId() > 0 && !w.isSelfHidden() && b.y < view.y && b.y + b.height > view.y)
			{
				marks.drawMarks(graphics, w.getItemId(), b);
			}
		}
		graphics.setClip(clip);
		return null;
	}
}
