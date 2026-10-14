/*
 * Bossman Mode: what the started, unfinished quests on a boss's quest chain open up: the NPCs they have you fight, the
 * items they need that otherwise only come from shops or ordinary monsters, and the items the quest itself hands you.
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

final class ActiveQuests
{
	private static final Logger log = LoggerFactory.getLogger(ActiveQuests.class);

	/** quest → the standardized names of the NPCs it has you fight */
	private final Map<Quest, List<String>> foes = new HashMap<>();
	/** quest → the items it needs */
	private final Map<Quest, List<Integer>> items = new HashMap<>();
	/** quests seen finished: they can't become unfinished, so their state isn't read again */
	private final Set<Quest> finished = new HashSet<>();
	private Set<String> npcs = Collections.emptySet();
	private Set<Integer> itemIds = Collections.emptySet();

	ActiveQuests(Map<String, BossmanData.QuestNeeds> data)
	{
		for (Map.Entry<String, BossmanData.QuestNeeds> e : data.entrySet())
		{
			Quest quest;
			try
			{
				quest = Quest.valueOf(e.getKey());
			}
			catch (IllegalArgumentException ex)
			{
				// one quest RuneLite renamed must not take the rest of the plugin down with it
				log.warn("Quest data names a quest this RuneLite doesn't know: {}", e.getKey());
				continue;
			}
			List<String> names = new ArrayList<>();
			for (String n : e.getValue().npcs)
			{
				names.add(Text.standardize(n));
			}
			foes.put(quest, names);
			List<Integer> ids = new ArrayList<>();
			for (BossmanData.ItemRef r : e.getValue().items)
			{
				ids.add(r.id);
			}
			items.put(quest, ids);
		}
	}

	/** Standardized names of the NPCs a started quest has you fight. */
	Set<String> npcs()
	{
		return npcs;
	}

	/** Ids of the items a started quest needs. */
	Set<Integer> itemIds()
	{
		return itemIds;
	}

	/** Another account logged in. */
	void reset()
	{
		finished.clear();
		npcs = Collections.emptySet();
		itemIds = Collections.emptySet();
	}

	/**
	 * Re-read each unfinished quest's state. With Quest.getState, which runs a client script: client thread, and not
	 * from inside a menu or script callback.
	 */
	void refresh(Function<Quest, QuestState> state)
	{
		Set<String> names = new HashSet<>();
		Set<Integer> ids = new HashSet<>();
		for (Quest quest : foes.keySet())
		{
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
				names.addAll(foes.get(quest));
				ids.addAll(items.get(quest));
			}
		}
		npcs = names;
		itemIds = ids;
	}
}
