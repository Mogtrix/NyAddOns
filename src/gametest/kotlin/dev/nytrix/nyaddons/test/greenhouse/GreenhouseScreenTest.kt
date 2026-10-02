package dev.nytrix.nyaddons.test.greenhouse

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.config.GreenhouseView
import dev.nytrix.nyaddons.core.Storage
import dev.nytrix.nyaddons.features.greenhouse.GhCrop
import dev.nytrix.nyaddons.features.greenhouse.GhData
import dev.nytrix.nyaddons.features.greenhouse.GhLayout
import dev.nytrix.nyaddons.features.greenhouse.GhMutation
import dev.nytrix.nyaddons.features.greenhouse.GhPlanner
import dev.nytrix.nyaddons.features.greenhouse.GhRequirement
import dev.nytrix.nyaddons.features.greenhouse.GhStock
import dev.nytrix.nyaddons.features.greenhouse.Greenhouse
import dev.nytrix.nyaddons.features.greenhouse.GreenhouseScreen
import dev.nytrix.nyaddons.features.greenhouse.roseDragonNeeds
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/** The Greenhouse window: three views, the dropdown, ticking a mutation, and the layout grid. */
@Suppress("UnstableApiUsage")
class GreenhouseScreenTest : FabricClientGameTest {

    private val crops = listOf(
        GhCrop("wheat", "Wheat", 1, "farmland"),
        GhCrop("nether_wart", "Nether Wart", 1, "soul_sand"),
        GhCrop("helianthus", "Helianthus", 2, "farmland"),
        GhCrop("moonflower", "Moonflower", 1, "farmland"),
    )

    private fun mut(id: String, name: String, rarity: String, coins: Long, copper: Int, vararg req: Pair<String, Int>) =
        GhMutation(id, name, rarity, 1, "soul_sand", req.map { GhRequirement(it.first, it.second) }, 3, id.length % 2 == 0, coins, copper, copper / 20)

    private val mutations = listOf(
        mut("ashwreath", "Ashwreath", "common", 10_000, 5, "nether_wart" to 2),
        mut("cheesebite", "Cheesebite", "uncommon", 400_000, 400, "wheat" to 4, "moonflower" to 1),
        mut("glasscorn", "Glasscorn", "rare", 2_000_000, 1000, "wheat" to 3, "nether_wart" to 3),
        mut("devourer", "Devourer", "epic", 10_000_000, 5000, "wheat" to 2, "ashwreath" to 2),
        mut("all_in_aloe", "All-in Aloe", "epic", 8_000_000, 4000, "moonflower" to 6),
        mut("phantomleaf", "Phantomleaf", "legendary", 15_500_000, 7750, "glasscorn" to 2, "nether_wart" to 2),
        mut("timestalk", "Timestalk", "legendary", 19_000_000, 9500, "devourer" to 2, "wheat" to 4),
        mut("chocoberry", "Chocoberry", "mythic", 1_500_000_000, 750000, "cheesebite" to 3),
    )

    private val fakeData = object : GhData {
        override val ready = true
        override val crops = this@GreenhouseScreenTest.crops
        override val mutations = this@GreenhouseScreenTest.mutations
        override fun mutation(id: String) = mutations.firstOrNull { it.id == id }
        override fun nameOf(id: String) = mutation(id)?.name ?: crops.firstOrNull { it.id == id }?.name ?: id
        override fun request() {}
    }

    private val fakeStock = object : GhStock {
        private val items = mapOf("wheat" to 640, "nether wart" to 12, "helianthus" to 12, "condensed helianthus" to 2, "glasscorn" to 1, "ashwreath" to 0)
        override fun count(itemName: String): Int? = items[itemName.lowercase()]
        override fun inSacks(itemName: String): Int? = count(itemName)
        override fun inInventory(itemName: String) = 0
        override val sacksUpdatedAt = System.currentTimeMillis() - 5 * 60_000
    }

    private val fakePlanner = object : GhPlanner {
        override fun plan(target: GhMutation): GhLayout {
            val cells = Array(10) { arrayOfNulls<String>(10) }
            for (r in 0 until 10) for (c in 0 until 10) {
                cells[r][c] = when {
                    r in 3..6 && c in 3..6 && (r + c) % 2 == 0 -> "wheat"
                    r in 3..6 && c in 3..6 -> "nether_wart"
                    else -> null
                }
            }
            cells[4][4] = target.id
            cells[0][0] = "helianthus"
            return GhLayout(10, cells, target.id)
        }
    }

    override fun runTest(context: ClientGameTestContext) {
        val oldData = Greenhouse.data
        val oldStock = Greenhouse.stock
        val oldPlanner = Greenhouse.planner
        val oldView = NyAddOns.config.garden.greenhouse.view
        val oldAnalysed = context.computeOnClient<Set<String>, RuntimeException> { Storage.profile.greenhouse.analysed.toSet() }
        try {
            context.worldBuilder().create().use { run(context) }
        } finally {
            context.onClient {
                Greenhouse.data = oldData
                Greenhouse.stock = oldStock
                Greenhouse.planner = oldPlanner
                NyAddOns.config.garden.greenhouse.view = oldView
                Storage.profile.greenhouse.analysed = oldAnalysed.toMutableSet()
            }
            context.setScreen { null }
            context.input.resizeWindow(854, 480)
        }
    }

    private fun run(context: ClientGameTestContext) {
        context.input.resizeWindow(854, 480)
        context.onClient {
            Greenhouse.data = fakeData
            Greenhouse.stock = fakeStock
            Greenhouse.planner = fakePlanner
            NyAddOns.config.garden.greenhouse.view = GreenhouseView.UNIQUE_MUTATIONS
            Storage.profile.greenhouse.analysed = mutableSetOf()
        }
        context.onClient { NyAddOns.openScreen { GreenhouseScreen() } }
        context.waitForScreen(GreenhouseScreen::class.java)
        context.waitTicks(25)
        check(screen(context).view == GreenhouseView.UNIQUE_MUTATIONS)
        context.takeScreenshot("gh-1-unique-854")

        // Ticking the cheapest row (Ashwreath).
        click(context) { it.checkboxX() to it.uniqueRowY(0) }
        context.onClient {
            check("ashwreath" in Storage.profile.greenhouse.analysed) { "the checkbox did not tick Ashwreath: ${Storage.profile.greenhouse.analysed}" }
        }
        context.waitTicks(3)
        context.takeScreenshot("gh-2-unique-ticked")

        // Dropdown: Rose Dragon.
        pickView(context, 1)
        context.onClient { check(NyAddOns.config.garden.greenhouse.view == GreenhouseView.ROSE_DRAGON) { "view not remembered" } }
        check(screen(context).view == GreenhouseView.ROSE_DRAGON)
        val helianthus = roseDragonNeeds(fakeData, fakeStock).first { it.label.startsWith("Helianthus") }
        check(helianthus.need == 45) { "Rose Dragon should need 45 Helianthus, got ${helianthus.need}" }
        context.waitTicks(3)
        context.takeScreenshot("gh-3-rose-854")

        // Dropdown: All Mutations, select Glasscorn-ish row and plan.
        pickView(context, 2)
        context.onClient { check(NyAddOns.config.garden.greenhouse.view == GreenhouseView.ALL_MUTATIONS) }
        context.waitTicks(3)
        context.takeScreenshot("gh-4-all-854")
        click(context) { it.allRowCenter(3).let { p -> p[0] to p[1] } }
        context.waitTicks(3)
        click(context) { it.planButtonCenter().let { p -> p[0] to p[1] } }
        context.waitTicks(10)
        context.takeScreenshot("gh-5-layout-854")
        context.onClient { check(screenNow().hasLayout) { "Plan layout did not produce a layout" } }

        // Bigger window.
        context.input.resizeWindow(1280, 720)
        context.waitTicks(10)
        context.takeScreenshot("gh-6-all-1280")
        pickView(context, 0)
        context.waitTicks(3)
        context.takeScreenshot("gh-7-unique-1280")
        pickView(context, 1)
        context.waitTicks(3)
        context.takeScreenshot("gh-8-rose-1280")
        context.setScreen { null }
        context.input.resizeWindow(854, 480)
    }

    private fun ClientGameTestContext.onClient(block: (net.minecraft.client.Minecraft) -> Unit) {
        runOnClient<RuntimeException> { block(it) }
    }

    private fun screenNow() = net.minecraft.client.Minecraft.getInstance().screen as GreenhouseScreen

    private fun screen(context: ClientGameTestContext) = context.computeOnClient<GreenhouseScreen, RuntimeException> { screenNow() }

    private fun pickView(context: ClientGameTestContext, index: Int) {
        click(context) { it.dropdownCenter().let { p -> p[0] to p[1] } }
        click(context) { it.dropdownOptionCenter(index).let { p -> p[0] to p[1] } }
        context.waitTicks(2)
    }

    private fun click(context: ClientGameTestContext, where: (GreenhouseScreen) -> Pair<Int, Int>) {
        val (x, y, scale) = context.computeOnClient<Triple<Int, Int, Double>, RuntimeException> {
            val s = screenNow()
            val (gx, gy) = where(s)
            Triple(gx, gy, it.window.screenWidth.toDouble() / it.window.guiScaledWidth)
        }
        context.input.setCursorPos(x * scale, y * scale)
        context.waitTick()
        context.input.holdMouse(0)
        context.waitTick()
        context.input.releaseMouse(0)
        context.waitTick()
    }
}
