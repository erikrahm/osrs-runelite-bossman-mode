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

public class QuestFoesTest
{
	private static QuestFoes foes()
	{
		Map<String, BossmanData.QuestKills> data = new HashMap<>();
		BossmanData.QuestKills ds1 = new BossmanData.QuestKills();
		ds1.npcs = Arrays.asList("Elvarg", "Melzar the Mad");
		data.put("DRAGON_SLAYER_I", ds1);
		BossmanData.QuestKills renamed = new BossmanData.QuestKills();
		renamed.npcs = Arrays.asList("Nobody");
		data.put("A_QUEST_RUNELITE_RENAMED", renamed);
		return new QuestFoes(data);
	}

	@Test
	public void aStartedQuestOpensItsFoes()
	{
		QuestFoes f = foes();
		f.refresh(q -> QuestState.IN_PROGRESS);
		assertTrue(f.npcs().contains("elvarg"));
		assertTrue(f.npcs().contains("melzar the mad"));
	}

	@Test
	public void unstartedAndFinishedQuestsDont()
	{
		QuestFoes f = foes();
		f.refresh(q -> QuestState.NOT_STARTED);
		assertTrue(f.npcs().isEmpty());
		f.refresh(q -> QuestState.FINISHED);
		assertTrue(f.npcs().isEmpty());
	}

	@Test
	public void aFinishedQuestIsNotReadAgainUntilReset()
	{
		QuestFoes f = foes();
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
