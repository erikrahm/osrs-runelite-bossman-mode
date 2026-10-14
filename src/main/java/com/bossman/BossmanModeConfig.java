/*
 * Copyright (c) 2019, CodePanter <https://github.com/codepanter> (Another Bronzeman Mode)
 * Copyright (c) 2026, Erik Rahm (Bossman Mode)
 * All rights reserved. BSD-2-Clause, see LICENSE.
 */
package com.bossman;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(BossmanModePlugin.CONFIG_GROUP)
public interface BossmanModeConfig extends Config
{
	@ConfigSection(name = "Unlocks", description = "How items get unlocked", position = 0)
	String unlocksSection = "unlocks";

	@ConfigSection(name = "Ladder", description = "How the boss ladder decides a boss is done", position = 1)
	String ladderSection = "ladder";

	@ConfigSection(name = "Restrictions", description = "What locked items can't do", position = 2)
	String restrictionsSection = "restrictions";

	@ConfigSection(name = "Display", description = "Popups, overlays and chat", position = 3)
	String displaySection = "display";

	@ConfigSection(name = "Advanced", description = "Destructive commands", position = 4, closedByDefault = true)
	String advancedSection = "advanced";

	@ConfigItem(keyName = "extraSources", name = "Extra unlock sources", position = 0, section = unlocksSection,
		description = "Comma-separated NPC or loot-tracker event names that<br>may unlock items, in addition to the bundled boss<br>list (e.g. a boss released after this version). NPCs<br>named here can also be fought.")
	default String extraSources()
	{
		return "";
	}

	@ConfigItem(keyName = "freeTools", name = "Tools are always unlocked", position = 1, section = unlocksSection,
		description = "Every base-tier item on the wiki Tool page (axes,<br>pickaxes, nets, rods, moulds, farming and hunter kit,<br>light sources) and the basic containers (vials, buckets,<br>jugs, bowls, plant pots, empty or holding water or soil)<br>is usable without a boss drop. Turn off for a stricter<br>mode where even tools must drop.")
	default boolean freeTools()
	{
		return true;
	}

	@ConfigItem(keyName = "freeAccessGear", name = "Access gear is always unlocked", position = 2, section = unlocksSection,
		description = "Slayer access gear, keys/totems, grapple and rope<br>are usable without a boss drop (they only get you<br>to the boss). Turn off for a stricter mode.")
	default boolean freeAccessGear()
	{
		return true;
	}

	@ConfigItem(keyName = "freeSlayerTools", name = "Slayer tools are always unlocked", position = 3, section = unlocksSection,
		description = "Consumable slayer tools a task monster needs to be<br>finished (rock hammer, bag of salt, ice cooler,<br>fungicide, slayer bell, lit bug lantern) are usable<br>without a boss drop. Turn off for a stricter mode.")
	default boolean freeSlayerTools()
	{
		return true;
	}

	@ConfigItem(keyName = "freeBossCraftables", name = "Boss area craftables are always unlocked", position = 4, section = unlocksSection,
		description = "Items made or handed out inside a boss's area for<br>use there (Wintertodt's rejuvenation potions, Chambers<br>of Xeric potions and food, Gauntlet gear, Tombs of<br>Amascut supplies) are usable without a boss drop.")
	default boolean freeBossCraftables()
	{
		return true;
	}

	@ConfigItem(keyName = "receiptRules", name = "Completion-reward unlocks", position = 5, section = unlocksSection,
		description = "Unlock the Fire and Infernal cape on receipt (with the Tokkul<br>that comes with them), and anything received in the Gauntlet<br>lobby (the Gauntlet chest is not a tracked loot source). Bank<br>withdrawals and items you take off don't count.")
	default boolean receiptRules()
	{
		return true;
	}

	@ConfigItem(keyName = "importCollectionLog", name = "Import from collection log", position = 6, section = unlocksSection,
		description = "When you open a boss tab in the in-game collection<br>log, items shown as obtained are recorded as unlocks<br>(for drops you had before installing the plugin).")
	default boolean importCollectionLog()
	{
		return true;
	}

	@ConfigItem(keyName = "unlockBond", name = "Bonds are always unlocked", position = 7, section = unlocksSection,
		description = "Old School bonds are usable without a boss drop, so membership<br>can be bought. Takes effect immediately either way.")
	default boolean unlockBond()
	{
		return true;
	}

	@Range(min = 2)
	@ConfigItem(keyName = "memeThreshold", name = "Meme-grind threshold", position = 0, section = ladderSection,
		description = "Collection-log items at 1 in this many kills or<br>rarer are 'meme grinds': shown in the log but<br>optional, so they never block the next boss.<br>Pets and jars are handled separately (backlog).")
	default int memeThreshold()
	{
		return 5000;
	}

	@ConfigItem(keyName = "petsGate", name = "Pets gate the ladder", position = 1, section = ladderSection,
		description = "Require a boss's pet before the next boss can<br>be started. Off by default: pets go on the<br>backlog once your KC reaches their drop rate.")
	default boolean petsGate()
	{
		return false;
	}

	@ConfigItem(keyName = "jarsGate", name = "Jars gate the ladder", position = 2, section = ladderSection,
		description = "Require a boss's jar before the next boss can<br>be started. Off by default: jars go on the<br>backlog once your KC reaches their drop rate.")
	default boolean jarsGate()
	{
		return false;
	}

	@ConfigItem(keyName = "caGate", name = "Combat Achievements gate the ladder", position = 3, section = ladderSection,
		description = "Require every Combat Achievement task for a boss<br>before the next boss can be started. Completion is<br>read from the game's own task state (so tasks done<br>before the plugin count). Note some tasks need items<br>the ruleset forbids (anti-dragon shield, salamanders,<br>demonbane weapons, chinchompas, salve amulet).")
	default boolean caGate()
	{
		return false;
	}

	@ConfigItem(keyName = "restrictGeBuy", name = "Grey out locked items in GE search", position = 0, section = restrictionsSection,
		description = "Locked items are greyed out and unclickable<br>in the Grand Exchange search results.")
	default boolean restrictGeBuy()
	{
		return true;
	}

	@ConfigItem(keyName = "restrictGeSell", name = "Block selling locked items", position = 1, section = restrictionsSection,
		description = "Removes the 'Offer' option on locked items in the Grand<br>Exchange: only unlocked items may be sold.")
	default boolean restrictGeSell()
	{
		return true;
	}

	@ConfigItem(keyName = "restrictShops", name = "Block buying locked items in shops", position = 2, section = restrictionsSection,
		description = "Removes the 'Buy' options on locked items in<br>every shop. Unlocked items can still be bought<br>(e.g. bars of a type a boss has dropped).")
	default boolean restrictShops()
	{
		return true;
	}

	@ConfigItem(keyName = "blockLockedActions", name = "Block using locked items", position = 3, section = restrictionsSection,
		description = "Removes the actions listed below from locked items<br>(inventory, bank and equipment screens), so a locked<br>item can be carried and dropped but not used.")
	default boolean blockLockedActions()
	{
		return true;
	}

	@ConfigItem(keyName = "blockedActions", name = "Blocked actions", position = 4, section = restrictionsSection,
		description = "Comma-separated menu options removed from locked items.<br>'Use' is not in the default list because quest hand-ins and<br>skilling both go through it; add it for a stricter mode.")
	default String blockedActions()
	{
		return "Wield, Wear, Equip, Eat, Drink, Bury, Scatter, Rub, Light, Break, Teleport";
	}

	@ConfigItem(keyName = "blockLockedBanking", name = "Locked items can't be banked", position = 5, section = restrictionsSection,
		description = "Removes the deposit options from locked items at banks and<br>deposit boxes, and stops 'Deposit inventory' and 'Deposit<br>worn items' while you carry or wear one. Items already in<br>the bank can still be taken out.")
	default boolean blockLockedBanking()
	{
		return true;
	}

	@ConfigItem(keyName = "allowTrading", name = "Allow player trading", position = 6, section = restrictionsSection,
		description = "Allow trade requests (off by default — a bossman stands alone).")
	default boolean allowTrading()
	{
		return false;
	}

	@ConfigItem(keyName = "restrictCombat", name = "Only fight bosses", position = 7, section = restrictionsSection,
		description = "Removes 'Attack' and combat spells from NPCs that aren't<br>bosses. Still attackable: anything in a boss's arena,<br>monsters that drop a boss's key or KC, your slayer task<br>(needs the Slayer plugin on) and NPCs fought in a started<br>quest on the way to a ladder boss. Type !bmattack and an<br>NPC's name to see why. Auto-retaliate still fights back:<br>turn it off.")
	default boolean restrictCombat()
	{
		return true;
	}

	@ConfigItem(keyName = "extraAttackable", name = "Additional attackable enemies", position = 8, section = restrictionsSection,
		description = "Comma-separated NPC names or NPC ids (from the<br>wiki infobox) that may always be attacked, on top of<br>the bundled lists.")
	default String extraAttackable()
	{
		return "";
	}

	@ConfigItem(keyName = "attackNoticeShown", name = "", description = "", hidden = true)
	default boolean attackNoticeShown()
	{
		return false;
	}

	@ConfigItem(keyName = "dimLocked", name = "Dim locked items", position = 0, section = displaySection,
		description = "Draw locked items darker in the inventory,<br>bank and equipment, with a lock marker. Items a<br>started quest needs get a quest icon instead.")
	default boolean dimLocked()
	{
		return true;
	}

	@ConfigItem(keyName = "markFree", name = "Mark tools / access gear", position = 1, section = displaySection,
		description = "Draw a small marker on items that are free under<br>the Tool, access-equipment, slayer-tool or boss area<br>craftable rules.")
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
		description = "Take a screenshot whenever a new item is<br>unlocked: as its banner ends when the popup is<br>on, right away (one per loot) when it's off.")
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

	@ConfigItem(keyName = "resetCommand", name = "Enable !bmreset", position = 0, section = advancedSection,
		description = "Enables the !bmreset chat command, which wipes the<br>ledger (unlocks, KC, backlog, manual completions). A<br>backup is saved first; if it can't be, nothing is wiped.")
	default boolean resetCommand()
	{
		return false;
	}
}
