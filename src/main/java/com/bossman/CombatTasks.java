/*
 * Bossman Mode — reads Combat Achievement state straight from the game.
 *
 * The task list lives in the cache: one enum per tier (3981..3986) listing task structs, each struct carrying
 * the task's numeric id (param 1306) and title (param 1308). Completion is a bit-field across the
 * CA_TASK_COMPLETED_n varps: task n is bit n % 32 of varp n / 32 — the same thing the in-game interface reads.
 * Everything here must run on the client thread.
 */
package com.bossman;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.VarPlayerID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class CombatTasks
{
	private static final Logger log = LoggerFactory.getLogger(CombatTasks.class);

	private static final int[] TIER_ENUMS = {3981, 3982, 3983, 3984, 3985, 3986};   // easy .. grandmaster
	private static final int PARAM_TASK_ID = 1306;
	private static final int PARAM_TASK_NAME = 1308;
	private static final int[] COMPLETED_VARPS = {
		VarPlayerID.CA_TASK_COMPLETED_0, VarPlayerID.CA_TASK_COMPLETED_1, VarPlayerID.CA_TASK_COMPLETED_2,
		VarPlayerID.CA_TASK_COMPLETED_3, VarPlayerID.CA_TASK_COMPLETED_4, VarPlayerID.CA_TASK_COMPLETED_5,
		VarPlayerID.CA_TASK_COMPLETED_6, VarPlayerID.CA_TASK_COMPLETED_7, VarPlayerID.CA_TASK_COMPLETED_8,
		VarPlayerID.CA_TASK_COMPLETED_9, VarPlayerID.CA_TASK_COMPLETED_10, VarPlayerID.CA_TASK_COMPLETED_11,
		VarPlayerID.CA_TASK_COMPLETED_12, VarPlayerID.CA_TASK_COMPLETED_13, VarPlayerID.CA_TASK_COMPLETED_14,
		VarPlayerID.CA_TASK_COMPLETED_15, VarPlayerID.CA_TASK_COMPLETED_16, VarPlayerID.CA_TASK_COMPLETED_17,
		VarPlayerID.CA_TASK_COMPLETED_18, VarPlayerID.CA_TASK_COMPLETED_19, VarPlayerID.CA_TASK_COMPLETED_20,
	};
	private static final Set<Integer> COMPLETED_VARP_SET = new HashSet<>();

	static
	{
		for (int v : COMPLETED_VARPS)
		{
			COMPLETED_VARP_SET.add(v);
		}
	}

	private final Client client;
	/** task id → title, read once from the cache */
	private Map<Integer, String> names = Collections.emptyMap();

	CombatTasks(Client client)
	{
		this.client = client;
	}

	static boolean isCompletionVarp(int varpId)
	{
		return COMPLETED_VARP_SET.contains(varpId);
	}

	/** Load the task table from the cache if it hasn't been yet. Client thread. */
	private boolean ensureLoaded()
	{
		if (!names.isEmpty())
		{
			return true;
		}
		Map<Integer, String> out = new HashMap<>();
		for (int enumId : TIER_ENUMS)
		{
			EnumComposition tier = client.getEnum(enumId);
			if (tier == null)
			{
				continue;
			}
			for (int structId : tier.getIntVals())
			{
				StructComposition s = client.getStructComposition(structId);
				if (s == null)
				{
					continue;
				}
				String name = s.getStringValue(PARAM_TASK_NAME);
				if (name != null && !name.isEmpty())
				{
					out.put(s.getIntValue(PARAM_TASK_ID), name);
				}
			}
		}
		if (out.isEmpty())
		{
			log.debug("combat achievement enums not available yet");
			return false;
		}
		names = out;
		log.debug("loaded {} combat achievement tasks", out.size());
		return true;
	}

	/** Titles of every task the account has completed, per the game's own state. Client thread. Empty if the cache isn't readable yet. */
	Set<String> completedNames()
	{
		if (!ensureLoaded())
		{
			return Collections.emptySet();
		}
		int[] bits = new int[COMPLETED_VARPS.length];
		for (int i = 0; i < bits.length; i++)
		{
			bits[i] = client.getVarpValue(COMPLETED_VARPS[i]);
		}
		Set<String> done = new HashSet<>();
		for (Map.Entry<Integer, String> en : names.entrySet())
		{
			int id = en.getKey();
			int word = id / 32;
			if (word >= 0 && word < bits.length && ((bits[word] >>> (id % 32)) & 1) == 1)
			{
				done.add(en.getValue());
			}
		}
		return done;
	}
}
