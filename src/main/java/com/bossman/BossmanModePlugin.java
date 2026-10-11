/*
 * Copyright (c) 2019, CodePanter <https://github.com/codepanter> (Another Bronzeman Mode)
 * Copyright (c) 2026, Erik Rahm (Bossman Mode)
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.bossman;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.MessageNode;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.RuneLite;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

@PluginDescriptor(
	name = "Bossman Mode",
	description = "Bronzeman-style item locking where only boss drops unlock items. Ledger, GE restrictions and a boss-ladder panel.",
	tags = {"overlay", "bronzeman", "bossman", "bossing", "collection log"}
)
public class BossmanModePlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(BossmanModePlugin.class);
	static final String CONFIG_GROUP = "bossmanmode";
	private static final String LEDGER_FILE = "bossman-mode-ledger.json";
	/** ledgers live in RuneLite/bossman-mode/<world type>-<account hash>/ */
	private static final String LEDGER_DIR = "bossman-mode";
	private static final int GE_SEARCH_BUILD_SCRIPT = 751;
	private static final int GE_REGION_ID = 12598;
	private static final int ITEM_COINS = 995;
	static final int ITEM_BOND = 13190;
	/** "Your Vorkath kill count is: 14", "Your Barrows chest count is: 3", "Your Gauntlet completion count is: 2" */
	private static final Pattern KC_PATTERN = Pattern.compile("Your (.+?) (?:kill |chest |completion )?count is:? ?([\\d,]+)", Pattern.CASE_INSENSITIVE);
	/** "Your completed Chambers of Xeric count is: 12" */
	private static final Pattern RAID_KC_PATTERN = Pattern.compile("Your completed (.+?) count is:? ?([\\d,]+)", Pattern.CASE_INSENSITIVE);
	/** a receipt rule's companion items (Tokkul) count when gained this many ticks after the rule's own item */
	private static final int COMPANION_TICKS = 5;
	/** a failed save is retried after this long */
	private static final long SAVE_RETRY_MS = 10_000;
	/** How long after the last change the ledger is written to disk (writes are coalesced). */
	private static final long SAVE_DELAY_MS = 1500;
	private static final Pattern CA_PATTERN = Pattern.compile("Congratulations, you've completed an? (?:easy|medium|hard|elite|master|grandmaster) combat task: (.+?)\\.?$", Pattern.CASE_INSENSITIVE);

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ItemManager itemManager;
	@Inject private OverlayManager overlayManager;
	@Inject private ConfigManager configManager;
	@Inject private ChatMessageManager chatMessageManager;
	@Inject private ChatCommandManager chatCommandManager;
	@Inject private Notifier notifier;
	@Inject private ClientToolbar clientToolbar;
	@Inject private BossmanModeConfig config;
	@Inject private Gson gson;
	@Inject private UnlockOverlay unlockOverlay;
	@Inject private LockedItemOverlay lockedItemOverlay;
	@Inject private ScheduledExecutorService executor;

	private final BossmanData data = new BossmanData();
	/** placeholder (no file) until a player logs in */
	private volatile Ledger ledger = new Ledger();
	private LadderRules rules;
	private CombatTasks combatTasks;
	/** lower-cased menu options stripped from locked items (from config; replaced whole on the config thread) */
	private volatile Set<String> blockedActions = Collections.emptySet();
	private BufferedImage unlockImage;

	public BossmanData getData() { return data; }
	public Ledger getLedger() { return ledger; }
	public LadderRules getRules() { return rules; }
	public BufferedImage getUnlockImage() { return unlockImage; }

	private BossmanPanel panel;
	private NavigationButton navButton;
	private boolean loggingIn;
	/** the account hash wasn't known yet when the player logged in: open the ledger on the next tick */
	private boolean openPending;
	/** inventory counts at the last change; null until the first inventory after login is seen (that one is the baseline) */
	private volatile Map<Integer, Integer> lastInventory;
	/** worn items as of the last inventory change: an item that moved from here into the inventory wasn't received */
	private Map<Integer, Integer> lastEquipment = Collections.emptyMap();
	/** tick at which each receipt rule last matched, for its companions */
	private final Map<String, Integer> receiptTicks = new HashMap<>();
	/** the ledger with changes not yet on disk (null when clean); a save is scheduled whenever this goes non-null */
	private final AtomicReference<Ledger> dirtyLedger = new AtomicReference<>();
	private final AtomicBoolean saveFailureReported = new AtomicBoolean();
	private int lastScreenshotTick = -1;
	/** a panel rebuild is queued on the Swing thread; further requests until it runs are no-ops */
	private final AtomicBoolean refreshPending = new AtomicBoolean();

	@Provides
	BossmanModeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BossmanModeConfig.class);
	}

	@Override
	protected void startUp()
	{
		data.load(gson);
		data.setExtraSources(config.extraSources());
		rules = new LadderRules(data, config, itemManager::canonicalize,
			id -> ItemVariationMapping.getVariations(ItemVariationMapping.map(id)), () -> ledger);
		combatTasks = new CombatTasks(client);
		blockedActions = parseActions(config.blockedActions());
		unlockImage = ImageUtil.loadImageResource(getClass(), "/item-unlocked.png");
		unlockOverlay.updateScreenshotUnlock(config.screenshotUnlock(), config.includeFrame());
		overlayManager.add(unlockOverlay);
		overlayManager.add(lockedItemOverlay);

		chatCommandManager.registerCommand("!bmcount", this::onCountCommand);
		chatCommandManager.registerCommand("!bmboss", this::onRungCommand);
		chatCommandManager.registerCommand("!bmbackup", this::onBackupCommand);
		if (config.resetCommand())
		{
			chatCommandManager.registerCommand("!bmreset", this::onResetCommand);
		}

		panel = injector.getInstance(BossmanPanel.class);
		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/bossman_icon.png");
		navButton = NavigationButton.builder().tooltip("Bossman Mode").icon(icon).panel(panel).priority(6).build();
		clientToolbar.addNavigation(navButton);

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			// startUp runs on the Swing thread; the ledger and the inventory snapshot need the client
			clientThread.invokeLater(() ->
			{
				openLedger();
				snapshotInventory();
			});
		}
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(unlockOverlay);
		overlayManager.remove(lockedItemOverlay);
		chatCommandManager.unregisterCommand("!bmcount");
		chatCommandManager.unregisterCommand("!bmboss");
		chatCommandManager.unregisterCommand("!bmbackup");
		chatCommandManager.unregisterCommand("!bmreset");
		clientToolbar.removeNavigation(navButton);
		unlockOverlay.clear();
		lastInventory = null;
		openPending = false;
		flushLedger();
	}

	// ------------------------------------------------------------------ lifecycle

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGING_IN)
		{
			loggingIn = true;
		}
		if (e.getGameState() == GameState.LOGGED_IN && loggingIn)
		{
			loggingIn = false;
			lastInventory = null;   // the first inventory the server sends is the baseline, not a gain
			openLedger();
		}
		if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			unlockOverlay.clear();   // banners queued for this account don't carry over to the next
			flushLedger();
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (openPending)
		{
			openLedger();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!CONFIG_GROUP.equals(e.getGroup()))
		{
			return;
		}
		switch (e.getKey())
		{
			case "extraSources":
				data.setExtraSources(config.extraSources());
				break;
			case "blockedActions":
				blockedActions = parseActions(config.blockedActions());
				break;
			case "screenshotUnlock":
			case "includeFrame":
				unlockOverlay.updateScreenshotUnlock(config.screenshotUnlock(), config.includeFrame());
				break;
			case "unlockBond":
			case "memeThreshold":
			case "petsGate":
			case "jarsGate":
			case "caGate":
			case "freeTools":
			case "freeAccessGear":
				refreshPanel();
				break;
			case "resetCommand":
				if (config.resetCommand())
				{
					chatCommandManager.registerCommand("!bmreset", this::onResetCommand);
				}
				else
				{
					chatCommandManager.unregisterCommand("!bmreset");
				}
				break;
			default:
				break;
		}
	}

	/** Load the logged-in account's ledger. Client thread. */
	private void openLedger()
	{
		File file = ledgerFile();
		if (file == null)
		{
			openPending = true;   // RuneLite hasn't been told the account hash yet
			return;
		}
		openPending = false;
		flushLedger(); // whatever the previous account had pending
		importLegacyLedger(file);
		Ledger l = Ledger.load(gson, file);
		// bonds used to be written into the ledger; they are a rule now (LadderRules.freeKind), so the toggle works both ways
		l.unlocks.values().removeIf(en -> en.itemId == ITEM_BOND && Ledger.KIND_DEFAULT.equals(en.kind));
		ledger = l;
		if (l.getSetAside() != null)
		{
			sendChatMessage(l.getFile() == null
				? "Bossman: your ledger file could not be read, and nothing will be saved this session so it isn't overwritten: " + l.getSetAside()
				: "Bossman: your ledger file could not be read and was moved to " + l.getSetAside().getName() + ". Starting a new ledger.");
		}
		receiptTicks.clear();
		syncCombatTasks();
		ledgerChanged();
	}

	/**
	 * RuneLite/bossman-mode/<world type>-<account hash>/ledger. Keyed on the account hash, which is known from the
	 * first login on, unlike RuneLite's RS profile (created a moment after the first login of a new account), and
	 * on the world type so a Leagues or Deadman character never shares a ledger with the main game.
	 */
	private File ledgerFile()
	{
		long hash = client.getAccountHash();
		if (hash == -1)
		{
			return null;
		}
		String type = RuneScapeProfileType.getCurrent(client).name().toLowerCase(Locale.ROOT);
		return new File(new File(new File(RuneLite.RUNELITE_DIR, LEDGER_DIR), type + "-" + hash), LEDGER_FILE);
	}

	/** Versions before 0.1.1 kept the ledger in the RS profile folder (or a folder named after the login): copy it over once. */
	private void importLegacyLedger(File target)
	{
		if (target.exists())
		{
			return;
		}
		Set<String> folders = new java.util.LinkedHashSet<>();
		if (configManager.getRSProfileKey() != null)
		{
			folders.add(configManager.getRSProfileKey());
		}
		String user = client.getUsername();
		if (user != null && !user.trim().isEmpty())
		{
			folders.add(user);
		}
		for (String folder : folders)
		{
			File legacy = new File(new File(RuneLite.RUNELITE_DIR, "profiles/" + folder), LEDGER_FILE);
			if (!legacy.isFile())
			{
				continue;
			}
			try
			{
				target.getParentFile().mkdirs();
				Files.copy(legacy.toPath(), target.toPath());
				log.info("copied legacy ledger {} to {}", legacy, target);
				return;
			}
			catch (Exception ex)
			{
				log.warn("could not copy legacy ledger {}", legacy, ex);
			}
		}
	}

	private static Set<String> parseActions(String csv)
	{
		Set<String> out = new HashSet<>();
		if (csv != null)
		{
			for (String s : csv.split(","))
			{
				String t = s.trim().toLowerCase(Locale.ROOT);
				if (!t.isEmpty())
				{
					out.add(t);
				}
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ persistence

	/** Something in the ledger changed: queue one disk write and one panel rebuild, however many changes arrive before they run. */
	private void ledgerChanged()
	{
		if (dirtyLedger.getAndSet(ledger) == null)
		{
			executor.schedule(this::flushLedger, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
		}
		refreshPanel();
	}

	/**
	 * Write the dirty ledger now if there is one (scheduled save, shutdown, logout, backup, account switch). The
	 * ledger instance is the one that changed, so an account switch can't save one account's changes into the other.
	 *
	 * @return false if a save was needed and failed
	 */
	private boolean flushLedger()
	{
		Ledger l = dirtyLedger.getAndSet(null);
		if (l == null || l.save(gson))
		{
			return true;
		}
		// keep it dirty and try again; tell the player once, so a full disk doesn't silently eat their progress
		if (dirtyLedger.compareAndSet(null, l))
		{
			executor.schedule(this::flushLedger, SAVE_RETRY_MS, TimeUnit.MILLISECONDS);
		}
		if (saveFailureReported.compareAndSet(false, true))
		{
			sendChatMessage("Bossman: could not save your ledger (see the RuneLite log). Retrying.");
		}
		return false;
	}

	// ------------------------------------------------------------------ unlock sources

	/**
	 * NPC kills: the loot the server itself reports for an NPC (what the core Loot Tracker records too). The older
	 * NpcLootReceived guesses from items appearing on the drop tiles, which misses loot that never lands there and
	 * can pick up anything else dropped on those tiles that tick.
	 */
	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot e)
	{
		String name = e.getComposition() != null ? e.getComposition().getName() : null;
		String source = name == null ? null : data.canonicalNpcName(Text.removeTags(name));
		if (source == null || e.getItems().isEmpty())
		{
			return;
		}
		unlockLoot(e.getItems(), source, countLoot(source, 1));
	}

	/** Chests, caskets, permits, crates: posted by the Loot Tracker plugin (must be enabled). */
	@Subscribe
	public void onLootReceived(LootReceived e)
	{
		// events only: NPC records are ServerNpcLoot above, and the tracker also posts empty UNKNOWN records
		// (Maggot King's popped eggs) that are not kills
		if (e.getType() != LootRecordType.EVENT || e.getItems().isEmpty())
		{
			return;
		}
		String name = e.getName();
		if (!data.isEventSource(name))
		{
			return;
		}
		unlockLoot(e.getItems(), name, countLoot(name, Math.max(1, e.getAmount())));
	}

	/** KC after a loot event: counted here unless the game reports this source's KC in chat (or its loot isn't per kill). */
	private int countLoot(String source, int amount)
	{
		return data.isKcChatOnly(source) ? ledger.getKc(source) : ledger.countLoot(source, amount);
	}

	/** Completion rewards and untracked chests: things that unlock the moment they appear in the inventory. */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		if (e.getContainerId() != InventoryID.INV)
		{
			return;
		}
		ItemContainer container = e.getItemContainer();
		if (container == null)
		{
			return;
		}
		Map<Integer, Integer> now = count(container);
		Map<Integer, Integer> before = lastInventory;
		lastInventory = now;
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		Map<Integer, Integer> equipNow = worn != null ? count(worn) : Collections.emptyMap();
		Map<Integer, Integer> equipBefore = lastEquipment;
		lastEquipment = equipNow;
		if (before == null)
		{
			return;   // first inventory after login: the baseline, not a gain
		}
		boolean changed = false;
		if (config.receiptRules() && client.getLocalPlayer() != null && !isBankOpen())
		{
			int region = client.getLocalPlayer().getWorldLocation().getRegionID();
			int tick = client.getTickCount();
			List<Integer> gained = new ArrayList<>();
			for (Map.Entry<Integer, Integer> en : now.entrySet())
			{
				int id = en.getKey();
				int unequipped = Math.max(0, equipBefore.getOrDefault(id, 0) - equipNow.getOrDefault(id, 0));
				if (en.getValue() > before.getOrDefault(id, 0) + unequipped)
				{
					gained.add(id);
				}
			}
			// the rules' own items first, so a companion gained in the same change sees the match
			for (int id : gained)
			{
				for (BossmanData.ReceiptRule rule : data.getSources().receiptRules)
				{
					if (rule.matches(id, region))
					{
						receiptTicks.put(rule.name, tick);
						if (!rules.isAllowed(id))
						{
							recordUnlock(id, Ledger.KIND_RECEIPT, rule.name, null, rule.note, true);
							changed = true;
						}
						break;
					}
				}
			}
			for (int id : gained)
			{
				if (rules.isAllowed(id))
				{
					continue;
				}
				for (BossmanData.ReceiptRule rule : data.getSources().receiptRules)
				{
					Integer at = receiptTicks.get(rule.name);
					if (rule.companions != null && rule.companions.contains(id) && at != null && tick - at <= COMPANION_TICKS)
					{
						recordUnlock(id, Ledger.KIND_RECEIPT, rule.name, null, rule.note, true);
						changed = true;
						break;
					}
				}
			}
		}
		if (changed)
		{
			ledgerChanged();
		}
	}

	private void unlockLoot(Collection<ItemStack> items, String source, int kc)
	{
		for (ItemStack stack : items)
		{
			int id = itemManager.canonicalize(stack.getId());
			if (id <= 1 || stack.getQuantity() <= 0 || ledger.isUnlocked(id))
			{
				continue;
			}
			recordUnlock(id, Ledger.KIND_DROP, source, kc, null, true);
		}
		ledgerChanged(); // KC moved even if nothing new dropped
	}

	/**
	 * The one place unlocks are written. Client thread only (item names). Does not save or refresh:
	 * the caller does that once with ledgerChanged(), however many items it records.
	 */
	private void recordUnlock(int itemId, String kind, String source, Integer kc, String reason, boolean announce)
	{
		Ledger.Entry en = new Ledger.Entry();
		en.itemId = itemId;
		en.kind = kind;
		en.source = source;
		en.kc = kc;
		en.reason = reason;
		en.time = System.currentTimeMillis();
		ItemComposition comp = itemManager.getItemComposition(itemId);
		en.name = comp != null ? comp.getMembersName() : ("item " + itemId);
		ledger.unlocks.put(itemId, en);
		if (announce)
		{
			String caption = kc != null ? source + " — KC " + kc : source;
			if (config.unlockPopup())
			{
				unlockOverlay.addItemUnlock(itemId, caption);   // screenshots, if on, are taken as its banner ends
			}
			else if (config.screenshotUnlock() && lastScreenshotTick != client.getTickCount())
			{
				lastScreenshotTick = client.getTickCount();   // one per loot, however many items it unlocked
				unlockOverlay.screenshot("ItemUnlocked " + en.name + " ");
			}
			if (config.sendChatMessage())
			{
				sendChatMessage("Unlocked: " + en.name + " (" + caption + ")");
			}
			if (config.sendNotification())
			{
				notifier.notify("Bossman unlock: " + en.name + " (" + caption + ")");
			}
		}
	}

	// ------------------------------------------------------------------ player choices

	public void setBacklogged(BossmanData.Rung r, String item, boolean backlogged)
	{
		String key = LadderRules.backlogKey(r, item);
		if (backlogged)
		{
			ledger.backlog.add(key);
		}
		else
		{
			ledger.backlog.remove(key);
		}
		ledgerChanged();
	}

	public void markRungComplete(BossmanData.Rung r, boolean complete)
	{
		if (complete)
		{
			ledger.manualComplete.add(r.boss);
		}
		else
		{
			ledger.manualComplete.remove(r.boss);
		}
		ledgerChanged();
	}

	// ------------------------------------------------------------------ restrictions

	@Subscribe
	public void onScriptPostFired(ScriptPostFired e)
	{
		if (e.getScriptId() == GE_SEARCH_BUILD_SCRIPT && config.restrictGeBuy())
		{
			killSearchResults();
		}
		else if (e.getScriptId() == ScriptID.COLLECTION_DRAW_LIST && config.importCollectionLog())
		{
			importCollectionLogTab();
		}
	}

	/**
	 * When a boss tab of the in-game collection log is drawn, record every obtained item in it as an unlock
	 * (kind "clog"): the account may have had the drop before the plugin was installed.
	 */
	private void importCollectionLogTab()
	{
		Widget header = client.getWidget(InterfaceID.Collection.HEADER_TEXT);
		Widget items = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
		if (header == null || header.getChildren() == null || header.getChildren().length == 0 || items == null || items.getChildren() == null)
		{
			return;
		}
		String tab = Text.removeTags(header.getChild(0).getText()).trim();
		BossmanData.Rung rung = null;
		for (BossmanData.Rung r : data.getLadder().rungs)
		{
			if (r.clogTab != null && r.clogTab.equalsIgnoreCase(tab))
			{
				rung = r;
				break;
			}
		}
		if (rung == null)
		{
			return; // not a tab we track (Slayer, Miscellaneous, All Pets, ...)
		}
		int imported = 0;
		String source = rung.sources.isEmpty() ? rung.boss : rung.sources.get(0);
		for (Widget child : items.getChildren())
		{
			int id = child.getItemId();
			if (id <= 0 || child.getOpacity() != 0)
			{
				continue; // unobtained items are drawn translucent
			}
			int canon = itemManager.canonicalize(id);
			if (ledger.isUnlocked(canon))
			{
				continue;
			}
			recordUnlock(canon, Ledger.KIND_CLOG, source, null, "imported from the collection log (" + tab + ")", false);
			imported++;
		}
		if (imported > 0)
		{
			sendChatMessage("Bossman: imported " + imported + " item" + (imported == 1 ? "" : "s") + " from your " + tab + " collection log.");
			ledgerChanged();
		}
	}

	/**
	 * Strip the options a locked item must not have: 'Offer' on the GE (no selling), 'Buy …' in shops, and the
	 * configured use-actions (wield, eat, bury, …) in the inventory / bank / equipment. Examine, drop, bank and
	 * pick-up are always left alone — a locked item may be held, just not used.
	 */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded e)
	{
		MenuEntry entry = e.getMenuEntry();
		if (entry == null)
		{
			return;
		}
		int itemId = entry.getItemId();
		if (itemId <= 0)
		{
			return;
		}
		String option = Text.removeTags(e.getOption());
		boolean block;
		if ("Offer".equals(option))
		{
			block = config.restrictGeSell();
		}
		else if (option.startsWith("Buy"))
		{
			block = config.restrictShops();
		}
		else
		{
			block = config.blockLockedActions() && blockedActions.contains(option.toLowerCase(Locale.ROOT));
		}
		if (block && !rules.isAllowed(itemId))
		{
			client.getMenu().removeMenuEntry(entry);
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		if ((e.getMenuOption().equals("Trade with") || e.getMenuOption().equals("Accept trade")) && !config.allowTrading())
		{
			e.consume();
			sendChatMessage("You are a bossman. Only bosses can give you things.");
		}
	}

	private void killSearchResults()
	{
		if (client.getLocalPlayer() == null || client.getLocalPlayer().getWorldLocation().getRegionID() != GE_REGION_ID)
		{
			return;
		}
		Widget results = client.getWidget(ComponentID.CHATBOX_GE_SEARCH_RESULTS);
		if (results == null)
		{
			return;
		}
		Widget[] children = results.getDynamicChildren();
		if (children == null || children.length < 2 || children.length % 3 != 0)
		{
			return;
		}
		for (int i = 0; i < children.length; i += 3)
		{
			if (!rules.isAllowed(children[i + 2].getItemId()))
			{
				children[i].setHidden(true);
				children[i + 1].setOpacity(70);
				children[i + 2].setOpacity(70);
			}
		}
	}

	// ------------------------------------------------------------------ combat achievements

	/** The game flips a completion bit; pick it up (also covers tasks done before the plugin, on login). */
	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		if (CombatTasks.isCompletionVarp(e.getVarpId()))
		{
			syncCombatTasks();
		}
	}

	/** Copy every completed task title the game reports into the ledger. Client thread. */
	private void syncCombatTasks()
	{
		Set<String> done = combatTasks.completedNames();
		if (done.isEmpty())
		{
			return;
		}
		int added = 0;
		for (String name : done)
		{
			if (ledger.caDone.add(name))
			{
				added++;
			}
		}
		if (added > 0)
		{
			log.debug("combat achievements: {} task(s) imported from game state", added);
			ledgerChanged();
		}
	}

	// ------------------------------------------------------------------ KC sync from chat

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		if (e.getType() != ChatMessageType.GAMEMESSAGE && e.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		String msg = Text.removeTags(e.getMessage());
		Matcher ca = CA_PATTERN.matcher(msg);
		if (ca.find())
		{
			ledger.caDone.add(ca.group(1).trim());
			ledgerChanged();
			return;
		}
		Matcher m = RAID_KC_PATTERN.matcher(msg);
		if (!m.find())
		{
			m = KC_PATTERN.matcher(msg);
			if (!m.find())
			{
				return;
			}
		}
		int n;
		try
		{
			n = Integer.parseInt(m.group(2).replace(",", ""));
		}
		catch (NumberFormatException ex)
		{
			return;
		}
		// the game writes names with non-breaking spaces at times
		String source = data.canonicalSource(m.group(1).replace('\u00A0', ' '));
		if (source != null)
		{
			ledger.setKcFromChat(source, n);
			ledgerChanged();
		}
	}

	// ------------------------------------------------------------------ chat commands

	private void onCountCommand(ChatMessage chatMessage, String message)
	{
		if (!sentByPlayer(chatMessage))
		{
			return;
		}
		long drops = ledger.unlocks.values().stream().filter(x -> Ledger.KIND_DROP.equals(x.kind)).count();
		long real = ledger.unlocks.values().stream().filter(x -> !Ledger.KIND_DEFAULT.equals(x.kind)).count();
		long free = data.getFreeItems().keySet().stream().filter(id -> rules.freeKind(id) != null).count();
		reply(chatMessage, "Bossman: " + real + " items unlocked (" + drops + " from boss drops), " + free + " free tools/access items.");
	}

	private void onRungCommand(ChatMessage chatMessage, String message)
	{
		if (!sentByPlayer(chatMessage))
		{
			return;
		}
		BossmanData.Rung r = rules.currentRung();
		if (r == null)
		{
			reply(chatMessage, "Bossman: ladder complete.");
			return;
		}
		LadderRules.Progress p = rules.progress(r);
		reply(chatMessage, "Bossman boss " + r.rung + ": " + r.boss + " - " + p.have + "/" + p.need
			+ " to collect, KC " + rules.rungKc(r) + (r.log.expectedKc != null ? " (expected ~" + r.log.expectedKc + ")" : ""));
	}

	private void onBackupCommand(ChatMessage chatMessage, String message)
	{
		if (!sentByPlayer(chatMessage))
		{
			return;
		}
		if (backupLedger())
		{
			sendChatMessage("Bossman: ledger backed up.");
		}
	}

	/** Copy the ledger file next to itself with a timestamp. False if there was nothing to copy or the copy failed. */
	private boolean backupLedger()
	{
		if (!flushLedger())
		{
			sendChatMessage("Bossman: backup failed: the ledger could not be saved first.");
			return false;
		}
		File f = ledger.getFile();
		if (f == null || !f.exists())
		{
			sendChatMessage("Bossman: no ledger to back up yet.");
			return false;
		}
		try
		{
			String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
			Files.copy(f.toPath(), new File(f.getParentFile(), "bossman-mode-ledger_" + stamp + ".backup").toPath(), StandardCopyOption.REPLACE_EXISTING);
			return true;
		}
		catch (Exception ex)
		{
			log.warn("backup failed", ex);
			sendChatMessage("Bossman: backup failed (see the RuneLite log).");
			return false;
		}
	}

	private void onResetCommand(ChatMessage chatMessage, String message)
	{
		if (!sentByPlayer(chatMessage))
		{
			return;
		}
		File f = ledger.getFile();
		if (f != null && f.exists() && !backupLedger())
		{
			sendChatMessage("Bossman: reset cancelled, since the ledger could not be backed up first.");
			return;
		}
		if (f != null && f.exists() && !f.delete())
		{
			sendChatMessage("Bossman: reset failed: the ledger file could not be deleted.");
			return;
		}
		openLedger();
		sendChatMessage("Bossman: ledger reset (a backup was saved next to it).");
	}

	private boolean sentByPlayer(ChatMessage chatMessage)
	{
		MessageNode node = chatMessage.getMessageNode();
		return client.getLocalPlayer() != null && Text.sanitize(node.getName()).equals(Text.sanitize(client.getLocalPlayer().getName()));
	}

	private void reply(ChatMessage chatMessage, String text)
	{
		String response = new ChatMessageBuilder().append(ChatColorType.HIGHLIGHT).append(text).build();
		chatMessage.getMessageNode().setRuneLiteFormatMessage(response);
		client.refreshChat();
	}

	public void sendChatMessage(String text)
	{
		String message = new ChatMessageBuilder().append(ChatColorType.HIGHLIGHT).append(text).build();
		chatMessageManager.queue(QueuedMessage.builder().type(ChatMessageType.CONSOLE).runeLiteFormattedMessage(message).build());
	}

	// ------------------------------------------------------------------ helpers

	/** Take the inventory as it is now as the baseline (plugin turned on while logged in). Client thread. */
	private void snapshotInventory()
	{
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		lastInventory = inv != null ? count(inv) : null;
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		lastEquipment = worn != null ? count(worn) : Collections.emptyMap();
	}

	/** Withdrawals aren't receipts. */
	private boolean isBankOpen()
	{
		Widget bank = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		return bank != null && !bank.isHidden();
	}

	private Map<Integer, Integer> count(ItemContainer container)
	{
		Map<Integer, Integer> m = new HashMap<>();
		for (Item it : container.getItems())
		{
			if (it.getId() > 1 && it.getQuantity() > 0)
			{
				int id = itemManager.canonicalize(it.getId());
				m.merge(id, it.getQuantity(), Integer::sum);
			}
		}
		return m;
	}

	/** Queue one panel rebuild on the Swing thread; calls that arrive while one is queued are folded into it. */
	private void refreshPanel()
	{
		if (panel != null && refreshPending.compareAndSet(false, true))
		{
			SwingUtilities.invokeLater(() ->
			{
				refreshPending.set(false);
				panel.refresh();
			});
		}
	}
}
