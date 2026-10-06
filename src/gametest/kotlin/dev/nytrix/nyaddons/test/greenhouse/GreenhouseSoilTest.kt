package dev.nytrix.nyaddons.test.greenhouse

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.config.GreenhouseView
import dev.nytrix.nyaddons.features.greenhouse.GhCrop
import dev.nytrix.nyaddons.features.greenhouse.GhData
import dev.nytrix.nyaddons.features.greenhouse.GhLayout
import dev.nytrix.nyaddons.features.greenhouse.GhMutation
import dev.nytrix.nyaddons.features.greenhouse.GhPlanner
import dev.nytrix.nyaddons.features.greenhouse.GhRequirement
import dev.nytrix.nyaddons.features.greenhouse.GhSoil
import dev.nytrix.nyaddons.features.greenhouse.GhStock
import dev.nytrix.nyaddons.features.greenhouse.Greenhouse
import dev.nytrix.nyaddons.features.greenhouse.GreenhouseScreen
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/** Screenshots of the planner grid with one block per soil, to check the soil colours and the soil key. */
@Suppress("UnstableApiUsage")
class GreenhouseSoilTest : FabricClientGameTest {

    private val soils = listOf("farmland", "soul_sand", "sand", "mycelium", "end_stone", "netherrack")
    private val crops = soils.map { GhCrop("c_$it", "Crop on $it", 1, it) }
    private val target = GhMutation("tgt", "Target", "rare", 1, "farmland", listOf(GhRequirement("c_farmland", 1)), 3, false, 1000L, 5)

    private val data = object : GhData {
        override val ready = true
        override val crops = this@GreenhouseSoilTest.crops
        override val mutations = listOf(target)
        override fun mutation(id: String) = mutations.firstOrNull { it.id == id }
        override fun nameOf(id: String) = mutation(id)?.name ?: crops.firstOrNull { it.id == id }?.name ?: id
        override fun request() {}
    }

    private val stock = object : GhStock {
        override fun count(itemName: String): Int? = null
        override fun inSacks(itemName: String): Int? = null
        override fun inInventory(itemName: String) = 0
        override val sacksUpdatedAt get() = 0L
    }

    /** The target in the middle and one plant per id in a row beside it. */
    private fun plannerFor(ids: List<String>) = object : GhPlanner {
        override fun plan(target: GhMutation): GhLayout? = plan(target, null)
        override fun plan(target: GhMutation, unlocked: BooleanArray?): GhLayout {
            val cells = Array(10) { arrayOfNulls<String>(10) }
            cells[4][3] = target.id
            for ((i, id) in ids.withIndex()) cells[4][4 + i] = id
            return GhLayout(10, cells, target.id)
        }
    }

    override fun runTest(context: ClientGameTestContext) {
        // Every soil the screen knows has its own colour and letter; an unknown one falls back to grey and "?".
        for (soil in soils) {
            check(GhSoil.known(soil) && GhSoil.color(soil) != GhSoil.UNKNOWN && GhSoil.letter(soil) != "?") { "soil $soil has no look" }
        }
        check(soils.map { GhSoil.letter(it) }.toSet().size == soils.size) { "two soils share a letter" }
        check(!GhSoil.known("lava_rock") && GhSoil.color("lava_rock") == GhSoil.UNKNOWN && GhSoil.letter("lava_rock") == "?") { "unknown soil is not grey" }
        val oldData = Greenhouse.data
        val oldStock = Greenhouse.stock
        val oldPlanner = Greenhouse.planner
        val oldView = NyAddOns.config.garden.greenhouse.view
        System.setProperty("nyaddons.devArea", "Garden")
        try {
            context.worldBuilder().create().use {
                context.input.resizeWindow(1280, 720)
                // The six-soil picture stays as the colour reference (made-up crops, one per soil).
                shot(context, "all-six", crops.map { it.id })
            }
        } finally {
            context.runOnClient<RuntimeException> {
                Greenhouse.data = oldData
                Greenhouse.stock = oldStock
                Greenhouse.planner = oldPlanner
                NyAddOns.config.garden.greenhouse.view = oldView
            }
            System.clearProperty("nyaddons.devArea")
            context.setScreen { null }
            context.input.resizeWindow(854, 480)
        }
    }

    private fun shot(context: ClientGameTestContext, tag: String, ids: List<String>) = shot(context, tag, data, plannerFor(ids))

    private fun shot(context: ClientGameTestContext, tag: String, shotData: GhData, shotPlanner: GhPlanner) {
        context.runOnClient<RuntimeException> {
            Greenhouse.data = shotData
            Greenhouse.stock = stock
            Greenhouse.planner = shotPlanner
            NyAddOns.config.garden.greenhouse.view = GreenhouseView.ALL_MUTATIONS
            NyAddOns.openScreen { GreenhouseScreen() }
        }
        context.waitForScreen(GreenhouseScreen::class.java)
        context.waitTicks(20)
        fun click(where: (GreenhouseScreen) -> IntArray) {
            val (x, y, scale) = context.computeOnClient<Triple<Int, Int, Double>, RuntimeException> {
                val p = where(it.screen as GreenhouseScreen)
                Triple(p[0], p[1], it.window.screenWidth.toDouble() / it.window.guiScaledWidth)
            }
            context.input.setCursorPos(x * scale, y * scale)
            context.waitTick()
            context.input.holdMouse(0)
            context.waitTick()
            context.input.releaseMouse(0)
            context.waitTicks(3)
        }
        click { it.allRowCenter(0) }
        click { it.planButtonCenter() }
        context.waitTicks(10)
        check(context.computeOnClient<Boolean, RuntimeException> { (it.screen as GreenhouseScreen).hasLayout }) { "no layout for $tag" }
        context.takeScreenshot("soil-$tag")
        context.setScreen { null }
    }
}
