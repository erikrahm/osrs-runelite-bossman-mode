/*
 * Bossman Mode — the ruleset, as pure queries over the bundled data, the ledger and the config.
 * Nothing here mutates anything or touches the client; the plugin feeds it and the panel reads it,
 * from any thread (the ledger's collections are concurrent).
 */
package com.bossman;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.runelite.client.game.ItemManager;

public class LadderRules
{
	private final BossmanData data;
	private final BossmanModeConfig config;
	private final ItemManager itemManager;
	private final Supplier<Ledger> ledger;

	public LadderRules(BossmanData data, BossmanModeConfig config, ItemManager itemManager, Supplier<Ledger> ledger)
	{
		this.data = data;
		this.config = config;
		this.itemManager = itemManager;
		this.ledger = ledger;
	}

	private Ledger ledger()
	{
		return ledger.get();
	}

	// ------------------------------------------------------------------ items

	/** Unlocked by a boss, or free under the Tool / access rules. */
	public boolean isAllowed(int itemId)
	{
		int id = itemManager.canonicalize(itemId);
		return ledger().isUnlocked(id) || freeKindOfCanonical(id) != null;
	}

	/** "tool" / "access" if the item is free under a rule that is switched on, else null. */
	public String freeKind(int itemId)
	{
		return freeKindOfCanonical(itemManager.canonicalize(itemId));
	}

	private String freeKindOfCanonical(int id)
	{
		String kind = data.getFreeItems().get(id);
		if ("tool".equals(kind) && !config.freeTools())
		{
			return null;
		}
		if ("access".equals(kind) && !config.freeAccessGear())
		{
			return null;
		}
		return kind;
	}

	// ------------------------------------------------------------------ log items

	/** Rarer than the configured threshold → optional "meme grind", does not gate the ladder. */
	public boolean isMeme(BossmanData.GatingItem gi)
	{
		return gi.denominator != null && gi.denominator >= config.memeThreshold();
	}

	public boolean isJar(BossmanData.DebtItem d)
	{
		return d.item != null && d.item.toLowerCase(Locale.ROOT).startsWith("jar of");
	}

	/** Pets/jars gate only when the matching option is on. */
	public boolean isDebtItemGating(BossmanData.DebtItem d)
	{
		return isJar(d) ? config.jarsGate() : config.petsGate();
	}

	public boolean isDebtItemUnlocked(BossmanData.DebtItem d)
	{
		if (anyUnlocked(d.ids))
		{
			return true;
		}
		// pets are often recorded under a different id (follower vs inventory form); fall back to the name
		for (Ledger.Entry en : ledger().unlocks.values())
		{
			if (en.name != null && en.name.equalsIgnoreCase(d.item))
			{
				return true;
			}
		}
		return false;
	}

	public boolean isLogItemUnlocked(BossmanData.GatingItem gi)
	{
		return anyUnlocked(gi.ids);
	}

	private boolean anyUnlocked(List<Integer> ids)
	{
		Ledger l = ledger();
		for (int id : ids)
		{
			if (l.isUnlocked(id))
			{
				return true;
			}
		}
		return false;
	}

	public static String backlogKey(BossmanData.Rung r, String item)
	{
		return r.boss + "|" + item;
	}

	public boolean isBacklogged(BossmanData.Rung r, String item)
	{
		return ledger().backlog.contains(backlogKey(r, item));
	}

	// ------------------------------------------------------------------ combat achievements

	public boolean isCaGating()
	{
		return config.caGate();
	}

	public boolean isCaDone(BossmanData.CaTask t)
	{
		for (String n : ledger().caDone)
		{
			if (n.equalsIgnoreCase(t.name))
			{
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ rungs

	/** Kills at every source that feeds this rung (e.g. all three Dagannoth Kings). */
	public int rungKc(BossmanData.Rung r)
	{
		Ledger l = ledger();
		int total = 0;
		for (String s : r.sources)
		{
			total += l.getKc(s);
		}
		return total;
	}

	public boolean isRungComplete(BossmanData.Rung r)
	{
		if (ledger().manualComplete.contains(r.boss))
		{
			return true;
		}
		if (r.isFirstKillOnly())
		{
			return rungKc(r) >= 1;
		}
		boolean anyGating = false;
		for (BossmanData.GatingItem gi : r.log.gatingItems)
		{
			if (isMeme(gi) || isBacklogged(r, gi.item))
			{
				continue;
			}
			anyGating = true;
			if (!isLogItemUnlocked(gi))
			{
				return false;
			}
		}
		for (BossmanData.DebtItem d : r.log.debtPetsJars)
		{
			if (!isDebtItemGating(d) || isBacklogged(r, d.item))
			{
				continue;
			}
			anyGating = true;
			if (!isDebtItemUnlocked(d))
			{
				return false;
			}
		}
		if (config.caGate() && r.ca != null)
		{
			for (BossmanData.CaTask t : r.ca.list)
			{
				anyGating = true;
				if (!isCaDone(t))
				{
					return false;
				}
			}
		}
		if (!anyGating)
		{
			return rungKc(r) >= 1;   // nothing to collect: the first kill is the rung
		}
		return true;
	}

	/** Lowest incomplete rung = the boss the series is on; null once the ladder is done. */
	public BossmanData.Rung currentRung()
	{
		for (BossmanData.Rung r : data.orderedRungs())
		{
			if (!isRungComplete(r))
			{
				return r;
			}
		}
		return null;
	}
}
