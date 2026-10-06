# NyAddOns

Hypixel SkyBlock add-ons for Minecraft Java 26.1.2 (Fabric).

## Features

**Greenhouse** (Garden)
- `/gh` opens a window to plan your Greenhouse mutations. Mutation and crop data is downloaded from
  [SkyShards-Greenhouse](https://github.com/Campionnn/SkyShards-Greenhouse) and kept in `config/nyaddons`.
- **Planner:** type how many of each mutation you want, or press Max, and the layout comes from the
  public [SkyShards](https://skyshards.com) solver (`https://api.skyshards.com`). The side panel shows the
  queue position and progress. If the server cannot be reached your amounts are kept and the last good plan
  stays on screen, greyed out, with a Retry button. Answers are cached on disk in
  `config/nyaddons/skyshards-cache.json`; only the goals and unlocked squares are sent, no player data.
- **Rose tree:** the Rose Dragon panel shows every step needed, as pills with have/need counts.
- **Soil colours:** squares are coloured by soil, with a key.
- **World overlay:** draws see-through boxes over your real Greenhouse for the layout you last viewed,
  only on squares that are still empty. Stand on the plan's top-left square and press the
  "Align Greenhouse overlay" key (or the Align button in the settings). Both overlay keys are unbound
  by default and are listed in Controls under "NyAddOns Greenhouse". It only reads blocks and draws.

## Commands

| Command | What it does |
|---|---|
| `/ny` or `/nyaddons` | Open the config |
| `/ny gui` | Move and resize every overlay |
| `/ny reset` | Clear all timers |
| `/gh` or `/ny greenhouse` | Open the Greenhouse window |
| `/ny greenhouse bench` | Time SkyShards requests (written to `config/nyaddons/skyshards-timing.log`) |

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
and saves screenshots to `build/run/clientGameTest/screenshots`. `./gradlew prodClientGameTest`
does the same against the finished mod jar, which also checks the packaging. The pictures in
`screenshots/` come from it.

## Licence

MIT, see `LICENSE`. The config screen is [MoulConfig](https://github.com/NotEnoughUpdates/MoulConfig)
(LGPL-3.0), bundled under a relocated package; its licence is included in the jar as
`LICENSE_MoulConfig`. The Greenhouse crop and mutation data is downloaded from
[SkyShards-Greenhouse](https://github.com/Campionnn/SkyShards-Greenhouse) (MIT, see `LICENSE_SkyShardsGreenhouse` in the jar). The Greenhouse head icons use skin textures from the [NotEnoughUpdates-REPO](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO) item files (MIT, see `LICENSE_NEU-REPO` in the jar). The textures in `assets/nyaddons/moulconfig` are recoloured copies of
MoulConfig's, and `gui/ConfigTheme.kt` swaps its panel colours for a neutral grey and blue palette.

Not affiliated with Hypixel or SkyHanni. Mods are used on Hypixel at your own risk.
