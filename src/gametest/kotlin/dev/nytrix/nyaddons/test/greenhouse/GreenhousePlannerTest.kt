package dev.nytrix.nyaddons.test.greenhouse

import com.google.gson.JsonParser
import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.features.greenhouse.GhCrop
import dev.nytrix.nyaddons.features.greenhouse.GhData
import dev.nytrix.nyaddons.features.greenhouse.GhLayout
import dev.nytrix.nyaddons.features.greenhouse.GhMutation
import dev.nytrix.nyaddons.features.greenhouse.GhRequirement
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
                    o["growth_stages"].asInt, o["requires_watering"].asBoolean, 0L, 0, 0,
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
        check(planned >= 36, "only $planned of 40 mutations got a layout; null: $nulls")
        check(selfContained.size >= 12, "only ${selfContained.size} self-contained layouts: $selfContained")
    }

    private fun dump(l: GhLayout) = l.cells.joinToString("\n") { row -> row.joinToString(" ") { (it ?: ".").take(4).padEnd(4) } }
}
