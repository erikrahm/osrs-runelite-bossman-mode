package com.bossman;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReceiptDetectorTest
{
	private static final int FIRE_CAPE = 6570;
	private static final int TOKKUL = 6529;
	private static final int GAUNTLET_LOBBY = 12127;
	private static final int ELSEWHERE = 1;

	private final Map<String, Integer> lastMatch = new HashMap<>();
	private final Set<Integer> unlocked = new HashSet<>();

	private static BossmanData.ReceiptRule fightCave()
	{
		BossmanData.ReceiptRule r = new BossmanData.ReceiptRule();
		r.name = "TzHaar Fight Cave";
		r.itemIds = Collections.singletonList(FIRE_CAPE);
		r.companions = Collections.singletonList(TOKKUL);
		return r;
	}

	private static BossmanData.ReceiptRule gauntlet()
	{
		BossmanData.ReceiptRule r = new BossmanData.ReceiptRule();
		r.name = "The Gauntlet";
		r.regions = Collections.singletonList(GAUNTLET_LOBBY);
		return r;
	}

	private List<ReceiptDetector.Receipt> detect(List<Integer> gained, int region, int tick)
	{
		return ReceiptDetector.detect(gained, region, tick, Arrays.asList(fightCave(), gauntlet()), lastMatch, unlocked::contains);
	}

	private static Map<Integer, Integer> inv(int... idQty)
	{
		Map<Integer, Integer> m = new HashMap<>();
		for (int i = 0; i < idQty.length; i += 2)
		{
			m.put(idQty[i], idQty[i + 1]);
		}
		return m;
	}

	@Test
	public void aCapeBringsItsTokkul()
	{
		List<ReceiptDetector.Receipt> r = detect(Arrays.asList(TOKKUL, FIRE_CAPE), ELSEWHERE, 100);
		assertEquals(2, r.size());
	}

	@Test
	public void tokkulAloneUnlocksNothing()
	{
		assertTrue(detect(Collections.singletonList(TOKKUL), ELSEWHERE, 100).isEmpty());
	}

	@Test
	public void tokkulCountsOnlyWithinTheCompanionWindow()
	{
		detect(Collections.singletonList(FIRE_CAPE), ELSEWHERE, 100);
		assertEquals(1, detect(Collections.singletonList(TOKKUL), ELSEWHERE, 100 + ReceiptDetector.COMPANION_TICKS).size());
		lastMatch.put("TzHaar Fight Cave", 100);
		assertTrue(detect(Collections.singletonList(TOKKUL), ELSEWHERE, 100 + ReceiptDetector.COMPANION_TICKS + 1).isEmpty());
	}

	@Test
	public void anythingGainedInTheGauntletLobbyUnlocksButNotElsewhere()
	{
		assertEquals(1, detect(Collections.singletonList(4151), GAUNTLET_LOBBY, 5).size());
		assertTrue(detect(Collections.singletonList(4151), ELSEWHERE, 5).isEmpty());
	}

	@Test
	public void alreadyUsableItemsArentUnlockedAgain()
	{
		unlocked.add(FIRE_CAPE);
		assertTrue(detect(Collections.singletonList(FIRE_CAPE), ELSEWHERE, 1).isEmpty());
	}

	@Test
	public void takingAnItemOffIsNotAGain()
	{
		List<Integer> gained = ReceiptDetector.gained(inv(), inv(4151, 1), inv(4151, 1), inv());
		assertTrue(gained.isEmpty());
	}

	@Test
	public void aRealGainIsSeenEvenWhileUnequippingSomethingElse()
	{
		List<Integer> gained = ReceiptDetector.gained(inv(), inv(4151, 1, 995, 10), inv(4151, 1), inv());
		assertEquals(Collections.singletonList(995), gained);
	}
}
