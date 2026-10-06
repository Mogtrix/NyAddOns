package dev.nytrix.nyaddons.features.greenhouse

import dev.nytrix.nyaddons.core.Safe
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether each mutation can be laid out on the unlocked squares. Planning runs on a background thread, one mutation after
 * another through SkyShards, and the window reads the answers as they arrive ([version] changes whenever one does). When the
 * server cannot be used the run stops and is not tried again for [RETRY_MILLIS].
 */
class GreenhouseFit {

    private val answers = ConcurrentHashMap<String, Int>()
    @Volatile private var generation = 0
    private var mutations: List<GhMutation>? = null
    private var maskKey = ""
    @Volatile private var failedAt = 0L

    @Volatile var version = 0
        private set

    /** [FITS], [UNKNOWN], [NO_FIT] or the number of squares still missing. */
    fun check(id: String): Int = answers[id] ?: UNKNOWN

    /** Starts (or restarts) the work when the mutations or the unlocked squares are not what the last call used. */
    fun request(data: GhData, mask: BooleanArray) {
        val list = data.mutations
        val key = GreenhousePlots.encode(mask)
        if (!data.ready || list.isEmpty() || (list === mutations && key == maskKey)) return
        if (failedAt != 0L && System.currentTimeMillis() - failedAt < RETRY_MILLIS) return
        failedAt = 0L
        mutations = list
        maskKey = key
        answers.clear()
        version++
        val mine = ++generation
        val copy = mask.copyOf()
        val unlocked = GreenhousePlots.count(copy)
        Safe.background("greenhouse fit") {
            for (m in list) {
                if (mine != generation) return@background
                if (m.id in GreenhouseGoals.skippedMutations) continue
                val result = try {
                    compute(m, copy, unlocked, mine)
                } catch (_: SkyShardsAborted) {
                    return@background
                } catch (_: SkyShardsException) {
                    // Unreachable: stop here; request() starts over once the pause is over.
                    if (mine == generation) {
                        failedAt = System.currentTimeMillis()
                        mutations = null
                    }
                    return@background
                } catch (_: Throwable) {
                    UNKNOWN
                }
                if (mine != generation) return@background
                if (result != UNKNOWN) answers[m.id] = result
                version++
            }
        }
    }

    private fun compute(m: GhMutation, mask: BooleanArray, unlocked: Int, mine: Int): Int {
        val planner = Greenhouse.planner
        // The layout with every square unlocked: it says how many squares the mutation needs and, often, that it already fits.
        val full = planner.plan(m, null) ?: return UNKNOWN
        if (mine != generation) return UNKNOWN
        if (unlocked >= GreenhousePlots.CELLS) return FITS
        var cells = 0
        var inside = true
        for (r in 0 until full.size) for (c in 0 until full.size) {
            if (full.cells[r][c] == null) continue
            cells++
            if (!mask[r * full.size + c]) inside = false
        }
        if (inside || planner.plan(m, mask) != null) return FITS
        return if (cells > unlocked) cells - unlocked else NO_FIT
    }

    fun stop() {
        generation++
        answers.clear()
        mutations = null
        maskKey = ""
    }

    companion object {
        const val FITS = 0
        const val UNKNOWN = -1
        const val NO_FIT = -2
        private const val RETRY_MILLIS = 30_000L
    }
}
