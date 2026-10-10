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
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
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

	private final BossmanData data = new BossmanData();
	private Ledger ledger = new Ledger();
	private BufferedImage unlockImage;

	public BossmanData getData() { return data; }
	public Ledger getLedger() { return ledger; }
	public BufferedImage getUnlockImage() { return unlockImage; }

	private BossmanPanel panel;
	private NavigationButton navButton;
	private boolean loggingIn;
	private Map<Integer, Integer> lastInventory = new HashMap<>();

	@Provides
	BossmanModeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BossmanModeConfig.class);
	}

	@Override
	protected void startUp()
	{
		data.load(gson);
		data.addExtraSources(config.extraSources());
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

		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				openLedger();
			}
		});
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
		lastInventory.clear();
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
				data.load(gson);
				data.addExtraSources(config.extraSources());
				break;
			case "screenshotUnlock":
			case "includeFrame":
				unlockOverlay.updateScreenshotUnlock(config.screenshotUnlock(), config.includeFrame());
				break;
			case "memeThreshold":
			case "petsGate":
			case "jarsGate":
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
		String profileKey = configManager.getRSProfileKey();
		String folderName = profileKey != null ? profileKey : client.getUsername();
		File folder = new File(RuneLite.RUNELITE_DIR, "profiles/" + folderName);
		ledger = Ledger.load(gson, new File(folder, LEDGER_FILE));
		if (config.unlockBond() && !ledger.isUnlocked(ITEM_BOND))
		{
			recordUnlock(ITEM_BOND, Ledger.KIND_DEFAULT, "default", null, "bonds buy membership", false);
		}
		ledger.save(gson);
		snapshotInventory();
		refreshPanel();
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
		String clean = Text.removeTags(name);
		if (!data.isNpcSource(clean))
		{
			return;
		}
		int kc = ledger.incrementKc(canonicalSource(clean));
		unlockLoot(e.getItems(), canonicalSource(clean), kc);
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
		if (config.receiptRules() && client.getLocalPlayer() != null)
		{
			int region = client.getLocalPlayer().getWorldLocation().getRegionID();
			for (Map.Entry<Integer, Integer> en : now.entrySet())
			{
				int id = en.getKey();
				if (en.getValue() <= lastInventory.getOrDefault(id, 0) || isAllowed(id))
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
						break;
					}
				}
			}
		}
		lastInventory = now;
	}

	private void unlockLoot(Collection<ItemStack> items, String source, int kc)
	{
		boolean any = false;
		for (ItemStack stack : items)
		{
			int id = itemManager.canonicalize(stack.getId());
			if (id <= 1 || stack.getQuantity() <= 0 || ledger.isUnlocked(id))
			{
				continue;
			}
			recordUnlock(id, Ledger.KIND_DROP, source, kc, null, true);
			any = true;
		}
		if (!any)
		{
			ledger.save(gson); // KC changed
		}
		refreshPanel();
	}

	/** The one place unlocks are written. Must run on the client thread (item names). */
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
		ledger.save(gson);
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
		refreshPanel();
	}

	/** Lesser/greater pairs and brothers share a log; KC is tracked per NPC name, this just normalises case. */
	private String canonicalSource(String npcName)
	{
		for (String s : data.getSources().npcNames)
		{
			if (s.equalsIgnoreCase(npcName))
			{
				return s;
			}
		}
		return npcName;
	}

	// ------------------------------------------------------------------ queries

	/** Unlocked by a boss, or free under the Tool / access rules. */
	public boolean isAllowed(int itemId)
	{
		int id = itemManager.canonicalize(itemId);
		if (id == ITEM_COINS)
		{
			return ledger.isUnlocked(ITEM_COINS);
		}
		if (ledger.isUnlocked(id))
		{
			return true;
		}
		String kind = freeKind(id);
		return kind != null;
	}

	/** "tool" / "access" if the item is free under a rule that is switched on, else null. */
	public String freeKind(int itemId)
	{
		String kind = data.getFreeItems().get(itemManager.canonicalize(itemId));
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

	public static String backlogKey(BossmanData.Rung r, String item)
	{
		return r.boss + "|" + item;
	}

	public boolean isBacklogged(BossmanData.Rung r, String item)
	{
		return ledger.backlog.contains(backlogKey(r, item));
	}

	public void setBacklogged(BossmanData.Rung r, String item, boolean backlogged)
	{
		if (backlogged)
		{
			ledger.backlog.add(backlogKey(r, item));
		}
		else
		{
			ledger.backlog.remove(backlogKey(r, item));
		}
		ledger.save(gson);
		refreshPanel();
	}

	/** Rarer than the configured threshold → optional "meme grind", does not gate the ladder. */
	public boolean isMeme(BossmanData.GatingItem gi)
	{
		return gi.denominator != null && gi.denominator >= config.memeThreshold();
	}

	public boolean isJar(BossmanData.DebtItem d)
	{
		return d.item != null && d.item.toLowerCase(java.util.Locale.ROOT).startsWith("jar of");
	}

	/** Pets/jars gate only when the matching option is on. */
	public boolean isDebtItemGating(BossmanData.DebtItem d)
	{
		return isJar(d) ? config.jarsGate() : config.petsGate();
	}

	public boolean isDebtItemUnlocked(BossmanData.DebtItem d)
	{
		for (int id : d.ids)
		{
			if (ledger.isUnlocked(id))
			{
				return true;
			}
		}
		for (Ledger.Entry en : ledger.unlocks.values())
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
		for (int id : gi.ids)
		{
			if (ledger.isUnlocked(id))
			{
				return true;
			}
		}
		return false;
	}

	public int rungKc(BossmanData.Rung r)
	{
		int total = 0;
		for (String s : r.sources)
		{
			total += ledger.getKc(s);
		}
		return total;
	}

	public boolean isRungComplete(BossmanData.Rung r)
	{
		if (ledger.manualComplete.contains(r.boss))
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
		if (!anyGating)
		{
			return rungKc(r) >= 1;
		}
		return true;
	}

	/** Lowest incomplete rung = the boss the series is on. */
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
		ledger.save(gson);
		refreshPanel();
	}

	// ------------------------------------------------------------------ restrictions

	@Subscribe
	public void onScriptPostFired(ScriptPostFired e)
	{
		if (e.getScriptId() == GE_SEARCH_BUILD_SCRIPT && config.restrictGeBuy())
		{
			killSearchResults();
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded e)
	{
		if (!config.restrictGeSell())
		{
			return;
		}
		MenuEntry entry = e.getMenuEntry();
		if (entry == null || !"Offer".equals(e.getOption()))
		{
			return;
		}
		int itemId = entry.getItemId();
		if (itemId > 0 && !isAllowed(itemId))
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
			if (!isAllowed(children[i + 2].getItemId()))
			{
				children[i].setHidden(true);
				children[i + 1].setOpacity(70);
				children[i + 2].setOpacity(70);
			}
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
		for (String s : data.getSources().npcNames)
		{
			if (s.equalsIgnoreCase(boss))
			{
				ledger.setKcIfHigher(s, n);
				ledger.save(gson);
				refreshPanel();
				return;
			}
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
		long free = data.getFreeItems().keySet().stream().filter(id -> freeKind(id) != null).count();
		reply(chatMessage, "Bossman: " + real + " items unlocked (" + drops + " from boss drops), " + free + " free tools/access items.");
	}

	private void onRungCommand(ChatMessage chatMessage, String message)
	{
		if (!sentByPlayer(chatMessage))
		{
			return;
		}
		BossmanData.Rung r = currentRung();
		if (r == null)
		{
			reply(chatMessage, "Bossman: ladder complete.");
			return;
		}
		int have = 0;
		int need = 0;
		for (BossmanData.GatingItem gi : r.log.gatingItems)
		{
			if (isMeme(gi) || isBacklogged(r, gi.item))
			{
				continue;
			}
			need++;
			if (isLogItemUnlocked(gi))
			{
				have++;
			}
		}
		reply(chatMessage, "Bossman boss " + r.rung + ": " + r.boss + " - " + have + "/" + need
			+ " log items, KC " + rungKc(r) + (r.log.expectedKc != null ? " (expected ~" + r.log.expectedKc + ")" : ""));
	}

	private void onBackupCommand(ChatMessage chatMessage, String message)
	{
		if (!sentByPlayer(chatMessage))
		{
			return;
		}
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

	private void refreshPanel()
	{
		if (panel != null)
		{
			SwingUtilities.invokeLater(panel::refresh);
		}
	}
}
