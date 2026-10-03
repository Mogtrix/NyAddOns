# SkyMutations Greenhouse planner: observed behaviour (INCOMPLETE)

Page: https://skymutations.eu/greenhouse (tabs on the site: Wiki, Calculator, Greenhouse, Profit).
Method: driven only through the UI in the browser pane; no source, bundles or data were viewed or saved. Nothing copied.

## Blocker: the experiments could not be run
The first press of a layout-affecting control (the per-row MAX button; Generate Layout is behind the same gate) opened a modal
"Verification required ... please verify you are human before running complex calculations" with an "I am human" button.
That is bot detection. I am not allowed to bypass or complete it, so I stopped. Therefore sections (a) to (e) of the task
(single-mutation maxima, pairs/triples, per-row max accounting, over-capacity, global best mix, rules visible in layouts)
are NOT observed. No layout results exist in this file. A human has to click through the check and run the experiments
(a script of what to try is at the end). Forum/Reddit threads were not readable (hypixel.net returned 403).

## UI flow observed (Greenhouse page)
- Header: GREENHOUSE, an AUTO-SAVE toggle (on by default), a RESET button (disabled until something changes).
- Grid: 10 columns x 10 rows of square cells. Cell states in the legend: Target Crop (blue), Ingredients (orange),
  Unique Crops (purple), Blocked. Locked cells are drawn dark/dim; unlocked cells slightly lighter.
- Default unlocked set = 12 cells in the middle, in this shape (rows 3 to 6, 0-indexed from the top; columns 0-indexed):
  ```
  row3: . . . . X X . . . .
  row4: . . . X X X X . . .
  row5: . . . X X X X . . .
  row6: . . . . X X . . . .
  ```
  Header text in Grid Manager: "TOTAL UNLOCKED 12 / 100 SLOTS", "0 ETHEREAL VINES USED", an upgrade-progress ring (12%),
  "price for new slots" with vine count, price per vine (Instant Buy / Buy Order, with refresh) and a coins total.
  Legend there: Default, Added, Locked. Expansion tools: "Recommend Best Slot" (prioritises efficiency and closeness),
  and size shortcuts 5x5, 6x6, 7x7, 8x8, 9x9, 10x10 (presumably unlock a centred square of that size; not tested).
  Unlocking is therefore a per-cell choice by the user (priced in Ethereal Vines), not fixed sizes only.
- Right panel has three tabs: Grid Manager, Auto-Planner, Manual Plan; plus a collapsible "How to use".
- Auto-Planner tab, top to bottom:
  1. "ADD MUTATION REQUEST": searchable dropdown of mutations (filters: Rarity, Discovered; each entry coloured by rarity,
     with icons), a number box (default 1) and an ADD button. "CLEAR ALL" appears once a request exists.
  2. Request list. Each row: icon, name, a MAX button, a minus button, the amount (editable), a plus button, and an x to remove.
     Empty state text: "No requests added. Add crops to generate a layout."
     Adding Ashwreath with the box at 1 produced a row with amount 1. Only a request list; the grid does not change until
     Generate Layout is pressed (the grid stayed empty after Add).
  3. Profit dropdown (loading prices), a green "MAX FEATURE" note: "Click MAX to auto-calculate the highest possible quantity
     for that crop."
  4. "Unique crop bonus" slider (0 to ?) showing "+0% Speed, +0% Yield" (growth bonus per distinct crop; not a placement limit).
  5. "Block space": CLEAR ALL and a BLOCK SLOTS toggle; when active, clicking unlocked cells marks them blocked so the planner
     avoids them.
  6. View: Icons / Renders / Surfaces; "Visualize Efficiency" checkbox (overlap efficiency score).
  7. GENERATE LAYOUT button; below it SAVE, LOAD, IMAGE, LINK (share).
- There is no global "Max / best mix" button visible in the Auto-Planner tab; the only MAX is per request row (not
  confirmed whether Manual Plan has more).
- Wiki tab (readable text) lists 40 mutations by rarity (Common 9, Uncommon 6, Rare 9, Epic 9, Legendary 7) with a
  analysis cost in coins/copper and a numeric "growth" value; not planner behaviour.

## Differences from our assumptions (only what is confirmed)
- Layout is generated on an explicit Generate Layout press, with a human-verification gate; our planner runs live. (UI only.)
- Unlocking: SkyMutations models Ethereal Vine cost and per-cell unlocking with a default 12-cell centre shape, 5x5..10x10
  shortcuts and a "recommend best slot". Our docs only say default 12 in the middle; the exact shape above is now recorded.
- Players can mark unlocked cells as Blocked so the planner never uses them. We have no such control.
- The UI separates cell roles: target, ingredient, unique crop, blocked.
- Nothing else could be compared (adjacency rule, sizes, soil, shared crops, stocked vs self-contained are unobserved).

## Concrete rules to match (UI-level only; solver rules still unknown)
1. Default unlocked mask is the 12-cell shape above (rows 3..6, cols 4-5 / 3-6 / 3-6 / 4-5).
2. A request list holds (mutation, amount) rows; amount editable, +/- steppers, remove, clear all.
3. Per-row MAX should compute the highest amount for that row given the others (stated intent: "highest possible quantity
   for that crop"); exactly how it accounts for other rows could NOT be verified.
4. Support blocked cells excluded from planning.

## Experiments still to run by a human (after passing the check)
For each: record amounts in, resulting amounts, grid (10x10 names), messages.
- Each of Ashwreath, Chocoberry, Cheesebite, Blastberry, Glasscorn, Godseed alone: MAX with default 12 cells, then with
  all 100 unlocked (10x10 shortcut).
- A=3 plus B=4, press MAX on C; also press MAX on A afterwards (does it change?).
- Over-capacity (e.g. 50 of a 3x3 mutation on 12 cells): message text, does the amount clamp?
- Whether any best-mix control exists; with 12/30/60/100 unlocked.
- Whether neighbour mutations (Chocoberry needs Choconut + Gloomgourd) appear as extra planted ingredients.
