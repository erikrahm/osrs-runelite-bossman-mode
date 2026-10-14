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
import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
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
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.plugins.slayer.SlayerPlugin;
import net.runelite.client.plugins.slayer.SlayerPluginService;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

@PluginDescriptor(
	name = "Bossman Mode",
	description = "Bronzeman-style item locking where only boss drops unlock items. Ledger, GE restrictions and a boss-ladder panel.",
	tags = {"overlay", "bronzeman", "bossman", "bossing", "collection log"},
	internalName = "bossman-mode",
	// ledgers written by development builds before 1.0.0 live in .runelite/bossman-mode; RuneLite moves them over once
	legacyDataDirectory = "bossman-mode"
)
@PluginDependency(SlayerPlugin.class)
public class BossmanModePlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(BossmanModePlugin.class);
	static final String CONFIG_GROUP = "bossmanmode";
	private static final String LEDGER_FILE = "bossman-mode-ledger.json";
	private static final int GE_SEARCH_BUILD_SCRIPT = 751;
	private static final int GE_REGION_ID = 12598;
	/** a failed save is retried after this long */
	private static final long SAVE_RETRY_MS = 10_000;
	/** How long after the last change the ledger is written to disk (writes are coalesced). */
	private static final long SAVE_DELAY_MS = 1500;
	/** how often the quest-chain quests' states are re-read */
	private static final int QUEST_POLL_TICKS = 5;

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ItemManager itemManager;
	@Inject private OverlayManager overlayManager;
	@Inject private ChatMessageManager chatMessageManager;
	@Inject private ChatCommandManager chatCommandManager;
	@Inject private Notifier notifier;
	@Inject private ClientToolbar clientToolbar;
	@Inject private BossmanModeConfig config;
	@Inject private Gson gson;
	@Inject private UnlockOverlay unlockOverlay;
	@Inject private LockedItemOverlay lockedItemOverlay;
	@Inject private LockedBankEdgeOverlay lockedBankEdgeOverlay;
	@Inject private ScheduledExecutorService executor;
	@Inject private SlayerPluginService slayerService;
	@Inject private PluginManager pluginManager;
	@Inject private ConfigManager configManager;

	private final BossmanData data = new BossmanData();
	/** placeholder (no file) until a player logs in */
	private volatile Ledger ledger = new Ledger();
	private LadderRules rules;
	private CombatTasks combatTasks;
	/** lower-cased menu options stripped from locked items (from config; replaced whole on the config thread) */
	private volatile Set<String> blockedActions = Collections.emptySet();
	/** standardized NPC names and NPC ids from config that may always be attacked (replaced whole on the config thread) */
	private volatile Set<String> extraAttackable = Collections.emptySet();
	/** client thread */
	private ActiveQuests activeQuests;
	/** ticks until the next quest-state read; set to 0 (from any thread) to read on the next tick */
	private volatile int ticksToQuestPoll;
	/** the restriction's first-login notice and Slayer plugin check are due on the next tick */
	private boolean attackNoticesDue;
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
	/**
	 * ledgers with changes not yet on disk. Each ledger knows its own dirty state, so an account switch or a reset
	 * can't push one ledger's pending (or failing) save out for another's.
	 */
	private final Set<Ledger> unsaved = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean saveFailureReported = new AtomicBoolean();
	/** ladder CA names the game's task table doesn't know were logged (once a session) */
	private boolean caNamesChecked;
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
		activeQuests = new ActiveQuests(data.getAttackable().quests);
		ticksToQuestPoll = 0;
		rules = new LadderRules(data, config, itemManager::canonicalize,
			id -> ItemVariationMapping.getVariations(ItemVariationMapping.map(id)), () -> ledger,
			id -> activeQuests.itemIds().contains(id));
		combatTasks = new CombatTasks(client);
		blockedActions = MenuRules.parseList(config.blockedActions());
		extraAttackable = MenuRules.parseList(config.extraAttackable());
		unlockImage = ImageUtil.loadImageResource(getClass(), "/item-unlocked.png");
		unlockOverlay.updateScreenshotUnlock(config.screenshotUnlock(), config.includeFrame());
		overlayManager.add(unlockOverlay);
		overlayManager.add(lockedItemOverlay);
		overlayManager.add(lockedBankEdgeOverlay);

		chatCommandManager.registerCommand("!bmcount", this::onCountCommand);
		chatCommandManager.registerCommand("!bmboss", this::onRungCommand);
		chatCommandManager.registerCommand("!bmbackup", this::onBackupCommand);
		chatCommandManager.registerCommand("!bmrelock", this::onRelockCommand);
		chatCommandManager.registerCommand("!bmattack", this::onAttackCommand);
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
		overlayManager.remove(lockedBankEdgeOverlay);
		chatCommandManager.unregisterCommand("!bmcount");
		chatCommandManager.unregisterCommand("!bmboss");
		chatCommandManager.unregisterCommand("!bmbackup");
		chatCommandManager.unregisterCommand("!bmrelock");
		chatCommandManager.unregisterCommand("!bmattack");
		chatCommandManager.unregisterCommand("!bmreset");
		clientToolbar.removeNavigation(navButton);
		unlockOverlay.clear();
		lastInventory = null;
		openPending = false;
		flushLedgers();
	}

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
			activeQuests.reset();
			ticksToQuestPoll = 0;
			attackNoticesDue = true;
			openLedger();
		}
		if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			unlockOverlay.clear();   // banners queued for this account don't carry over to the next
			flushLedgers();
		}
		if (e.getGameState() == GameState.LOGIN_SCREEN)
		{
			// no account until the next login: nothing may be written into the last one's ledger meanwhile
			ledger = new Ledger();
			refreshPanel();
		}
	}

	/** The client is closing (no shutDown or logout comes first): write what's pending before it goes. */
	@Subscribe
	public void onClientShutdown(ClientShutdown e)
	{
		e.waitFor(executor.submit(this::flushLedgers));
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (openPending)
		{
			openLedger();
		}
		if (--ticksToQuestPoll <= 0)
		{
			ticksToQuestPoll = QUEST_POLL_TICKS;
			activeQuests.refresh(q -> q.getState(client));
		}
		if (attackNoticesDue)
		{
			attackNoticesDue = false;
			sendAttackNotices();
		}
	}

	/** Once per account: what the restriction does. Every login: a warning when slayer tasks can't be recognised. */
	private void sendAttackNotices()
	{
		if (!config.restrictCombat())
		{
			return;
		}
		if (!config.attackNoticeShown())
		{
			sendChatMessage("Bossman: only bosses can be fought. Slayer tasks, boss arenas and quests on a boss's quest chain"
				+ " are exceptions; '!bmattack <npc>' says why an NPC can or can't be fought. Turn off auto-retaliate to stay strict.");
			configManager.setConfiguration(CONFIG_GROUP, "attackNoticeShown", true);
		}
		boolean slayerOn = pluginManager.getPlugins().stream()
			.anyMatch(p -> p instanceof SlayerPlugin && pluginManager.isPluginEnabled(p));
		if (!slayerOn)
		{
			sendChatMessage("Bossman: the Slayer plugin is off, so your slayer task can't be attacked. Turn it on to fight your task.");
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
				blockedActions = MenuRules.parseList(config.blockedActions());
				break;
			case "extraAttackable":
				extraAttackable = MenuRules.parseList(config.extraAttackable());
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
			case "freeSlayerTools":
			case "freeBossCraftables":
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
		Filepath file;
		try
		{
			file = ledgerFile();
		}
		catch (IOException | IllegalArgumentException e)
		{
			log.warn("could not open the plugin data directory", e);
			openPending = false;
			ledger = new Ledger();
			sendChatMessage("Bossman: could not open the plugin's data folder (see the RuneLite log); nothing will be saved this session.");
			refreshPanel();
			return;
		}
		if (file == null)
		{
			// RuneLite hasn't been told the account hash yet: until it has, nothing may land in another account's ledger
			openPending = true;
			ledger = new Ledger();
			return;
		}
		openPending = false;
		Ledger current = ledger;
		boolean flushed = flushLedgers(); // whatever the previous account had pending
		if (!flushed && current.isDirty() && file.equals(current.getFile()))
		{
			// same account (a hop or relog) and its unsaved changes couldn't be written: keep them in memory rather
			// than reload an older copy from disk; the retry is still scheduled
			receiptTicks.clear();
			syncCombatTasks();
			refreshPanel();
			return;
		}
		Ledger l = Ledger.load(gson, file);
		// bonds used to be written into the ledger; they are a rule now (LadderRules.freeKind), so the toggle works both ways
		l.unlocks.values().removeIf(en -> en.itemId == ItemID.OSRS_BOND && Ledger.KIND_DEFAULT.equals(en.kind));
		ledger = l;
		if (l.getSetAside() != null)
		{
			sendChatMessage(l.getFile() == null
				? "Bossman: your ledger file could not be read, and nothing will be saved this session so it isn't overwritten: " + l.getSetAside()
				: "Bossman: your ledger file could not be read and was moved to " + l.getSetAside().getFileName() + ". Starting a new ledger.");
		}
		receiptTicks.clear();
		syncCombatTasks();
		ledgerChanged();
	}

	/**
	 * <plugin data directory>/<world type>-<account hash>/ledger, or null while the account hash is unknown. Keyed
	 * on the account hash, which is known from the first login on, unlike RuneLite's RS profile (created a moment
	 * after the first login of a new account), and on the world type so a Leagues or Deadman character never shares
	 * a ledger with the main game.
	 */
	private Filepath ledgerFile() throws IOException
	{
		long hash = client.getAccountHash();
		if (hash == -1)
		{
			return null;
		}
		String type = RuneScapeProfileType.getCurrent(client).name().toLowerCase(Locale.ROOT);
		return getPluginDirectory().joinSegment(type + "-" + hash).joinSegment(LEDGER_FILE);
	}

	/** The current ledger changed: queue one disk write and one panel rebuild, however many changes arrive before they run. */
	private void ledgerChanged()
	{
		ledgerChanged(ledger);
	}

	private void ledgerChanged(Ledger l)
	{
		l.markDirty();
		if (unsaved.add(l))
		{
			executor.schedule(this::flushLedgers, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
		}
		refreshPanel();
	}

	/**
	 * Write every ledger with unsaved changes now (scheduled save, shutdown, logout, backup, account switch).
	 *
	 * @return false if a save was needed and failed (it is retried, and the player told once per failure spell)
	 */
	private boolean flushLedgers()
	{
		boolean ok = true;
		for (Ledger l : unsaved)
		{
			if (!l.saveIfDirty(gson))
			{
				ok = false;
				continue;
			}
			unsaved.remove(l);
			if (l.isDirty() && unsaved.add(l))
			{
				// changed again while it was being written
				executor.schedule(this::flushLedgers, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
			}
		}
		if (ok)
		{
			saveFailureReported.set(false);
			return true;
		}
		executor.schedule(this::flushLedgers, SAVE_RETRY_MS, TimeUnit.MILLISECONDS);
		if (saveFailureReported.compareAndSet(false, true))
		{
			sendChatMessage("Bossman: could not save your ledger (see the RuneLite log). Retrying.");
		}
		return false;
	}

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
		if (!data.isEventSource(e.getName()))
		{
			return;
		}
		String source = data.canonicalSource(e.getName());
		String name = source != null ? source : e.getName();
		unlockLoot(e.getItems(), name, countLoot(name, Math.max(1, e.getAmount())));
	}

	/** KC after a loot event: counted here unless the game reports this source's KC in chat (or its loot isn't per kill). */
	private int countLoot(String source, int amount)
	{
		return data.isKcChatOnly(source) ? ledger.getKcAllModes(source) : ledger.countLoot(source, amount);
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
		if (!config.receiptRules() || client.getLocalPlayer() == null || isBankOpen())
		{
			return;   // withdrawals aren't receipts
		}
		List<ReceiptDetector.Receipt> receipts = ReceiptDetector.detect(
			ReceiptDetector.gained(before, now, equipBefore, equipNow),
			client.getLocalPlayer().getWorldLocation().getRegionID(), client.getTickCount(),
			data.getSources().receiptRules, receiptTicks, rules::isAllowed);
		for (ReceiptDetector.Receipt r : receipts)
		{
			recordUnlock(r.itemId, Ledger.KIND_RECEIPT, r.rule.name, null, r.rule.note, true);
		}
		boolean changed = !receipts.isEmpty();
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

	// The panel calls these from the Swing thread; ledger writes happen on the client thread, against the one ledger
	// that is current when they run.

	public void setBacklogged(BossmanData.Rung r, String item, boolean backlogged)
	{
		clientThread.invoke(() ->
		{
			Ledger l = ledger;
			String key = LadderRules.backlogKey(r, item);
			if (backlogged)
			{
				l.backlog.add(key);
			}
			else
			{
				l.backlog.remove(key);
			}
			ledgerChanged(l);
		});
	}

	public void markRungComplete(BossmanData.Rung r, boolean complete)
	{
		clientThread.invoke(() ->
		{
			Ledger l = ledger;
			if (complete)
			{
				l.manualComplete.add(r.boss);
			}
			else
			{
				l.manualComplete.remove(r.boss);
			}
			ledgerChanged(l);
		});
	}

	/** Make a skilling boss current ahead of the ladder; null goes back to following the ladder. */
	public void setFocus(BossmanData.Rung r)
	{
		clientThread.invoke(() ->
		{
			Ledger l = ledger;
			l.focus = r == null ? null : r.boss;
			ledgerChanged(l);
		});
	}

	/** Take back an unlock recorded by mistake (panel right-click, !bmrelock). */
	public void relock(int itemId)
	{
		clientThread.invoke(() ->
		{
			Ledger l = ledger;
			Ledger.Entry en = l.unlocks.remove(itemId);
			if (en != null)
			{
				sendChatMessage("Bossman: re-locked " + en.name + ".");
				ledgerChanged(l);
			}
		});
	}

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
		if (client.isInInstancedRegion())
		{
			// another player's collection log is only shown in a player-owned house (an instance): never import from one
			return;
		}
		String tab = Text.removeTags(header.getChild(0).getText()).trim();
		List<BossmanData.Rung> tabRungs = new ArrayList<>();
		for (BossmanData.Rung r : data.getLadder().rungs)
		{
			if (r.clogTab != null && r.clogTab.equalsIgnoreCase(tab))
			{
				tabRungs.add(r);
			}
		}
		if (tabRungs.isEmpty())
		{
			return; // not a tab we track (Slayer, Miscellaneous, All Pets, ...)
		}
		int imported = 0;
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
			BossmanData.Rung rung = rungForTabItem(tabRungs, canon);
			String source = rung.sources.isEmpty() ? rung.boss : rung.sources.get(0);
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
	 * Several rungs can share a collection-log tab (Vet'ion and Calvar'ion, the two Gauntlets, the two Nightmares):
	 * credit an item to the rung that lists it, else to the main (blocking) one.
	 */
	private static BossmanData.Rung rungForTabItem(List<BossmanData.Rung> tabRungs, int itemId)
	{
		for (BossmanData.Rung r : tabRungs)
		{
			boolean listed = r.log.gatingItems.stream().anyMatch(g -> g.ids.contains(itemId))
				|| r.log.debtPetsJars.stream().anyMatch(d -> d.ids.contains(itemId))
				|| r.drops.stream().anyMatch(d -> d.ids.contains(itemId));
			if (listed)
			{
				return r;
			}
		}
		return tabRungs.stream().filter(r -> r.blocking).findFirst().orElse(tabRungs.get(0));
	}

	/**
	 * Strip the options a locked item must not have ({@link MenuRules}): a locked item may be held, just not used.
	 * Examine, drop, bank and pick-up stay by default. Strip Attack and combat spells from NPCs that may not be fought
	 * ({@link AttackRules}).
	 */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded e)
	{
		MenuEntry entry = e.getMenuEntry();
		if (entry == null)
		{
			return;
		}
		String option = Text.removeTags(e.getOption());
		NPC npc = entry.getNpc();
		if (npc != null && config.restrictCombat() && AttackRules.isCombatOption(option, Text.removeTags(e.getTarget()))
			&& attackReason(npc) == null)
		{
			client.getMenu().removeMenuEntry(entry);
			return;
		}
		int itemId = entry.getItemId();
		if (itemId <= 0)
		{
			return;
		}
		boolean block = MenuRules.strips(option, config.restrictGeSell(), config.restrictShops(), config.blockLockedActions(), blockedActions);
		if (block && !rules.isAllowed(itemId))
		{
			client.getMenu().removeMenuEntry(entry);
		}
	}

	/** Why the NPC may be fought, or null if it may not. */
	private AttackRules.Reason attackReason(NPC npc)
	{
		if (!AttackRules.tutorialDone(client.getVarpValue(VarPlayerID.TUTORIAL)))
		{
			return AttackRules.Reason.TUTORIAL;
		}
		// instanced fights (raids, the Gauntlet, DT2 bosses) are matched on the region they are copied from
		int region = WorldPoint.fromLocalInstance(client, npc.getLocalLocation()).getRegionID();
		String name = npc.getName() == null ? "" : Text.standardize(npc.getName());
		return AttackRules.reason(npc.getId(), name, region, slayerService.getTargets().contains(npc), activeQuests.npcs(),
			extraAttackable, data);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		// another plugin may rebuild a menu after onMenuEntryAdded stripped it
		NPC npc = e.getMenuEntry().getNpc();
		if (npc != null && config.restrictCombat()
			&& AttackRules.isCombatOption(Text.removeTags(e.getMenuOption()), Text.removeTags(e.getMenuTarget()))
			&& attackReason(npc) == null)
		{
			e.consume();
			sendChatMessage("You are a bossman. Only bosses are worth fighting.");
			return;
		}
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
		Widget results = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
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
		checkCaNames();
		int added = 0;
		for (String name : done)
		{
			if (ledger.addCaDone(name))
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

	/**
	 * The ladder's CA names come from the wiki, the completions from the game's own task table, matched by title:
	 * log any ladder task the game doesn't know, since with CA gating on it could never count as done.
	 */
	private void checkCaNames()
	{
		if (caNamesChecked)
		{
			return;
		}
		Set<String> known = new HashSet<>();
		for (String n : combatTasks.allNames())
		{
			known.add(Ledger.caKey(n));
		}
		if (known.isEmpty())
		{
			return;
		}
		caNamesChecked = true;
		for (BossmanData.Rung r : data.getLadder().rungs)
		{
			for (BossmanData.CaTask t : r.ca.list)
			{
				if (!known.contains(Ledger.caKey(t.name)))
				{
					log.warn("combat task '{}' ({}) isn't in the game's task table; it can't be marked done", t.name, r.boss);
				}
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		if (e.getType() != ChatMessageType.GAMEMESSAGE && e.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		// game messages only: other players can type "Your Vorkath kill count is: 9999" in public chat
		KcMessages.Kc kc = KcMessages.parse(Text.removeTags(e.getMessage()), data::canonicalSource);
		if (kc != null)
		{
			ledger.setKcFromChat(kc.source, kc.key, kc.count);
			ledgerChanged();
		}
	}

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
		reply(chatMessage, "Bossman " + r.label() + " - " + p.have + "/" + p.need
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
		if (!flushLedgers())
		{
			sendChatMessage("Bossman: backup failed: the ledger could not be saved first.");
			return false;
		}
		Filepath f = ledger.getFile();
		if (f == null || !f.exists())
		{
			sendChatMessage("Bossman: no ledger to back up yet.");
			return false;
		}
		try
		{
			String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
			f.copyTo(f.getParent().joinSegment("bossman-mode-ledger_" + stamp + ".backup"), StandardCopyOption.REPLACE_EXISTING);
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
		Ledger l = ledger;
		Filepath f = l.getFile();
		if (f != null && f.exists() && !backupLedger())
		{
			sendChatMessage("Bossman: reset cancelled, since the ledger could not be backed up first.");
			return;
		}
		if (!l.discard())
		{
			sendChatMessage("Bossman: reset failed: the ledger file could not be deleted.");
			return;
		}
		openLedger();
		sendChatMessage("Bossman: ledger reset (a backup was saved next to it).");
	}

	/** Why the nearest NPC with that name or id can or can't be fought, and its id to add to the config list. */
	private void onAttackCommand(ChatMessage chatMessage, String message)
	{
		if (!sentByPlayer(chatMessage))
		{
			return;
		}
		String arg = message.length() > "!bmattack".length() ? message.substring("!bmattack".length()).trim() : "";
		if (arg.isEmpty())
		{
			reply(chatMessage, "Bossman: !bmattack <npc name or id>");
			return;
		}
		String wanted = Text.standardize(arg);
		WorldPoint here = client.getLocalPlayer().getWorldLocation();
		NPC npc = client.getTopLevelWorldView().npcs().stream()
			.filter(n -> n.getName() != null && (Text.standardize(n.getName()).equals(wanted) || Integer.toString(n.getId()).equals(wanted)))
			.min(Comparator.comparingInt(n -> n.getWorldLocation().distanceTo(here)))
			.orElse(null);
		if (npc == null)
		{
			reply(chatMessage, "Bossman: no " + arg + " nearby.");
			return;
		}
		String who = npc.getName() + " (id " + npc.getId() + ")";
		if (!config.restrictCombat())
		{
			reply(chatMessage, "Bossman: " + who + " can be fought: 'Only fight bosses' is off.");
			return;
		}
		AttackRules.Reason why = attackReason(npc);
		reply(chatMessage, why == null
			? "Bossman: " + who + " can't be fought. Add its name or id to 'Additional attackable enemies' to allow it."
			: "Bossman: " + who + " can be fought: " + why.text + ".");
	}

	/** !bmrelock &lt;item name or id&gt;: take back one unlock recorded by mistake. */
	private void onRelockCommand(ChatMessage chatMessage, String message)
	{
		if (!sentByPlayer(chatMessage))
		{
			return;
		}
		String arg = message.length() > "!bmrelock".length() ? message.substring("!bmrelock".length()).trim() : "";
		if (arg.isEmpty())
		{
			reply(chatMessage, "Bossman: !bmrelock <item name or id>");
			return;
		}
		Ledger.Entry match = null;
		for (Ledger.Entry en : ledger.unlocks.values())
		{
			if (String.valueOf(en.itemId).equals(arg) || (en.name != null && en.name.equalsIgnoreCase(arg)))
			{
				match = en;
				break;
			}
		}
		if (match == null)
		{
			reply(chatMessage, "Bossman: nothing unlocked called " + arg + ".");
			return;
		}
		relock(match.itemId);
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
