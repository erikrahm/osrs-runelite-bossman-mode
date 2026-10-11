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
		assertTrue(d.orderedRungs().get(5).isFirstKillOnly()); // The Mimic
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

	// ------------------------------------------------------------------ data invariants (generator / fix_data.py)

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
