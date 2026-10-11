/*
 * Bossman Mode — the ruleset, as pure queries over the bundled data, the ledger and the config.
 * Nothing here mutates anything. The rung and log queries are safe from any thread (the ledger's collections are
 * concurrent); isAllowed and freeKind canonicalize item ids, which reads the item cache, so call those on the client
 * thread.
 */
package com.bossman;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

public class LadderRules
{
	/** What a rung still needs: gating items, gating pets/jars and (when they gate) combat tasks. */
	public static final class Progress
	{
		public final int have;
		public final int need;

		Progress(int have, int need)
		{
			this.have = have;
			this.need = need;
		}

		public boolean complete()
		{
			return have >= need;
		}
	}

	private final BossmanData data;
	private final BossmanModeConfig config;
	/** unnote / un-placeholder / worn → inventory id (ItemManager.canonicalize in the plugin) */
	private final IntUnaryOperator canonicalize;
	/** every id in an item's variation group: potion doses, jewellery charges, degraded and charged forms */
	private final IntFunction<Collection<Integer>> variations;
	private final Supplier<Ledger> ledger;

	public LadderRules(BossmanData data, BossmanModeConfig config, IntUnaryOperator canonicalize,
		IntFunction<Collection<Integer>> variations, Supplier<Ledger> ledger)
	{
		this.data = data;
		this.config = config;
		this.canonicalize = canonicalize;
		this.variations = variations;
		this.ledger = ledger;
	}

	// ------------------------------------------------------------------ items

	/**
	 * Unlocked by a boss, free under the Tool / access / bond rules, or a shop pack whose contents all are. Another
	 * form of an unlocked item counts too: Prayer potion(3) once Prayer potion(4) dropped, a degraded Dharok's helm,
	 * an uncharged Trident. Client thread.
	 */
	public boolean isAllowed(int itemId)
	{
		return isAllowed(ledger.get(), canonicalize.applyAsInt(itemId));
	}

	private boolean isAllowed(Ledger l, int id)
	{
		if (l.isUnlocked(id) || freeKindOfCanonical(id) != null)
		{
			return true;
		}
		for (int v : variations.apply(id))
		{
			if (v != id && (l.isUnlocked(v) || freeKindOfCanonical(v) != null))
			{
				return true;
			}
		}
		List<Integer> contents = data.getSources().itemPacks.get(id);
		if (contents == null || contents.isEmpty())
		{
			return false;
		}
		for (int c : contents)
		{
			if (!isAllowed(l, c))
			{
				return false;
			}
		}
		return true;
	}

	/** "tool" / "access" / "bond" if the item is free under a rule that is switched on, else null. Client thread. */
	public String freeKind(int itemId)
	{
		return freeKindOfCanonical(canonicalize.applyAsInt(itemId));
	}

	private String freeKindOfCanonical(int id)
	{
		if (id == BossmanModePlugin.ITEM_BOND)
		{
			return config.unlockBond() ? "bond" : null;
		}
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

	/**
	 * Optional: never gates the ladder. Either marked so in the data (mode-locked, a KC-milestone reward) or a
	 * "meme grind": 1 in the configured threshold or rarer.
	 */
	public boolean isMeme(BossmanData.GatingItem gi)
	{
		return gi.optional || (gi.denominator != null && gi.denominator >= config.memeThreshold());
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

	/** Past the KC where the pet/jar is expected (false when its rate is unknown). */
	public boolean isDebtOwed(BossmanData.Rung r, BossmanData.DebtItem d)
	{
		return d.debtAtKc != null && rungKc(r) >= d.debtAtKc;
	}

	public boolean isDebtItemUnlocked(BossmanData.DebtItem d)
	{
		return isDebtItemUnlocked(ledger.get(), d);
	}

	private boolean isDebtItemUnlocked(Ledger l, BossmanData.DebtItem d)
	{
		if (anyUnlocked(l, d.ids))
		{
			return true;
		}
		// pets are often recorded under a different id (follower vs inventory form); fall back to the name
		for (Ledger.Entry en : l.unlocks.values())
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
		return anyUnlocked(ledger.get(), gi.ids);
	}

	private static boolean anyUnlocked(Ledger l, List<Integer> ids)
	{
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
		return ledger.get().backlog.contains(backlogKey(r, item));
	}

	// ------------------------------------------------------------------ combat achievements

	public boolean isCaGating()
	{
		return config.caGate();
	}

	public boolean isCaDone(BossmanData.CaTask t)
	{
		return isCaDone(ledger.get(), t);
	}

	private static boolean isCaDone(Ledger l, BossmanData.CaTask t)
	{
		for (String n : l.caDone)
		{
			if (n.equalsIgnoreCase(t.name))
			{
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ rungs

	/**
	 * The rung's KC: its own KC source if it names one (Lunar Chest for the Moons, the Sire for the Sire and Unsired),
	 * else the sum over its sources (all three Dagannoth Kings).
	 */
	public int rungKc(BossmanData.Rung r)
	{
		Ledger l = ledger.get();
		if (r.kcSource != null)
		{
			return l.getKc(sourceKey(r.kcSource));
		}
		int total = 0;
		for (String s : r.sources)
		{
			total += l.getKc(sourceKey(s));
		}
		return total;
	}

	/** KC at each of the rung's sources, in data order (for rungs with more than one: the three Moons, the chest). */
	public Map<String, Integer> kcBySource(BossmanData.Rung r)
	{
		Ledger l = ledger.get();
		Map<String, Integer> out = new LinkedHashMap<>();
		for (String s : r.sources)
		{
			out.put(s, l.getKc(sourceKey(s)));
		}
		return out;
	}

	/** The key KC is stored under: the bundled spelling of the source (the ladder may write it differently). */
	private String sourceKey(String s)
	{
		String c = data.canonicalSource(s);
		return c != null ? c : s;
	}

	/** Have / need over everything that gates the rung; need is 0 when nothing does (then the first kill is the rung). */
	public Progress progress(BossmanData.Rung r)
	{
		Ledger l = ledger.get();
		int have = 0;
		int need = 0;
		for (BossmanData.GatingItem gi : r.log.gatingItems)
		{
			if (isMeme(gi) || l.backlog.contains(backlogKey(r, gi.item)))
			{
				continue;
			}
			need++;
			if (anyUnlocked(l, gi.ids))
			{
				have++;
			}
		}
		for (BossmanData.DebtItem d : r.log.debtPetsJars)
		{
			if (!isDebtItemGating(d) || l.backlog.contains(backlogKey(r, d.item)))
			{
				continue;
			}
			need++;
			if (isDebtItemUnlocked(l, d))
			{
				have++;
			}
		}
		if (config.caGate() && r.ca != null)
		{
			for (BossmanData.CaTask t : r.ca.list)
			{
				need++;
				if (isCaDone(l, t))
				{
					have++;
				}
			}
		}
		return new Progress(have, need);
	}

	public boolean isRungComplete(BossmanData.Rung r)
	{
		if (ledger.get().manualComplete.contains(r.boss))
		{
			return true;
		}
		if (r.isFirstKillOnly())
		{
			return rungKc(r) >= 1;
		}
		Progress p = progress(r);
		return p.need == 0 ? rungKc(r) >= 1 : p.complete();
	}

	/**
	 * Lowest incomplete rung = the boss the series is on; null once the ladder is done. Side bosses (non-blocking:
	 * no log of their own, or a log shared with a bigger boss) never hold the ladder up.
	 */
	public BossmanData.Rung currentRung()
	{
		for (BossmanData.Rung r : data.orderedRungs())
		{
			if (r.blocking && !isRungComplete(r))
			{
				return r;
			}
		}
		return null;
	}
}
