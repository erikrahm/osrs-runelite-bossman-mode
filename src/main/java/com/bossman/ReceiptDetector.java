/*
 * Bossman Mode: which inventory gains are completion rewards (receipt rules). No client state, so it is tested in
 * ReceiptDetectorTest; the plugin feeds it inventory and equipment counts.
 */
package com.bossman;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

final class ReceiptDetector
{
	/** a rule's companion items (Tokkul) count when gained this many ticks after the rule's own item */
	static final int COMPANION_TICKS = 5;

	/** An item to unlock, and the rule it unlocks under. */
	static final class Receipt
	{
		final int itemId;
		final BossmanData.ReceiptRule rule;

		Receipt(int itemId, BossmanData.ReceiptRule rule)
		{
			this.itemId = itemId;
			this.rule = rule;
		}
	}

	private ReceiptDetector()
	{
	}

	/**
	 * Items gained between two inventories, not counting ones that just came off the player (unequipped).
	 */
	static List<Integer> gained(Map<Integer, Integer> before, Map<Integer, Integer> now,
		Map<Integer, Integer> equipBefore, Map<Integer, Integer> equipNow)
	{
		List<Integer> out = new ArrayList<>();
		for (Map.Entry<Integer, Integer> en : now.entrySet())
		{
			int id = en.getKey();
			int unequipped = Math.max(0, equipBefore.getOrDefault(id, 0) - equipNow.getOrDefault(id, 0));
			if (en.getValue() > before.getOrDefault(id, 0) + unequipped)
			{
				out.add(id);
			}
		}
		return out;
	}

	/**
	 * The gained items that unlock under a rule. A rule's own items come first, so a companion gained in the same
	 * change (or within {@link #COMPANION_TICKS}) sees the match.
	 *
	 * @param lastMatch tick each rule last matched; updated here
	 * @param allowed   items already usable (not unlocked again)
	 */
	static List<Receipt> detect(List<Integer> gained, int region, int tick, List<BossmanData.ReceiptRule> rules,
		Map<String, Integer> lastMatch, IntPredicate allowed)
	{
		List<Receipt> out = new ArrayList<>();
		for (int id : gained)
		{
			for (BossmanData.ReceiptRule rule : rules)
			{
				if (rule.matches(id, region))
				{
					lastMatch.put(rule.name, tick);
					if (!allowed.test(id))
					{
						out.add(new Receipt(id, rule));
					}
					break;
				}
			}
		}
		for (int id : gained)
		{
			if (allowed.test(id) || out.stream().anyMatch(r -> r.itemId == id))
			{
				continue;
			}
			for (BossmanData.ReceiptRule rule : rules)
			{
				Integer at = lastMatch.get(rule.name);
				if (rule.companions != null && rule.companions.contains(id) && at != null && tick - at <= COMPANION_TICKS)
				{
					out.add(new Receipt(id, rule));
					break;
				}
			}
		}
		return out;
	}
}
