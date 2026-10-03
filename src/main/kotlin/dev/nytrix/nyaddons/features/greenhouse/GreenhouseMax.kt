package dev.nytrix.nyaddons.features.greenhouse

/**
 * What [GhMax.solve] found. [counts] is how many blocks of each placed mutation id the [layout] grows (only ids with at least 1),
 * [layout] is the combined picture (null when nothing fits), [unplaced] maps every candidate with a count of 0 to a short reason.
 * [stocked] lists mutations that stand in the layout only as neighbouring plants (their own ring is not satisfied there, so they must
 * come from stock, see the planner's rule 4). [totalCells] is the number of squares the layout uses.
 */
class GhMaxResult(
    val counts: Map<String, Int>,
    val layout: GhLayout?,
    val unplaced: Map<String, String>,
    val stocked: List<String> = emptyList(),
    val totalCells: Int = 0,
) {
    val total get() = counts.values.sum()
}

/**
 * What [GhMax.place] did with the asked amounts. [placed] is how many of each requested id the shared [layout] grows (ids with at
 * least 1), [unplaced] maps every requested id that did not fully fit to a short reason, [requested] is what was asked.
 */
class GhPlaceResult(
    val placed: Map<String, Int>,
    val layout: GhLayout?,
    val unplaced: Map<String, String>,
    val requested: Map<String, Int>,
    val stocked: List<String> = emptyList(),
    val totalCells: Int = 0,
) {
    val placedTotal get() = placed.values.sum()
    val requestedTotal get() = requested.values.sum()

    /** How many layouts the whole request takes: requested blocks / placed blocks rounded up; 0 when everything fits or nothing does. */
    val rounds get() = if (requestedTotal <= placedTotal || placedTotal <= 0) 0 else (requestedTotal + placedTotal - 1) / placedTotal
}

/**
 * "Max" solver: the largest set of mutations that can spawn in ONE shared layout.
 *
 * Objective: most placed mutation blocks (several blocks of the same id count, as the grid has room for them); ties are broken by
 * preferring blocks of mutations not in `analysed`. Every counted block satisfies the planner's own spawn rule
 * ([PlannerCore.census], the same ring check as [PlannerCore.spawns]); crops and neighbouring plants are shared between blocks
 * because later blocks are planted into the existing picture and count what is already around them.
 *
 * Heuristic: greedy rounds. In each round every still-possible candidate is tried a few times ([PlannerCore.extend], randomised
 * placement) on top of the current layout; the extension with the best gain per newly used square wins (ties: more unanalysed
 * blocks, then candidate order, which lists unanalysed ones first). Candidates that fail to fit are dropped for that pass (the grid
 * only gets fuller). Passes repeat with randomised choices among the top few until the time budget or [MAX_PASSES] runs out;
 * the best pass (total, then unanalysed count, then fewer squares) is returned. Limits: not optimal (no backtracking within a pass,
 * so a different early choice can leave room for more), Godseed/Shellfruit/Jerryflower and the skipped mutations are never placed,
 * and results depend on the budget (pass 0 is deterministic, later passes use a fixed seed but stop on the clock).
 * Neighbouring mutations of a block are planted from stock, not grown (listed in [GhMaxResult.stocked]).
 */
object GhMax {
    const val DEFAULT_BUDGET_MILLIS = 400L
    const val PLACE_BUDGET_MILLIS = 700L
    private const val MAX_PASSES = 40
    private const val ATTEMPTS = 6

    /** As [solve] with the core, for callers (and tests) that hold a [PlannerCore] on their own data. */
    fun solve(core: PlannerCore, data: GhData, candidates: List<String>, unlocked: BooleanArray?, analysed: Set<String>, budgetMillis: Long = DEFAULT_BUDGET_MILLIS, oneOfEach: Boolean = false): GhMaxResult {
        val cap = if (oneOfEach) 1 else Int.MAX_VALUE
        val start = System.nanoTime()
        val deadline = start + budgetMillis * 1_000_000L
        val mask = unlocked?.takeIf { it.size == 100 }
        val unlockedCells = mask?.count { it } ?: 100
        val unplaced = LinkedHashMap<String, String>()
        val cands = ArrayList<GhMutation>()
        for (id in candidates.distinct()) {
            val m = data.mutation(id)
            when {
                m == null -> unplaced[id] = "unknown mutation"
                id in GreenhouseGoals.skippedMutations -> unplaced[id] = "not part of the Max plan (needs an event or quest items)"
                !core.targetable(id) -> unplaced[id] = "cannot be planned"
                else -> cands += m
            }
        }
        // unanalysed first, then the caller's order
        val order = cands.withIndex().sortedWith(compareBy({ if (it.value.id in analysed) 1 else 0 }, { it.index })).map { it.value }

        var bestLayout: GhLayout? = null
        var bestKey = longArrayOf(0, 0, 0)
        val candIds = order.map { it.id }.toSet()
        val rng = Rng(12345)
        var pass = 0
        while (pass < MAX_PASSES && order.isNotEmpty()) {
            if (pass > 0 && System.nanoTime() > deadline) break
            var layout: GhLayout? = null
            var score = Score(0, 0)
            var cells = 0
            val alive = ArrayList(order)
            var attemptBase = pass * 31
            while (alive.isNotEmpty()) {
                val picks = ArrayList<Pick>()
                val it = alive.iterator()
                var timedOut = false
                while (it.hasNext()) {
                    val m = it.next()
                    var best: Pick? = null
                    for (a in 0 until ATTEMPTS) {
                        val next = core.extend(layout, m, mask, attemptBase + a) ?: continue
                        val census = core.census(next) ?: continue
                        val s = score(census, candIds, analysed, cap)
                        val gain = s.total - score.total
                        if (gain <= 0) continue
                        val used = countCells(next)
                        val added = (used - cells).coerceAtLeast(1)
                        val p = Pick(m, next, s, used, gain.toDouble() / added, s.fresh - score.fresh)
                        if (best == null || better(p, best)) best = p
                    }
                    if (best == null) it.remove() else picks.add(best)
                    if (System.nanoTime() > deadline) { timedOut = true; break }
                }
                if (picks.isEmpty()) break
                picks.sortWith { a, b -> if (better(a, b)) -1 else if (better(b, a)) 1 else 0 }
                val chosen = if (pass == 0) picks[0] else picks[rng.next(minOf(3, picks.size))]
                layout = chosen.layout; score = chosen.score; cells = chosen.cells
                attemptBase += ATTEMPTS
                if (timedOut) break
            }
            val key = longArrayOf(score.total.toLong(), score.fresh.toLong(), -cells.toLong())
            if (layout != null && compare(key, bestKey) > 0) { bestKey = key; bestLayout = layout }
            pass++
        }

        val census = bestLayout?.let { core.census(it) }
        val counts = LinkedHashMap<String, Int>()
        if (census != null) for (m in order) census.satisfied[m.id]?.let { if (it > 0) counts[m.id] = minOf(it, cap) }
        for (m in order) if (m.id !in counts) unplaced[m.id] = whyNot(core, m, mask, unlockedCells)
        val layout = bestLayout?.let { l ->
            val head = counts.keys.firstOrNull { it !in analysed } ?: counts.keys.firstOrNull()
            if (head == null) null else GhLayout(l.size, l.cells, head)
        }
        return GhMaxResult(counts, layout, unplaced, census?.stocked ?: emptyList(), layout?.let(::countCells) ?: 0)
    }

    /** Solves on the planner's own core. [planner] null (or not the real planner) means nothing can be planned. */
    fun solve(planner: GhPlanner?, data: GhData, candidates: List<String>, unlocked: BooleanArray?, analysed: Set<String>, budgetMillis: Long = DEFAULT_BUDGET_MILLIS, oneOfEach: Boolean = false): GhMaxResult {
        if (planner == null || !data.ready) return GhMaxResult(emptyMap(), null, candidates.associateWith { "planner not ready" })
        return solve(coreOf(planner, data), data, candidates, unlocked, analysed, budgetMillis, oneOfEach)
    }

    private fun coreOf(planner: GhPlanner, data: GhData): PlannerCore = (planner as? GreenhousePlannerImpl)?.coreFor(data) ?: PlannerCore(data)

    /** As [place] on the planner's own core; nothing is placed when [planner] is null or the data is not loaded. */
    fun place(planner: GhPlanner?, data: GhData, amounts: Map<String, Int>, unlocked: BooleanArray?, budgetMillis: Long = PLACE_BUDGET_MILLIS): GhPlaceResult {
        val asked = amounts.filterValues { it > 0 }
        if (planner == null || !data.ready) return GhPlaceResult(emptyMap(), null, asked.mapValues { "planner not ready" }, asked)
        return place(coreOf(planner, data), data, amounts, unlocked, budgetMillis)
    }

    /**
     * Puts the asked [amounts] (mutation id to count) into ONE shared layout on the unlocked squares. The hardest go first (bigger
     * blocks, then more mutation ingredients, then more ingredients), and each block is added with [PlannerCore.extend] on top of
     * the picture so far, keeping the extension that uses the fewest squares and breaks none of the blocks already there. A kind
     * stops at its first block that does not fit; what is left over is explained in [GhPlaceResult.unplaced]. Same limits as [solve]:
     * greedy and time boxed, so a different order could sometimes fit more.
     */
    fun place(core: PlannerCore, data: GhData, amounts: Map<String, Int>, unlocked: BooleanArray?, budgetMillis: Long = PLACE_BUDGET_MILLIS): GhPlaceResult {
        val deadline = System.nanoTime() + budgetMillis * 1_000_000L
        val mask = unlocked?.takeIf { it.size == 100 }
        val unlockedCells = mask?.count { it } ?: 100
        val requested = LinkedHashMap<String, Int>()
        val unplaced = LinkedHashMap<String, String>()
        val order = ArrayList<GhMutation>()
        for ((id, n) in amounts) {
            if (n <= 0) continue
            requested[id] = n
            val m = data.mutation(id)
            when {
                m == null -> unplaced[id] = "unknown mutation"
                id in GreenhouseGoals.skippedMutations -> unplaced[id] = "not part of the plan (needs an event or quest items)"
                !core.targetable(id) -> unplaced[id] = "cannot share a layout"
                else -> order += m
            }
        }
        order.sortWith(
            compareByDescending<GhMutation> { it.size }
                .thenByDescending { m -> m.requirements.count { data.mutation(it.crop) != null } }
                .thenByDescending { m -> m.requirements.sumOf { it.count } }
                .thenBy { it.id },
        )
        var layout: GhLayout? = null
        var now: Map<String, Int> = emptyMap()
        var attempt = 0
        for (m in order) {
            val want = requested.getValue(m.id)
            while ((now[m.id] ?: 0) < want && System.nanoTime() < deadline) {
                var best: GhLayout? = null
                var bestMap: Map<String, Int> = now
                var bestCells = Int.MAX_VALUE
                for (a in 0 until ATTEMPTS) {
                    val next = core.extend(layout, m, mask, attempt + a) ?: continue
                    val census = core.census(next)?.satisfied ?: continue
                    if ((census[m.id] ?: 0) <= (now[m.id] ?: 0) || now.any { (id, n) -> (census[id] ?: 0) < n }) continue
                    val used = countCells(next)
                    if (used < bestCells) { best = next; bestMap = census; bestCells = used }
                }
                attempt += ATTEMPTS
                if (best == null) break
                layout = best
                now = bestMap
            }
        }
        val placed = LinkedHashMap<String, Int>()
        for ((id, want) in requested) {
            val got = minOf(now[id] ?: 0, want)
            if (got > 0) placed[id] = got
            if (got >= want || id in unplaced) continue
            val m = data.mutation(id) ?: continue
            unplaced[id] = if (got > 0) "no room left" else whyNot(core, m, mask, unlockedCells)
        }
        val head = placed.keys.firstOrNull()
        val shown = layout?.let { l -> if (head == null) null else GhLayout(l.size, l.cells, head) }
        return GhPlaceResult(placed, shown, unplaced, requested, layout?.let { core.census(it)?.stocked } ?: emptyList(), shown?.let(::countCells) ?: 0)
    }

    private class Score(val total: Int, val fresh: Int)

    private class Pick(val m: GhMutation, val layout: GhLayout, val score: Score, val cells: Int, val efficiency: Double, val freshGain: Int)

    private fun better(a: Pick, b: Pick): Boolean {
        if (a.efficiency != b.efficiency) return a.efficiency > b.efficiency
        if (a.freshGain != b.freshGain) return a.freshGain > b.freshGain
        return false
    }

    private fun compare(a: LongArray, b: LongArray): Int {
        for (i in a.indices) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }

    private fun score(c: PlannerCore.Census, cands: Set<String>, analysed: Set<String>, cap: Int): Score {
        var total = 0
        var fresh = 0
        for ((id, n) in c.satisfied) if (id in cands) { val k = minOf(n, cap); total += k; if (id !in analysed) fresh += k }
        return Score(total, fresh)
    }

    private fun countCells(l: GhLayout): Int { var n = 0; for (row in l.cells) for (c in row) if (c != null) n++; return n }

    private fun whyNot(core: PlannerCore, m: GhMutation, mask: BooleanArray?, unlockedCells: Int): String {
        var minCells = Int.MAX_VALUE
        for (a in 0 until 8) core.extend(null, m, null, a)?.let { minCells = minOf(minCells, countCells(it)) }
        if (minCells == Int.MAX_VALUE) return "no layout found"
        if (minCells > unlockedCells) return "needs ${minCells - unlockedCells} more squares"
        for (a in 0 until 8) if (core.extend(null, m, mask, a) != null) return "no room left alongside the others"
        return "does not fit the unlocked squares (needs about $minCells, the shape does not allow it)"
    }

    private class Rng(seed: Int) {
        private var s = seed * 0x9E3779B1.toInt() + 0x7F4A7C15
        fun next(bound: Int): Int {
            s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
            if (s == 0) s = 1
            return (s ushr 1) % bound
        }
    }
}
