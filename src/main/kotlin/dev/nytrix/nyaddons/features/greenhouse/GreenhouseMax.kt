package dev.nytrix.nyaddons.features.greenhouse

/**
 * What [GhMax.solve] found. [counts] is how many blocks of each placed mutation id the [layout] grows (only ids with at least 1),
 * [layout] is the combined picture (null when nothing fits), [unplaced] maps every candidate with a count of 0 to a short reason.
 * [stocked] lists mutations that stand in the layout only as neighbouring plants; SkyShards places its own ingredients, so it is
 * empty. [totalCells] is the number of squares the layout uses.
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
 * What [GhMax.replan] did with the asked amounts. [placed] is how many of each requested id the shared [layout] grows (ids with at
 * least 1), [unplaced] maps every requested id that did not fully fit to a short reason, [requested] is what was asked.
 * [note] is an extra sentence for the player (set by [GhMax.maxAlongside]), empty otherwise.
 */
class GhPlaceResult(
    val placed: Map<String, Int>,
    val layout: GhLayout?,
    val unplaced: Map<String, String>,
    val requested: Map<String, Int>,
    val stocked: List<String> = emptyList(),
    val totalCells: Int = 0,
    val note: String = "",
) {
    val placedTotal get() = placed.values.sum()
    val requestedTotal get() = requested.values.sum()

    /** How many layouts the whole request takes: requested blocks / placed blocks rounded up; 0 when everything fits or nothing does. */
    val rounds get() = if (requestedTotal <= placedTotal || placedTotal <= 0) 0 else (requestedTotal + placedTotal - 1) / placedTotal
}

/**
 * The Planner's questions, each answered by one SkyShards solve ([SkyShards]). Every function blocks, throws [SkyShardsException] when
 * the server cannot be used and [SkyShardsAborted] when [abort] turns true, so call them from a background thread.
 *
 * * [solve] (Max): every candidate is a "maximize" goal; SkyShards weighs the kinds by their spawn rate, not by block count.
 * * [replan]: the typed amounts are exact counts. A set that does not fit together is asked again without the row typed last: when
 *   that fits, only that row says it does not fit with the others; otherwise nothing is placed and every row says it does not fit.
 * * [maxAlongside] / [capAlongside]: the other rows as exact counts and the asked row as a "maximize" goal.
 * * [settle]: plans the counts a Max found as exact counts, so the boxes and the layout agree.
 */
object GhMax {

    private const val NO_FIT = "does not fit the unlocked squares"
    private const val NO_ROOM = "no room left"
    private const val NO_FIT_TOGETHER = "does not fit with the others"

    /** The Max button's tooltip. */
    const val MAX_TIP = "Max asks SkyShards for the best spawn rate, not the most blocks. The result may use fewer blocks."

    /** Why [id] is not sent to the solver, or null when it can be. */
    private fun skipReason(data: GhData, id: String): String? = when {
        data.mutation(id) == null -> "unknown mutation"
        id in GreenhouseGoals.skippedMutations -> "not part of the plan (needs an event or quest items)"
        else -> null
    }

    private fun countCells(layout: GhLayout?): Int = layout?.cells?.sumOf { row -> row.count { it != null } } ?: 0

    /** The most mutations one layout can grow among [candidates]. [oneOfEach] counts at most one block per kind. */
    fun solve(data: GhData, candidates: List<String>, unlocked: BooleanArray?, oneOfEach: Boolean = false, abort: () -> Boolean = { false }, progress: (String) -> Unit = {}): GhMaxResult {
        if (!data.ready) return GhMaxResult(emptyMap(), null, candidates.associateWith { "data not loaded" })
        val unplaced = LinkedHashMap<String, String>()
        val goals = ArrayList<SkyGoal>()
        for (id in candidates) {
            val why = skipReason(data, id)
            if (why != null) unplaced[id] = why else goals += SkyGoal(id, null)
        }
        if (goals.isEmpty()) return GhMaxResult(emptyMap(), null, unplaced)
        val sol = SkyShards.solve(goals, unlocked, abort, progress)
        if (sol == null) {
            for (g in goals) unplaced[g.id] = NO_FIT
            return GhMaxResult(emptyMap(), null, unplaced)
        }
        val counts = LinkedHashMap<String, Int>()
        for (g in goals) {
            val n = (sol.counts[g.id] ?: 0).let { if (oneOfEach) minOf(it, 1) else it }
            if (n > 0) counts[g.id] = n else unplaced[g.id] = NO_ROOM
        }
        val layout = counts.keys.firstOrNull()?.let(sol::layout)
        return GhMaxResult(counts, layout, unplaced, emptyList(), countCells(layout))
    }

    /** A fresh plan of the whole set [amounts] as exact counts. Nothing is placed when the set does not fit together. */
    fun replan(data: GhData, amounts: Map<String, Int>, unlocked: BooleanArray?, abort: () -> Boolean = { false }, progress: (String) -> Unit = {}, lastEdited: String? = null): GhPlaceResult {
        val requested = LinkedHashMap<String, Int>()
        val unplaced = LinkedHashMap<String, String>()
        val goals = ArrayList<SkyGoal>()
        for ((id, n) in amounts) {
            if (n <= 0) continue
            requested[id] = n
            val why = if (data.ready) skipReason(data, id) else "data not loaded"
            if (why != null) unplaced[id] = why else goals += SkyGoal(id, n)
        }
        if (goals.isEmpty()) return GhPlaceResult(emptyMap(), null, unplaced, requested)
        val sol = SkyShards.solve(goals, unlocked, abort, progress)
        if (sol == null) {
            // Second request: without the row typed last. When the rest fits, only that row is the one that broke the set.
            if (lastEdited != null && goals.size > 1 && goals.any { it.id == lastEdited }) {
                val rest = goals.filter { it.id != lastEdited }
                val partial = SkyShards.solve(rest, unlocked, abort, progress)
                if (partial != null) {
                    unplaced[lastEdited] = NO_FIT_TOGETHER
                    return assemble(partial, rest.associate { it.id to it.count!! }, requested, unplaced)
                }
            }
            for (g in goals) unplaced[g.id] = NO_FIT
            return GhPlaceResult(emptyMap(), null, unplaced, requested)
        }
        return assemble(sol, goals.associate { it.id to it.count!! }, requested, unplaced)
    }

    private fun assemble(sol: SkySolution, wanted: Map<String, Int>, requested: Map<String, Int>, unplaced: MutableMap<String, String>): GhPlaceResult {
        val placed = LinkedHashMap<String, Int>()
        for ((id, want) in wanted) {
            val got = minOf(sol.counts[id] ?: 0, want)
            if (got > 0) placed[id] = got
            if (got < want) unplaced[id] = if (got > 0) NO_ROOM else NO_FIT
        }
        val layout = placed.keys.firstOrNull()?.let(sol::layout)
        return GhPlaceResult(placed, layout, unplaced, requested, emptyList(), countCells(layout))
    }

    /**
     * Keeps a claimed answer ([claimed]: what Max or a row's max found) consistent with a fresh plan: the claimed counts are planned
     * as exact counts and that plan is returned when it places all of them, otherwise the claimed one stays.
     */
    fun settle(data: GhData, claimed: GhPlaceResult, unlocked: BooleanArray?, abort: () -> Boolean = { false }, progress: (String) -> Unit = {}): GhPlaceResult {
        if (claimed.placed.isEmpty() || claimed.unplaced.isNotEmpty()) return claimed
        val fresh = replan(data, claimed.placed, unlocked, abort, progress)
        return if (fresh.unplaced.isEmpty() && fresh.placedTotal >= claimed.placedTotal) GhPlaceResult(fresh.placed, fresh.layout, fresh.unplaced, fresh.requested, fresh.stocked, fresh.totalCells, claimed.note) else claimed
    }

    /**
     * How many blocks of [id] fit alongside the other rows of [amounts] (the row's max), for the Planner's cap on that row. Null when
     * the other rows do not fit on their own: then there is no cap to apply.
     */
    fun capAlongside(data: GhData, amounts: Map<String, Int>, id: String, unlocked: BooleanArray?, abort: () -> Boolean = { false }): Int? {
        if (!data.ready) return null
        val r = maxAlongside(data, amounts, id, unlocked, abort)
        if (r.unplaced.keys.any { it != id }) return null
        return r.placed[id] ?: 0
    }

    /**
     * The most blocks of [id] that fit in one shared layout together with everything typed in the OTHER rows of [amounts] (the amount
     * of [id] itself is ignored). The result is the combined plan: [GhPlaceResult.placed] has the others and [id] with its count (when
     * above 0). When the others do not fit on their own, nothing is placed, [id] gets 0 and a [GhPlaceResult.note] says so.
     */
    fun maxAlongside(data: GhData, amounts: Map<String, Int>, id: String, unlocked: BooleanArray?, abort: () -> Boolean = { false }, progress: (String) -> Unit = {}): GhPlaceResult {
        val name = data.nameOf(id)
        val others = LinkedHashMap<String, Int>()
        for ((k, n) in amounts) if (k != id && n > 0) others[k] = n
        val why = if (data.ready) skipReason(data, id) else "data not loaded"
        if (why != null) {
            val r = replan(data, others, unlocked, abort, progress)
            return GhPlaceResult(r.placed, r.layout, r.unplaced + (id to why), r.requested, r.stocked, r.totalCells, "$name: $why.")
        }
        val unplaced = LinkedHashMap<String, String>()
        val goals = ArrayList<SkyGoal>()
        for ((k, n) in others) {
            val skip = skipReason(data, k)
            if (skip != null) unplaced[k] = skip else goals += SkyGoal(k, n)
        }
        goals += SkyGoal(id, null)
        val sol = SkyShards.solve(goals, unlocked, abort, progress)
        if (sol == null) {
            // The asked row can always stay at 0, so the other rows are what cannot be placed.
            for (g in goals) if (g.id != id || others.isEmpty()) unplaced[g.id] = NO_FIT
            val note = if (others.isEmpty()) "$name $NO_FIT." else "The other rows already do not fit, so $name gets 0."
            return GhPlaceResult(emptyMap(), null, unplaced, others, note = note)
        }
        val n = sol.counts[id] ?: 0
        val wanted = LinkedHashMap<String, Int>()
        for (g in goals) if (g.id != id) wanted[g.id] = g.count!!
        val requested = LinkedHashMap(others)
        if (n > 0) {
            wanted[id] = n
            requested[id] = n
        }
        val r = assemble(sol, wanted, requested, unplaced)
        return if (n > 0) r else GhPlaceResult(r.placed, r.layout, r.unplaced, r.requested, r.stocked, r.totalCells, "No room left for $name alongside the other rows.")
    }
}
