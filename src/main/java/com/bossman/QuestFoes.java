/*
 * Bossman Mode: the NPCs a started, unfinished quest on a boss's quest chain has you fight.
 */
package com.bossman;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.client.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class QuestFoes
{
	private static final Logger log = LoggerFactory.getLogger(QuestFoes.class);

	/** quest → the standardized names of the NPCs it has you fight */
	private final Map<Quest, List<String>> kills = new HashMap<>();
	/** quests seen finished: they can't become unfinished, so their state isn't read again */
	private final Set<Quest> finished = new HashSet<>();
	private Set<String> npcs = Collections.emptySet();

	QuestFoes(Map<String, BossmanData.QuestKills> data)
	{
		for (Map.Entry<String, BossmanData.QuestKills> e : data.entrySet())
		{
			Quest quest;
			try
			{
				quest = Quest.valueOf(e.getKey());
			}
			catch (IllegalArgumentException ex)
			{
				// one quest RuneLite renamed must not take the rest of the plugin down with it
				log.warn("attackable.json names a quest this RuneLite doesn't know: {}", e.getKey());
				continue;
			}
			List<String> names = new ArrayList<>();
			for (String n : e.getValue().npcs)
			{
				names.add(Text.standardize(n));
			}
			kills.put(quest, names);
		}
	}

	/** Standardized names. */
	Set<String> npcs()
	{
		return npcs;
	}

	/** Another account logged in. */
	void reset()
	{
		finished.clear();
		npcs = Collections.emptySet();
	}

	/**
	 * Re-read each unfinished quest's state. With Quest.getState, which runs a client script: client thread, and not
	 * from inside a menu or script callback.
	 */
	void refresh(Function<Quest, QuestState> state)
	{
		Set<String> names = new HashSet<>();
		for (Map.Entry<Quest, List<String>> e : kills.entrySet())
		{
			Quest quest = e.getKey();
			if (finished.contains(quest))
			{
				continue;
			}
			QuestState s = state.apply(quest);
			if (s == QuestState.FINISHED)
			{
				finished.add(quest);
			}
			else if (s == QuestState.IN_PROGRESS)
			{
				names.addAll(e.getValue());
			}
		}
		npcs = names;
	}
}
