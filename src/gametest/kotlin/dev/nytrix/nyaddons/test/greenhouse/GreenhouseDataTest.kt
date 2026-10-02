package dev.nytrix.nyaddons.test.greenhouse

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.features.greenhouse.AnalysisCosts
import dev.nytrix.nyaddons.features.greenhouse.GreenhouseDataImpl
import dev.nytrix.nyaddons.features.greenhouse.GreenhouseGoals
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/** Parses a copy of the real SkyShards-Greenhouse data.json and checks the data layer and goal logic. Needs no world. */
@Suppress("UnstableApiUsage")
class GreenhouseDataTest : FabricClientGameTest {

    private fun check(ok: Boolean, message: () -> String) {
        if (!ok) throw AssertionError("GreenhouseData: ${message()}")
    }

    private fun usedHeap(): Long {
        repeat(3) { System.gc() }
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }

    override fun runTest(context: ClientGameTestContext) {
        val json = javaClass.getResourceAsStream("/greenhouse/data.json")!!.bufferedReader().readText()
        val data = GreenhouseDataImpl
        data.clear()
        check(!data.ready) { "ready before load" }

        val before = usedHeap()
        val start = System.nanoTime()
        data.loadFrom(json.reader())
        val parseNanos = System.nanoTime() - start
        val retained = usedHeap() - before
        NyAddOns.logger.info("[NyBench] greenhouse data: parsed in ${parseNanos / 1000} us, retains about ${retained / 1024} KB")

        check(data.ready) { "not ready after load" }
        check(data.mutations.size == 40) { "mutations ${data.mutations.size}" }
        check(data.crops.size == 17) { "crops ${data.crops.size}" }

        val ash = data.mutation("ashwreath") ?: throw AssertionError("no ashwreath")
        check(ash.name == "Ashwreath" && ash.size == 1 && ash.soil == "soul_sand" && ash.rarity == "common") { "ashwreath fields" }
        check(ash.requirements.size == 2 && ash.requirements[0].crop == "nether_wart" && ash.requirements[0].count == 2 &&
            ash.requirements[1].crop == "fire" && ash.requirements[1].count == 2) { "ashwreath requirements" }
        check(ash.analysisCoins == 10_000L && ash.analysisCopper == 5 && ash.firstAnalysisCopper == 250) { "ashwreath costs" }
        check(data.mutation("devourer")!!.requiresWatering) { "devourer watering" }
        check(data.nameOf("all_in_aloe") == "All-in Aloe" && data.nameOf("nether_wart") == "Nether Wart" && data.nameOf("zzz") == "zzz") { "nameOf" }
        check(AnalysisCosts.size == 40) { "cost table ${AnalysisCosts.size}" }

        val noRequirements = setOf("lonelily", "shellfruit", "godseed", "jerryflower")
        val known = HashSet<String>()
        data.crops.forEach { known.add(it.id) }
        data.mutations.forEach { known.add(it.id) }
        for (m in data.mutations) {
            check(m.analysisCoins > 0 && m.analysisCopper > 0 && m.firstAnalysisCopper > 0) { "no costs for ${m.id}" }
            // Four mutations spawn by a special rule (such as zero neighbours) and list no crop requirements.
            check(m.requirements.isNotEmpty() || m.id in noRequirements) { "no requirements for ${m.id}" }
            for (r in m.requirements) check(r.crop in known && r.count > 0) { "${m.id} requires unknown ${r.crop}" }
        }

        // Unique order: sorted, excludes analysed.
        val analysed = setOf("ashwreath", "choconut")
        val order = GreenhouseGoals.uniqueOrder(data, { null }, analysed)
        check(order.size == 36 && order.none { it.mutation.id in analysed }) { "uniqueOrder size ${order.size}" }
        for (i in 1 until order.size) {
            val a = order[i - 1].mutation
            val b = order[i].mutation
            check(a.analysisCoins < b.analysisCoins || (a.analysisCoins == b.analysisCoins && (a.analysisCopper < b.analysisCopper ||
                (a.analysisCopper == b.analysisCopper && a.name <= b.name)))) { "unsorted at ${a.name}, ${b.name}" }
        }
        check(order.last().mutation.id == "timestalk") { "last should be timestalk" }
        check(order.all { it.canStartNow }) { "unknown stock should not block" }
        val none = GreenhouseGoals.uniqueOrder(data, { 0 }, emptySet())
        check(none.all { it.canStartNow == it.mutation.requirements.isEmpty() && it.missing.isNotEmpty() == it.mutation.requirements.isNotEmpty() }) { "empty stock should block all" }
        val rich = GreenhouseGoals.uniqueOrder(data, { 999 }, emptySet())
        check(rich.all { it.canStartNow && it.missing.isEmpty() }) { "full stock should allow all" }

        // Milestones.
        val m0 = GreenhouseGoals.milestones(0)
        check(m0.current == null && m0.next!!.threshold == 1 && m0.moreNeeded == 1) { "milestone 0" }
        val m1 = GreenhouseGoals.milestones(1)
        check(m1.current!!.threshold == 1 && m1.next!!.threshold == 10 && m1.moreNeeded == 9) { "milestone 1" }
        val m10 = GreenhouseGoals.milestones(10)
        check(m10.current!!.tier == 2 && m10.next!!.threshold == 15 && m10.moreNeeded == 5) { "milestone 10" }
        val m39 = GreenhouseGoals.milestones(39)
        check(m39.current!!.tier == 5 && m39.next!!.threshold == 40 && m39.moreNeeded == 1) { "milestone 39" }
        val m40 = GreenhouseGoals.milestones(40)
        check(m40.current!!.tier == 6 && m40.next == null && m40.moreNeeded == 0) { "milestone 40" }

        // Rose Dragon.
        val stock = mapOf("Helianthus" to 20, "Condensed Helianthus" to 2, "Devourer" to 1)
        val needs = GreenhouseGoals.roseDragonNeeds(data) { stock[it] }
        val helianthus = needs.first { it.name == "Helianthus" }
        check(helianthus.need == 45 && helianthus.have == 20 && helianthus.missing == 25) { "helianthus line" }
        val condensed = needs.first { it.name == "Condensed Helianthus" }
        check(condensed.need == 5 && condensed.missing == 3) { "condensed line" }
        for (name in GreenhouseGoals.roseDragonMutations) {
            val need = needs.firstOrNull { it.name == name } ?: throw AssertionError("rose dragon lacks $name")
            check(need.need == 1 && need.parts.isNotEmpty()) { "rose dragon $name parts" }
        }
        val devourer = needs.first { it.name == "Devourer" }
        check(devourer.missing == 0 && devourer.parts.map { it.name to it.need } == listOf("Puffercloud" to 4, "Zombud" to 4)) { "devourer parts" }

        // Idle release.
        data.expireNow()
        check(!data.ready) { "data not released when idle" }
        check(data.mutations.isEmpty() && data.mutation("ashwreath") == null) { "released data still readable" }
        val after = usedHeap()
        NyAddOns.logger.info("[NyBench] greenhouse data: heap after release ${(after - before) / 1024} KB vs before load")
        data.loadFrom(json.reader())
        check(data.ready) { "reload failed" }
        data.clear()
    }
}
