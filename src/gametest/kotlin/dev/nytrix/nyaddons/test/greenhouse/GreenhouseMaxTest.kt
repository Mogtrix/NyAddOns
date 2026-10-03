package dev.nytrix.nyaddons.test.greenhouse

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.features.greenhouse.GhLayout
import dev.nytrix.nyaddons.features.greenhouse.GhMax
import dev.nytrix.nyaddons.features.greenhouse.GhMaxResult
import dev.nytrix.nyaddons.features.greenhouse.GhPlaceResult
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

        // default shape (the 12 square centre blob) and all-unlocked (null mask)
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

        // One of each: never more than one block of a kind, never more kinds than without, still a valid layout.
        val many = GhMax.solve(core, data, all, null, emptySet(), 200)
        val one = GhMax.solve(core, data, all, null, emptySet(), 200, oneOfEach = true)
        validate(core, one, null, all, "one of each")
        check(one.counts.values.all { it == 1 } && one.total == one.counts.size) { "one of each gave ${one.counts}" }
        check(one.total >= 1 && one.total <= many.total) { "one of each ${one.total} vs ${many.total}" }
        check(many.counts.values.any { it > 1 } || many.total == one.total) { "without the cap some kind should repeat: ${many.counts}" }
        val oneTiny = GhMax.solve(core, data, all, GreenhousePlots.fill(12), emptySet(), 100, oneOfEach = true)
        check(oneTiny.counts.values.all { it == 1 }) { "one of each in 12 squares: ${oneTiny.counts}" }
        NyAddOns.logger.info("[Greenhouse] max one of each: ${one.total} kinds vs ${many.total} mutations without the cap")

        // place: asked amounts into one shared layout.
        val easy = all.filter { GhMax.solve(core, data, listOf(it), null, emptySet(), 20).total >= 1 }.take(3)
        check(easy.size == 3) { "need three placeable mutations: $easy" }
        val ask = mapOf(easy[0] to 2, easy[1] to 1, easy[2] to 1)
        val fit = GhMax.place(core, data, ask, null)
        validatePlace(core, fit, null, "place all")
        check(fit.placed == ask && fit.unplaced.isEmpty() && fit.rounds == 0) { "everything should fit in 100 squares: ${fit.placed} ${fit.unplaced}" }
        val empty = GhMax.place(core, data, mapOf(easy[0] to 0), null)
        check(empty.layout == null && empty.requestedTotal == 0 && empty.rounds == 0 && empty.unplaced.isEmpty()) { "nothing asked, nothing placed" }
        val tight = GreenhousePlots.fill(12)
        val big = all.associateWith { 6 }
        val part = GhMax.place(core, data, big, tight)
        validatePlace(core, part, tight, "place too many")
        check(part.placedTotal in 1 until part.requestedTotal) { "12 squares cannot take 6 of everything: ${part.placedTotal}/${part.requestedTotal}" }
        check(part.rounds >= 2 && part.unplaced.isNotEmpty()) { "rounds ${part.rounds}, unplaced ${part.unplaced.size}" }
        check(part.unplaced.keys.all { (part.placed[it] ?: 0) < big.getValue(it) && part.unplaced.getValue(it).isNotBlank() }) { "unplaced ids must be short of their amount" }
        val locked = GhMax.place(core, data, ask, BooleanArray(100))
        check(locked.layout == null && locked.placedTotal == 0 && locked.unplaced.keys == ask.keys) { "locked grid places nothing" }
        val bad = GhMax.place(core, data, mapOf("shellfruit" to 1, "nope" to 1), null)
        check(bad.placedTotal == 0 && bad.unplaced.size == 2) { "skipped and unknown ids are explained: ${bad.unplaced}" }
        check(GhMax.place(null, data, ask, null).placedTotal == 0) { "null planner places nothing" }
        alongside(core, data, easy, all)
        NyAddOns.logger.info("[Greenhouse] place 12 squares x6 each: ${part.placedTotal}/${part.requestedTotal}, rounds ${part.rounds}, unplaced ${part.unplaced.size}, cells ${part.totalCells}")
    }

    /** Per-row max: the most of one mutation that fits together with the other rows' amounts. */
    private fun alongside(core: PlannerCore, data: GreenhousePlannerTest.TestData, easy: List<String>, all: List<String>) {
        val (a, b, c) = easy
        val mask = GreenhousePlots.fill(40)
        val none = GhMax.maxAlongside(core, data, emptyMap(), c, mask)
        val solo = GhMax.solve(core, data, listOf(c), mask, emptySet(), GhMax.ALONGSIDE_BUDGET_MILLIS)
        check(none.placed == mapOf(c to solo.counts.getValue(c)) && none.unplaced.isEmpty()) { "nothing else typed must equal the solo max: ${none.placed} vs ${solo.counts}" }
        validatePlace(core, none, mask, "alongside empty")
        // Its own previous amount is ignored.
        val own = GhMax.maxAlongside(core, data, mapOf(c to 3), c, mask)
        check(own.placed == none.placed) { "the row's own amount must be ignored: ${own.placed} vs ${none.placed}" }

        var last = none.placed.getValue(c)
        val stages = listOf(mapOf(a to 1), mapOf(a to 3), mapOf(a to 3, b to 4))
        val log = StringBuilder("$last")
        for (others in stages) {
            val res = GhMax.maxAlongside(core, data, others, c, mask)
            validatePlace(core, res, mask, "alongside $others")
            val n = res.placed[c] ?: 0
            check(others.all { (id, k) -> res.placed[id] == k }) { "the other rows must be placed in full: ${res.placed} for $others" }
            check(n <= last) { "adding other amounts must not raise the max of $c: $n after $last with $others" }
            check(res.layout == null || res.requested == res.placed) { "a fitting plan asks only for what it places" }
            last = n
            log.append(" -> $n")
        }
        NyAddOns.logger.info("[Greenhouse] max alongside of $c in 40 squares as the others grow: $log")

        // Others that cannot fit: the row gets 0 and the note says why.
        val tight = GreenhousePlots.fill(12)
        val crowded = GhMax.maxAlongside(core, data, mapOf(a to 40, b to 40), c, tight)
        check(crowded.placed[c] == null && crowded.note.contains("do not fit") && crowded.unplaced.isNotEmpty()) { "crowded: ${crowded.note} ${crowded.unplaced}" }
        validatePlace(core, crowded, tight, "alongside crowded")
        val skipped = GhMax.maxAlongside(core, data, mapOf(a to 1), "shellfruit", null)
        check(skipped.placed["shellfruit"] == null && skipped.note.isNotEmpty()) { "skipped mutation: ${skipped.note}" }
        check(GhMax.maxAlongside(null, data, mapOf(a to 1), c, null).placedTotal == 0) { "null planner places nothing" }

        // A blocked square is never used: planner mask = unlocked and not blocked.
        val blocked = BooleanArray(100).also { it[44] = true; it[45] = true; it[54] = true; it[55] = true }
        val usable = GreenhousePlots.usable(mask, blocked)
        check(GreenhousePlots.count(usable) == 36 && !usable[44] && usable[43]) { "usable = unlocked and not blocked" }
        val around = GhMax.maxAlongside(core, data, mapOf(a to 2), c, usable)
        validatePlace(core, around, usable, "alongside blocked")
        check(around.layout != null && listOf(44, 45, 54, 55).all { around.layout!!.cells[it / 10][it % 10] == null }) { "a blocked square must stay empty" }
        val all36 = GhMax.solve(core, data, all, usable, emptySet(), 150)
        validate(core, all36, usable, all, "blocked centre")
    }

    private fun validatePlace(core: PlannerCore, res: GhPlaceResult, mask: BooleanArray?, what: String) {
        val layout = res.layout ?: run { check(res.placedTotal == 0) { "$what: counts without a layout" }; return }
        if (mask != null) for (r in 0 until 10) for (c in 0 until 10) check(layout.cells[r][c] == null || mask[r * 10 + c]) { "$what: uses locked square $r,$c" }
        val census = core.census(layout) ?: throw AssertionError("$what: layout malformed")
        for ((id, n) in res.placed) {
            check(n <= res.requested.getValue(id)) { "$what: $id placed $n of ${res.requested[id]}" }
            check((census.satisfied[id] ?: 0) >= n) { "$what: $id placed $n but only ${census.satisfied[id]} spawn" }
        }
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
