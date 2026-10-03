package dev.nytrix.nyaddons.features.greenhouse

/*
 * GREENHOUSE LAYOUT PLANNER: the rules it works to, and where they come from.
 *
 * Sources: SkyShards-Greenhouse (MIT, github.com/Campionnn/SkyShards-Greenhouse): public/greenhouse/data.json,
 * src/context/DesignerContext.tsx (its spawn check: getPossibleMutations / getTargetValidation),
 * src/utilities/effectSimulation.ts (effect propagation, godseed), src/utilities/mutationLayoutGenerator.ts,
 * src/constants/index.ts (GRID_SIZE = 10) and the Hypixel SkyBlock wiki pages Greenhouse and Mutations.
 * Nothing is copied from SkyShards-Solver (AGPL), SkyMutations (no licence) or the LGPL mods; this file is written from scratch.
 *
 * Established rules (what the planner and `spawns` assume):
 *  1. The grid is 10x10 cells (GRID_SIZE = 10). The player unlocks cells over time (default 12 in the middle, Ethereal Vines
 *     unlock more). Callers pass an `unlocked` mask (row-major, 100 entries) and the planner only uses those cells; null means
 *     all 100 are usable.
 *  2. A mutation spawns on an EMPTY spot, 1x1 / 2x2 / 3x3 by its `size`. The spot must be fully empty, in bounds, and free of
 *     other plants. Multi-cell plants are one block; in a GhLayout every cell of a block holds the id, and blocks are read
 *     back by scanning row-major (the first uncovered cell of an id is a block's top-left).
 *  3. Neighbours are the EIGHT surrounding cells (diagonals count), taken around the whole block: the ring of cells touching
 *     the block, so 8 for 1x1, 12 for 2x2, 16 for 3x3 (DesignerContext.tsx lists N,S,E,W and the four diagonals; the wiki's
 *     "orthogonally adjacent" wording is for the crop BUFFS, a different mechanic). Each requirement is a number of ring CELLS
 *     holding that id (SkyShards counts cells, so a 2x2 neighbour touching the ring with two cells counts twice). Unverified
 *     in game whether the real server counts cells or plants for multi-cell neighbours; SkyShards measured the rest.
 *  4. Requirement ids may be mutations (chocoberry needs choconut and gloomgourd). A planted mutation counts as a neighbour
 *     exactly like a crop. Two readings are supported, both checked by `spawns`:
 *       - STOCKED (default check): only the target's own ring is tested; neighbouring mutations are plants the player already
 *         owns (harvested earlier, kept in the Mutations Sack, planted like seeds). This is what SkyShards' designer does
 *         (DesignerContext.tsx tests only the target against the placed plants). Planting mutations from a sack is inferred
 *         from the sack and Rose Dragon notes and from SkyShards treating them as plants; it was not verified in game.
 *       - SELF-CONTAINED (`selfContained`): additionally every neighbouring mutation has its own ring satisfied in the same
 *         picture, crops shared between rings, so the whole tree grows in one 10x10. Needs no stock but is often impossible:
 *         a mutation that needs 8 identical neighbours (creambloom: 8 choconut) can never stand next to another spot, and a
 *         ring made only of mutations that each need 4+ crops (cindershade) does not fit, because a ring cell on a side has
 *         only 3 cells of its own outside the parent's ring. Those cases are only possible by growing a mutation, harvesting
 *         it and replanting it (the stocked reading), or by phased building that a single picture cannot show.
 *  5. Lonelily has no requirement list; its special is "requires_zero_adjacent": when it spawns its ring is empty. In a final
 *     layout its ring may be filled afterwards, except by another Lonelily (two adjacent Lonelilies cannot both have been
 *     alone). As the target itself, its ring must be empty. Inference from the data field; not verified in game.
 *  6. Godseed ("all_positive_crop_effects", 3x3, no crop list): an empty spot spawns it when its cells receive every positive
 *     effect it lists. Plants give their listed buffs to their four orthogonal neighbours (effectSimulation.ts). Only direct
 *     crop effects are modelled here (no effect_spread relays, no mutation buffs), which is conservative. The crop-to-buff table
 *     for the six needed effects is hard-coded (wiki + data.json: nether wart improved harvest boost, cactus improved water
 *     retain, sugar cane improved XP boost, potato immunity, pumpkin bonus drops, wild rose effect spread).
 *  7. NOT plannable as a target, so `plan` returns null: Shellfruit (a Turtlellini blown up twice by a Blastberry: an event,
 *     not a neighbour count) and Jerryflower (needs quest items, not a layout). Shellfruit still works as a stocked neighbour
 *     (Phantomleaf, Timestalk) but never counts as self-contained.
 *  8. Soil: data.json gives every crop and mutation a `ground` (farmland, soul sand, mycelium, end stone, sand, netherrack for
 *     fire). SkyShards' UI uses it only to draw the tile and enforces nothing: no soil check in its spawn code. Whether the
 *     player must prepare a spot's soil in game, and how a plot gets soul sand / mycelium / end stone / sand, could not be
 *     established from the sources, so soil is NOT a constraint here. The window can show `GhMutation.soil` for the target
 *     spot and `GhCrop.soil` for crops.
 *  9. Planting slots: the wiki's "12 unique crop types" is a growth-speed and yield bonus (+2.5% speed, +3% yield per distinct
 *     non-mutated crop), not a limit. Nothing in the sources caps distinct crops. The planner just prefers fewer distinct base
 *     crops, then fewer cells.
 * 10. Watering (`requires_watering`) affects growth only, not spawning; ignored. Crop growth stage is not part of any rule found.
 *     Whether crops must be fully grown to count could not be verified; SkyShards ignores it.
 */

/** Plans layouts on the live [Greenhouse.data]. The core is [PlannerCore]. */
/** A planned [layout] plus the neighbouring mutations whose own rings the layout does not satisfy (they must come from stock). */
class GhPlan(val layout: GhLayout, val stocked: List<String>) {
    val selfContained get() = stocked.isEmpty()
}

object GreenhousePlannerImpl : GhPlanner {
    private var core: PlannerCore? = null
    private var coreMutations: List<GhMutation>? = null

    override fun plan(target: GhMutation): GhLayout? = planDetailed(target)?.layout

    override fun plan(target: GhMutation, unlocked: BooleanArray?): GhLayout? = planDetailed(target, unlocked)?.layout

    /** Like [plan] but says which neighbouring mutations must be planted from stock; a self-contained layout is preferred. */
    fun planDetailed(target: GhMutation, unlocked: BooleanArray? = null): GhPlan? {
        val data = Greenhouse.data
        if (!data.ready) return null
        return coreFor(data).planDetailed(target, unlocked)
    }

    /** The cached core for [data] (rebuilt when its mutation list changes). */
    fun coreFor(data: GhData): PlannerCore = synchronized(this) {
        val known = core
        if (known != null && coreMutations === data.mutations) known
        else PlannerCore(data).also { core = it; coreMutations = data.mutations }
    }
}

/** The rules and the search, on any [GhData]. Not thread safe per call state: each call builds its own search arrays. */
class PlannerCore(data: GhData) {
    private val n: Int
    private val ids: Array<String>
    private val index = HashMap<String, Int>()
    private val size: IntArray
    private val isMut: BooleanArray
    private val reqCrop: Array<IntArray>
    private val reqCount: Array<IntArray>
    private val blocked: BooleanArray // cannot grow in one picture (itself or through what it needs)
    private val noTarget: BooleanArray // cannot be planned as a target at all

    init {
        val list = ArrayList<String>()
        val sizes = ArrayList<Int>()
        val muts = ArrayList<Boolean>()
        for (c in data.crops) if (index.putIfAbsent(c.id, list.size) == null) { list.add(c.id); sizes.add(c.size); muts.add(false) }
        for (m in data.mutations) if (index.putIfAbsent(m.id, list.size) == null) { list.add(m.id); sizes.add(m.size); muts.add(true) }
        n = list.size
        ids = list.toTypedArray()
        size = IntArray(n) { sizes[it].coerceIn(1, 3) }
        isMut = BooleanArray(n) { muts[it] }
        reqCrop = Array(n) { IntArray(0) }
        reqCount = Array(n) { IntArray(0) }
        blocked = BooleanArray(n)
        noTarget = BooleanArray(n)
        for (m in data.mutations) {
            val i = index[m.id] ?: continue
            if (m.id == SHELLFRUIT || m.id == JERRYFLOWER) { blocked[i] = true; noTarget[i] = true }
            // Sorted: mutations first (harder to fit), bigger blocks first, larger counts first.
            val sorted = m.requirements.filter { it.count > 0 }.sortedWith(
                compareByDescending<GhRequirement> { index[it.crop]?.let { c -> if (isMut[c]) 1 else 0 } ?: 0 }
                    .thenByDescending { index[it.crop]?.let { c -> size[c] } ?: 0 }
                    .thenByDescending { it.count },
            )
            reqCrop[i] = IntArray(sorted.size) { index[sorted[it].crop] ?: -1 }
            reqCount[i] = IntArray(sorted.size) { sorted[it].count }
            if (reqCrop[i].any { it < 0 }) { blocked[i] = true; noTarget[i] = true }
        }
        // Anything that needs a blocked mutation is blocked too (a chain is at most a few deep).
        var changed = true
        while (changed) {
            changed = false
            for (i in 0 until n) if (!blocked[i] && reqCrop[i].any { it >= 0 && blocked[it] }) { blocked[i] = true; changed = true }
        }
    }

    // ---------------------------------------------------------------- the rules

    /** Whether [layout] is a legal picture in which [targetId] spawns, neighbouring mutations taken as stocked plants. */
    fun spawns(layout: GhLayout, targetId: String): Boolean = check(layout, targetId, false)

    /** Like [spawns] but every neighbouring mutation must also have its own ring satisfied in the picture. */
    fun spawnsSelfContained(layout: GhLayout, targetId: String): Boolean = check(layout, targetId, true)

    /** Mutations in [layout] (other than [targetId]) whose own ring is not satisfied there, so they would have to be stocked. */
    fun stockedIn(layout: GhLayout, targetId: String): List<String> {
        val d = decode(layout) ?: return emptyList()
        val out = ArrayList<String>()
        for (e in 0 until d.count) {
            val id = d.eId[e]
            if (!isMut[id] || ids[id] == targetId || ids[id] in out) continue
            if (blocked[id] || !entitySatisfied(d.grid, d.eId, d.eR, d.eC, e, false)) out += ids[id]
        }
        return out
    }

    private class Decoded(val grid: IntArray, val eId: IntArray, val eR: IntArray, val eC: IntArray, val count: Int)

    private fun decode(layout: GhLayout): Decoded? {
        if (layout.size != GRID || layout.cells.size != GRID) return null
        val grid = IntArray(GRID * GRID) { -1 } // entity index per cell
        val eId = IntArray(GRID * GRID)
        val eR = IntArray(GRID * GRID)
        val eC = IntArray(GRID * GRID)
        var count = 0
        for (r in 0 until GRID) {
            val row = layout.cells[r]
            if (row.size != GRID) return null
            for (c in 0 until GRID) {
                val name = row[c] ?: continue
                if (grid[r * GRID + c] >= 0) continue
                val id = index[name] ?: return null
                val s = size[id]
                if (r + s > GRID || c + s > GRID) return null
                for (dr in 0 until s) for (dc in 0 until s) {
                    if (grid[(r + dr) * GRID + c + dc] >= 0 || layout.cells[r + dr][c + dc] != name) return null
                    grid[(r + dr) * GRID + c + dc] = count
                }
                eId[count] = id; eR[count] = r; eC[count] = c
                count++
            }
        }
        return Decoded(grid, eId, eR, eC, count)
    }

    private fun check(layout: GhLayout, targetId: String, selfContained: Boolean): Boolean {
        val target = index[targetId] ?: return false
        if (!isMut[target] || noTarget[target] || (selfContained && blocked[target])) return false
        val d = decode(layout) ?: return false
        val grid = d.grid; val eId = d.eId; val eR = d.eR; val eC = d.eC; val count = d.count
        var targets = 0
        for (e in 0 until count) {
            val id = eId[e]
            if (!isMut[id]) continue
            if (id == target) targets++
            else if (!selfContained) continue
            if (selfContained && blocked[id]) return false
            if (!entitySatisfied(grid, eId, eR, eC, e, id == target)) return false
        }
        return targets > 0
    }

    private fun entitySatisfied(grid: IntArray, eId: IntArray, eR: IntArray, eC: IntArray, e: Int, isTarget: Boolean): Boolean {
        val id = eId[e]
        val s = size[id]
        val r0 = eR[e]
        val c0 = eC[e]
        val name = ids[id]
        if (name == GODSEED) return godseedSpot(grid, eId, r0, c0, s)
        if (name == LONELILY) {
            for (r in r0 - 1..r0 + s) for (c in c0 - 1..c0 + s) {
                if (r !in 0 until GRID || c !in 0 until GRID || (r in r0 until r0 + s && c in c0 until c0 + s)) continue
                val o = grid[r * GRID + c]
                if (o < 0) continue
                if (isTarget || eId[o] == id) return false
            }
            return true
        }
        val rc = reqCrop[id]
        val rn = reqCount[id]
        for (k in rc.indices) {
            var have = 0
            for (r in maxOf(0, r0 - 1)..minOf(GRID - 1, r0 + s)) for (c in maxOf(0, c0 - 1)..minOf(GRID - 1, c0 + s)) {
                if (r in r0 until r0 + s && c in c0 until c0 + s) continue
                val o = grid[r * GRID + c]
                if (o >= 0 && eId[o] == rc[k]) have++
            }
            if (have < rn[k]) return false
        }
        return true
    }

    /** Godseed: the six positive effects must reach the spot from orthogonal neighbours (direct crop effects only). */
    private fun godseedSpot(grid: IntArray, eId: IntArray, r0: Int, c0: Int, s: Int): Boolean {
        var held = 0
        fun take(r: Int, c: Int) {
            if (r !in 0 until GRID || c !in 0 until GRID) return
            val o = grid[r * GRID + c]
            if (o >= 0) held = held or effectBits(ids[eId[o]])
        }
        for (k in 0 until s) { take(r0 - 1, c0 + k); take(r0 + s, c0 + k); take(r0 + k, c0 - 1); take(r0 + k, c0 + s) }
        return held == ALL_EFFECTS
    }

    // ---------------------------------------------------------------- the planner

    /** A layout for [target], or null when it is not plannable or none was found within the small search budget. */
    fun plan(target: GhMutation, unlocked: BooleanArray? = null): GhLayout? = planDetailed(target, unlocked)?.layout

    /**
     * The best self-contained layout if one is found, else the smallest stocked one (neighbouring mutations from stock).
     * Only cells set in [unlocked] (row-major, GRID * GRID) are used; null allows every cell.
     */
    fun planDetailed(target: GhMutation, unlocked: BooleanArray? = null): GhPlan? {
        val t = index[target.id] ?: return null
        if (!isMut[t] || noTarget[t]) return null
        val mask = unlocked?.takeIf { it.size == GRID * GRID }
        if (target.id == GODSEED) {
            val layout = planGodseed(target) ?: return null
            if (mask != null && !usesOnly(layout, mask)) return null
            return GhPlan(layout, emptyList())
        }
        val own = search(t, target.id, true, SELF_BUDGET_NANOS, EXTRA_SUCCESSES, mask)
        if (own != null) return GhPlan(own, emptyList())
        val stocked = search(t, target.id, false, STOCK_BUDGET_NANOS, 4, mask) ?: return null
        return GhPlan(stocked, stockedIn(stocked, target.id))
    }

    /** Result of [census]: mutation entities whose ring is satisfied, by id, and ids of mutations present but unsatisfied (stock-only). */
    class Census(val satisfied: Map<String, Int>, val stocked: List<String>)

    /** Counts, for every mutation block in [layout], whether it would spawn (strict: Lonelily needs an empty ring). Null if malformed. */
    fun census(layout: GhLayout): Census? {
        val d = decode(layout) ?: return null
        val ok = HashMap<String, Int>()
        val stocked = ArrayList<String>()
        for (e in 0 until d.count) {
            val id = d.eId[e]
            if (!isMut[id]) continue
            if (!noTarget[id] && entitySatisfied(d.grid, d.eId, d.eR, d.eC, e, true)) ok.merge(ids[id], 1) { a, b -> a + b }
            else if (ids[id] !in stocked) stocked += ids[id]
        }
        return Census(ok, stocked)
    }

    /**
     * Adds one more block of [target] to [base] (null = empty grid) with the crops its ring needs, reusing what is already placed
     * (stocked reading: neighbouring mutations are plants, not grown). Randomised by [attempt]. Returns the new layout, in which
     * [target] spawns, or null when no room was found. [base] is not modified. Not usable for Godseed.
     */
    fun extend(base: GhLayout?, target: GhMutation, mask: BooleanArray?, attempt: Int): GhLayout? {
        val t = index[target.id] ?: return null
        if (!isMut[t] || noTarget[t] || target.id == GODSEED) return null
        val d = if (base == null) null else decode(base) ?: return null
        val run = Search(t, attempt, false, mask?.takeIf { it.size == GRID * GRID }, d)
        if (!run.run()) return null
        val layout = run.toLayout(target.id)
        return if (spawns(layout, target.id)) layout else null
    }

    /** True when [id] can be asked of [extend] / counted by [census] as a target. */
    fun targetable(id: String): Boolean = index[id]?.let { isMut[it] && !noTarget[it] && id != GODSEED } ?: false

    private fun usesOnly(layout: GhLayout, mask: BooleanArray): Boolean {
        for (r in 0 until GRID) for (c in 0 until GRID) if (layout.cells[r][c] != null && !mask[r * GRID + c]) return false
        return true
    }

    private fun search(t: Int, targetId: String, selfContained: Boolean, budget: Long, extra: Int, mask: BooleanArray?): GhLayout? {
        if (selfContained && blocked[t]) return null
        val start = System.nanoTime()
        var best: GhLayout? = null
        var bestCost = Int.MAX_VALUE
        var found = 0
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            val elapsed = System.nanoTime() - start
            if (found == 0 && elapsed > budget) break
            if (found > 0 && (found >= extra || elapsed > budget * 2)) break
            val run = Search(t, attempt, selfContained, mask)
            if (run.run()) {
                val layout = run.toLayout(targetId)
                if (if (selfContained) spawnsSelfContained(layout, targetId) else spawns(layout, targetId)) {
                    found++
                    val cost = run.cost()
                    if (cost < bestCost) { bestCost = cost; best = layout }
                }
            }
            attempt++
        }
        return best
    }

    private fun planGodseed(target: GhMutation): GhLayout? {
        val crops = GODSEED_CROPS.map { index[it] ?: return null }
        val cells = Array(GRID) { arrayOfNulls<String>(GRID) }
        for (dr in 0 until 3) for (dc in 0 until 3) cells[4 + dr][3 + dc] = target.id
        for (k in 0 until 3) { cells[3][3 + k] = ids[crops[k]]; cells[7][3 + k] = ids[crops[k + 3]] }
        val layout = GhLayout(GRID, cells, target.id)
        return if (spawns(layout, target.id)) layout else null
    }

    private class Rng(seed: Int) {
        private var s = seed * 0x9E3779B1.toInt() + 0x7F4A7C15
        fun next(bound: Int): Int {
            s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
            if (s == 0) s = 1
            return (s ushr 1) % bound
        }
    }

    /** One greedy attempt: place the target, then satisfy every placed mutation's ring, nearest first. */
    private inner class Search(val target: Int, val attempt: Int, val selfContained: Boolean, val mask: BooleanArray?, val base: Decoded? = null) {
        var root = 0
        val rnd = Rng(attempt + 1)
        val grid = IntArray(GRID * GRID) { -1 }
        val eId = IntArray(MAX_ENTITIES)
        val eR = IntArray(MAX_ENTITIES)
        val eC = IntArray(MAX_ENTITIES)
        var count = 0
        var cells = 0

        fun cost(): Int {
            var kinds = 0
            var seen = 0L
            for (e in 0 until count) {
                val id = eId[e]
                if (!isMut[id] && (seen shr id) and 1L == 0L) { seen = seen or (1L shl id); kinds++ }
            }
            return kinds * 1000 + cells
        }

        fun toLayout(targetId: String): GhLayout {
            val out = Array(GRID) { arrayOfNulls<String>(GRID) }
            for (e in 0 until count) {
                val s = size[eId[e]]
                for (dr in 0 until s) for (dc in 0 until s) out[eR[e] + dr][eC[e] + dc] = ids[eId[e]]
            }
            return GhLayout(GRID, out, targetId)
        }

        fun fits(r: Int, c: Int, s: Int): Boolean {
            if (r < 0 || c < 0 || r + s > GRID || c + s > GRID) return false
            for (dr in 0 until s) for (dc in 0 until s) {
                val i = (r + dr) * GRID + c + dc
                if (grid[i] >= 0 || (mask != null && !mask[i])) return false
            }
            return true
        }

        fun put(id: Int, r: Int, c: Int): Int {
            if (count >= MAX_ENTITIES) return -1
            val s = size[id]
            for (dr in 0 until s) for (dc in 0 until s) grid[(r + dr) * GRID + c + dc] = count
            eId[count] = id; eR[count] = r; eC[count] = c
            cells += s * s
            return count++
        }

        fun pop() {
            count--
            val s = size[eId[count]]
            for (dr in 0 until s) for (dc in 0 until s) grid[(eR[count] + dr) * GRID + eC[count] + dc] = -1
            cells -= s * s
        }

        fun ringCount(e: Int, x: Int): Int {
            val s = size[eId[e]]
            val r0 = eR[e]
            val c0 = eC[e]
            var have = 0
            for (r in maxOf(0, r0 - 1)..minOf(GRID - 1, r0 + s)) for (c in maxOf(0, c0 - 1)..minOf(GRID - 1, c0 + s)) {
                if (r in r0 until r0 + s && c in c0 until c0 + s) continue
                val o = grid[r * GRID + c]
                if (o >= 0 && eId[o] == x) have++
            }
            return have
        }

        fun freeRing(e: Int): Int {
            val s = size[eId[e]]
            val r0 = eR[e]
            val c0 = eC[e]
            var free = 0
            for (r in maxOf(0, r0 - 1)..minOf(GRID - 1, r0 + s)) for (c in maxOf(0, c0 - 1)..minOf(GRID - 1, c0 + s)) {
                if (r in r0 until r0 + s && c in c0 until c0 + s) continue
                if (grid[r * GRID + c] < 0) free++
            }
            return free
        }

        fun unmet(e: Int): Int {
            val id = eId[e]
            if (!isMut[id] || (!selfContained && e != root)) return 0
            var sum = 0
            val rc = reqCrop[id]
            val rn = reqCount[id]
            for (k in rc.indices) sum += maxOf(0, rn[k] - ringCount(e, rc[k]))
            return sum
        }

        /** True when entity [e] could still get all it needs from its free ring cells. */
        fun feasible(e: Int) = unmet(e) <= freeRing(e)

        fun touches(f: Int, r: Int, c: Int, s: Int): Boolean {
            val fs = size[eId[f]]
            return r + s - 1 >= eR[f] - 1 && r <= eR[f] + fs && c + s - 1 >= eC[f] - 1 && c <= eC[f] + fs
        }

        fun lonelyOk(id: Int, r: Int, c: Int, s: Int): Boolean {
            if (!selfContained || ids[id] != LONELILY) return true
            for (rr in maxOf(0, r - 1)..minOf(GRID - 1, r + s)) for (cc in maxOf(0, c - 1)..minOf(GRID - 1, c + s)) {
                val o = grid[rr * GRID + cc]
                if (o >= 0 && eId[o] == id) return false
            }
            return true
        }

        fun run(): Boolean {
            if (base != null) for (b in 0 until base.count) if (put(base.eId[b], base.eR[b], base.eC[b]) < 0) return false
            root = count
            val ts = size[target]
            val tr: Int
            val tc: Int
            if (mask != null || base != null) {
                // Only spots where the target fits in the unlocked cells: the one nearest the middle first, then random ones.
                var pick = -1
                var picks = 0
                var bestDist = Float.MAX_VALUE
                for (r in 0..GRID - ts) for (c in 0..GRID - ts) {
                    if (!fits(r, c, ts)) continue
                    picks++
                    val dist = kotlin.math.abs(r + ts / 2f - 4.5f) + kotlin.math.abs(c + ts / 2f - 4.5f)
                    if (attempt == 0) { if (dist < bestDist) { bestDist = dist; pick = r * GRID + c } }
                    else if (rnd.next(picks) == 0) pick = r * GRID + c // reservoir sample
                }
                if (pick < 0) return false
                tr = pick / GRID
                tc = pick % GRID
            } else if (attempt == 0) { tr = (GRID - ts) / 2; tc = (GRID - ts) / 2 }
            else { tr = rnd.next(GRID - ts + 1); tc = rnd.next(GRID - ts + 1) }
            val placed = put(target, tr, tc)
            if (placed != root) return false
            if (root < 0 || !feasible(root)) return false
            var q = 0
            q = root
            while (q < count) {
                val e = q++
                val id = eId[e]
                if (!isMut[id] || ids[id] == LONELILY || (!selfContained && e != root)) continue
                val rc = reqCrop[id]
                val rn = reqCount[id]
                for (k in rc.indices) {
                    var need = rn[k] - ringCount(e, rc[k])
                    while (need > 0) {
                        if (!placeOne(e, rc[k], need)) { if (DEBUG && attempt == 0) { println("FAIL e=${ids[id]} want ${ids[rc[k]]}"); println(toLayout("x").cells.joinToString("\n") { r -> r.joinToString(" ") { (it ?: ".").take(5).padEnd(5) } }) }; return false }
                        need = rn[k] - ringCount(e, rc[k])
                    }
                }
            }
            return true
        }

        /** Puts one block of [x] into the ring of [e], choosing the position by a small score; false when nothing fits. */
        fun placeOne(e: Int, x: Int, need: Int): Boolean {
            val xs = size[x]
            val er = eR[e]
            val ec = eC[e]
            val es = size[eId[e]]
            var bestScore = Float.MAX_VALUE
            var bestR = -1
            var bestC = -1
            for (r in er - xs..er + es) for (c in ec - xs..ec + es) {
                if (!fits(r, c, xs) || !lonelyOk(x, r, c, xs)) continue
                // cells of the block that touch e's block: all of them count only if inside e's box
                var ov = 0
                for (dr in 0 until xs) for (dc in 0 until xs) {
                    val rr = r + dr
                    val cc = c + dc
                    if (rr >= er - 1 && rr <= er + es && cc >= ec - 1 && cc <= ec + es) ov++
                }
                if (ov == 0) continue
                var score = -minOf(ov, need) * 10f + maxOf(0, ov - need) * 4f
                for (f in 0 until count) {
                    if (f == e || !isMut[eId[f]] || (!selfContained && f != root) || !touches(f, r, c, xs)) continue
                    val fid = eId[f]
                    var want = 0
                    for (k in reqCrop[fid].indices) if (reqCrop[fid][k] == x) want = reqCount[fid][k] - ringCount(f, x)
                    val fs = size[fid]
                    var fov = 0
                    for (dr in 0 until xs) for (dc in 0 until xs) {
                        val rr = r + dr
                        val cc = c + dc
                        if (rr >= eR[f] - 1 && rr <= eR[f] + fs && cc >= eC[f] - 1 && cc <= eC[f] + fs) fov++
                    }
                    score += if (want > 0) -4f * minOf(want, fov) else 1.5f * fov
                }
                if (isMut[x]) {
                    // a mutation about to be placed needs room for its own ring
                    val probe = put(x, r, c)
                    if (probe < 0) continue
                    var ok = feasible(probe)
                    if (ok) for (f in 0 until count - 1) if (isMut[eId[f]] && touches(f, r, c, xs) && !feasible(f)) { ok = false; break }
                    pop()
                    if (!ok) continue
                    score -= 0.5f * size[x]
                } else {
                    val probe = put(x, r, c)
                    if (probe < 0) continue
                    var ok = true
                    for (f in 0 until count - 1) if (isMut[eId[f]] && touches(f, r, c, xs) && !feasible(f)) { ok = false; break }
                    pop()
                    if (!ok) continue
                }
                // stay roughly central so rings keep their room
                score += 0.05f * (kotlin.math.abs(r + xs / 2f - 4.5f) + kotlin.math.abs(c + xs / 2f - 4.5f))
                if (attempt > 0) score += rnd.next(30) / 10f * (1 + attempt % 4 * 2)
                if (score < bestScore) { bestScore = score; bestR = r; bestC = c }
            }
            if (bestR < 0) return false
            return put(x, bestR, bestC) >= 0
        }
    }

    private companion object {
        const val DEBUG = false
        const val GRID = 10
        const val MAX_ENTITIES = 100
        const val MAX_ATTEMPTS = 2500
        const val EXTRA_SUCCESSES = 12
        const val SELF_BUDGET_NANOS = 10_000_000L
        const val STOCK_BUDGET_NANOS = 10_000_000L
        const val LONELILY = "lonelily"
        const val GODSEED = "godseed"
        const val SHELLFRUIT = "shellfruit"
        const val JERRYFLOWER = "jerryflower"

        // improved_harvest_boost, improved_water_retain, improved_xp_boost, immunity, bonus_drops, effect_spread
        const val ALL_EFFECTS = 0b111111
        val GODSEED_CROPS = listOf("nether_wart", "cactus", "sugar_cane", "potato", "pumpkin", "wild_rose")

        fun effectBits(crop: String): Int = when (crop) {
            "nether_wart", "red_mushroom", "brown_mushroom" -> 1
            "cactus" -> 2
            "sugar_cane" -> 4
            "potato", "cocoa_beans" -> 8
            "pumpkin", "sunflower", "moonflower" -> 16
            "wild_rose" -> 32
            else -> 0
        }
    }
}
