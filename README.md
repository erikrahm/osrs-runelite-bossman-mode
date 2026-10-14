# Bossman Mode

A RuneLite plugin for a "bossman" account: a bronzeman-style ruleset where **items are only ever unlocked by boss drops**.
Forked from [Another Bronzeman Mode](https://github.com/CodePanter/another-bronzeman-mode) (BSD-2).

## What it does
- **Source-gated unlocks.** An item becomes *unlocked* only when it drops from an allowlisted source: the 75 boss NPCs,
  the chest/event loot the RuneLite Loot Tracker reports (Barrows, Lunar Chest, CoX/ToB/ToA, Fortis Colosseum, Unsired,
  Tempoross, Wintertodt, Doom of Mokhaiotl), plus completion rewards (Fire/Infernal cape, with the Tokkul that comes with
  them) and anything received in the Gauntlet lobby (not bank withdrawals or items you take off). NPC kills use the loot
  the server reports, as the core Loot Tracker does. Shop item packs (bucket pack, feather pack, rune packs…) follow their contents. Slayer drops, clue caskets, shops, skilling and sea loot never unlock anything.
  Once unlocked, copies from any source are fine (bronzeman semantics), and so are other forms of the same item: other
  potion doses, jewellery charges, degraded Barrows gear, charged and uncharged weapons. Coins are locked until Brutus
  drops some.
- **Free items.** Everything on the wiki Tool page at base tier and the basic containers, empty or holding water or
  soil (vial of water, bucket of water, filled plant pot…) so herblore and planting boss-dropped seeds work
  (`bossman/tools.json`), access equipment
  (`bossman/access.json`: slayer access gear, keys/totems, grapple, rope), the consumable slayer tools a task monster
  needs (rock hammer, bag of salt, ice cooler, fungicide…), boss area craftables (made or handed out inside a boss's
  area for use there: Wintertodt's rejuvenation potions, Chambers of Xeric potions and food, Gauntlet gear, Tombs of
  Amascut supplies) and bonds are allowed by default. Each is its own config toggle ("Tools / Access gear / Slayer
  tools / Boss area craftables / Bonds are always unlocked") for stricter variants of the mode.
- **Quest items.** While a quest on a ladder boss's quest chain is in progress, the items it needs that otherwise only
  come from shops or ordinary monsters (Druidic Ritual's raw meats, climbing boots, the Tourist Trap's desert robes…)
  and the items the quest itself hands you (X Marks the Spot's treasure scroll, Dragon Slayer I's anti-dragon shield…)
  count as unlocked: they can be bought, worn and used, and carry a quest icon. They lock again when the quest is done.
- **Ledger.** Every unlock is written to `~/.runelite/bossman-mode/<world type>-<account hash>/bossman-mode-ledger.json`
  with its source, the KC at that source, and a timestamp. A ledger file that can't be read is renamed to
  `*.corrupt-<time>` and never overwritten; pending changes are written on logout and when the client closes. Per-source
  kill counts are tracked too: once the game reports a source's count in chat ("Your X kill count is", "chest count",
  "completion count", "Your completed X count is"), that number is used and loot no longer adds to it. Raid modes the
  game counts separately (Challenge Mode, Hard/Entry Mode, Expert Mode) add up to the raid's KC.
- **Restrictions.** Locked items are dimmed with a lock marker in the inventory, bank and equipment; hidden in GE search
  results; cannot be *sold* on the GE (the `Offer` option is removed) or *bought* in shops (`Buy` removed); and cannot be
  *used*: the configurable "Blocked actions" list (default Wield, Wear, Equip, Eat, Drink, Bury, Scatter, Rub, Light,
  Break, Teleport) is stripped from their menus. A locked item can still be picked up, carried, banked and dropped.
  `Use` is not blocked by default because quest hand-ins and skilling go through it — add it for a stricter mode.
- **Only bosses can be fought** (toggle: "Only fight bosses", on by default). `Attack` and combat spells are removed
  from every other NPC. Still attackable (`bossman/attackable.json`): anything inside a boss's arena or a raid (adds,
  waves, God Wars followers for KC, the Gauntlet, Fight Caves, Inferno, Colosseum), monsters that drop a boss's key
  (hill and moss giants, revenants, Catacombs monsters), fight adds outside a known arena (matched by NPC id), your
  slayer task, the NPCs fought in a started quest that unlocks a ladder boss or leads to one (Desert Treasure I for
  DT2), Tutorial Island's rats, and any NPC name or NPC id in "Additional attackable enemies" (names in "Extra unlock
  sources" too). Slayer tasks come from the core **Slayer** plugin, so keep it enabled. `!bmattack <npc name or id>`
  says why the nearest such NPC can or can't be fought, with its id. Auto-retaliate still fights back against monsters
  that attack you; turn it off to stay strict.
- **Unlock banner** captioned with the source and KC ("Crazy archaeologist — KC 14"); items from the same loot share one
  banner ("+3 more"). Optional screenshot / chat / notification.
- **Ladder panel.** The 67-rung boss ladder (`bossman/ladder.json`): current rung, gate, collection-log items gating it
  (with drop rates), KC vs expected KC, what the boss has unlocked for you and what it still could, and the **backlog** —
  pets/jars that fall into it once KC reaches their drop rate, plus any log item you right-click → "Move to backlog" (it
  stops gating; reversible). 1/5,000+ items are "meme grinds" (optional), as are mode-locked rewards (Challenge Mode, Hard
  Mode, high-invocation ToA). Rungs fed by several sources show each one's KC (the three Moons separately from the chest).
  Browse other bosses with `<` / `>`. Side bosses with no log of their own (Gemstone Crab, Mimic…) never hold the ladder
  up, and can be marked complete by hand. The skilling bosses (Tempoross and Wintertodt, listed after the ladder, and
  Zalcano) can be done out of order: tick "Do this boss now" on one and it is the current boss until its log is done.
- **Pre-plugin drops.** Open a boss tab in the in-game collection log and every obtained item there is imported as an unlock (toggle: "Import from collection log"). Nothing is imported inside an instance such as a player-owned house, where another player's log can be shown.
- **Combat Achievements** are read from the game's own task state (the completion varps + the task table in the cache), on
  login and whenever a task completes — tasks done before the plugin count, no interface needs opening.
- Chat commands: `!bmcount`, `!bmboss`, `!bmbackup`, `!bmrelock <item name or id>` (take back an unlock recorded by
  mistake; also a right-click on the panel's Unlocks rows), `!bmattack <npc name or id>`, `!bmreset` (opt-in, under Advanced; backs up first and does
  nothing if it can't).
- Config: "Meme-grind threshold" (default 5000) — log items rarer than 1/N are optional and never block the ladder; "Pets gate the ladder" / "Jars gate the ladder" / "Combat Achievements gate the ladder" (default off); "Tools / Access gear are always unlocked" toggles for stricter variants.

Requires the **Loot Tracker** plugin to be enabled for chest/event sources (NPC kills work without it), and the **Slayer**
plugin for slayer-task monsters to stay attackable.

## Code layout
- `BossmanModePlugin` — RuneLite event handling (loot, inventory, chat, scripts, menus) and the only writes to the ledger,
  all on the client thread (the panel's actions hop there). Writes are coalesced: a change marks that ledger dirty, one
  save runs ~1.5 s later on RuneLite's executor (and on logout, client exit, backup, account switch; a failed save is
  retried), and one panel rebuild is queued on the Swing thread however many events arrive meanwhile.
- `KcMessages`, `ReceiptDetector`, `MenuRules`, `AttackRules` — the chat KC parsing, the completion-reward detection,
  the menu stripping decision and who may be fought as plain functions, each with its own test.
- `ActiveQuests` — what started, unfinished quests on a boss's quest chain open up (their NPCs and items), re-read
  every few ticks. Covered by `ActiveQuestsTest`.
- `LadderRules` — the ruleset as pure queries (what is allowed, what gates a rung, which rung is current, its progress).
  No state of its own; reads data + ledger + config. Rung queries are safe from any thread; `isAllowed`/`freeKind` read the
  item cache, so the client thread. Covered by `LadderRulesTest`.
- `Ledger` — the persisted state (unlocks, KC, backlog, manual completions, combat tasks) and its file I/O, with its own
  unsaved-changes flag. Concurrent collections, since the client thread writes while the panel reads. Covered by `LedgerTest`.
- `CombatTasks` — reads the Combat Achievement task table and completion bits from the client.
- `BossmanData` — the bundled JSON (tools, access gear, sources, ladder) and lookups over it.
- `BossmanPanel` — the side panel, rebuilt from scratch on each refresh (skipped while hidden or while a menu is open, and
  done when it shows). `LockedItemOverlay` / `LockedBankEdgeOverlay` / `UnlockOverlay` — the in-game marks (the edge
  overlay covers the bank row RuneLite leaves out at the top of the scroll area) and the unlock banner.

## Building
```
./gradlew build
```
Run `BossmanModePluginTest` from `src/test` to launch a client with the plugin loaded.

## Data
`tools.json`, `access.json`, `sources.json` and `ladder.json` in `src/main/resources/bossman/` are generated by
`plugin-data/build_data.py` in the series project from the OSRS wiki scrapes, then corrected by `plugin-data/fix_data.py` (log items the generator drops, wrong ids, raid rates, mode-locked
items, KC sources and aliases, receipt rules). Run both when the ladder or rules change; `BossmanDataTest` checks the
invariants the fixes restore. Extra unlock sources (e.g. a boss released after this
version) can be added in the plugin config without rebuilding.

`attackable.json` is maintained by hand. Edit it when a boss gets an arena whose other NPCs must be fought (`regions`,
by map region id), a non-boss monster starts dropping what opens a boss or a boss fight gains adds outside its arena
(`access_npcs`, by name or by NPC id from the wiki infobox), or a ladder boss's gating quest, or one of its
prerequisites, has you fight someone or needs an item that otherwise only comes from a shop or ordinary monsters
(`quests`, keyed by RuneLite's `Quest` constant). The items the quests hand you are in `quest_items.json`, generated by
`plugin-data/quest_items.py` from the wiki's item infoboxes; rerun it when a quest is added to a chain.

## Credits
- Boss drop tables, drop rates, collection-log contents, Combat Achievement tasks and item IDs are derived from the
  [Old School RuneScape Wiki](https://oldschool.runescape.wiki/), licensed
  [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/).
- Item locking, the unlock banner and the overlays started from
  [Another Bronzeman Mode](https://github.com/CodePanter/another-bronzeman-mode) by CodePanter (BSD-2-Clause).
