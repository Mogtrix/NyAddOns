# Changelog

## 0.9.0

NyAddOns is now Greenhouse only.

- The planner gets every layout from the public SkyShards server (`https://api.skyshards.com`); the built-in solver is gone. The side panel shows queue position and progress, and a failure keeps your amounts and the last good plan (greyed, with Retry).
- Answers are cached on disk (`config/nyaddons/skyshards-cache.json`), and `/ny greenhouse bench` logs request timings.
- New world overlay: see-through boxes over your real Greenhouse for the layout you last viewed. Two unbound keys (toggle, align), an Align button, an on/off setting and a distance slider.
- SkyShards requests never wait past the 30 s limit, a lost status check is retried (the third in a row ends the solve), an unreadable result shows an error line, and the server job is cancelled on any failure.
- The SkyShards progress text wraps inside the planner side panel, and the overlay labels only the 3 nearest ghost crops within 6 blocks.
- The Rose Dragon tree percentage no longer counts mutations you already hold enough of.
- Pin-to-screen is removed, replaced by the overlay.
- Hunting and Foraging features are hidden and off the settings screen (the code is kept).
- README rewritten for Greenhouse.

## 0.8.4

Greenhouse soil colours and key, planner reuses placed crops, reuse readout.
