# Greenhouse helper: notes for implementers (v0.6.0)

Goal: an in-game window (`/gh`, `/ny greenhouse`, optional keybind) that helps with the Hypixel SkyBlock Greenhouse,
in the spirit of the website SkyMutations, using only what the game client can see (no Hypixel web API, no Bazaar).

## Decisions made with the user
- Window: custom Screen in the same charcoal/blue style as `features/hunting/ShardPickerScreen.kt`. A dropdown at the top switches
  between three views: **Unique Mutations**, **Rose Dragon**, **All Mutations** (browser + layout planner).
- Unique Mutations: the goal is to analyse each of the 40 mutations once (DNA Analysis Milestone: tiers at 1, 10, 15, 20, 30, 40 distinct
  mutations; rewards: I Bioanalysis Talisman+Small Mutations Sack, II Overgrown Grass+upgraded HydroCan, III Medium Mutations Sack recipe,
  IV Bioanalysis Ring upgrade, V Large Mutations Sack recipe+Estate Greenhouse Skin, VI Bioanalysis Artifact upgrade; each tier also +10 Crop
  Growth, +5 Farming Fortune, +15 SkyBlock XP). Show a checklist with have/need, a "next up" line (cheapest to analyse first), the next
  milestone, and whether the player can start a mutation now with what they hold.
- Rose Dragon view lists NEEDS only: 5 Condensed Helianthus (each = 9 Helianthus; the wiki says 9, SkyMutations says 8, user chose 9) and one each
  of Glasscorn, Devourer, All-in Aloe, Phantomleaf, Timestalk, with have/need and what is missing. Do NOT show the 500M coins / 20,000 copper
  cost and do NOT implement anything about Ludleth's offer (location, unlock state).
- Analysis costs ARE shown (coins and copper), as a fixed table. Nothing is priced from the Bazaar anywhere.
- Which mutations are analysed: the player ticks them by clicking in the window (stored per profile). Automatic detection comes later, once the
  user pastes F8 menu dumps (F8 in any menu copies title/slots/lore to the clipboard, see core/MenuDump.kt).
- Stock (what the player has): sack contents + the player's own inventory, shown with a "sacks last updated" time. Sack menus and `[Sacks]`
  chat lines are read ONLY while the player is on the Garden (tab list area "Garden", includes the Greenhouse plot). The window itself opens anywhere.
- Mutation data: downloaded from the MIT-licensed SkyShards-Greenhouse repo, cached on disk, refreshed at most once a day, only when the player is
  on the Garden or opens the window. SkyMutations itself has no licence and no repo: do not copy anything from it.
- Memory/speed: stream-parse into compact structures, load lazily on first need, free after the window has been closed a few minutes, no work
  when the window is closed or off the Garden. Follow the patterns in `features/hunting/ShardRepo.kt` (lazy, streaming, daily refresh via
  `core/Downloads.refresh`) and `FusionCalculator.kt` (`FusionData`: streaming JsonReader into arrays).

## Data
- `https://raw.githubusercontent.com/Campionnn/SkyShards-Greenhouse/master/public/greenhouse/data.json` (35 KB, MIT, keep the licence text in the jar
  as LICENSE_SkyShardsGreenhouse). Top-level keys `crops` (17), `mutations` (40), `effects` (12). Mutation entry:
  `"ashwreath": {"name":"Ashwreath","size":1,"ground":"soul_sand","requirements":[{"crop":"nether_wart","count":2},{"crop":"fire","count":2}],
  "rarity":"common","growth_stages":0,"decay":3,"positive_buffs":[...],"negative_buffs":[...],"requires_watering":false,"drops":{"nether_wart":180}}`.
  Requirements are COUNTS of adjacent crops (crop or mutation ids), not positions. Also `special`/`harvest_info` on odd mutations.
- The same repo has `src/types/greenhouse.ts` (exact schema), `public/greenhouse/default_priorities.json`, and front-end code that encodes the
  placement rules. MIT: reading and adapting with attribution is fine. Its solver repo (SkyShards-Solver) is AGPL: do not copy from it.
  Skyblocker (LGPL-3.0) and SkyHanni (LGPL-2.1) clones are in <scratchpad>/
  for reference on menu parsing; write your own code rather than copying.
- Analysis costs: wiki table https://hypixelskyblock.minecraft.wiki/w/Crop_Analyzer (about 40 rows: first-analysis copper, copper per analysis,
  coins). Rule of thumb from the wiki: coins = 2000 x copper per analysis (Cheesebite 1000 x). Examples: Ashwreath 250 first / 5 per / 10,000 coins;
  Devourer 3000 / 5000 / 10,000,000; Timestalk 4000 / 9500 / 19,000,000. wiki.hypixel.net returns 403; the minecraft.wiki mirror works.
- Greenhouse basics: a Garden plot converted to a Greenhouse (Garden level VII), 10x10 grid of cells, plot origin used by Skyblocker is plot corner + (43,73,43).
  Mutations spawn on an empty soil cell when neighbouring crops satisfy the requirement counts; each mutation needs a soil (farmland, soul sand, mycelium,
  end stone, sand) and a size (1x1 to 3x3). Verify exact adjacency (4- or 8-neighbour) from the SkyShards-Greenhouse code/wiki before relying on it.

## Sacks (what the client can read)
- Sack menu title regex `.* Sack`; item lore `Stored: 1,234/…` (colour codes in raw lore, k/m/b suffixes possible); item name = the stored item's name.
- Chat: `[Sacks] +14 items. (Last 5s.)` with hover text listing ` +12 Wheat (...)`; chat drifts, so the opened menu is authoritative.
- Relevant sacks: "Mutations" (Phantomleaf, Timestalk, Devourer, Glasscorn, All-in Aloe...), "Garden" (Helianthus, Ethereal Vine, Compost...),
  "Agronomy", "Enchanted Agronomy". Condensed Helianthus is probably inventory-only (unverified). `/sacks` opens the overview; no command opens one sack.
- Reference implementations: SkyblockAPI `SacksAPI.kt`, SkyHanni `SackApi.kt` (clones in the scratchpad dir above).

## Repo conventions
- Kotlin, package `dev.nytrix.nyaddons`, Fabric 26.1.2, Mojang names (no mappings). `Feature` objects registered in `features/Feature.kt`.
- Per-profile saved data lives in `core/Storage.kt` (`ProfileData`, now with `greenhouse: GreenhouseProfile`). Config classes in `config/`.
- Tests: `./gradlew build prodClientGameTest` runs the in-game tests (a real Minecraft window, ~1-2 min). Each greenhouse piece has its own test class in
  `src/gametest/kotlin/dev/nytrix/nyaddons/test/greenhouse/` already registered in the gametest `fabric.mod.json`. Look at `NyAddOnsGameTest.kt` for how
  to open stand-in menus (`openMenu`, `headStack`) and take screenshots. Screenshots land in `build/run/prodClientGameTest/screenshots/` (view with Read).
- Version stays 0.6.0. Never run `./deploy.sh`, never push.
