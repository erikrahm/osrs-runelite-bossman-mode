package com.bossman;

import com.google.gson.Gson;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class KcMessagesTest
{
	private static BossmanData data;

	@BeforeClass
	public static void load()
	{
		data = new BossmanData();
		data.load(new Gson());
	}

	private static KcMessages.Kc parse(String message)
	{
		return KcMessages.parse(message, data::canonicalSource);
	}

	private static void assertKc(String message, String source, String key, int count)
	{
		KcMessages.Kc kc = parse(message);
		assertNotNull(message, kc);
		assertEquals(message, source, kc.source);
		assertEquals(message, key, kc.key);
		assertEquals(message, count, kc.count);
	}

	@Test
	public void collectionLogHeaderLinesGiveTheirSourcesKc()
	{
		KcMessages.Kc kc = KcMessages.parseCollectionLog("Wintertodt kills: 410", data::canonicalSource);
		assertEquals("Reward cart (Wintertodt)", kc.source);
		assertEquals(410, kc.count);
		assertEquals(1054, KcMessages.parseCollectionLog("Vorkath kills: 1,054", data::canonicalSource).count);
		assertNull(KcMessages.parseCollectionLog("Obtained: 12/16", data::canonicalSource));
		assertNull(KcMessages.parseCollectionLog("Wintertodt", data::canonicalSource));
	}

	@Test
	public void bossKillCounts()
	{
		assertKc("Your Vorkath kill count is: 14.", "Vorkath", "Vorkath", 14);
		assertKc("Your Brutus kill count is: 1,033.", "Brutus", "Brutus", 1033);
		assertKc("Your Nightmare kill count is: 3.", "The Nightmare", "The Nightmare", 3);
		assertKc("Your Phosani's Nightmare kill count is: 2.", "Phosani's Nightmare", "Phosani's Nightmare", 2);
		assertKc("Your Grotesque Guardians kill count is: 9.", "Dusk", "Dusk", 9);
		assertKc("Your Deranged Archaeologist kill count is: 4.", "Deranged archaeologist", "Deranged archaeologist", 4);
	}

	@Test
	public void chestAndCompletionCounts()
	{
		assertKc("Your Barrows chest count is: 31.", "Barrows", "Barrows", 31);
		assertKc("Your Lunar Chest count is: 5.", "Lunar Chest", "Lunar Chest", 5);
		assertKc("Your Gauntlet completion count is: 2.", "Crystalline Hunllef", "Crystalline Hunllef", 2);
		assertKc("Your Corrupted Gauntlet completion count is: 7.", "Corrupted Hunllef", "Corrupted Hunllef", 7);
	}

	@Test
	public void raidModesAreCountedApart()
	{
		assertKc("Your completed Chambers of Xeric count is: 12.", "Chambers of Xeric", "Chambers of Xeric", 12);
		assertKc("Your completed Chambers of Xeric Challenge Mode count is: 3.", "Chambers of Xeric", "Chambers of Xeric (Challenge Mode)", 3);
		assertKc("Your completed Theatre of Blood count is: 8.", "Theatre of Blood", "Theatre of Blood", 8);
		assertKc("Your completed Theatre of Blood: Hard Mode count is: 2.", "Theatre of Blood", "Theatre of Blood (Hard Mode)", 2);
		assertKc("Your completed Theatre of Blood: Entry Mode count is: 1.", "Theatre of Blood", "Theatre of Blood (Entry Mode)", 1);
		assertKc("Your completed Tombs of Amascut: Expert Mode count is: 6.", "Tombs of Amascut", "Tombs of Amascut (Expert Mode)", 6);
	}

	@Test
	public void nonBreakingSpacesAreSpaces()
	{
		assertKc("Your King Black Dragon kill count is: 50.", "King Black Dragon", "King Black Dragon", 50);
	}

	@Test
	public void otherMessagesAndSourcesAreIgnored()
	{
		assertNull(parse("Your Hill Giant kill count is: 5."));
		assertNull(parse("Your reward count is: 5."));
		assertNull(parse("Fight duration: 0:20. Personal best: 0:14"));
	}
}
