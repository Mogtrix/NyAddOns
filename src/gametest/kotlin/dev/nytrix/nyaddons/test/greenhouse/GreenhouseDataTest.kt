package dev.nytrix.nyaddons.test.greenhouse

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.features.greenhouse.GreenhouseDataImpl
import dev.nytrix.nyaddons.features.greenhouse.GreenhouseGoals
import dev.nytrix.nyaddons.features.greenhouse.nextMilestone
import dev.nytrix.nyaddons.features.greenhouse.uniqueOrder
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
        check(ash.analysisCoins == 10_000L && ash.analysisCopper == 5) { "ashwreath costs" }
        check(data.mutation("devourer")!!.requiresWatering) { "devourer watering" }
        check(data.nameOf("all_in_aloe") == "All-in Aloe" && data.nameOf("nether_wart") == "Nether Wart" && data.nameOf("zzz") == "zzz") { "nameOf" }

        val noRequirements = setOf("lonelily", "shellfruit", "godseed", "jerryflower")
        val known = HashSet<String>()
        data.crops.forEach { known.add(it.id) }
        data.mutations.forEach { known.add(it.id) }
        for (m in data.mutations) {
            check(m.analysisCoins > 0 && m.analysisCopper > 0) { "no costs for ${m.id}" }
            // Four mutations spawn by a special rule (such as zero neighbours) and list no crop requirements.
            check(m.requirements.isNotEmpty() || m.id in noRequirements) { "no requirements for ${m.id}" }
            for (r in m.requirements) check(r.crop in known && r.count > 0) { "${m.id} requires unknown ${r.crop}" }
        }

        // Unique order: cheapest first, without the two left out of the checklist.
        val order = uniqueOrder(data.mutations)
        check(order.size == 38 && order.none { it.id in GreenhouseGoals.skippedMutations }) { "uniqueOrder size ${order.size}" }
        for (i in 1 until order.size) {
            val a = order[i - 1]
            val b = order[i]
            check(a.analysisCoins < b.analysisCoins || (a.analysisCoins == b.analysisCoins && (a.analysisCopper < b.analysisCopper ||
                (a.analysisCopper == b.analysisCopper && a.name <= b.name)))) { "unsorted at ${a.name}, ${b.name}" }
        }
        check(order.last().id == "timestalk") { "last should be timestalk" }

        // Milestones.
        check(nextMilestone(0)!!.let { it.threshold == 1 && it.remaining == 1 && it.roman == "I" }) { "milestone 0" }
        check(nextMilestone(1)!!.let { it.threshold == 10 && it.remaining == 9 }) { "milestone 1" }
        check(nextMilestone(10)!!.let { it.tier == 3 && it.threshold == 15 && it.remaining == 5 }) { "milestone 10" }
        check(nextMilestone(39)!!.let { it.tier == 6 && it.threshold == 40 && it.remaining == 1 }) { "milestone 39" }
        check(nextMilestone(40) == null) { "milestone 40" }

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
