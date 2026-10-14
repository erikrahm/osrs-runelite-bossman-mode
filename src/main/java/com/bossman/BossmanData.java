/*
 * Bossman Mode: the hand-written model of the bundled rule files (tools / access / sources / ladder / attackable) and
 * lookups over them. tools, access, sources and ladder are generated in the series project (plugin-data/build_data.py,
 * then fix_data.py) from the OSRS wiki scrapes; attackable.json is maintained by hand.
 */
package com.bossman;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.client.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BossmanData
{
	private static final Logger log = LoggerFactory.getLogger(BossmanData.class);

	public static class ItemRef
	{
		public int id;
		public String name;
		public String note;
	}

	public static class Tools
	{
		public Map<String, List<ItemRef>> groups = new HashMap<>();
	}

	public static class AccessGroup
	{
		public String note;
		public List<ItemRef> items = new ArrayList<>();
	}

	public static class Access
	{
		public Map<String, AccessGroup> groups = new HashMap<>();
	}

	/**
	 * Items that unlock the moment they enter the inventory. Both conditions must hold when both are given: one of
	 * {@code itemIds}, gained inside one of {@code regions}. {@code companions} unlock only when gained within a few
	 * ticks of a match (Tokkul comes with the Fire cape, but is also sold and dropped all over Mor Ul Rek).
	 */
	public static class ReceiptRule
	{
		public String name;
		@SerializedName("item_ids")
		public List<Integer> itemIds;
		public List<Integer> regions;
		public List<Integer> companions;
		public String note;

		public boolean matches(int itemId, int region)
		{
			return (itemIds == null || itemIds.contains(itemId)) && (regions == null || regions.contains(region));
		}
	}

	public static class Sources
	{
		@SerializedName("npc_names")
		public List<String> npcNames = new ArrayList<>();
		@SerializedName("loot_tracker_events")
		public Map<String, String> lootTrackerEvents = new HashMap<>();
		@SerializedName("receipt_rules")
		public List<ReceiptRule> receiptRules = new ArrayList<>();
		/** shop item pack id → the item ids inside it; a pack is allowed when all of its contents are */
		@SerializedName("item_packs")
		public Map<Integer, List<Integer>> itemPacks = new HashMap<>();
		/** name in a "Your X kill count is" message (lower case) → the source it counts, where they differ */
		@SerializedName("kc_aliases")
		public Map<String, String> kcAliases = new HashMap<>();
		/** sources whose loot events don't map one-to-one to kills (Maggot King's eggs, Doom's delve levels): KC from chat only */
		@SerializedName("kc_chat_only")
		public Set<String> kcChatOnly = new HashSet<>();
	}

	public static class GatingItem
	{
		public String item;
		public List<Integer> ids = new ArrayList<>();
		public String rate;
		/** 1/denominator per kill (null: unknown or not a flat rate); items at or above the meme threshold do not gate the ladder */
		public Integer denominator;
		/** never gates, whatever its rate: mode-locked (Challenge/Hard Mode, high invocation) or a KC-milestone reward */
		public boolean optional;
		/** why it is optional, for the panel */
		public String note;
	}

	public static class DebtItem
	{
		public String item;
		public List<Integer> ids = new ArrayList<>();
		public String rate;
		/** KC at which the pet/jar is "owed" (null when the rate is unknown) */
		@SerializedName("debt_at_kc")
		public Integer debtAtKc;
	}

	public static class Gate
	{
		public String type;     // quest / diary / skill / combat / task / item / wilderness / kc / none / other
		public String text;
		public String skill;    // for type=skill
		public Integer level;
		@SerializedName("item_ids")
		public List<Integer> itemIds = new ArrayList<>();
	}

	public static class RungLog
	{
		@SerializedName("gating_items")
		public List<GatingItem> gatingItems = new ArrayList<>();
		@SerializedName("debt_pets_jars")
		public List<DebtItem> debtPetsJars = new ArrayList<>();
		@SerializedName("expected_kc")
		public Integer expectedKc;
	}

	public static class Drop
	{
		public String item;
		public List<Integer> ids = new ArrayList<>();
		public String rate;
		@SerializedName("in_log")
		public boolean inLog;
	}

	public static class CaTask
	{
		public String name;
		public String description;
		public String type;
		public String tier;
		public int points;
	}

	public static class Ca
	{
		public int tasks;
		public int points;
		public List<CaTask> list = new ArrayList<>();
	}

	public static class Rung
	{
		public String boss;
		@SerializedName("combat_level")
		public Integer combatLevel;
		public String gate;
		public List<Gate> gates = new ArrayList<>();
		@SerializedName("wiki_page")
		public String wikiPage;
		public Integer rung;          // null for floating (Tempoross / Wintertodt)
		public boolean floating;
		public boolean blocking;
		public String completion;
		public List<String> sources = new ArrayList<>();
		/** the one source whose KC is the rung's (e.g. Lunar Chest for the Moons); null: the sum of all sources */
		@SerializedName("kc_source")
		public String kcSource;
		@SerializedName("clog_tab")
		public String clogTab;
		public RungLog log = new RungLog();
		public Ca ca = new Ca();
		/** everything the boss (or its chest) can drop, best rate first */
		public List<Drop> drops = new ArrayList<>();

		public boolean isFirstKillOnly()
		{
			return completion != null && completion.startsWith("first kill");
		}
	}

	public static class Ladder
	{
		public List<Rung> rungs = new ArrayList<>();
	}

	/** Map regions where every NPC may be fought. Only ids is read; the rest documents the entry. */
	public static class ArenaRegion
	{
		public List<Integer> ids = new ArrayList<>();
		public String boss;
		/** "fight": the boss's own arena, adds and waves included; "access": monsters there drop what opens a boss */
		public String role;
		public String area;
	}

	/** A monster that drops what opens a boss, or a fight add whose arena region isn't known. */
	public static class AccessNpc
	{
		public String name;
		/** when given, only these NPC ids match: the name is shared with monsters outside the fight */
		public List<Integer> ids;
		public String boss;
		public String why;
	}

	public static class QuestNeeds
	{
		public List<String> npcs = new ArrayList<>();
		/** needed items that otherwise only come from shops or ordinary monsters */
		public List<ItemRef> items = new ArrayList<>();
		public String wiki;
	}

	/** What may be attacked besides the bosses themselves, and what a started quest opens up. */
	public static class Attackable
	{
		public List<ArenaRegion> regions = new ArrayList<>();
		@SerializedName("access_npcs")
		public List<AccessNpc> accessNpcs = new ArrayList<>();
		/** RuneLite Quest constant → the NPCs that quest has you fight and the items it needs */
		public Map<String, QuestNeeds> quests = new HashMap<>();
	}

	private Tools tools;
	private Access access;
	private Sources sources;
	private Ladder ladder;
	private Attackable attackable;
	private final Set<Integer> arenaRegions = new HashSet<>();
	/** standardized names of the access NPCs listed without ids */
	private final Set<String> accessNpcs = new HashSet<>();
	private final Set<Integer> accessNpcIds = new HashSet<>();

	/** access.json groups whose items have their own setting */
	static final String SLAYER_TOOLS_GROUP = "slayer tools";
	static final String BOSS_CRAFTABLES_GROUP = "boss area craftables";

	/** item id → why it's free (whether that rule is switched on is LadderRules' call) */
	private final Map<Integer, FreeKind> freeItems = new HashMap<>();
	/**
	 * lower-cased NPC name → name as written in the bundled list (the key KC is tracked under). Replaced whole, never
	 * edited, when the extra-sources config changes: the client thread reads it while the config panel writes.
	 */
	private volatile Map<String, String> npcSources = Collections.emptyMap();
	/** loot-tracker event names (exact) that may unlock items; replaced whole like npcSources */
	private volatile Set<String> eventSources = Collections.emptySet();
	/** lower-cased event name → the exact name; replaced whole like npcSources */
	private volatile Map<String, String> eventsByLowerName = Collections.emptyMap();
	/** the ladder in order, without the floating rungs (computed once on load) */
	private List<Rung> orderedRungs = Collections.emptyList();
	/** the user's "extra sources" config, kept so the source sets can be rebuilt without re-reading the bundled files */
	private String extraSources = "";

	public Tools getTools() { return tools; }
	public Access getAccess() { return access; }
	public Sources getSources() { return sources; }
	public Ladder getLadder() { return ladder; }
	public Attackable getAttackable() { return attackable; }
	public Map<Integer, FreeKind> getFreeItems() { return freeItems; }
	public Set<String> getEventSources() { return eventSources; }

	public void load(Gson gson)
	{
		tools = read(gson, "/bossman/tools.json", Tools.class);
		access = read(gson, "/bossman/access.json", Access.class);
		sources = read(gson, "/bossman/sources.json", Sources.class);
		ladder = read(gson, "/bossman/ladder.json", Ladder.class);
		attackable = read(gson, "/bossman/attackable.json", Attackable.class);

		freeItems.clear();
		for (List<ItemRef> group : tools.groups.values())
		{
			for (ItemRef r : group)
			{
				freeItems.put(r.id, FreeKind.TOOL);
			}
		}
		for (Map.Entry<String, AccessGroup> group : access.groups.entrySet())
		{
			FreeKind kind = SLAYER_TOOLS_GROUP.equals(group.getKey()) ? FreeKind.SLAYER
				: BOSS_CRAFTABLES_GROUP.equals(group.getKey()) ? FreeKind.BOSS_CRAFTABLE
				: FreeKind.ACCESS;
			for (ItemRef r : group.getValue().items)
			{
				freeItems.putIfAbsent(r.id, kind);
			}
		}
		List<Rung> ordered = new ArrayList<>();
		for (Rung r : ladder.rungs)
		{
			if (!r.floating)
			{
				ordered.add(r);
			}
		}
		orderedRungs = Collections.unmodifiableList(ordered);
		arenaRegions.clear();
		accessNpcs.clear();
		accessNpcIds.clear();
		for (ArenaRegion r : attackable.regions)
		{
			arenaRegions.addAll(r.ids);
		}
		for (AccessNpc n : attackable.accessNpcs)
		{
			if (n.ids != null && !n.ids.isEmpty())
			{
				accessNpcIds.addAll(n.ids);
			}
			else
			{
				accessNpcs.add(Text.standardize(n.name));
			}
		}
		rebuildSources();
		log.info("Bossman data loaded: {} free items, {} npc sources, {} event sources, {} rungs",
			freeItems.size(), npcSources.size(), eventSources.size(), ladder.rungs.size());
	}

	/** Comma-separated NPC / event names from config that may unlock items on top of the bundled list. */
	public void setExtraSources(String csv)
	{
		extraSources = csv == null ? "" : csv;
		rebuildSources();
	}

	private void rebuildSources()
	{
		Map<String, String> npcs = new HashMap<>();
		Set<String> events = new HashSet<>();
		for (String n : sources.npcNames)
		{
			npcs.put(n.toLowerCase(Locale.ROOT), n);
		}
		events.addAll(sources.lootTrackerEvents.keySet());
		for (String s : extraSources.split(","))
		{
			String t = s.trim();
			if (!t.isEmpty())
			{
				npcs.putIfAbsent(t.toLowerCase(Locale.ROOT), t);
				events.add(t);
			}
		}
		Map<String, String> byLower = new HashMap<>();
		for (String e : events)
		{
			byLower.putIfAbsent(e.toLowerCase(Locale.ROOT), e);
		}
		npcSources = Collections.unmodifiableMap(npcs);
		eventSources = Collections.unmodifiableSet(events);
		eventsByLowerName = Collections.unmodifiableMap(byLower);
	}

	public boolean isNpcSource(String npcName)
	{
		return npcName != null && npcSources.containsKey(npcName.toLowerCase(Locale.ROOT));
	}

	/** The bundled spelling of an NPC source name (KC and unlocks are keyed by it), or null if it isn't a source. */
	public String canonicalNpcName(String npcName)
	{
		return npcName == null ? null : npcSources.get(npcName.toLowerCase(Locale.ROOT));
	}

	/**
	 * The source a name refers to, however the game spells it: an NPC or event name in any case, the same without
	 * a leading "The" ("Nightmare" → "The Nightmare"), or a bundled alias ("Gauntlet" → "Crystalline Hunllef").
	 * Null if it isn't a source.
	 */
	public String canonicalSource(String name)
	{
		if (name == null)
		{
			return null;
		}
		String low = name.trim().toLowerCase(Locale.ROOT);
		String alias = sources.kcAliases.get(low);
		if (alias != null)
		{
			return alias;
		}
		String npc = npcSources.get(low);
		if (npc == null)
		{
			npc = npcSources.get("the " + low);
		}
		return npc != null ? npc : eventsByLowerName.get(low);
	}

	public boolean isKcChatOnly(String source)
	{
		return sources.kcChatOnly.contains(source);
	}

	public boolean isEventSource(String eventName)
	{
		return eventName != null && (eventSources.contains(eventName) || npcSources.containsKey(eventName.toLowerCase(Locale.ROOT)));
	}

	/** A boss arena (adds and waves are part of the fight) or an area whose monsters give boss access. */
	public boolean isArenaRegion(int regionId)
	{
		return arenaRegions.contains(regionId);
	}

	/** Entries with ids match only by id; the others by name (a Text.standardize'd one). */
	public boolean isAccessNpc(int npcId, String standardName)
	{
		return accessNpcIds.contains(npcId) || accessNpcs.contains(standardName);
	}

	public List<Rung> orderedRungs()
	{
		return orderedRungs;
	}

	private static <T> T read(Gson gson, String path, Class<T> type)
	{
		try (InputStream in = BossmanData.class.getResourceAsStream(path))
		{
			if (in == null)
			{
				throw new IllegalStateException("missing resource " + path);
			}
			try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				return gson.fromJson(r, type);
			}
		}
		catch (Exception e)
		{
			throw new IllegalStateException("failed to load " + path, e);
		}
	}
}
