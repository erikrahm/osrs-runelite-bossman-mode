/*
 * Bossman Mode: the ruleset, as pure queries over the bundled data, the ledger and the config.
 * Nothing here mutates anything. The rung and log queries are safe from any thread (the ledger's collections are
 * concurrent); isAllowed and freeKind canonicalize item ids, which reads the item cache, so call those on the client
 * thread.
 */
package com.bossman;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.Set;
import java.util.function.Supplier;
import net.runelite.api.gameval.ItemID;

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
	/** an item a started quest on a boss's quest chain needs (canonical id) */
	private final IntPredicate questItem;
	/** lower-cased names of everything unlocked, for the pet/jar fallback; rebuilt when the ledger or its size changes */
	private Ledger namesOf;
	private int namesSize = -1;
	private Set<String> unlockedNames = new HashSet<>();

	public LadderRules(BossmanData data, BossmanModeConfig config, IntUnaryOperator canonicalize,
		IntFunction<Collection<Integer>> variations, Supplier<Ledger> ledger, IntPredicate questItem)
	{
		this.data = data;
		this.config = config;
		this.canonicalize = canonicalize;
		this.variations = variations;
		this.ledger = ledger;
		this.questItem = questItem;
	}

	/**
	 * Unlocked by a boss, free under the Tool / access / bond rules, a shop pack whose contents all are, or needed by
	 * a started quest. Another form of an unlocked item counts too: Prayer potion(3) once Prayer potion(4) dropped, a
	 * degraded Dharok's helm, an uncharged Trident. Client thread.
	 */
	public boolean isAllowed(int itemId)
	{
		int id = canonicalize.applyAsInt(itemId);
		return isAllowed(ledger.get(), id) || questItem.test(id);
	}

	/** Allowed only while the quest that needs it is in progress. Client thread. */
	public boolean isQuestItemOnly(int itemId)
	{
		int id = canonicalize.applyAsInt(itemId);
		return questItem.test(id) && !isAllowed(ledger.get(), id);
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

	/** Why the item is free, if it is under a rule that is switched on, else null. Client thread. */
	public FreeKind freeKind(int itemId)
	{
		return freeKindOfCanonical(canonicalize.applyAsInt(itemId));
	}

	private FreeKind freeKindOfCanonical(int id)
	{
		if (id == ItemID.OSRS_BOND)
		{
			return config.unlockBond() ? FreeKind.BOND : null;
		}
		FreeKind kind = data.getFreeItems().get(id);
		if (kind == null)
		{
			return null;
		}
		switch (kind)
		{
			case TOOL:
				return config.freeTools() ? kind : null;
			case ACCESS:
				return config.freeAccessGear() ? kind : null;
			case SLAYER:
				return config.freeSlayerTools() ? kind : null;
			case BOSS_CRAFTABLE:
				return config.freeBossCraftables() ? kind : null;
			default:
				return kind;
		}
	}

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
		// pets are often recorded under a different id (follower vs inventory form); fall back to the name
		return anyUnlocked(l, d.ids) || (d.item != null && unlockedNames(l).contains(d.item.toLowerCase(Locale.ROOT)));
	}

	private synchronized Set<String> unlockedNames(Ledger l)
	{
		if (l != namesOf || l.unlocks.size() != namesSize)
		{
			Set<String> names = new HashSet<>();
			for (Ledger.Entry en : l.unlocks.values())
			{
				if (en.name != null)
				{
					names.add(en.name.toLowerCase(Locale.ROOT));
				}
			}
			namesOf = l;
			namesSize = l.unlocks.size();
			unlockedNames = names;
		}
		return unlockedNames;
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

	public boolean isCaGating()
	{
		return config.caGate();
	}

	public boolean isCaDone(BossmanData.CaTask t)
	{
		return ledger.get().isCaDone(t.name);
	}

	/**
	 * The rung's KC: its own KC source if it names one (Lunar Chest for the Moons, the Sire for the Sire and Unsired),
	 * else the sum over its sources (all three Dagannoth Kings). A raid's modes, counted apart by the game, add up.
	 */
	public int rungKc(BossmanData.Rung r)
	{
		Ledger l = ledger.get();
		if (r.kcSource != null)
		{
			return l.getKcAllModes(sourceKey(r.kcSource));
		}
		int total = 0;
		for (String s : r.sources)
		{
			total += l.getKcAllModes(sourceKey(s));
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
			out.put(s, l.getKcAllModes(sourceKey(s)));
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
				if (l.isCaDone(t.name))
				{
					have++;
				}
			}
		}
		return new Progress(have, need);
	}

	public boolean isManuallyComplete(BossmanData.Rung r)
	{
		return ledger.get().manualComplete.contains(r.boss);
	}

	public boolean isRungComplete(BossmanData.Rung r)
	{
		if (isManuallyComplete(r))
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
	 * The skilling boss the player chose to do first, until it is complete; otherwise the lowest incomplete rung, the
	 * boss the series is on. Null once the ladder is done. Side bosses (non-blocking: no log of their own, or a log
	 * shared with a bigger boss) never hold the ladder up.
	 */
	public BossmanData.Rung currentRung()
	{
		BossmanData.Rung focused = focusedRung();
		if (focused != null)
		{
			return focused;
		}
		for (BossmanData.Rung r : data.orderedRungs())
		{
			if (r.blocking && !isRungComplete(r))
			{
				return r;
			}
		}
		return null;
	}

	/** The skilling boss the player chose to do ahead of the ladder, while it is incomplete; else null. */
	public BossmanData.Rung focusedRung()
	{
		String boss = ledger.get().focus;
		if (boss == null)
		{
			return null;
		}
		for (BossmanData.Rung r : data.browsableRungs())
		{
			if (r.skilling && r.boss.equals(boss) && !isRungComplete(r))
			{
				return r;
			}
		}
		return null;
	}
}
