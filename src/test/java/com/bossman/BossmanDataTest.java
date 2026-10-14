package com.bossman;

import com.google.gson.Gson;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class BossmanDataTest
{
	private static BossmanData load()
	{
		BossmanData d = new BossmanData();
		d.load(new Gson());
		return d;
	}

	@Test
	public void bundledDataLoads()
	{
		BossmanData d = load();
		assertTrue(d.getFreeItems().size() > 50);
		assertTrue(d.isNpcSource("Brutus"));
		assertTrue(d.isNpcSource("calvar'ion"));
		assertFalse(d.isNpcSource("Hill Giant"));
		assertTrue(d.isEventSource("Barrows"));
		assertFalse(d.isEventSource("Brimstone Chest"));
		assertEquals(67, d.orderedRungs().size());
		assertEquals("Brutus", d.orderedRungs().get(0).boss);
		assertEquals("The Mimic", d.orderedRungs().get(5).boss);
		assertTrue(d.orderedRungs().get(5).isFirstKillOnly());
		assertEquals(1351, (int) d.getTools().groups.get("general").get(0).id); // bronze axe
	}

	@Test
	public void chatNamesFindTheirSource()
	{
		BossmanData d = load();
		assertEquals("The Nightmare", d.canonicalSource("Nightmare"));
		assertEquals("Phosani's Nightmare", d.canonicalSource("Phosani's Nightmare"));
		assertEquals("Deranged archaeologist", d.canonicalSource("Deranged Archaeologist"));
		assertEquals("Crystalline Hunllef", d.canonicalSource("Gauntlet"));
		assertEquals("Corrupted Hunllef", d.canonicalSource("Corrupted Gauntlet"));
		assertEquals("Barrows", d.canonicalSource("barrows"));
		assertNull(d.canonicalSource("Hill Giant"));
	}

	@Test
	public void extraSourcesAddWithoutDroppingBundledOnes()
	{
		BossmanData d = load();
		d.setExtraSources("Some New Boss, ");
		assertTrue(d.isNpcSource("some new boss"));
		assertTrue(d.isNpcSource("Brutus"));
		d.setExtraSources("");
		assertFalse(d.isNpcSource("Some New Boss"));
	}

	@Test
	public void everyRungSourceIsSomethingThePluginCounts()
	{
		BossmanData d = load();
		Set<String> receiptNames = new HashSet<>();
		for (BossmanData.ReceiptRule r : d.getSources().receiptRules)
		{
			receiptNames.add(r.name);
		}
		for (BossmanData.Rung r : d.getLadder().rungs)
		{
			for (String s : r.sources)
			{
				assertTrue(r.boss + ": source " + s + " is neither an NPC, an event nor a receipt rule",
					d.canonicalSource(s) != null || receiptNames.contains(s));
			}
			if (r.kcSource != null)
			{
				assertTrue(r.boss + ": kc_source is not one of its sources", r.sources.contains(r.kcSource));
			}
		}
	}

	@Test
	public void kcIsStoredUnderTheSpellingTheLadderUses()
	{
		// KC is keyed by the bundled NPC spelling; a ladder source spelled differently read 0 forever
		BossmanData d = load();
		for (BossmanData.Rung r : d.getLadder().rungs)
		{
			for (String s : r.sources)
			{
				String c = d.canonicalNpcName(s);
				assertTrue(r.boss + ": " + s + " vs " + c, c == null || c.equals(s));
			}
		}
	}

	@Test
	public void everyLogItemCanBeUnlocked()
	{
		BossmanData d = load();
		for (BossmanData.Rung r : d.getLadder().rungs)
		{
			for (BossmanData.GatingItem g : r.log.gatingItems)
			{
				assertFalse(r.boss + ": " + g.item + " has no item id", g.ids.isEmpty());
			}
		}
	}

	@Test
	public void raidAndYamaRatesAreNotUniqueTableWeights()
	{
		// the scrape gives purple-table weights for raids ("Twisted bow 1/28") and "Always" for Yama's armour
		BossmanData d = load();
		for (BossmanData.Rung r : d.getLadder().rungs)
		{
			if (r.boss.contains("(raid)"))
			{
				for (BossmanData.GatingItem g : r.log.gatingItems)
				{
					assertNull(r.boss + ": " + g.item, g.denominator);
				}
				assertNull(r.boss, r.log.expectedKc);
			}
			if (r.boss.equals("Yama"))
			{
				for (BossmanData.GatingItem g : r.log.gatingItems)
				{
					assertTrue("Yama: " + g.item, g.denominator == null || g.denominator > 1);
				}
			}
		}
	}

	@Test
	public void theLadderIsInOrderWithUniqueNames()
	{
		BossmanData d = load();
		java.util.List<BossmanData.Rung> ordered = d.orderedRungs();
		for (int i = 0; i < ordered.size(); i++)
		{
			assertEquals(ordered.get(i).boss, Integer.valueOf(i + 1), ordered.get(i).rung);
		}
		Set<String> bosses = new HashSet<>();
		for (BossmanData.Rung r : d.getLadder().rungs)
		{
			assertTrue("duplicate boss " + r.boss, bosses.add(r.boss));   // manualComplete and backlog keys
			assertEquals(r.boss, r.rung == null, r.floating);
			Set<String> items = new HashSet<>();
			for (BossmanData.GatingItem g : r.log.gatingItems)
			{
				assertTrue(r.boss + ": duplicate log item " + g.item, items.add(g.item));   // backlog keys
			}
			for (BossmanData.DebtItem x : r.log.debtPetsJars)
			{
				assertTrue(r.boss + ": duplicate log item " + x.item, items.add(x.item));
			}
		}
	}

	@Test
	public void kcAliasesPointAtRealSources()
	{
		BossmanData d = load();
		for (java.util.Map.Entry<String, String> a : d.getSources().kcAliases.entrySet())
		{
			assertEquals(a.getKey(), a.getValue(), d.canonicalSource(a.getValue()));
		}
		for (String e : d.getEventSources())
		{
			assertEquals(e, d.canonicalSource(e));
		}
	}

	@Test
	public void onlyKnownTabsAreShared()
	{
		// a shared tab's import credits each item to the rung that lists it; any new sharing should be deliberate
		Set<String> allowed = new HashSet<>(java.util.Arrays.asList(
			"Vet'ion and Calvar'ion", "Venenatis and Spindel", "Callisto and Artio", "The Gauntlet", "The Nightmare"));
		java.util.Map<String, Integer> uses = new java.util.HashMap<>();
		for (BossmanData.Rung r : load().getLadder().rungs)
		{
			if (r.clogTab != null)
			{
				uses.merge(r.clogTab, 1, Integer::sum);
			}
		}
		uses.forEach((tab, n) -> assertTrue("tab shared unexpectedly: " + tab, n == 1 || allowed.contains(tab)));
	}

	@Test
	public void slayerToolsHaveTheirOwnKind()
	{
		assertEquals(FreeKind.SLAYER, load().getFreeItems().get(4161));   // bag of salt
	}

	@Test
	public void bossAreaCraftablesHaveTheirOwnKind()
	{
		BossmanData d = load();
		assertEquals(FreeKind.BOSS_CRAFTABLE, d.getFreeItems().get(20996));   // overload (+)(4)
		assertEquals(FreeKind.BOSS_CRAFTABLE, d.getFreeItems().get(23888));   // crystal helm (perfected)
	}

	@Test
	public void containersAreTools()
	{
		BossmanData d = load();
		assertEquals(FreeKind.TOOL, d.getFreeItems().get(227));    // vial of water
		assertEquals(FreeKind.TOOL, d.getFreeItems().get(1929));   // bucket of water
		assertEquals(FreeKind.TOOL, d.getFreeItems().get(5354));   // filled plant pot
		assertNull("bucket of milk", d.getFreeItems().get(1927));
		assertNull("pot of flour", d.getFreeItems().get(1933));
		assertNull("bucket of sand", d.getFreeItems().get(1783));
	}

	@Test
	public void attackableQuestsAreQuestsRuneLiteKnows()
	{
		for (String quest : load().getAttackable().quests.keySet())
		{
			net.runelite.api.Quest.valueOf(quest);
		}
	}

	@Test
	public void attackableDataIsWellFormed()
	{
		BossmanData d = load();
		BossmanData.Attackable a = d.getAttackable();
		Set<Integer> regions = new HashSet<>();
		for (BossmanData.ArenaRegion r : a.regions)
		{
			assertTrue(r.boss + ": role " + r.role, "fight".equals(r.role) || "access".equals(r.role));
			assertFalse(r.boss + ": no region ids", r.ids.isEmpty());
			for (int id : r.ids)
			{
				assertTrue(r.boss + ": region " + id + " listed twice", regions.add(id));
			}
			assertTrue(r.boss + " is not a boss the plugin knows", isKnownBoss(d, r.boss));
		}
		for (BossmanData.AccessNpc n : a.accessNpcs)
		{
			assertFalse("access npc without a name", n.name == null || n.name.trim().isEmpty());
			assertTrue(n.name + ": empty id list", n.ids == null || !n.ids.isEmpty());
			assertTrue(n.name + ": " + n.boss + " is not a boss the plugin knows", isKnownBoss(d, n.boss));
		}
		a.quests.forEach((quest, needs) ->
		{
			assertFalse(quest + ": no npcs or items", needs.npcs.isEmpty() && needs.items.isEmpty());
			needs.npcs.forEach(n -> assertFalse(quest + ": blank npc name", n.trim().isEmpty()));
			needs.items.forEach(i -> assertTrue(quest + ": item without an id", i.id > 0));
		});
	}

	private static boolean isKnownBoss(BossmanData d, String boss)
	{
		return d.isNpcSource(boss) || d.isEventSource(boss) || d.canonicalSource(boss) != null
			|| d.getLadder().rungs.stream().anyMatch(r -> r.boss.equals(boss));
	}

	@Test
	public void receiptRulesAreNarrow()
	{
		BossmanData d = load();
		for (BossmanData.ReceiptRule r : d.getSources().receiptRules)
		{
			assertTrue(r.name + " would unlock anything anywhere", r.itemIds != null || r.regions != null);
			assertFalse("Tokkul (6529) is sold and dropped all over Mor Ul Rek: only as a companion",
				r.itemIds != null && r.itemIds.contains(6529));
		}
	}

	@Test
	public void sideBossesAreTheOnesWithoutTheirOwnLog()
	{
		BossmanData d = load();
		for (BossmanData.Rung r : d.orderedRungs())
		{
			if (!r.blocking)
			{
				assertTrue(r.boss + " is non-blocking but needs more than a first kill", r.isFirstKillOnly());
			}
		}
	}
}
