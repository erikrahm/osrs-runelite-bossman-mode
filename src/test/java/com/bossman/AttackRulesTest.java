package com.bossman;

import com.google.gson.Gson;
import java.util.Collections;
import java.util.Set;
import net.runelite.client.util.Text;
import org.junit.Test;
import static org.junit.Assert.*;

public class AttackRulesTest
{
	private static final BossmanData DATA = load();
	private static final Set<String> NONE = Collections.emptySet();
	private static final int NOWHERE = -1;
	private static final int LUMBRIDGE = 12850;

	private static BossmanData load()
	{
		BossmanData d = new BossmanData();
		d.load(new Gson());
		return d;
	}

	private static AttackRules.Reason reason(String inGameName)
	{
		return AttackRules.reason(-1, Text.standardize(inGameName), NOWHERE, false, NONE, NONE, DATA);
	}

	private static AttackRules.Reason reasonIn(int region)
	{
		return AttackRules.reason(-1, "snakeling", region, false, NONE, NONE, DATA);
	}

	@Test
	public void attackingAndCombatSpellsAreCombat()
	{
		assertTrue(AttackRules.isCombatOption("Attack", "Goblin  (level-2)"));
		assertTrue(AttackRules.isCombatOption("Fight", "Kolodion"));
		assertTrue(AttackRules.isCombatOption("Cast", "Wind Strike -> Goblin  (level-2)"));
		assertTrue(AttackRules.isCombatOption("Cast", "Ice Barrage -> Goblin  (level-2)"));
	}

	@Test
	public void talkingTradingAndNonCombatSpellsAreNot()
	{
		assertFalse(AttackRules.isCombatOption("Talk-to", "Goblin"));
		assertFalse(AttackRules.isCombatOption("Pickpocket", "Man"));
		assertFalse(AttackRules.isCombatOption("Use", "Bucket -> Cow"));
		assertFalse(AttackRules.isCombatOption("Cast", "Monster Examine -> Goblin  (level-2)"));
		assertFalse(AttackRules.isCombatOption("Cast", "Dark Lure -> Goblin  (level-2)"));
		assertFalse(AttackRules.isCombatOption("Cast", null));
		assertFalse(AttackRules.isCombatOption(null, null));
	}

	@Test
	public void bossesMayBeFoughtAndOrdinaryMonstersMayNot()
	{
		assertEquals(AttackRules.Reason.BOSS, reason("Zulrah"));
		assertEquals(AttackRules.Reason.BOSS, reason("Brutus"));
		assertEquals(AttackRules.Reason.BOSS, reason("<col=ffff00>Vorkath"));
		assertNull(reason("Goblin"));
		assertNull(reason("Cow"));
	}

	@Test
	public void namesMatchWithTagsAndNonBreakingSpaces()
	{
		assertEquals(AttackRules.Reason.BOSS_ACCESS, reason("<col=ff0000>Revenant imp</col>"));
	}

	@Test
	public void everythingInABossArenaMayBeFought()
	{
		int[] arenas = {
			11347,   // God Wars Dungeon (Bandos side, followers for KC)
			7512,    // the Gauntlet
			9551,    // Fight Caves
			9043,    // Inferno
			7216,    // Fortis Colosseum
			13137,   // Chambers of Xeric
			12613,   // Theatre of Blood
			9007,    // Zulrah's shrine
			6556,    // Catacombs of Kourend (Skotizo's totem pieces)
		};
		for (int region : arenas)
		{
			assertEquals("region " + region, AttackRules.Reason.ARENA, reasonIn(region));
		}
		assertNull("Lumbridge", reasonIn(LUMBRIDGE));
		assertNull("Varrock", reasonIn(12853));
	}

	@Test
	public void monstersThatDropABossKeyMayBeFought()
	{
		assertEquals(AttackRules.Reason.BOSS_ACCESS, reason("Hill Giant"));
		assertEquals(AttackRules.Reason.BOSS_ACCESS, reason("Moss giant"));
		assertEquals(AttackRules.Reason.BOSS_ACCESS, reason("Revenant knight"));
		assertNull(reason("Cyclops"));   // drops no boss key, and opening it would free the Warriors' Guild
	}

	@Test
	public void fightAddsListedByIdMatchOnlyThoseIds()
	{
		int vetionHellhound = 6613;
		int myrequeHellhound = 5054;
		assertEquals(AttackRules.Reason.BOSS_ACCESS,
			AttackRules.reason(vetionHellhound, "skeleton hellhound", NOWHERE, false, NONE, NONE, DATA));
		assertNull(AttackRules.reason(myrequeHellhound, "skeleton hellhound", NOWHERE, false, NONE, NONE, DATA));
		assertEquals(AttackRules.Reason.BOSS_ACCESS,
			AttackRules.reason(12001, "spindel's spiderling", NOWHERE, false, NONE, NONE, DATA));
	}

	@Test
	public void slayerTaskQuestAndConfigOpenUpAMonster()
	{
		String goblin = "goblin";
		assertEquals(AttackRules.Reason.SLAYER_TASK, AttackRules.reason(-1, goblin, NOWHERE, true, NONE, NONE, DATA));
		assertEquals(AttackRules.Reason.QUEST,
			AttackRules.reason(-1, goblin, NOWHERE, false, Collections.singleton(goblin), NONE, DATA));
		assertEquals(AttackRules.Reason.EXTRA_ATTACKABLE,
			AttackRules.reason(-1, goblin, NOWHERE, false, NONE, Collections.singleton(goblin), DATA));
		assertEquals(AttackRules.Reason.EXTRA_ATTACKABLE,
			AttackRules.reason(3029, goblin, NOWHERE, false, NONE, Collections.singleton("3029"), DATA));
	}

	@Test
	public void tutorialIslandRatsMayBeFoughtUntilTheTutorialIsDone()
	{
		assertFalse(AttackRules.tutorialDone(AttackRules.TUTORIAL_DONE - 1));
		assertTrue(AttackRules.tutorialDone(AttackRules.TUTORIAL_DONE));
	}
}
