/*
 * Bossman Mode: why an item is usable without a boss drop.
 */
package com.bossman;

enum FreeKind
{
	/** base tier of the wiki Tool page */
	TOOL,
	/** gear that only gets you to a boss: slayer access gear, keys and totems, grapple, rope */
	ACCESS,
	/** consumable slayer tools a task monster needs to be finished (rock hammer, bag of salt, ...) */
	SLAYER,
	/** Old School bonds, so membership can be bought */
	BOND
}
