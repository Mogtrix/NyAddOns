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

## Commands

| Command | What it does |
|---|---|
| `/ny` or `/nyaddons` | Open the config |
| `/ny gui` | Move and resize every overlay |
| `/ny reset` | Clear all timers |

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
