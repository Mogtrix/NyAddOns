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
import dev.nytrix.nyaddons.features.greenhouse.GreenhousePlots
import dev.nytrix.nyaddons.core.GreenhouseProfile
import dev.nytrix.nyaddons.features.greenhouse.GreenhouseTree
import dev.nytrix.nyaddons.features.greenhouse.GhStock
import dev.nytrix.nyaddons.features.greenhouse.Greenhouse
import dev.nytrix.nyaddons.features.greenhouse.GreenhouseScreen
import dev.nytrix.nyaddons.features.greenhouse.roseDragonNeeds
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
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
        val items = mutableMapOf("wheat" to 640, "nether wart" to 12, "helianthus" to 12, "condensed helianthus" to 2, "glasscorn" to 1, "ashwreath" to 0)
        var updated = System.currentTimeMillis() - 5 * 60_000
        override fun count(itemName: String): Int? = items[itemName.lowercase()]
        override fun inSacks(itemName: String): Int? = count(itemName)
        override fun inInventory(itemName: String) = 0
        override val sacksUpdatedAt get() = updated
    }

    /** Needs 8 unlocked squares: the target in the one nearest the middle and seven plants around it. Honours the mask like the real planner. */
    private val fakePlanner = object : GhPlanner {
        override fun plan(target: GhMutation): GhLayout? = plan(target, null)
        override fun plan(target: GhMutation, unlocked: BooleanArray?): GhLayout? {
            val free = (0 until 100).filter { unlocked == null || unlocked[it] }
                .sortedBy { Math.abs(it / 10 - 4.5) + Math.abs(it % 10 - 4.5) }
            if (free.size < 8) return null
            val cells = Array(10) { arrayOfNulls<String>(10) }
            for ((i, cell) in free.take(8).withIndex()) cells[cell / 10][cell % 10] = if (i == 0) target.id else if (i % 2 == 0) "wheat" else "nether_wart"
            return GhLayout(10, cells, target.id)
        }
    }

    override fun runTest(context: ClientGameTestContext) {
        val oldData = Greenhouse.data
        val oldStock = Greenhouse.stock
        val oldPlanner = Greenhouse.planner
        val oldView = NyAddOns.config.garden.greenhouse.view
        val oldAnalysed = context.computeOnClient<Set<String>, RuntimeException> { Storage.profile.greenhouse.analysed.toSet() }
        val oldAmounts = context.computeOnClient<Map<String, Int>, RuntimeException> { Storage.profile.greenhouse.amounts.toMap() }
        val oldPlots = context.computeOnClient<String, RuntimeException> { Storage.profile.greenhouse.plots }
        try {
            context.worldBuilder().create().use { run(context) }
        } finally {
            context.onClient {
                Greenhouse.data = oldData
                Greenhouse.stock = oldStock
                Greenhouse.planner = oldPlanner
                NyAddOns.config.garden.greenhouse.view = oldView
                Storage.profile.greenhouse.analysed = oldAnalysed.toMutableSet()
                Storage.profile.greenhouse.amounts = oldAmounts.toMutableMap()
                Storage.profile.greenhouse.plots = oldPlots
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
            Storage.profile.greenhouse.amounts = mutableMapOf()
            Storage.profile.greenhouse.plots = ""
        }
        context.onClient { NyAddOns.openScreen { GreenhouseScreen() } }
        context.waitForScreen(GreenhouseScreen::class.java)
        context.waitTicks(25)
        check(screen(context).view == GreenhouseView.UNIQUE_MUTATIONS)
        context.takeScreenshot("gh-1-unique-854")
        checkHeader(context, "Mutations found 0/38")

        // Ticking the cheapest row (Ashwreath).
        click(context) { it.checkboxX() to it.uniqueRowY(0) }
        context.onClient {
            check("ashwreath" in Storage.profile.greenhouse.analysed) { "the checkbox did not tick Ashwreath: ${Storage.profile.greenhouse.analysed}" }
        }
        context.waitTicks(3)
        context.takeScreenshot("gh-2-unique-ticked")
        checkHeader(context, "Mutations found 1/38")
        uniqueAmounts(context)
        context.takeScreenshot("gh-9-unique-amounts-854")

        // Dropdown: Rose Dragon.
        pickView(context, 1)
        context.onClient { check(NyAddOns.config.garden.greenhouse.view == GreenhouseView.ROSE_DRAGON) { "view not remembered" } }
        check(screen(context).view == GreenhouseView.ROSE_DRAGON)
        val helianthus = roseDragonNeeds(fakeData, fakeStock).first { it.label.startsWith("Helianthus") }
        check(helianthus.need == 45) { "Rose Dragon should need 45 Helianthus, got ${helianthus.need}" }
        context.waitTicks(3)
        context.takeScreenshot("gh-3-rose-854")
        roseTree(context)
        plotsPicker(context)

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
        checkHeader(context, "Mutations found 1/38")
        pickView(context, 1)
        context.waitTicks(3)
        context.takeScreenshot("gh-8-rose-1280")
        checkHeader(context, "Mutations found 1/38")
        // Tree with a layout in the side panel, and the Plots picker, in the bigger window.
        click(context) { it.treeArrowCenter(2).let { p -> p[0] to p[1] } }
        click(context) { it.treeTextCenter(1).let { p -> p[0] to p[1] } }
        context.waitTicks(15)
        context.takeScreenshot("gh-13-rose-panel-1280")
        click(context) { it.plotsButtonCenter().let { p -> p[0] to p[1] } }
        context.waitTicks(3)
        context.takeScreenshot("gh-14-plots-1280")
        context.setScreen { null }
        context.input.resizeWindow(854, 480)
    }

    private fun checkHeader(context: ClientGameTestContext, found: String) {
        context.onClient {
            val s = screenNow()
            val text = s.headerText()
            check(text[0].startsWith("Rose Dragon ~") && text[0].endsWith("% (rough estimate)")) { "Rose Dragon line: ${text[0]}" }
            check(text[1] == found) { "found line: ${text[1]} (wanted $found)" }
            check(text[3] == "Shellfruit and Jerryflower are not included yet: the mod is being updated for them.") { "disclaimer: ${text[3]}" }
            check(s.headerFits()) { "the header does not fit this window size: $text" }
        }
    }

    private fun typeDigits(context: ClientGameTestContext, digits: String) {
        context.onClient { for (ch in digits) screenNow().charTyped(CharacterEvent(ch.code)) }
        context.waitTick()
    }

    private fun pressKey(context: ClientGameTestContext, key: Int) {
        context.onClient { screenNow().keyPressed(KeyEvent(key, 0, 0)) }
        context.waitTick()
    }

    private fun amount(id: String) = Storage.profile.greenhouse.amounts[id] ?: 1

    /** Number boxes in the Unique Mutations view: typing, Backspace, the 4 digit limit, "set all", and the totals line. */
    private fun uniqueAmounts(context: ClientGameTestContext) {
        // Row 1 is Cheesebite (4 Wheat + 1 Moonflower each).
        click(context) { it.amountBoxCenter(1).let { p -> p[0] to p[1] } }
        typeDigits(context, "200")
        context.onClient { check(amount("cheesebite") == 200) { "typed 200 but amount is ${amount("cheesebite")}" } }
        pressKey(context, 259)
        context.onClient { check(amount("cheesebite") == 20) { "Backspace should leave 20, got ${amount("cheesebite")}" } }
        typeDigits(context, "2")
        context.onClient { check(amount("cheesebite") == 202) }
        typeDigits(context, "200")
        context.onClient { check(amount("cheesebite") == 2022) { "four digits at most (202 + 2, the rest ignored): ${amount("cheesebite")}" } }
        pressKey(context, 259)
        pressKey(context, 259)
        pressKey(context, 259)
        typeDigits(context, "00")
        context.onClient { check(amount("cheesebite") == 200) { "got ${amount("cheesebite")}" } }
        context.waitTicks(3)
        context.onClient {
            val s = screenNow()
            check(s.uniqueText()[1].contains("200")) { "row text ${s.uniqueText()[1]}" }
            val totals = s.headerText()[2]
            check(totals.contains("short") && totals.contains("Wheat")) { "800 Wheat needed but 640 held, totals line: $totals" }
        }
        // Escape leaves the box without closing the window.
        pressKey(context, 256)
        context.onClient { check(net.minecraft.client.Minecraft.getInstance().screen is GreenhouseScreen) }

        click(context) { it.setAllCenter().let { p -> p[0] to p[1] } }
        typeDigits(context, "0")
        context.onClient {
            check(screenNow().headerText()[2].startsWith("Nothing to make")) { "totals with every amount 0: ${screenNow().headerText()[2]}" }
            check(Storage.profile.greenhouse.amounts.values.all { it == 0 })
        }
        typeDigits(context, "3")
        context.onClient { check(amount("cheesebite") == 3 && amount("devourer") == 3) }
        // Wheat: Cheesebite 4 + Glasscorn 3 + Devourer 2 + Timestalk 4 = 13 per set, x3 = 39 (Ashwreath is analysed and left out).
        context.waitTicks(3)
        context.takeScreenshot("gh-10-unique-all3-854")
        typeDigits(context, "")
        pressKey(context, 259)
        typeDigits(context, "1")
        context.onClient {
            check(Storage.profile.greenhouse.amounts.isEmpty()) { "an amount of 1 is the default and is not stored: ${Storage.profile.greenhouse.amounts}" }
        }
        pressKey(context, 256)
        // Old save files without the new fields still load with defaults.
        context.onClient {
            val old = com.google.gson.Gson().fromJson("{\"analysed\":[\"a\"]}", GreenhouseProfile::class.java)
            check(old.amounts.isEmpty() && old.plots.isEmpty() && "a" in old.analysed) { "old profile data did not load with defaults" }
            check(GreenhousePlots.parse("").contentEquals(GreenhousePlots.default()) && GreenhousePlots.count(GreenhousePlots.default()) == 12)
        }
    }

    /** The Rose Dragon tree: expanding, counts, picking an item, the side panel and refreshed sack numbers. */
    private fun roseTree(context: ClientGameTestContext) {
        fun tree() = context.computeOnClient<List<String>, RuntimeException> { screenNow().treeText() }
        val start = tree()
        check(start.size == 6 && start[0] == "> Condensed Helianthus 2/5" && start[1].startsWith("> Glasscorn 1/1")) { "collapsed tree: $start" }
        click(context) { it.treeArrowCenter(2).let { p -> p[0] to p[1] } }
        val devourer = tree()
        check(devourer.size == 8 && devourer[2].startsWith("v Devourer") && devourer[3] == "  x2 Wheat 640/2" && devourer[4] == "  > x2 Ashwreath 0/2") { "Devourer expanded: $devourer" }
        click(context) { it.treeArrowCenter(4).let { p -> p[0] to p[1] } }
        val deep = tree()
        check(deep.size == 9 && deep[5] == "    x4 Nether Wart 12/4") { "Ashwreath expanded: $deep" }
        context.takeScreenshot("gh-11-rose-tree-854")
        click(context) { it.treeArrowCenter(2).let { p -> p[0] to p[1] } }
        check(tree().size == 6) { "collapsing Devourer should hide its ingredients: ${tree()}" }
        click(context) { it.treeArrowCenter(2).let { p -> p[0] to p[1] } }

        // Picking the two Ashwreath: needs 2, the fake layout makes one per round.
        click(context) { it.treeTextCenter(4).let { p -> p[0] to p[1] } }
        context.waitTicks(15)
        context.onClient {
            val s = screenNow()
            check(s.panelItem == "Ashwreath x2") { "panel item ${s.panelItem}" }
            check(s.panelHasLayout && s.panelRoundCount == 2) { "rounds ${s.panelRoundCount}, layout ${s.panelHasLayout}" }
            val layout = s.panelLayout!!
            check(layout.target == "ashwreath")
            val mask = GreenhousePlots.current()
            for (r in 0 until 10) for (c in 0 until 10) check(layout.cells[r][c] == null || mask[r * 10 + c]) { "layout uses locked square $r,$c" }
        }
        context.takeScreenshot("gh-12-rose-panel-854")
        click(context) { it.treeTextCenter(1).let { p -> p[0] to p[1] } }
        context.waitTicks(15)
        context.onClient {
            val s = screenNow()
            check(s.panelItem == "Glasscorn x1" && s.panelRoundCount == 1 && s.panelHasLayout) { "Glasscorn panel: ${s.panelItem} ${s.panelRoundCount}" }
        }
        click(context) { it.treeTextCenter(0).let { p -> p[0] to p[1] } }
        context.waitTicks(3)
        context.onClient {
            val s = screenNow()
            check(!s.panelHasLayout && s.panelMessage.contains("base crop")) { "crop panel: '${s.panelMessage}'" }
        }

        // Fresh sack numbers show without reopening the window.
        context.onClient { fakeStock.items["wheat"] = 7; fakeStock.updated = System.currentTimeMillis() }
        context.waitTicks(4)
        val fresh = tree()
        check(fresh[3] == "  x2 Wheat 7/2") { "tree did not pick up the new Wheat count: $fresh" }
        context.onClient { fakeStock.items["wheat"] = 640; fakeStock.updated = System.currentTimeMillis() - 5 * 60_000 }
        context.waitTicks(4)

        click(context) { it.treeTextCenter(1).let { p -> p[0] to p[1] } }
        click(context) { it.treeTextCenter(1).let { p -> p[0] to p[1] } }
        context.waitTicks(3)
        check(context.computeOnClient<String?, RuntimeException> { screenNow().panelItem } == null) { "clicking the picked item again should close the panel" }
        val percent = GreenhouseTree.percent(fakeData, fakeStock)
        check(percent == 6) { "1 of 15 needed mutations is held, got $percent%" }
    }

    /** The Plots picker: toggling, Fill to N, All, Default, persistence and the "needs more squares" flags. */
    private fun plotsPicker(context: ClientGameTestContext) {
        fun squares() = context.computeOnClient<Int, RuntimeException> { screenNow().unlockedSquares }
        fun saved() = context.computeOnClient<String, RuntimeException> { Storage.profile.greenhouse.plots }
        click(context) { it.plotsButtonCenter().let { p -> p[0] to p[1] } }
        check(context.computeOnClient<Boolean, RuntimeException> { screenNow().plotsShown }) { "Plots button did not open the picker" }
        check(squares() == 12)
        context.takeScreenshot("gh-15-plots-854")
        click(context) { it.plotCellCenter(0, 0).let { p -> p[0] to p[1] } }
        check(squares() == 13 && saved()[0] == '1') { "toggle on: ${squares()} ${saved()}" }
        click(context) { it.plotCellCenter(0, 0).let { p -> p[0] to p[1] } }
        check(squares() == 12 && saved()[0] == '0')
        click(context) { it.plotCellCenter(4, 4).let { p -> p[0] to p[1] } }
        check(squares() == 11 && saved()[44] == '0') { "lock the middle: ${squares()}" }
        click(context) { it.fillBoxCenter().let { p -> p[0] to p[1] } }
        typeDigits(context, "30")
        click(context) { it.fillButtonCenter().let { p -> p[0] to p[1] } }
        check(squares() == 30) { "Fill to 30 gave ${squares()}" }
        val mask = GreenhousePlots.parse(saved())
        check(mask[44] && mask[45] && mask[54] && mask[55] && !mask[0] && !mask[99]) { "the middle fills first: ${saved()}" }
        click(context) { it.fillPlusCenter().let { p -> p[0] to p[1] } }
        check(squares() == 31)
        click(context) { it.allPlotsCenter().let { p -> p[0] to p[1] } }
        check(squares() == 100 && saved().all { it == '1' })
        click(context) { it.defaultPlotsCenter().let { p -> p[0] to p[1] } }
        check(squares() == 12 && GreenhousePlots.parse(saved()).contentEquals(GreenhousePlots.default()))
        click(context) { it.fillBoxCenter().let { p -> p[0] to p[1] } }
        typeDigits(context, "3")
        pressKey(context, 257)
        check(squares() == 3) { "Enter in the Fill box should fill, got ${squares()}" }
        context.takeScreenshot("gh-16-plots-3-854")
        click(context) { it.donePlotsCenter().let { p -> p[0] to p[1] } }
        check(!context.computeOnClient<Boolean, RuntimeException> { screenNow().plotsShown })

        // With 3 squares the 8 square fake layout cannot fit: flagged in the lists and explained in the panel.
        pickView(context, 0)
        context.waitTicks(25)
        context.onClient {
            val s = screenNow()
            val row = s.uniqueText().first { !it.startsWith("Ashwreath") }
            check(row.contains("5")) { "a mutation needing 8 squares with 3 unlocked should say it needs 5 more: '$row'" }
        }
        context.takeScreenshot("gh-17-unique-flags-854")
        pickView(context, 1)
        click(context) { it.treeTextCenter(1).let { p -> p[0] to p[1] } }
        context.waitTicks(15)
        context.onClient {
            val s = screenNow()
            check(!s.panelHasLayout && s.panelMessage.contains("5 more squares")) { "panel with 3 squares: '${s.panelMessage}'" }
        }
        context.takeScreenshot("gh-18-rose-nofit-854")
        click(context) { it.treeTextCenter(1).let { p -> p[0] to p[1] } }
        click(context) { it.plotsButtonCenter().let { p -> p[0] to p[1] } }
        click(context) { it.defaultPlotsCenter().let { p -> p[0] to p[1] } }
        click(context) { it.donePlotsCenter().let { p -> p[0] to p[1] } }
        pickView(context, 1)
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
