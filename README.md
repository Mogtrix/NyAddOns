# NyAddOns

Hypixel SkyBlock add-ons for Minecraft Java 26.1.2 (Fabric), styled after SkyHanni.

## Features

**Honeycomb Tree Timer** (Foraging)
- Tracks every tree you used a Pot of Honeycomb on in Moonglade Marsh and Torrhus Canyon.
- Shows the time left in a movable overlay, and keeps counting while you are elsewhere or offline.
- Beacon beam and floating time at each tree: yellow while waiting, green when ready.
- Chat, sound and title alerts when a Critter has arrived.

**Honeyhive Timer** (Foraging)
- One-hour countdown from the first Honeyhive you loot, with a ready alert.

**Shard Tracker** (Hunting)
- Pick the shards you are hunting with `/hunt <shard>`, the picker (`/hunt`), or by pressing `H`
  while hovering one in the Hunting Box or Attribute Menu.
- A movable overlay shows how many of each are in your Hunting Box, the attribute's level, and
  how many more you need to max it, with an alert once you have enough.
- The numbers are read from the Hunting Box and Attribute Menu when you open them and kept
  current from chat. Bazaar sales, taking shards out and fusion ingredients are not announced in
  chat, so those are corrected the next time you open the Hunting Box.
- The shard list is downloaded from the
  [NotEnoughUpdates repository](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO) and
  the picker's icons from the [SkyblockAPI repository](https://github.com/SkyblockAPI/Repo);
  both are kept in `config/nyaddons` for offline use. The Hypixel API is not used.

**Fusion Tracker** (Hunting)
- For every tracked shard that still needs levelling, works out the quickest fusion tree and
  lists the shards to hunt for it as `have/need`, in its own movable overlay.
- The calculation is a port of [SkyShards](https://github.com/Campionnn/SkyShards)' calculator
  (MIT, see `LICENSE_SkyShards` in the jar), ironman only: shards are costed by hunting time,
  never by Bazaar price. Its recipe list and hunting rates are downloaded from the SkyShards
  repository and kept in `config/nyaddons`.
- Your attribute levels that affect fusing are read from the Hunting Box; Hunter Fortune,
  Kuudra tier and the other options are set in the Hunting tab.
- Shards already in your box are only counted for the materials you hunt, not for
  in-between fusion results.

**Fusion Tree** (Hunting)
- Inside the Fusion Box, Shard Fusion and Confirm Fusion menus, shows how to fuse each tracked
  shard: every step with have/need, finished steps greyed out, and the step you can do now in green.
- The Fusion Materials overlay lists each tracked shard's hunted materials under that shard, so a
  shard needed by two of them shows under both, and only what the tree still needs counts: a
  step in the middle you already hold enough of drops out along with everything below it.
- In the fusion menus the tree is drawn behind the menu, not over it.
- Three styles to choose from: an indented tree, a to-do list in fusing order, or a diagram with icons.
- Puts a lime background behind the shards of the next fusion in the menu. In Shard Fusion, a
  shard that is already in the machine (the top rows) is not lit again, so only what is still
  missing is; on Confirm Fusion the confirm button is lit when it is the next fusion in the tree.
- Every fused step shows the fusions it still needs, counting down as you fuse.
- Fusing subtracts the two ingredients shown on the Confirm Fusion screen from the counts.

**Menu helpers**
- At the top of the screen in the Hunting Box and Attribute Menu, a hint shows which key tracks a shard.
- Press `F8` in any menu to copy its title and every item's slot, name and lore to the clipboard.
  It reads what is on screen and sends nothing anywhere; it is how menus no mod documents get
  described when something does not line up.

## Commands

| Command | What it does |
|---|---|
| `/ny` or `/nyaddons` | Open the config |
| `/ny gui` | Move and resize every overlay |
| `/ny reset` | Clear all timers |
| `/hunt` or `/ny hunt` | Open the shard picker |
| `/hunt <shard>` | Track or untrack a shard |
| `/hunt clear` | Stop tracking all shards |

## Installing

Requires [Fabric API](https://modrinth.com/mod/fabric-api) and
[Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin).
Put the jar in your `mods` folder, or in the Modrinth App use *Add content → Add from file*.

## Building

```
./gradlew build
```

The mod jar is `build/libs/NyAddOns-<version>.jar`. Needs JDK 25 or newer.

`./deploy.sh` builds the mod and installs it into the Modrinth App profile "SkyBlock Enhanced"
(pass another profile name as an argument). Restart the instance to load the new build.

`./gradlew runClientGameTest` launches the game, runs the in-game tests in `src/gametest`
against a fake name tag and fake chat lines, and saves screenshots to
`build/run/clientGameTest/screenshots`. `./gradlew prodClientGameTest` does the same against
the finished mod jar, which also checks the packaging. The pictures in `screenshots/` come from it.

The game test also logs `[NyBench]` lines: the cost of the work the mod does every frame, tick,
second and chat line. Overlays and the fusion tree are rebuilt four times a second and reused in
between, menus are only re-read when an item in them changes, and downloaded lists are refreshed
at most once a day.

## Adding a feature

1. Add its options to a config class in `config/` (a new `@Category` in `NyConfig` for a new area).
2. Write an `object` implementing `Feature` under `features/`. In `init()`, subscribe to
   `NyEvents` (`second`, `chat`, `worldRender`) and register an `Overlay` if it shows HUD text.
3. Add it to the list in `features/Feature.kt`.

Shared helpers live in `core/` (chat, alerts, time formatting, island detection, saved data,
beams and floating text) and `gui/` (overlays and the position editor).

## Licence

MIT, see `LICENSE`. The config screen is [MoulConfig](https://github.com/NotEnoughUpdates/MoulConfig)
(LGPL-3.0), bundled under a relocated package; its licence is included in the jar as
`LICENSE_MoulConfig`. The textures in `assets/nyaddons/moulconfig` are recoloured copies of
MoulConfig's, and `gui/ConfigTheme.kt` swaps its panel colours for a neutral grey and blue palette.

Not affiliated with Hypixel or SkyHanni. Mods are used on Hypixel at your own risk.
