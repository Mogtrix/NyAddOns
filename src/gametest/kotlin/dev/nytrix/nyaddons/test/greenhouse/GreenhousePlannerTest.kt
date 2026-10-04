package dev.nytrix.nyaddons.test.greenhouse

import com.google.gson.JsonParser
import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.features.greenhouse.GhCrop
import dev.nytrix.nyaddons.features.greenhouse.GhData
import dev.nytrix.nyaddons.features.greenhouse.GhLayout
import dev.nytrix.nyaddons.features.greenhouse.GhMutation
import dev.nytrix.nyaddons.features.greenhouse.GhRequirement
import dev.nytrix.nyaddons.features.greenhouse.GhReuse
import dev.nytrix.nyaddons.features.greenhouse.GreenhousePlots
import dev.nytrix.nyaddons.features.greenhouse.PlannerCore
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/** Plans a layout for every mutation of the real SkyShards-Greenhouse data and checks each with the independent validator. */
@Suppress("UnstableApiUsage")
class GreenhousePlannerTest : FabricClientGameTest {
    /** Test-only parser for `/greenhouse/planner-data.json` (the real data agent's parser lives in another branch). */
    internal class TestData : GhData {
        override val ready = true
        override val crops: List<GhCrop>
        override val mutations: List<GhMutation>

        init {
            val root = javaClass.getResourceAsStream("/greenhouse/planner-data.json")!!.reader().use { JsonParser.parseReader(it).asJsonObject }
            crops = root.getAsJsonObject("crops").entrySet().map { (id, v) ->
                val o = v.asJsonObject
                GhCrop(id, o["name"].asString, o["size"].asInt, o["ground"].asString)
            }
            mutations = root.getAsJsonObject("mutations").entrySet().map { (id, v) ->
                val o = v.asJsonObject
                GhMutation(
                    id, o["name"].asString, o["rarity"].asString, o["size"].asInt, o["ground"].asString,
                    o.getAsJsonArray("requirements").map { GhRequirement(it.asJsonObject["crop"].asString, it.asJsonObject["count"].asInt) },
                    o["growth_stages"].asInt, o["requires_watering"].asBoolean, 0L, 0,
                )
            }
        }

        override fun mutation(id: String) = mutations.firstOrNull { it.id == id }
        override fun nameOf(id: String) = id
        override fun request() {}
    }

    private fun check(cond: Boolean, msg: String) { if (!cond) throw AssertionError(msg) }

    override fun runTest(context: ClientGameTestContext) {
        val data = TestData()
        check(data.mutations.size == 40 && data.crops.size == 17, "test data: ${data.mutations.size} mutations, ${data.crops.size} crops")
        val core = PlannerCore(data)
        val nulls = ArrayList<String>()
        val selfContained = ArrayList<String>()
        val perMutation = StringBuilder()
        var totalNanos = 0L
        var planned = 0
        val known = data.crops.map { it.id }.toSet() + data.mutations.map { it.id }
        for (m in data.mutations) {
            val t0 = System.nanoTime()
            val plan = core.planDetailed(m)
            val dt = System.nanoTime() - t0
            totalNanos += dt
            perMutation.append(m.id).append('=').append(dt / 1000).append("us ")
            if (plan == null) { nulls += m.id; continue }
            val layout = plan.layout
            planned++
            check(layout.size == 10 && layout.cells.size == 10 && layout.cells.all { it.size == 10 }, "${m.id}: layout is not 10x10")
            check(layout.target == m.id, "${m.id}: layout.target is ${layout.target}")
            check(layout.cells.all { row -> row.all { it == null || it in known } }, "${m.id}: unknown id in layout")
            check(core.spawns(layout, m.id), "${m.id}: validator says the planned layout does not spawn it\n${dump(layout)}")
            check(core.spawnsSelfContained(layout, m.id) == plan.stocked.isEmpty(), "${m.id}: stocked list ${plan.stocked} disagrees with the strict validator")
            if (plan.stocked.isEmpty()) selfContained += m.id
            check(!core.spawns(GhLayout(10, Array(10) { arrayOfNulls<String>(10) }, m.id), m.id), "${m.id}: an empty grid must not spawn it")
        }
        NyAddOns.logger.info("[NyBench] greenhouse planner: ${totalNanos / 1_000_000.0} ms total for 40 mutations ($planned layouts); $perMutation")
        NyAddOns.logger.info("[Greenhouse] planner: $planned of 40 planned (${selfContained.size} self-contained: $selfContained); null: $nulls")

        // the validator must reject damaged layouts: take Dustgrain's layout and remove one wheat
        val dust = core.plan(data.mutation("dustgrain")!!)!!
        val damaged = GhLayout(10, Array(10) { r -> dust.cells[r].copyOf() }, "dustgrain")
        loop@ for (r in 0 until 10) for (c in 0 until 10) if (damaged.cells[r][c] == "wheat") { damaged.cells[r][c] = null; break@loop }
        check(!core.spawns(damaged, "dustgrain"), "dustgrain with one wheat removed must not spawn")
        // Lonelily as a target needs an empty ring
        val lonely = core.plan(data.mutation("lonelily")!!)!!
        val crowded = GhLayout(10, Array(10) { r -> lonely.cells[r].copyOf() }, "lonelily")
        loop2@ for (r in 0 until 10) for (c in 0 until 10) if (crowded.cells[r][c] == "lonelily") { crowded.cells[r][(c + 1) % 10] = "wheat"; break@loop2 }
        check(!core.spawns(crowded, "lonelily"), "lonelily next to a crop must not spawn")
        check(core.plan(data.mutation("shellfruit")!!) == null && core.plan(data.mutation("jerryflower")!!) == null, "shellfruit/jerryflower are not plannable")
        // With only some squares unlocked, a layout may only use those squares, and it must still be a valid one.
        for ((name, mask) in listOf("default 12" to GreenhousePlots.default(), "30 middle" to GreenhousePlots.fill(30))) {
            var fitted = 0
            for (m in data.mutations) {
                val layout = core.plan(m, mask) ?: continue
                fitted++
                for (r in 0 until 10) for (c in 0 until 10) check(layout.cells[r][c] == null || mask[r * 10 + c]) { "${m.id} ($name) uses locked square $r,$c" }
                check(core.spawns(layout, m.id)) { "${m.id} ($name): masked layout does not spawn it\n${dump(layout)}" }
            }
            NyAddOns.logger.info("[Greenhouse] planner with $name unlocked squares: $fitted of 40 fit")
            check(fitted > 0) { "nothing fits in $name squares" }
        }
        check(data.mutations.all { core.plan(it, BooleanArray(100)) == null }) { "nothing can be planned with every square locked" }
        check(data.mutations.count { core.plan(it, GreenhousePlots.all()) != null } >= planned - 2) { "an all-unlocked mask should plan about as many as no mask" }
        // Extending a picture that already holds the target's ring crops should reuse them: a second block needs fewer new squares than a first.
        fun filled(l: GhLayout) = l.cells.sumOf { row -> row.count { it != null } }
        var fresh = 0
        var added = 0
        var extended = 0
        for (m in data.mutations) {
            if (!core.targetable(m.id)) continue
            val first = core.extend(null, m, null, 0) ?: continue
            val second = core.extend(first, m, null, 0) ?: continue
            extended++
            check(core.spawns(second, m.id)) { "${m.id}: extended layout does not spawn it\n${dump(second)}" }
            check(filled(second) > filled(first)) { "${m.id}: extending added nothing" }
            fresh += filled(first)
            added += filled(second) - filled(first)
        }
        NyAddOns.logger.info("[Greenhouse] planner reuse: $extended second blocks added $added squares vs $fresh for the first blocks")
        check(extended >= 20) { "only $extended mutations could be extended" }
        check(added < fresh) { "second blocks added $added squares, first blocks needed $fresh: ring crops are not reused" }
        // Reuse placement (core.reuseEnabled) over many base pictures: every attempt keeps the base, stays inside the mask (unlocked
        // minus blocked), spawns the target, and the 8 attempts still give more than one distinct layout. The same pairs are then run
        // with reuse off and the totals compared (best-of-8 new squares, summed over all base/target pairs).
        val blockedSq = BooleanArray(100).also { for (i in 0 until 100 step 7) it[i] = true }
        val reuseMask = GreenhousePlots.usable(GreenhousePlots.fill(70), blockedSq)
        var pairs = 0
        var bestSum = 0
        var attemptSum = 0
        var attemptCount = 0
        var lowVariety = 0
        for (mask in listOf<BooleanArray?>(null, reuseMask)) {
            val bases = data.mutations.filter { core.targetable(it.id) }.mapNotNull { m -> core.extend(null, m, mask, 0)?.let { m to it } }
            for ((bm, base) in bases.filterIndexed { i, _ -> i % 4 == 0 }) for (m in data.mutations) {
                if (!core.targetable(m.id)) continue
                val results = (0 until 8).mapNotNull { a -> core.extend(base, m, mask, a) }
                if (results.isEmpty()) continue
                for (r in results) {
                    check(core.spawns(r, m.id)) { "${m.id} on ${bm.id}: reuse layout does not spawn it\n${dump(r)}" }
                    for (rr in 0 until 10) for (cc in 0 until 10) {
                        val b = base.cells[rr][cc]
                        check(b == null || r.cells[rr][cc] == b) { "${m.id} on ${bm.id}: base square $rr,$cc was changed" }
                        if (mask != null) check(b != null || r.cells[rr][cc] == null || mask[rr * 10 + cc]) { "${m.id} on ${bm.id}: uses locked/blocked square $rr,$cc" }
                    }
                    attemptSum += filled(r) - filled(base)
                    attemptCount++
                }
                pairs++
                NyAddOns.logger.info("[Greenhouse] reusepair ${if (mask == null) "free" else "mask"} ${bm.id}+${m.id} ${results.minOf { filled(it) - filled(base) }}")
                bestSum += results.minOf { filled(it) - filled(base) }
                if (results.map { dump(it) }.toSet().size < 2 && results.size >= 4) lowVariety++
            }
        }
        // The reuse readout: an extension keeps every base square, so reused = base squares and added = the new ones.
        val one = core.extend(null, data.mutations.first { core.targetable(it.id) }, null, 0)!!
        val two = data.mutations.firstNotNullOf { m -> if (core.targetable(m.id)) core.extend(one, m, null, 0) else null }
        val kept = GhReuse.of(one, two)!!
        check(kept.reused == filled(one) && kept.added == filled(two) - filled(one)) { "reuse count ${kept.reused}/${kept.added}, expected ${filled(one)}/${filled(two) - filled(one)}" }
        check(GhReuse.of(null, two) == null && GhReuse.of(GhLayout(10, Array(10) { arrayOfNulls<String>(10) }, ""), two) == null) { "no base picture must give no readout" }
        val moved = GhReuse.of(two, one)!!
        check(moved.reused + moved.added == filled(one)) { "reuse + new must equal the squares of the new layout" }
        NyAddOns.logger.info("[Greenhouse] reuseAt: $pairs pairs, best-of-8 new squares $bestSum, mean per attempt ${attemptSum.toFloat() / attemptCount}, low variety $lowVariety")
        // Old placement over the same pairs, for comparison.
        core.reuseEnabled = false
        var oldBestSum = 0
        var oldPairs = 0
        for (mask in listOf<BooleanArray?>(null, reuseMask)) {
            val bases = data.mutations.filter { core.targetable(it.id) }.mapNotNull { m -> core.extend(null, m, mask, 0)?.let { m to it } }
            for ((_, base) in bases.filterIndexed { i, _ -> i % 4 == 0 }) for (m in data.mutations) {
                if (!core.targetable(m.id)) continue
                val results = (0 until 8).mapNotNull { a -> core.extend(base, m, mask, a) }
                if (results.isEmpty()) continue
                oldPairs++
                oldBestSum += results.minOf { filled(it) - filled(base) }
            }
        }
        core.reuseEnabled = true
        NyAddOns.logger.info("[Greenhouse] reuse on: best-of-8 new squares $bestSum over $pairs pairs; reuse off: $oldBestSum over $oldPairs pairs")
        check(bestSum.toFloat() / pairs <= oldBestSum.toFloat() / oldPairs) { "reuse placement needs $bestSum new squares over $pairs pairs, old placement $oldBestSum over $oldPairs" }
        check(pairs >= 100) { "only $pairs base/target pairs could be extended" }
        check(lowVariety * 10 <= pairs) { "$lowVariety of $pairs pairs gave one identical layout for every attempt" }
        check(planned >= 36, "only $planned of 40 mutations got a layout; null: $nulls")
        check(selfContained.size >= 12, "only ${selfContained.size} self-contained layouts: $selfContained")
    }

    private fun dump(l: GhLayout) = l.cells.joinToString("\n") { row -> row.joinToString(" ") { (it ?: ".").take(4).padEnd(4) } }
}
