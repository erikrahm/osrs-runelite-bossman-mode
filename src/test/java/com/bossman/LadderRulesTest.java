package com.bossman;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class LadderRulesTest
{
	private static final int PRAYER_POTION_4 = 2434;
	private static final int PRAYER_POTION_3 = 139;
	private static final int PRAYER_POTION_PACK_TEST = 99999;

	private final BossmanData data = new BossmanData();
	private final Ledger ledger = new Ledger();
	/** stand-in for ItemVariationMapping: id → its group */
	private final Map<Integer, Collection<Integer>> groups = new HashMap<>();
	private boolean petsGate;
	private boolean caGate;
	private boolean unlockBond = true;
	private boolean freeTools = true;
	private boolean freeAccessGear = true;
	private boolean freeSlayerTools = true;
	private boolean freeBossCraftables = true;
	private boolean cookedFood = true;
	private boolean jarsGate;
	private int memeThreshold = 5000;
	private final Set<Integer> questItems = new HashSet<>();
	private LadderRules rules;

	@Before
	public void setUp()
	{
		data.load(new Gson());
		List<Integer> prayer = Arrays.asList(PRAYER_POTION_4, PRAYER_POTION_3);
		groups.put(PRAYER_POTION_4, prayer);
		groups.put(PRAYER_POTION_3, prayer);
		BossmanModeConfig config = new BossmanModeConfig()
		{
			@Override
			public boolean petsGate()
			{
				return petsGate;
			}

			@Override
			public boolean caGate()
			{
				return caGate;
			}

			@Override
			public boolean unlockBond()
			{
				return unlockBond;
			}

			@Override
			public boolean freeTools()
			{
				return freeTools;
			}

			@Override
			public boolean freeAccessGear()
			{
				return freeAccessGear;
			}

			@Override
			public boolean freeSlayerTools()
			{
				return freeSlayerTools;
			}

			@Override
			public boolean freeBossCraftables()
			{
				return freeBossCraftables;
			}

			@Override
			public boolean cookedFood()
			{
				return cookedFood;
			}

			@Override
			public boolean jarsGate()
			{
				return jarsGate;
			}

			@Override
			public int memeThreshold()
			{
				return memeThreshold;
			}
		};
		rules = new LadderRules(data, config, id -> id, id -> groups.getOrDefault(id, Collections.singletonList(id)), () -> ledger,
			questItems::contains);
	}

	private BossmanData.Rung rung(String boss)
	{
		for (BossmanData.Rung r : data.getLadder().rungs)
		{
			if (r.boss.equals(boss))
			{
				return r;
			}
		}
		throw new AssertionError("no rung " + boss);
	}

	private void unlock(int id)
	{
		Ledger.Entry e = new Ledger.Entry();
		e.itemId = id;
		ledger.unlocks.put(id, e);
	}

	private void unlockAllGating(BossmanData.Rung r)
	{
		for (BossmanData.GatingItem g : r.log.gatingItems)
		{
			if (!rules.isMeme(g))
			{
				unlock(g.ids.get(0));
			}
		}
	}

	@Test
	public void anotherDoseOfAnUnlockedPotionIsAllowed()
	{
		assertFalse(rules.isAllowed(PRAYER_POTION_3));
		unlock(PRAYER_POTION_4);
		assertTrue(rules.isAllowed(PRAYER_POTION_3));
	}

	@Test
	public void containersFollowTheToolsSetting()
	{
		int vialOfWater = 227;
		int plantPotPack = 13250;
		assertTrue(rules.isAllowed(vialOfWater));
		assertTrue(rules.isAllowed(plantPotPack));
		freeTools = false;
		assertFalse(rules.isAllowed(vialOfWater));
		assertFalse(rules.isAllowed(plantPotPack));
	}

	@Test
	public void bossAreaCraftablesFollowTheirSetting()
	{
		int rejuvenationPotion4 = 20699;
		int crystalBowPerfected = 23903;
		assertEquals(FreeKind.BOSS_CRAFTABLE, rules.freeKind(rejuvenationPotion4));
		assertTrue(rules.isAllowed(crystalBowPerfected));
		freeBossCraftables = false;
		assertFalse(rules.isAllowed(rejuvenationPotion4));
		assertFalse(rules.isAllowed(crystalBowPerfected));
	}

	@Test
	public void aStartedQuestsItemsAreAllowedUntilItEnds()
	{
		int climbingBoots = 3105;
		assertFalse(rules.isAllowed(climbingBoots));
		questItems.add(climbingBoots);
		assertTrue(rules.isAllowed(climbingBoots));
		assertTrue(rules.isQuestItemOnly(climbingBoots));
		unlock(climbingBoots);
		assertFalse("unlocked for good: no quest icon", rules.isQuestItemOnly(climbingBoots));
	}

	@Test
	public void bondFollowsItsSettingBothWays()
	{
		assertTrue(rules.isAllowed(ItemID.OSRS_BOND));
		assertEquals(FreeKind.BOND, rules.freeKind(ItemID.OSRS_BOND));
		unlockBond = false;
		assertFalse(rules.isAllowed(ItemID.OSRS_BOND));
	}

	@Test
	public void eachFreeRuleHasItsOwnSwitch()
	{
		int bronzeAxe = 1351;
		int rope = 954;
		int bagOfSalt = 4161;
		assertEquals(FreeKind.TOOL, rules.freeKind(bronzeAxe));
		assertEquals(FreeKind.ACCESS, rules.freeKind(rope));
		assertEquals(FreeKind.SLAYER, rules.freeKind(bagOfSalt));
		freeTools = false;
		assertFalse(rules.isAllowed(bronzeAxe));
		assertTrue(rules.isAllowed(rope));
		freeAccessGear = false;
		assertFalse(rules.isAllowed(rope));
		assertTrue(rules.isAllowed(bagOfSalt));   // slayer tools aren't access gear any more
		freeSlayerTools = false;
		assertFalse(rules.isAllowed(bagOfSalt));
	}

	@Test
	public void aPackIsAllowedOnlyWhenEverythingInItIs()
	{
		data.getSources().itemPacks.put(PRAYER_POTION_PACK_TEST, Arrays.asList(PRAYER_POTION_4));
		assertFalse(rules.isAllowed(PRAYER_POTION_PACK_TEST));
		unlock(PRAYER_POTION_4);
		assertTrue(rules.isAllowed(PRAYER_POTION_PACK_TEST));
	}

	@Test
	public void theLadderStartsAtTheFirstBoss()
	{
		assertEquals("Brutus", rules.currentRung().boss);
	}

	@Test
	public void sideBossesDontHoldUpTheLadder()
	{
		// complete everything up to and including Bryophyta (rung 4); Gemstone Crab (rung 5) never drops loot
		for (BossmanData.Rung r : data.orderedRungs())
		{
			if (r.rung > 4)
			{
				break;
			}
			ledger.manualComplete.add(r.boss);
		}
		// Gemstone Crab (5) and The Mimic (6) are side bosses: the ladder moves straight on to rung 7
		assertEquals("Scurrius", rules.currentRung().boss);
	}

	@Test
	public void theLadderEndsAndFloatingRungsNeverBecomeCurrent()
	{
		for (BossmanData.Rung r : data.orderedRungs())
		{
			ledger.manualComplete.add(r.boss);
		}
		assertNull(rules.currentRung());   // Tempoross and Wintertodt float: never current
	}

	@Test
	public void aSkillingBossCanBeDoneAheadOfTheLadder()
	{
		ledger.manualComplete.add("Brutus");
		ledger.focus = "Wintertodt";
		assertEquals("Wintertodt", rules.currentRung().boss);
		ledger.focus = "Obor";   // not a skilling boss
		assertEquals("Obor", rules.currentRung().boss);
		ledger.focus = "Zalcano";
		ledger.manualComplete.add("Zalcano");
		assertEquals("Obor", rules.currentRung().boss);   // done: back to the ladder
	}

	@Test
	public void cookingAnUnlockedRawFoodKeepsItUnlocked()
	{
		int rawAnchovies = 321;
		int anchovies = 319;
		int burntFish = 323;
		int rawShark = 383;
		int shark = 385;
		assertFalse(rules.isAllowed(anchovies));
		unlock(rawAnchovies);
		assertTrue(rules.isAllowed(anchovies));
		assertTrue(rules.isAllowed(burntFish));
		assertFalse("another fish's raw form", rules.isAllowed(shark));
		assertFalse("cooking doesn't go backwards", rules.isAllowed(rawShark));
		cookedFood = false;
		assertFalse(rules.isAllowed(anchovies));
	}

	@Test
	public void dropRatesSortCommonestFirstAndUnknownLast()
	{
		assertEquals(1, LadderRules.dropChance("completion"), 0);
		assertEquals(4 / 180.0, LadderRules.dropChance("4/180"), 1e-9);
		assertEquals(1 / 5000.0, LadderRules.dropChance("1/5,000"), 1e-9);
		assertEquals(-1, LadderRules.dropChance("varies"), 0);
		assertTrue(LadderRules.dropChance("1/128") > LadderRules.dropChance("1/1000"));
	}

	@Test
	public void aFreeItemDoesNotFreeItsOtherForms()
	{
		int raidBurntFish = 20854;
		int burntFish = 323;
		List<Integer> burnt = Arrays.asList(burntFish, raidBurntFish);
		groups.put(burntFish, burnt);
		groups.put(raidBurntFish, burnt);
		assertTrue(rules.isAllowed(raidBurntFish));
		assertFalse(rules.isAllowed(burntFish));
	}

	@Test
	public void petsWhoseOptionIsOffAreBackloggedWithoutTouchingTheLedger()
	{
		BossmanData.Rung kbd = rung("King Black Dragon");
		BossmanData.DebtItem prince = kbd.log.debtPetsJars.get(0);
		assertTrue(rules.isAutoBacklogged(prince));
		ledger.backlog.add(LadderRules.backlogKey(kbd, prince.item));
		petsGate = true;
		assertFalse(rules.isAutoBacklogged(prince));
		assertTrue("the hand-made backlog entry survives the toggle", rules.isBacklogged(kbd, prince.item));
		petsGate = false;
		assertTrue(rules.isAutoBacklogged(prince));
		assertFalse("a meme-grind pet is optional, not backlogged", rules.isAutoBacklogged(rung("Wintertodt").log.debtPetsJars.get(0)));
	}

	@Test
	public void rarePetsAreMemeGrinds()
	{
		BossmanData.Rung wt = rung("Wintertodt");
		BossmanData.DebtItem phoenix = wt.log.debtPetsJars.get(0);
		assertTrue(rules.isMeme(phoenix));
		petsGate = true;
		assertFalse(rules.isDebtItemGating(phoenix));
		ledger.setKcFromChat("Reward cart (Wintertodt)", "Reward cart (Wintertodt)", 6000);
		assertFalse("never goes to the backlog", rules.isDebtOwed(wt, phoenix));
		assertFalse(rules.isMeme(rung("King Black Dragon").log.debtPetsJars.get(0)));   // Prince black dragon, 1/3000
	}

	@Test
	public void jarsGateOnlyWhenSwitchedOnAndAreToldApartFromPets()
	{
		BossmanData.Rung kraken = rung("Kraken");
		BossmanData.DebtItem jar = kraken.log.debtPetsJars.stream().filter(rules::isJar).findFirst().get();
		BossmanData.DebtItem pet = kraken.log.debtPetsJars.stream().filter(d -> !rules.isJar(d)).findFirst().get();
		assertFalse(rules.isDebtItemGating(jar));
		jarsGate = true;
		assertTrue(rules.isDebtItemGating(jar));
		assertFalse(rules.isDebtItemGating(pet));
	}

	@Test
	public void aPetIsOwedFromExactlyItsDebtKc()
	{
		BossmanData.Rung kbd = rung("King Black Dragon");
		BossmanData.DebtItem prince = kbd.log.debtPetsJars.get(0);
		ledger.setKcFromChat("King Black Dragon", "King Black Dragon", prince.debtAtKc - 1);
		assertFalse(rules.isDebtOwed(kbd, prince));
		ledger.setKcFromChat("King Black Dragon", "King Black Dragon", prince.debtAtKc);
		assertTrue(rules.isDebtOwed(kbd, prince));
		prince.debtAtKc = null;   // unknown rate: never owed
		assertFalse(rules.isDebtOwed(kbd, prince));
	}

	@Test
	public void aPetRecordedUnderAnotherIdCountsByName()
	{
		BossmanData.Rung kbd = rung("King Black Dragon");
		BossmanData.DebtItem prince = kbd.log.debtPetsJars.get(0);
		assertFalse(rules.isDebtItemUnlocked(prince));
		Ledger.Entry follower = new Ledger.Entry();
		follower.itemId = 99998;
		follower.name = "prince black dragon";
		ledger.unlocks.put(follower.itemId, follower);
		assertTrue(rules.isDebtItemUnlocked(prince));
	}

	@Test
	public void aRungCompletesWhenItsGatingItemsAreIn()
	{
		BossmanData.Rung kbd = rung("King Black Dragon");
		assertFalse(rules.isRungComplete(kbd));
		unlockAllGating(kbd);
		assertTrue(rules.isRungComplete(kbd));
	}

	@Test
	public void backloggedItemsStopGating()
	{
		BossmanData.Rung kbd = rung("King Black Dragon");
		unlockAllGating(kbd);
		BossmanData.GatingItem first = kbd.log.gatingItems.stream().filter(g -> !rules.isMeme(g)).findFirst().get();
		ledger.unlocks.remove(first.ids.get(0));
		assertFalse(rules.isRungComplete(kbd));
		ledger.backlog.add(LadderRules.backlogKey(kbd, first.item));
		assertTrue(rules.isRungComplete(kbd));
	}

	@Test
	public void optionalItemsNeverGate()
	{
		BossmanData.Rung cox = rung("Chambers of Xeric (raid)");
		BossmanData.GatingItem dust = cox.log.gatingItems.stream().filter(g -> g.item.equals("Metamorphic dust")).findFirst().get();
		assertTrue(rules.isMeme(dust));
		unlockAllGating(cox);
		assertTrue(rules.isRungComplete(cox));
	}

	@Test
	public void theMemeThresholdIsInclusive()
	{
		BossmanData.GatingItem g = new BossmanData.GatingItem();
		g.denominator = 5000;
		assertTrue(rules.isMeme(g));
		g.denominator = 4999;
		assertFalse(rules.isMeme(g));
	}

	@Test
	public void petsGateOnlyWhenSwitchedOn()
	{
		BossmanData.Rung kbd = rung("King Black Dragon");
		unlockAllGating(kbd);
		assertTrue(rules.isRungComplete(kbd));
		petsGate = true;
		assertFalse(rules.isRungComplete(kbd));
		assertEquals(rules.progress(kbd).need - 1, rules.progress(kbd).have);
	}

	@Test
	public void combatTasksCountTowardsProgressWhenTheyGate()
	{
		BossmanData.Rung kbd = rung("King Black Dragon");
		int items = rules.progress(kbd).need;
		caGate = true;
		assertEquals(items + kbd.ca.list.size(), rules.progress(kbd).need);
		assertFalse(kbd.ca.list.isEmpty());
		unlockAllGating(kbd);
		assertFalse(rules.isRungComplete(kbd));
		for (BossmanData.CaTask t : kbd.ca.list)
		{
			ledger.addCaDone(t.name.toUpperCase());   // matched case-insensitively
		}
		assertTrue(rules.isRungComplete(kbd));
	}

	@Test
	public void aFirstKillOnlyRungNeedsOneKill()
	{
		BossmanData.Rung mimic = rung("The Mimic");
		assertFalse(rules.isRungComplete(mimic));
		ledger.setKcFromChat("The Mimic", "The Mimic", 1);
		assertTrue(rules.isRungComplete(mimic));
	}

	@Test
	public void theMoonsKcIsTheChestAndEachMoonIsKeptApart()
	{
		BossmanData.Rung moons = rung("Blood Moon / Blue Moon / Eclipse Moon");
		ledger.countLoot("Lunar Chest", 1);
		ledger.setKcFromChat("Blood Moon", "Blood Moon", 3);
		ledger.setKcFromChat("Blue Moon", "Blue Moon", 2);
		assertEquals(1, rules.rungKc(moons));
		Map<String, Integer> by = rules.kcBySource(moons);
		assertEquals(Integer.valueOf(3), by.get("Blood Moon"));
		assertEquals(Integer.valueOf(2), by.get("Blue Moon"));
		assertEquals(Integer.valueOf(0), by.get("Eclipse Moon"));
	}

	@Test
	public void multiBossRungsWithoutAKcSourceSumTheirSources()
	{
		BossmanData.Rung dks = rung("Dagannoth Kings");
		ledger.countLoot("Dagannoth Rex", 2);
		ledger.countLoot("Dagannoth Prime", 1);
		assertEquals(3, rules.rungKc(dks));
	}

	@Test
	public void chatKcIsAuthoritative()
	{
		ledger.countLoot("Vorkath", 1);
		ledger.countLoot("Vorkath", 1);
		ledger.setKcFromChat("Vorkath", "Vorkath", 14);     // the game's own number replaces our count
		assertEquals(14, ledger.getKc("Vorkath"));
		assertEquals(14, ledger.countLoot("Vorkath", 1));   // and loot no longer adds to it
	}

	@Test
	public void raidModesAddUpToTheRaidsKc()
	{
		BossmanData.Rung cox = rung("Chambers of Xeric (raid)");
		ledger.setKcFromChat("Chambers of Xeric", "Chambers of Xeric", 10);
		ledger.setKcFromChat("Chambers of Xeric", "Chambers of Xeric (Challenge Mode)", 4);
		assertEquals(14, rules.rungKc(cox));
		assertEquals(14, ledger.countLoot("Chambers of Xeric", 1));   // the chest doesn't add a run on top
	}

	@Test
	public void aLadderSpellingThatDiffersStillFindsItsKc()
	{
		BossmanData.Rung r = new BossmanData.Rung();
		r.boss = "test";
		r.sources = Collections.singletonList("Deranged Archaeologist");
		ledger.countLoot("Deranged archaeologist", 1);
		assertEquals(1, rules.rungKc(r));
	}
}
