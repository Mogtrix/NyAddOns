# Greenhouse planner: how it is solved and how to check it

The planner no longer has a solver of its own. Every layout, Max, row max, row cap and "fits on my squares" answer comes from the public
SkyShards server (`https://api.skyshards.com`) over HTTP. Nothing has to be installed. The old search (`GreenhouseAnneal`, the greedy
rounds in `GhMax`, `PlannerCore`) and its benchmark (`greenhouseBench`, `greenhouseQa`, `docs/greenhouse-bench-best.json`) are gone: there
is no local search left to tune or compare.

Code: `features/greenhouse/GreenhouseSkyShards.kt` (client and cache), `GreenhouseMax.kt` (`GhMax`, the planner's questions as SkyShards
requests), `GreenhousePlanner.kt` (`GreenhousePlannerImpl`, one mutation), `GreenhouseFit.kt`.

## The API (read from `skyshards-solver/server.py`, the local copy of the same server)

| call | meaning |
|------|---------|
| `POST /greenhouse/jobs` | body `{"type": "greenhouse", "params": {...}}`, answer `{"job_id", "status", "message"}` |
| `GET /greenhouse/jobs/{id}` | `status` is `queued`, `running`, `completed`, `failed` or `cancelled`; `queue_position` while queued, `progress.percentage` while running, `result` when completed, `error` when failed |
| `DELETE /greenhouse/jobs/{id}` | cancels a job (sent when the mod stops waiting) |

`params`: `cells` (the unlocked squares as `[row, column]`, top left is `[0, 0]`), `targets` (each `{"mutation": id, "count": n}` for exactly n
blocks, or `{"mutation": id, "maximize": true}`), and `time_limit` (seconds; honoured by the local server, whether the public one honours
it is not known). Ids are the keys of the Greenhouse data file. The result lists `placements` (crops) and `mutations`, each
`{crop | mutation, position: [row, column], size}` with the top-left position; `GreenhouseSkyShards.kt` fills those onto a `GhLayout`.
No layout fits: the job fails with "No solution found", which the client turns into a null answer (cached).

## What asks what

| Planner feature | request |
|-----------------|---------|
| Layout of one mutation (All Mutations plan button, Rose Dragon side panel) | one target, `count` 1, on the unlocked squares |
| Fits on my squares (`GreenhouseFit`, about 40 mutations) | per mutation: `count` 1 on every square, then on the unlocked ones when the first layout leaves them. Sequential, answers cached |
| Typed amounts (shared layout) | every row with an amount is a `count` target. A set that does not fit together places nothing: every row says it does not fit (SkyShards has no partial answer) |
| Row max, row cap | the other rows as `count` targets and the row as a `maximize` target; infeasible means the other rows do not fit alone, so there is no cap |
| Max button | every candidate row is a `maximize` target (so the result follows SkyShards' spawn-rate objective, not the old "most blocks"), then the counts it found are planned again as `count` targets so the boxes and the layout agree |
| One of each | Max as above, counts clamped to 1, then planned again |

## Waiting, progress, errors

* One request waits at most 30 s (queue and solve together), polling every 0.5 s, then the job is cancelled and the error state shows.
* Progress in the planner side panel: `SkyShards: queued (position N)...`, then `SkyShards: solving 40%...`.
* Error (red, the amounts stay): `SkyShards is unreachable: <reason>. Your amounts are kept, try again.` The same text shows in the layout
  views. Failed requests are not cached; `GreenhouseFit` pauses 30 s after a failure.
* Everything runs on background threads; the render thread only reads the answer.
* Answers (and "nothing fits") are cached in memory for the session, 256 entries, dropped with the Greenhouse data when it is idle.

## Offline, cache, partial fit, debounce

* **Saved plan:** when SkyShards fails, the last good layout and amounts stay in the side panel, greyed, under the red error and
  `Saved plan from HH:mm`. A `Retry` button (where Pin sits) asks again for what is typed.
* **Disk cache:** `config/nyaddons/skyshards-cache.json`, the last 256 answers (nothing fits included), never expiring. Key = the goals
  plus the unlocked squares, no player data. A repeat plan is answered from it at once, also offline. Memory and disk are one cache.
* **Partial fit:** when the typed set does not fit together, a second request leaves out the row typed last. If the rest fits, that
  row says `does not fit with the others` and the rest is planned; if not, every row says it does not fit.
* **Debounce:** typing and +/- wait 400 ms (8 ticks) after the last change; a change also aborts the solve still running (its job is
  cancelled on the server).
* **Max tooltip:** `Max asks SkyShards for the best spawn rate, not the most blocks. The result may use fewer blocks.`

## Measuring latency

`/ny greenhouse bench` (in game, with the Greenhouse data loaded) times three sets on all 100 squares: one mutation, five mutations
(count 1 each), and max of all. Per set: cold, the same again (does the server remember it?), the same through the cache; then the
first set three times at once (queueing). Each run prints in chat; every solve (also normal use) is appended to
`config/nyaddons/skyshards-timing.log` as `time source ms, goals, queue peak, outcome` (source: server, memory or disk).

Live numbers (not measured yet: the build machine has no network access to api.skyshards.com):

| set | cold | again | cached | 3 at once (queue peak) |
|-----|------|-------|--------|------------------------|
| one mutation | | | | |
| five mutations | | | | |
| max of all | | | | |

## Checking it

* `./gradlew compileKotlin compileGametestKotlin build -q --offline` must pass.
* The game tests do not touch the network: `SkyShards.testSolver` replaces the server with a fake in `GreenhouseScreenTest`.
  The old rule tests (`GreenhousePlannerTest`, `GreenhouseMaxTest`) were removed with the solver; the rules now belong to SkyShards.
* A live check needs the network: open the planner, type amounts, watch the progress text, then block the host (or go offline) and check
  the red error and that the amounts stay.
