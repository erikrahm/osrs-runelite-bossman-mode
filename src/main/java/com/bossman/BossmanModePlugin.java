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
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import net.runelite.api.NPC;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
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
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
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
	private static final int GE_SEARCH_BUILD_SCRIPT = 751;
	private static final int GE_REGION_ID = 12598;
	private static final int ITEM_COINS = 995;
	private static final int ITEM_BOND = 13190;
	private static final Pattern KC_PATTERN = Pattern.compile("Your (.+?) kill count is: ?([\\d,]+)");
	/** How long after the last change the ledger is written to disk (writes are coalesced). */
	private static final long SAVE_DELAY_MS = 1500;
	private static final Pattern CA_PATTERN = Pattern.compile("Congratulations, you've completed an? (?:easy|medium|hard|elite|master|grandmaster) combat task: (.+?)\\.?$", Pattern.CASE_INSENSITIVE);

	@Inject private Client client;
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
	private volatile Ledger ledger = new Ledger();
	private LadderRules rules;
	private CombatTasks combatTasks;
	/** lower-cased menu options stripped from locked items (from config) */
	private Set<String> blockedActions = Collections.emptySet();
	private BufferedImage unlockImage;

	public BossmanData getData() { return data; }
	public Ledger getLedger() { return ledger; }
	public LadderRules getRules() { return rules; }
	public BufferedImage getUnlockImage() { return unlockImage; }

	private BossmanPanel panel;
	private NavigationButton navButton;
	private boolean loggingIn;
	private Map<Integer, Integer> lastInventory = new HashMap<>();
	/** a save is scheduled (or running); cleared when it completes */
	private final AtomicBoolean saveDirty = new AtomicBoolean();
	private ScheduledFuture<?> pendingSave;
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
		rules = new LadderRules(data, config, itemManager, () -> ledger);
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
			openLedger();
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
		lastInventory.clear();
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
			openLedger();
		}
		if (e.getGameState() == GameState.LOGIN_SCREEN)
		{
			flushLedger();
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

	private void openLedger()
	{
		flushLedger(); // whatever the previous account/profile had pending
		String profileKey = configManager.getRSProfileKey();
		String folderName = profileKey != null ? profileKey : client.getUsername();
		File folder = new File(RuneLite.RUNELITE_DIR, "profiles/" + folderName);
		ledger = Ledger.load(gson, new File(folder, LEDGER_FILE));
		if (config.unlockBond() && !ledger.isUnlocked(ITEM_BOND))
		{
			recordUnlock(ITEM_BOND, Ledger.KIND_DEFAULT, "default", null, "bonds buy membership", false);
		}
		snapshotInventory();
		syncCombatTasks();
		ledgerChanged();
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
		if (saveDirty.compareAndSet(false, true))
		{
			pendingSave = executor.schedule(this::flushLedger, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
		}
		refreshPanel();
	}

	/** Write the ledger now if anything is pending (shutdown, logout, backup, profile switch). */
	private void flushLedger()
	{
		ScheduledFuture<?> f = pendingSave;
		if (f != null)
		{
			f.cancel(false);
		}
		if (saveDirty.getAndSet(false))
		{
			ledger.save(gson);
		}
	}

	// ------------------------------------------------------------------ unlock sources

	/** NPC kills: posted by the client for every NPC whose loot is attributed to the player. */
	@Subscribe
	public void onNpcLootReceived(NpcLootReceived e)
	{
		NPC npc = e.getNpc();
		String name = npc != null ? npc.getName() : null;
		if (name == null)
		{
			return;
		}
		String source = data.canonicalNpcName(Text.removeTags(name));
		if (source == null)
		{
			return;
		}
		unlockLoot(e.getItems(), source, ledger.incrementKc(source));
	}

	/** Chests, caskets, permits, crates: posted by the Loot Tracker plugin (must be enabled). */
	@Subscribe
	public void onLootReceived(LootReceived e)
	{
		if (e.getType() == LootRecordType.NPC)
		{
			return; // handled by NpcLootReceived
		}
		String name = e.getName();
		if (!data.isEventSource(name))
		{
			return;
		}
		int kc = ledger.incrementKc(name);
		unlockLoot(e.getItems(), name, kc);
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
		boolean changed = false;
		if (config.receiptRules() && client.getLocalPlayer() != null)
		{
			int region = client.getLocalPlayer().getWorldLocation().getRegionID();
			for (Map.Entry<Integer, Integer> en : now.entrySet())
			{
				int id = en.getKey();
				if (en.getValue() <= lastInventory.getOrDefault(id, 0) || rules.isAllowed(id))
				{
					continue; // not a gain, or already fine
				}
				for (BossmanData.ReceiptRule rule : data.getSources().receiptRules)
				{
					boolean byItem = rule.itemIds != null && rule.itemIds.contains(id);
					boolean byRegion = rule.regions != null && rule.regions.contains(region);
					if (byItem || byRegion)
					{
						recordUnlock(id, Ledger.KIND_RECEIPT, rule.name, null, rule.note, true);
						changed = true;
						break;
					}
				}
			}
		}
		lastInventory = now;
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
				unlockOverlay.addItemUnlock(itemId, caption);
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
		Matcher m = KC_PATTERN.matcher(msg);
		if (!m.find())
		{
			return;
		}
		String boss = m.group(1).trim();
		int n;
		try
		{
			n = Integer.parseInt(m.group(2).replace(",", ""));
		}
		catch (NumberFormatException ex)
		{
			return;
		}
		String source = data.canonicalNpcName(boss);
		if (source != null)
		{
			ledger.setKcIfHigher(source, n);
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
		int have = 0;
		int need = 0;
		for (BossmanData.GatingItem gi : r.log.gatingItems)
		{
			if (rules.isMeme(gi) || rules.isBacklogged(r, gi.item))
			{
				continue;
			}
			need++;
			if (rules.isLogItemUnlocked(gi))
			{
				have++;
			}
		}
		reply(chatMessage, "Bossman boss " + r.rung + ": " + r.boss + " - " + have + "/" + need
			+ " log items, KC " + rules.rungKc(r) + (r.log.expectedKc != null ? " (expected ~" + r.log.expectedKc + ")" : ""));
	}

	private void onBackupCommand(ChatMessage chatMessage, String message)
	{
		if (!sentByPlayer(chatMessage))
		{
			return;
		}
		flushLedger();
		File f = ledger.getFile();
		if (f == null || !f.exists())
		{
			return;
		}
		try
		{
			String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
			Files.copy(f.toPath(), new File(f.getParentFile(), "bossman-mode-ledger_" + stamp + ".backup").toPath(), StandardCopyOption.REPLACE_EXISTING);
			sendChatMessage("Bossman: ledger backed up.");
		}
		catch (Exception ex)
		{
			log.warn("backup failed", ex);
		}
	}

	private void onResetCommand(ChatMessage chatMessage, String message)
	{
		if (!sentByPlayer(chatMessage))
		{
			return;
		}
		onBackupCommand(chatMessage, message);
		File f = ledger.getFile();
		if (f != null)
		{
			f.delete();
		}
		openLedger();
		sendChatMessage("Bossman: ledger reset.");
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

	private void snapshotInventory()
	{
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		lastInventory = inv != null ? count(inv) : new HashMap<>();
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
