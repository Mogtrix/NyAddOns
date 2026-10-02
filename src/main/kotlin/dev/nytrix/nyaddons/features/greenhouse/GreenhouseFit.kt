package dev.nytrix.nyaddons.features.greenhouse

import java.util.concurrent.ConcurrentHashMap

/**
 * Whether each mutation can be laid out on the unlocked squares. Planning runs on a background thread, one mutation after
 * another, and the window reads the answers as they arrive ([version] changes whenever one does).
 */
class GreenhouseFit {

    // Cells the planner needs with every square unlocked, per mutation id (0: no layout at all). Survives mask changes.
    private val fullCells = ConcurrentHashMap<String, Int>()
    private val answers = ConcurrentHashMap<String, Int>()
    @Volatile private var generation = 0
    private var mutations: List<GhMutation>? = null
    private var maskKey = ""

    @Volatile var version = 0
        private set

    /** [FITS], [UNKNOWN], [NO_FIT] or the number of squares still missing. */
    fun check(id: String): Int = answers[id] ?: UNKNOWN

    /** Starts (or restarts) the work when the mutations or the unlocked squares are not what the last call used. */
    fun request(data: GhData, mask: BooleanArray) {
        val list = data.mutations
        val key = GreenhousePlots.encode(mask)
        if (!data.ready || list.isEmpty() || (list === mutations && key == maskKey)) return
        if (list !== mutations) fullCells.clear()
        mutations = list
        maskKey = key
        answers.clear()
        version++
        val mine = ++generation
        val copy = mask.copyOf()
        val unlocked = GreenhousePlots.count(copy)
        Thread({
            for (m in list) {
                if (mine != generation) return@Thread
                if (m.id in GreenhouseGoals.skippedMutations) continue
                val result = try {
                    compute(m, copy, unlocked)
                } catch (_: Exception) {
                    UNKNOWN
                }
                if (mine != generation) return@Thread
                if (result != UNKNOWN) answers[m.id] = result
                version++
            }
        }, "NyAddOns greenhouse fit").apply { isDaemon = true }.start()
    }

    private fun compute(m: GhMutation, mask: BooleanArray, unlocked: Int): Int {
        val planner = Greenhouse.planner
        if (unlocked >= GreenhousePlots.CELLS) return if (planner.plan(m, null) != null) FITS else UNKNOWN
        if (planner.plan(m, mask) != null) return FITS
        val cells = fullCells.getOrPut(m.id) { planner.plan(m, null)?.cells?.sumOf { row -> row.count { it != null } } ?: 0 }
        if (cells <= 0) return UNKNOWN
        return if (cells > unlocked) cells - unlocked else NO_FIT
    }

    fun stop() {
        generation++
        answers.clear()
        fullCells.clear()
        mutations = null
        maskKey = ""
    }

    companion object {
        const val FITS = 0
        const val UNKNOWN = -1
        const val NO_FIT = -2
    }
}
