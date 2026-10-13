/*
 * Bossman Mode: why an item is usable without a boss drop.
 */
package com.bossman;

enum FreeKind
{
	/** base tier of the wiki Tool page, and basic containers empty or holding water or soil */
	TOOL,
	/** gear that only gets you to a boss: slayer access gear, keys and totems, grapple, rope */
	ACCESS,
	/** consumable slayer tools a task monster needs to be finished (rock hammer, bag of salt, ...) */
	SLAYER,
	/** made or handed out inside a boss's area for use there (Wintertodt's potions, raid supplies, Gauntlet gear) */
	BOSS_CRAFTABLE,
	/** Old School bonds, so membership can be bought */
	BOND
}
