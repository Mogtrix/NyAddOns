# Magic Find helper: notes for implementers (v0.6.1)

Feature: when the player kills a mob that has a drop MF can affect, print the Magic Find that applies to THAT mob in chat; `/trackmob <mob>` prints
the odds of each MF-affected drop at the player's MF; a menu picks which mobs report. Client only: no Hypixel web API, no Bazaar.

## Decisions made with the user
- Report on EVERY kill of an enabled mob (not only when something drops). Eligible mobs = mobs with at least one drop whose base chance is < 5%.
- Chat batching: collect kills for a window (default 4 s, config 1-10), then print once: one line per (mob, MF) pair, merged as `x N` when several kills
  share the same MF, e.g. `[Ny] Minotaur x3: 312% Magic Find`. Breakdown setting (dropdown in config, default Hover): Off / Hover (hover text on the line:
  general + this mob's bonus) / Inline.
- MF number = general MF (tab list Stats widget, cached, refreshed only when the widget changes; fallback: SkyBlock Menu slot 13 when opened) + a per-mob
  bonus the mod LEARNS: every `RARE DROP! ... (+N% ✯ Magic Find)` / `PET DROP! ...` line prints the exact MF used for that kill; compare with the general MF
  at that moment and store the difference for the mob that was just killed (per profile, small map). Until a mob has dropped once, show `312% (general only)`.
  Re-learn on every rare drop so gear/pet changes are picked up.
- Menu (`/mf`, `/ny magicfind`): custom window in the shard-picker/Greenhouse style (charcoal+blue); dropdown of categories; each category lists its mobs
  with checkboxes + all-on/all-off; remembered in config. Defaults: everything OFF except King Minos, Minos Inquisitor and every slayer boss at max tier
  (`MfMob.defaultOn`, applied once, tracked by `defaultsApplied` in config). Slayer bosses form their own virtual category "Slayer Bosses" (all tiers listed,
  max tier default on).
- Categories shown (from NEU bestiary.json ids): combat_1 Spider's Den, combat_3 The End, crimson_isle Crimson Isle, crystal_hollows Crystal Hollows,
  mining_3 Dwarven Mines, mythological_creatures Mythological Creatures, fishing Fishing (has subcategories), kuudra Kuudra, plus virtual "Slayer Bosses".
  Include foraging_2 (Moonglade Marsh) and foraging_3 (Torrhus Canyon) only for mobs that have a drop < 5%. NOT shown: dynamic (Private Island), hub,
  farming_1, mining_2, foraging_1, garden, lotus_atoll, safari, spooky_festival, jerry, catacombs (unverified whether MF applies in dungeons).
- /trackmob works for ANY mob in the game (also those not in the menu): `/trackmob <mob>` toggles, `/trackmob clear`, plain `/trackmob` lists. Tab-complete mob
  names. Print a one-line warning when a mob is added: tracking may spam chat. Tracked mobs report on kill even if not enabled in the menu.
  Per kill of a tracked mob print MF, then each MF-affected drop (base chance < 5%, plus pet drops) as e.g.
  `Stick: 1 in 16,667 base → 1 in 1,234 with Magic Find (+Looting V)`. Show the "base" part and the "with MF" part; include Looting only if the held weapon has it
  and it applies (Looting does not apply to slayer or pet drops). Pet drops use MF + Pet Luck. Drops with special rules ("per hit", "per Summoning Eye", empty)
  are listed as `special` without a number. Slayer bosses: MF line yes, NO odds (weighted loot pools + RNG meter make 1-in-N misleading), say so once.
- Memory/speed: general MF cached; at kill time only add the learned per-mob number; drop data loaded lazily, streamed, filtered, freed when idle (see
  `features/greenhouse/GreenhouseData.kt` and `features/hunting/ShardRepo.kt` for the pattern); nothing runs when no enabled/tracked mob is near.

## Facts from research (verify when you touch them)
- Formula: final chance = base x (1 + MF/100); MF applies only to drops with base chance STRICTLY < 5%; MF capped at 900; pet drops use MF + Pet Luck.
  Looting multiplies the base chance first and does not apply to slayer or pet drops (verify the per-level multiplier at
  https://hypixelskyblock.minecraft.wiki/index.php?title=Looting&action=raw). A Magic Find Rework is announced (would apply MF to all drops): keep the 5% threshold,
  the cap and the Looting rule as constants in ONE place (`MfMath`) so they can change.
- Chat: `RARE DROP! Enchanted Book (+208% ✯ Magic Find)`, `PET DROP! Baby Yeti (+168% ✯ Magic Find)` (the `%` may be missing, the icon is a private-use glyph, may
  render as nothing), `+5 Kill Combo +3% ✯ Magic Find`. Reference regexes: Skyblocker `skyblock/special/RareDropSpecialEffects.java:23`, SkyHanni
  `features/chat/RareDropMessages.kt`. Clones of other mods: <scratchpad>/
  (SkyHanni, Skyblocker, SkyOcean, SkyblockAPI, NoFrills; downloaded data in `w/` there). Read for ideas; write your own code (LGPL), do not copy.
- Tab list Stats widget: header `Stats:`, line ` Magic Find: ✯123` and ` Pet Luck: ...`; SkyHanni `data/model/SkyblockStat.kt:28,110,253` and `TabWidget.kt:108`.
  SkyBlock Menu slot 13 lore `\s*<icon> Magic Find <value>` (SkyblockStat.kt:222). The tab value is the GENERAL part; mob-specific parts (Bestiary family tier,
  Mythological gear, Magma Lord armour, Baby Yeti, Grandma Wolf kill combo, Witch Stews, ...) are not visible on the client: hence the learned bonus.
- Kill detection (client): track mobs near the player from name tags `[Lv100] Minotaur 1.5M/1.5M❤` / `☠ Revenant Horror IV 1.5M❤` (SkyHanni `data/mob/MobDetection.kt`,
  `MobFilter.kt:64-92`), remember which ones the player damaged recently, count a kill when such a mob dies (health 0 / `isDeadOrDying` / name tag `0❤`) within range
  and shortly after the player's last hit. Ignore plain despawns far away. Dungeon/Kuudra shared credit and owned/fished mobs are edge cases: accept occasional misses.
- Data (no key): SkyblockAPI Repo `https://raw.githubusercontent.com/SkyblockAPI/Repo/master/data/1_21_5/mobs.json` (~1.8 MB, MIT; 885 entries, ~348 with
  `lootTables`: `{id, chance (fraction, 0.02 = 2%), minAmount, maxAmount, pet?, tier?, level?, condition?, extraLore?}`, per table `mobLevel, xp, combatXp`; about 1,046
  drops < 5%; known gap: Zealot Summoning Eye) and NEU-REPO `constants/bestiary.json` (`https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/bestiary.json`,
  ~250 KB, MIT: `categories` with `{name (colour-coded), icon, mobs:[{name, cap, bracket, mobs:["arachne_500","arachne_300"]}]}`). NEU drops also live in
  `items/*_MONSTER|_BOSS|_MINIBOSS|_SC|_ANIMAL.json` recipes of type `drops` with string chances ("15%", "20% per hit", ".05% per Summoning Eye"). Mobs are matched by NAME.
  Keep the MIT notices in the jar (LICENSE_ files, see how LICENSE_SkyShards is packaged). Keep a tiny hand-written overrides table for known gaps.

## Repo conventions
- Kotlin, package `dev.nytrix.nyaddons`, Fabric 26.1.2, Mojang names. Features registered in `features/Feature.kt` (the three Magic Find features are pre-registered).
- Config in `config/CombatConfig.kt` (pre-made; the window owns only reading/writing `enabledMobs`). Per-profile saved data in `core/Storage.kt` (`ProfileData.magicFind`).
- Tests: `./gradlew build prodClientGameTest` runs in-game tests (real Minecraft window, 1-2 min). Each task has its own test class in
  `src/gametest/kotlin/dev/nytrix/nyaddons/test/magicfind/`, already registered in the gametest `fabric.mod.json`. See `NyAddOnsGameTest.kt` and
  `greenhouse/GreenhouseStockTest.kt` for stand-in menus (`openMenu`, `head`), worlds (`context.worldBuilder().create().use {}`), fake areas
  (`System.setProperty("nyaddons.devArea", ...)`) and screenshots (`build/run/prodClientGameTest/screenshots/`, view with Read).
- Version stays 0.6.1. Never run `./deploy.sh`, never push.
