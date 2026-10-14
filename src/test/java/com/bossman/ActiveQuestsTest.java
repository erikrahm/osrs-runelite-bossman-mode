package com.bossman;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import org.junit.Test;
import static org.junit.Assert.*;

public class ActiveQuestsTest
{
	private static ActiveQuests foes()
	{
		Map<String, BossmanData.QuestNeeds> data = new HashMap<>();
		BossmanData.QuestNeeds ds1 = new BossmanData.QuestNeeds();
		ds1.npcs = Arrays.asList("Elvarg", "Melzar the Mad");
		BossmanData.ItemRef mindBomb = new BossmanData.ItemRef();
		mindBomb.id = 1907;
		ds1.items = Arrays.asList(mindBomb);
		data.put("DRAGON_SLAYER_I", ds1);
		BossmanData.QuestNeeds renamed = new BossmanData.QuestNeeds();
		renamed.npcs = Arrays.asList("Nobody");
		data.put("A_QUEST_RUNELITE_RENAMED", renamed);
		return new ActiveQuests(data);
	}

	@Test
	public void aStartedQuestOpensItsFoesAndItems()
	{
		ActiveQuests f = foes();
		f.refresh(q -> QuestState.IN_PROGRESS);
		assertTrue(f.npcs().contains("elvarg"));
		assertTrue(f.npcs().contains("melzar the mad"));
		assertTrue(f.itemIds().contains(1907));
	}

	@Test
	public void unstartedAndFinishedQuestsDont()
	{
		ActiveQuests f = foes();
		f.refresh(q -> QuestState.NOT_STARTED);
		assertTrue(f.npcs().isEmpty());
		f.refresh(q -> QuestState.FINISHED);
		assertTrue(f.npcs().isEmpty());
		assertTrue(f.itemIds().isEmpty());
	}

	@Test
	public void aFinishedQuestIsNotReadAgainUntilReset()
	{
		ActiveQuests f = foes();
		Map<Quest, AtomicInteger> reads = new EnumMap<>(Quest.class);
		f.refresh(q ->
		{
			reads.computeIfAbsent(q, k -> new AtomicInteger()).incrementAndGet();
			return QuestState.FINISHED;
		});
		f.refresh(q ->
		{
			reads.computeIfAbsent(q, k -> new AtomicInteger()).incrementAndGet();
			return QuestState.IN_PROGRESS;
		});
		assertEquals(1, reads.get(Quest.DRAGON_SLAYER_I).get());
		assertTrue(f.npcs().isEmpty());
		f.reset();
		f.refresh(q -> QuestState.IN_PROGRESS);
		assertTrue(f.npcs().contains("elvarg"));
	}
}
