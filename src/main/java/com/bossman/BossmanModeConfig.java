/*
 * Copyright (c) 2019, CodePanter <https://github.com/codepanter> (Another Bronzeman Mode)
 * Copyright (c) 2026, Erik Rahm (Bossman Mode)
 * All rights reserved. BSD-2-Clause — see LICENSE.
 */
package com.bossman;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(BossmanModePlugin.CONFIG_GROUP)
public interface BossmanModeConfig extends Config
{
	@ConfigSection(name = "Unlocks", description = "How items get unlocked", position = 0)
	String unlocksSection = "unlocks";

	@ConfigSection(name = "Restrictions", description = "What locked items can't do", position = 1)
	String restrictionsSection = "restrictions";

	@ConfigSection(name = "Display", description = "Popups, overlays and chat", position = 2)
	String displaySection = "display";

	@ConfigItem(keyName = "extraSources", name = "Extra unlock sources", position = 0, section = unlocksSection,
		description = "Comma-separated NPC or loot-tracker event names that may unlock items, in addition to the bundled boss list (e.g. a boss released after this version).")
	default String extraSources()
	{
		return "";
	}

	@ConfigItem(keyName = "freeTools", name = "Tools are always unlocked", position = 1, section = unlocksSection,
		description = "Every base-tier item on the wiki Tool page (axes, pickaxes, nets, rods, moulds, farming and hunter kit, light sources) is usable without a boss drop. Turn off for a stricter mode where even tools must drop.")
	default boolean freeTools()
	{
		return true;
	}

	@ConfigItem(keyName = "freeAccessGear", name = "Access gear is always unlocked", position = 2, section = unlocksSection,
		description = "Slayer access gear, keys/totems, grapple and rope are usable without a boss drop (they only get you to the boss). Turn off for a stricter mode.")
	default boolean freeAccessGear()
	{
		return true;
	}

	@ConfigItem(keyName = "receiptRules", name = "Completion-reward unlocks", position = 3, section = unlocksSection,
		description = "Unlock Fire/Infernal cape + Tokkul on receipt, and anything received in the Gauntlet lobby (the Gauntlet chest is not a tracked loot source).")
	default boolean receiptRules()
	{
		return true;
	}

	@ConfigItem(keyName = "unlockBond", name = "Bonds are always unlocked", position = 4, section = unlocksSection,
		description = "Keep Old School bonds unlocked so membership can be bought.")
	default boolean unlockBond()
	{
		return true;
	}

	@ConfigItem(keyName = "restrictGeBuy", name = "Hide locked items in GE search", position = 0, section = restrictionsSection,
		description = "Locked items are greyed out and unclickable in the Grand Exchange search results.")
	default boolean restrictGeBuy()
	{
		return true;
	}

	@ConfigItem(keyName = "restrictGeSell", name = "Block selling locked items", position = 1, section = restrictionsSection,
		description = "Removes the 'Offer' option on locked items in the Grand Exchange — only unlocked items may be sold (rule 4).")
	default boolean restrictGeSell()
	{
		return true;
	}

	@ConfigItem(keyName = "allowTrading", name = "Allow player trading", position = 2, section = restrictionsSection,
		description = "Allow trade requests (off by default — a bossman stands alone).")
	default boolean allowTrading()
	{
		return false;
	}

	@ConfigItem(keyName = "dimLocked", name = "Dim locked items", position = 0, section = displaySection,
		description = "Draw locked items darker in the inventory, bank and equipment, with a lock marker.")
	default boolean dimLocked()
	{
		return true;
	}

	@ConfigItem(keyName = "markFree", name = "Mark tools / access gear", position = 1, section = displaySection,
		description = "Draw a small marker on items that are free under the Tool rule or the access-equipment rule.")
	default boolean markFree()
	{
		return false;
	}

	@ConfigItem(keyName = "unlockPopup", name = "Unlock popup", position = 2, section = displaySection,
		description = "Show the 'item unlocked' banner with the source boss and KC.")
	default boolean unlockPopup()
	{
		return true;
	}

	@ConfigItem(keyName = "screenshotUnlock", name = "Screenshot unlocks", position = 3, section = displaySection,
		description = "Take a screenshot whenever a new item is unlocked.")
	default boolean screenshotUnlock()
	{
		return false;
	}

	@ConfigItem(keyName = "includeFrame", name = "Include client frame", position = 4, section = displaySection,
		description = "Include the client frame in unlock screenshots.")
	default boolean includeFrame()
	{
		return false;
	}

	@ConfigItem(keyName = "sendChatMessage", name = "Chat message on unlock", position = 5, section = displaySection,
		description = "Send a chat message when a new item is unlocked.")
	default boolean sendChatMessage()
	{
		return true;
	}

	@ConfigItem(keyName = "sendNotification", name = "Notify on unlock", position = 6, section = displaySection,
		description = "Send a desktop notification when a new item is unlocked.")
	default boolean sendNotification()
	{
		return false;
	}

	@ConfigItem(keyName = "resetCommand", name = "Enable !bmreset", position = 7, section = displaySection,
		description = "Enables the !bmreset chat command, which wipes the ledger (unlocks, KC, manual completions).")
	default boolean resetCommand()
	{
		return false;
	}
}
