package dev.nytrix.nyaddons.test.greenhouse

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.features.greenhouse.GhLayout
import dev.nytrix.nyaddons.features.greenhouse.GhMax
import dev.nytrix.nyaddons.features.greenhouse.GhMaxResult
import dev.nytrix.nyaddons.features.greenhouse.GreenhouseGoals
import dev.nytrix.nyaddons.features.greenhouse.GreenhousePlots
import dev.nytrix.nyaddons.features.greenhouse.PlannerCore
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/** The Max solver: validity under the planner's validator, count consistency, monotonic in squares, tie-break, time. */
@Suppress("UnstableApiUsage")
class GreenhouseMaxTest : FabricClientGameTest {
    private fun check(cond: Boolean, msg: String) { if (!cond) throw AssertionError(msg) }

    override fun runTest(context: ClientGameTestContext) {
        val data = GreenhousePlannerTest.TestData()
        val core = PlannerCore(data)
        val all = data.mutations.map { it.id }.filter { it !in GreenhouseGoals.skippedMutations }
        val budget = 400L
        val totals = LinkedHashMap<Int, Int>()
        val summary = StringBuilder()

        // warm up so JIT does not distort the timing
        GhMax.solve(core, data, all, GreenhousePlots.fill(30), emptySet(), 100)

        for (size in listOf(12, 30, 60, 100)) {
            val mask = GreenhousePlots.fill(size)
            val t0 = System.nanoTime()
            val res = GhMax.solve(core, data, all, mask, emptySet(), budget)
            val ms = (System.nanoTime() - t0) / 1_000_000
            validate(core, res, mask, all, "size $size")
            totals[size] = res.total
            check(ms <= budget * 2 + 50) { "size $size took $ms ms for a $budget ms budget" }
            summary.append("$size squares: ${res.total} mutations in ${res.totalCells} cells, ${ms} ms, counts=${res.counts}\n")
            NyAddOns.logger.info("[Greenhouse] max $size squares: ${res.total} placed (${res.counts.size} kinds) in ${res.totalCells} cells, $ms ms; stocked=${res.stocked}; unplaced=${res.unplaced.size}")
            if (size == 12) NyAddOns.logger.info("[Greenhouse] max 12 squares unplaced reasons: ${res.unplaced.entries.take(4)}")
        }
        check(totals[12]!! >= 1, "12 squares should hold at least one mutation")
        check(totals[12]!! <= totals[30]!! && totals[30]!! <= totals[60]!! && totals[60]!! <= totals[100]!!) { "not monotonic: $totals" }

        // default shape (2x6) and all-unlocked (null mask)
        val dflt = GhMax.solve(core, data, all, GreenhousePlots.default(), emptySet(), 200)
        validate(core, dflt, GreenhousePlots.default(), all, "default")
        val nullMask = GhMax.solve(core, data, all, null, emptySet(), 200)
        validate(core, nullMask, null, all, "null mask")
        NyAddOns.logger.info("[Greenhouse] max default 12: ${dflt.total}, null mask: ${nullMask.total}")

        // nothing unlocked, empty candidates, skipped ones
        val none = GhMax.solve(core, data, all, BooleanArray(100), emptySet(), 100)
        check(none.total == 0 && none.layout == null && none.unplaced.keys.containsAll(all)) { "locked grid should place nothing" }
        val skipped = GhMax.solve(core, data, listOf("shellfruit", "jerryflower"), null, emptySet(), 100)
        check(skipped.total == 0 && skipped.unplaced.size == 2) { "skipped mutations must not be placed" }
        check(GhMax.solve(null, data, all, null, emptySet()).total == 0) { "null planner places nothing" }

        // unanalysed tie-break: find two mutations that each fit alone in n squares but not together
        var tieDone = false
        search@ for (n in listOf(6, 8, 10, 12, 16, 20)) {
            val mask = GreenhousePlots.fill(n)
            val fitsAlone = all.filter { GhMax.solve(core, data, listOf(it), mask, emptySet(), 20).total == 1 }
            for (i in fitsAlone.indices) for (j in i + 1 until fitsAlone.size) {
                val a = fitsAlone[i]
                val b = fitsAlone[j]
                val both = GhMax.solve(core, data, listOf(a, b), mask, emptySet(), 40)
                if (both.total != 1) continue
                val ra = GhMax.solve(core, data, listOf(a, b), mask, setOf(a), 40)
                val rb = GhMax.solve(core, data, listOf(a, b), mask, setOf(b), 40)
                check(ra.counts.keys == setOf(b)) { "$a analysed, $b not: expected $b but got ${ra.counts}" }
                check(rb.counts.keys == setOf(a)) { "$b analysed, $a not: expected $a but got ${rb.counts}" }
                NyAddOns.logger.info("[Greenhouse] max tie-break ok with $a vs $b in $n squares")
                tieDone = true
                break@search
            }
        }
        check(tieDone) { "no pair found for the tie-break test" }
        NyAddOns.logger.info("[Greenhouse] max summary:\n$summary")
    }

    private fun validate(core: PlannerCore, res: GhMaxResult, mask: BooleanArray?, cands: List<String>, what: String) {
        val layout: GhLayout? = res.layout
        if (layout == null) { check(res.total == 0) { "$what: counts without a layout" }; return }
        if (mask != null) for (r in 0 until 10) for (c in 0 until 10) check(layout.cells[r][c] == null || mask[r * 10 + c]) { "$what: uses locked square $r,$c" }
        check(res.counts.keys.all { it in cands }) { "$what: count for a non-candidate" }
        val census = core.census(layout) ?: throw AssertionError("$what: layout malformed")
        for ((id, n) in res.counts) {
            check(core.spawns(layout, id) || census.satisfied[id] == n) { "$what: $id does not spawn" }
            check(census.satisfied[id] == n) { "$what: $id count $n but layout has ${census.satisfied[id]}" }
        }
        val placed = census.satisfied.filterKeys { it in cands }.values.sum()
        check(placed == res.total) { "$what: sum ${res.total} != placed targets $placed" }
        check(res.unplaced.keys.none { it in res.counts }) { "$what: unplaced overlaps counts" }
        check(res.counts.keys.size + res.unplaced.size == cands.size) { "$what: every candidate must be counted or explained" }
        check(layout.target in res.counts) { "$what: layout target not a placed mutation" }
    }
}
