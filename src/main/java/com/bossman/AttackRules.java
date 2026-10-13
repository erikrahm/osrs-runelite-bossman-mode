/*
 * Bossman Mode: which NPCs may be fought, and why. No client state, so it is tested in AttackRulesTest.
 */
package com.bossman;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class AttackRules
{
	/** tutorial progress varp value once Tutorial Island is done; its rats must be fought before then */
	static final int TUTORIAL_DONE = 1000;
	/** spells cast on an NPC that don't fight it */
	private static final Set<String> NON_COMBAT_SPELLS = new HashSet<>(Arrays.asList("monster examine", "dark lure"));

	enum Reason
	{
		TUTORIAL("Tutorial Island isn't finished"),
		SLAYER_TASK("it's your slayer task"),
		BOSS("it's a boss"),
		ARENA("it's in a boss's arena"),
		BOSS_ACCESS("it drops what opens a boss, or joins a boss fight"),
		QUEST("a quest you've started has you fight it"),
		ALSO_ATTACKABLE("it's on your 'Also attackable' list");

		final String text;

		Reason(String text)
		{
			this.text = text;
		}
	}

	private AttackRules()
	{
	}

	/** Attacking an NPC, by hand or with a spell. Using items on NPCs stays: quest hand-ins go through it. */
	static boolean isCombatOption(String option, String target)
	{
		if ("Attack".equals(option) || "Fight".equals(option))
		{
			return true;
		}
		if (!"Cast".equals(option) || target == null)
		{
			return false;
		}
		int arrow = target.indexOf(" -> ");
		String spell = (arrow < 0 ? target : target.substring(0, arrow)).trim().toLowerCase(Locale.ROOT);
		return !NON_COMBAT_SPELLS.contains(spell);
	}

	static boolean tutorialDone(int tutorialProgress)
	{
		return tutorialProgress >= TUTORIAL_DONE;
	}

	/**
	 * Why the NPC may be fought, or null if it may not. {@code standardName} is the NPC's name through Text.standardize;
	 * {@code regionId} is where the NPC stands, taken out of any instance.
	 */
	static Reason reason(int npcId, String standardName, int regionId, boolean slayerTarget, Set<String> questNpcs,
		Set<String> alsoAttackable, BossmanData data)
	{
		if (slayerTarget)
		{
			return Reason.SLAYER_TASK;
		}
		if (data.isNpcSource(standardName))
		{
			return Reason.BOSS;
		}
		if (data.isArenaRegion(regionId))
		{
			return Reason.ARENA;
		}
		if (data.isAccessNpc(npcId, standardName))
		{
			return Reason.BOSS_ACCESS;
		}
		if (questNpcs.contains(standardName))
		{
			return Reason.QUEST;
		}
		if (alsoAttackable.contains(standardName) || alsoAttackable.contains(Integer.toString(npcId)))
		{
			return Reason.ALSO_ATTACKABLE;
		}
		return null;
	}
}
