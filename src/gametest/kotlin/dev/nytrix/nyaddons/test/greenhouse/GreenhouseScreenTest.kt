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
import dev.nytrix.nyaddons.core.PinnedPlot
import dev.nytrix.nyaddons.features.greenhouse.GreenhousePin
import dev.nytrix.nyaddons.gui.OverlayManager
import dev.nytrix.nyaddons.features.greenhouse.GreenhouseTree
import dev.nytrix.nyaddons.features.greenhouse.GhStock
import dev.nytrix.nyaddons.features.greenhouse.Greenhouse
import dev.nytrix.nyaddons.features.greenhouse.GreenhouseScreen
import dev.nytrix.nyaddons.features.greenhouse.GhIcons
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/** The Greenhouse window: four views, the dropdown, ticking a mutation, the layout grid and the Planner tab. */
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
        val oldAmounts = context.computeOnClient<Map<String, Int>, RuntimeException> { Storage.profile.greenhouse.planAmounts.toMap() }
        val oldOne = context.computeOnClient<Boolean, RuntimeException> { Storage.profile.greenhouse.planOneOfEach }
        val oldPlots = context.computeOnClient<String, RuntimeException> { Storage.profile.greenhouse.plots }
        val oldBlocked = context.computeOnClient<String, RuntimeException> { Storage.profile.greenhouse.blocked }
        val oldPin = context.computeOnClient<PinnedPlot?, RuntimeException> { Storage.profile.greenhouse.pin }
        System.setProperty("nyaddons.devArea", "Garden")
        try {
            context.worldBuilder().create().use { run(context) }
        } finally {
            context.onClient {
                Greenhouse.data = oldData
                Greenhouse.stock = oldStock
                Greenhouse.planner = oldPlanner
                NyAddOns.config.garden.greenhouse.view = oldView
                Storage.profile.greenhouse.analysed = oldAnalysed.toMutableSet()
                Storage.profile.greenhouse.planAmounts = oldAmounts.toMutableMap()
                Storage.profile.greenhouse.planOneOfEach = oldOne
                Storage.profile.greenhouse.plots = oldPlots
                Storage.profile.greenhouse.blocked = oldBlocked
                Storage.profile.greenhouse.pin = oldPin
                OverlayManager.invalidate()
            }
            System.clearProperty("nyaddons.devArea")
            context.setScreen { null }
            context.input.resizeWindow(854, 480)
        }
    }

    private fun run(context: ClientGameTestContext) {
        // Real head skins from a local fixture, so the test does not need the network.
        val fixture = java.util.Properties().apply {
            GreenhouseScreenTest::class.java.getResourceAsStream("/gh_icons_fixture.properties")!!.use { load(it) }
        }.entries.associate { it.key.toString() to it.value.toString() }
        GhIcons.useTextures(fixture)
        context.input.resizeWindow(854, 480)
        context.onClient {
            Greenhouse.data = fakeData
            Greenhouse.stock = fakeStock
            Greenhouse.planner = fakePlanner
            NyAddOns.config.garden.greenhouse.view = GreenhouseView.UNIQUE_MUTATIONS
            Storage.profile.greenhouse.analysed = mutableSetOf()
            Storage.profile.greenhouse.planAmounts = mutableMapOf()
            Storage.profile.greenhouse.planOneOfEach = false
            Storage.profile.greenhouse.plots = ""
            Storage.profile.greenhouse.blocked = ""
            Storage.profile.greenhouse.pin = null
        }
        context.onClient { NyAddOns.openScreen { GreenhouseScreen() } }
        context.waitForScreen(GreenhouseScreen::class.java)
        context.waitTicks(25)
        check(screen(context).view == GreenhouseView.UNIQUE_MUTATIONS)
        context.takeScreenshot("gh-1-unique-854")
        checkHeader(context, "Mutations found 0/38")
        noCosts(context)
        // The open dropdown covers the icons below it.
        click(context) { it.dropdownCenter().let { p -> p[0] to p[1] } }
        context.waitTicks(2)
        context.takeScreenshot("gh-26-dropdown-854")
        click(context) { it.dropdownOptionCenter(0).let { p -> p[0] to p[1] } }
        context.onClient {
            check(screenNow().iconsDrawn >= 8) { "every unique row should draw an icon: ${screenNow().iconsDrawn}" }
            check(GhIcons.has("ashwreath") && GhIcons.has("wheat"))
        }

        // Ticking the cheapest row (Ashwreath).
        click(context) { it.checkboxX() to it.uniqueRowY(0) }
        context.onClient {
            check("ashwreath" in Storage.profile.greenhouse.analysed) { "the checkbox did not tick Ashwreath: ${Storage.profile.greenhouse.analysed}" }
        }
        context.waitTicks(3)
        context.takeScreenshot("gh-2-unique-ticked")
        checkHeader(context, "Mutations found 1/38")
        uniquePlain(context)
        context.takeScreenshot("gh-9-unique-plain-854")

        // Dropdown: Rose Dragon.
        pickView(context, 1)
        context.onClient { check(NyAddOns.config.garden.greenhouse.view == GreenhouseView.ROSE_DRAGON) { "view not remembered" } }
        check(screen(context).view == GreenhouseView.ROSE_DRAGON)
        context.waitTicks(3)
        context.takeScreenshot("gh-3-rose-854")
        noCosts(context)
        roseTree(context)
        plotsPicker(context)
        plannerTab(context, "854", true)

        // Dropdown: All Mutations, select Glasscorn-ish row and plan.
        pickView(context, 2)
        context.onClient { check(NyAddOns.config.garden.greenhouse.view == GreenhouseView.ALL_MUTATIONS) }
        context.waitTicks(3)
        context.takeScreenshot("gh-4-all-854")
        noCosts(context)
        click(context) { it.allRowCenter(3).let { p -> p[0] to p[1] } }
        context.waitTicks(3)
        click(context) { it.planButtonCenter().let { p -> p[0] to p[1] } }
        context.waitTicks(10)
        context.takeScreenshot("gh-5-layout-854")
        context.onClient {
            check(screenNow().hasLayout) { "Plan layout did not produce a layout" }
            check(screenNow().iconsDrawn >= 12) { "the layout grid and the list should draw icons: ${screenNow().iconsDrawn}" }
        }

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
        click(context) { it.treeArrowCenter(1).let { p -> p[0] to p[1] } }
        click(context) { it.treeTextCenter(0).let { p -> p[0] to p[1] } }
        context.waitTicks(15)
        context.takeScreenshot("gh-13-rose-panel-1280")
        rosePin(context, "1280", true)
        click(context) { it.plotsButtonCenter().let { p -> p[0] to p[1] } }
        context.waitTicks(3)
        context.takeScreenshot("gh-14-plots-1280")
        click(context) { it.donePlotsCenter().let { p -> p[0] to p[1] } }
        plannerTab(context, "1280", false)

        // The size people play in: a 1708x960 window at GUI scale 2 is an 854x480 GUI, which GUI scale 1 gives here.
        context.input.resizeWindow(854, 480)
        context.onClient { it.options.guiScale().set(1); it.resizeGui() }
        context.waitTicks(10)
        context.takeScreenshot("gh-21-unique-gui854")
        checkHeader(context, "Mutations found 1/38")
        pickView(context, 1)
        click(context) { it.treeTextCenter(0).let { p -> p[0] to p[1] } }
        context.waitTicks(15)
        context.takeScreenshot("gh-22-rose-panel-gui854")
        rosePin(context, "gui854", true)
        pickView(context, 2)
        click(context) { it.allRowCenter(3).let { p -> p[0] to p[1] } }
        click(context) { it.planButtonCenter().let { p -> p[0] to p[1] } }
        context.waitTicks(10)
        context.takeScreenshot("gh-23-all-gui854")
        click(context) { it.plotsButtonCenter().let { p -> p[0] to p[1] } }
        context.waitTicks(3)
        context.takeScreenshot("gh-24-plots-gui854")
        click(context) { it.donePlotsCenter().let { p -> p[0] to p[1] } }
        plannerTab(context, "gui854", false)
        context.onClient { it.options.guiScale().set(0); it.resizeGui() }
        context.setScreen { null }
        context.input.resizeWindow(854, 480)
    }

    /** No coin or copper text anywhere in the current view. */
    private fun noCosts(context: ClientGameTestContext) {
        context.onClient {
            val text = screenNow().allVisibleText().lowercase()
            check(!text.contains("coin") && !text.contains("copper")) { "cost text is still shown: $text" }
        }
    }

    private fun planAmount(id: String) = Storage.profile.greenhouse.planAmounts[id] ?: 0

    /** Unlocks [squares] squares (the middle first) through the Plots picker, or the default block when null. */
    private fun setSquares(context: ClientGameTestContext, squares: Int?) {
        click(context) { it.plotsButtonCenter().let { p -> p[0] to p[1] } }
        if (squares == null) click(context) { it.defaultPlotsCenter().let { p -> p[0] to p[1] } }
        else {
            click(context) { it.fillBoxCenter().let { p -> p[0] to p[1] } }
            typeDigits(context, squares.toString())
            click(context) { it.fillButtonCenter().let { p -> p[0] to p[1] } }
        }
        click(context) { it.donePlotsCenter().let { p -> p[0] to p[1] } }
    }

    private fun setBox(context: ClientGameTestContext, row: Int, digits: String) {
        click(context) { it.plannerBoxCenter(row).let { p -> p[0] to p[1] } }
        typeDigits(context, digits)
        pressKey(context, 256)
    }

    /** Waits until the Planner has nothing running or pending (Max, or the layout of the typed amounts). */
    private fun waitPlanner(context: ClientGameTestContext) {
        var waited = 0
        while (context.computeOnClient<Boolean, RuntimeException> { screenNow().plannerBusy } && waited < 400) {
            context.waitTicks(2)
            waited += 2
        }
        context.waitTicks(3)
        check(!context.computeOnClient<Boolean, RuntimeException> { screenNow().plannerBusy }) { "the Planner never finished" }
    }

    private fun clickMax(context: ClientGameTestContext) {
        click(context) { it.maxButtonCenter().let { p -> p[0] to p[1] } }
        waitPlanner(context)
    }

    /** The plain Unique Mutations list: names, no amount boxes, no Max, the totals line, ticking still works. */
    private fun uniquePlain(context: ClientGameTestContext) {
        context.onClient {
            val s = screenNow()
            check(s.uniqueText()[0] == "Ashwreath") { "plain rows are just the name (and a note): ${s.uniqueText()}" }
            check(s.uniqueText().none { row -> row.any { it.isDigit() } }) { "no amounts in the list: ${s.uniqueText()}" }
            val totals = s.headerText()[2]
            check(totals.startsWith("The rest need") && totals.contains("short") && totals.contains("Ashwreath")) { "totals line over the unanalysed ones: $totals" }
            val text = s.allVisibleText().lowercase()
            check(!text.contains("set all")) { "the set-all box is gone from the list: $text" }
        }
        // Old save files (with the removed per-row amounts) still load with defaults.
        context.onClient {
            val old = com.google.gson.Gson().fromJson("{\"analysed\":[\"a\"],\"amounts\":{\"x\":3}}", GreenhouseProfile::class.java)
            check(old.planAmounts.isEmpty() && !old.planOneOfEach && old.plots.isEmpty() && old.blocked.isEmpty() && old.pin == null && "a" in old.analysed) { "old profile data did not load with defaults" }
            check(GreenhousePlots.parse("").contentEquals(GreenhousePlots.default()) && GreenhousePlots.count(GreenhousePlots.default()) == 12)
            // The default is the centre blob: rows 3-6 with 2, 4, 4, 2 squares, symmetric about the middle of the 10x10.
            val d = GreenhousePlots.default()
            for (r in 0 until 10) for (c in 0 until 10) {
                check(d[r * 10 + c] == d[(9 - r) * 10 + c] && d[r * 10 + c] == d[r * 10 + 9 - c]) { "the default is not centre-symmetric at $r,$c" }
                val want = r in 3..6 && (if (r == 3 || r == 6) c in 4..5 else c in 3..6)
                check(d[r * 10 + c] == want) { "default shape differs at $r,$c" }
            }
            check(GreenhousePlots.parseBlocked("").none { it } && GreenhousePlots.parseBlocked("1").none { it })
        }
    }

    /**
     * The Planner tab: amount boxes, the shared layout, per-row max, the main Max with One of each on and off, amounts that do not
     * fit, the crop list and persistence. [full] runs the checks, otherwise only the screenshots (other window sizes).
     */
    private fun plannerTab(context: ClientGameTestContext, tag: String, full: Boolean) {
        pickView(context, 3)
        context.onClient { check(NyAddOns.config.garden.greenhouse.view == GreenhouseView.PLANNER) { "view not remembered" } }
        setSquares(context, 40)
        click(context) { it.clearCenter().let { p -> p[0] to p[1] } }
        waitPlanner(context)
        context.onClient {
            val s = screenNow()
            check(s.plannerRowText().size == 8 && s.plannerRowText().all { it.endsWith(" 0") || it.endsWith(" 0 found") }) { "planner rows start at 0: ${s.plannerRowText()}" }
            check(s.plannerFits()) { "the Planner controls do not fit this window" }
            check(s.headerFits()) { "header does not fit" }
        }
        context.takeScreenshot("gh-30-planner-empty-$tag")
        if (full) {
            // The - and + buttons: step by one, never below 0 or above 9999.
            click(context) { it.plannerPlusCenter(3).let { p -> p[0] to p[1] } }
            click(context) { it.plannerPlusCenter(3).let { p -> p[0] to p[1] } }
            check(planAmount("chocoberry") == 2) { "two + presses: ${planAmount("chocoberry")}" }
            click(context) { it.plannerMinusCenter(3).let { p -> p[0] to p[1] } }
            check(planAmount("chocoberry") == 1) { "one - press: ${planAmount("chocoberry")}" }
            click(context) { it.plannerMinusCenter(3).let { p -> p[0] to p[1] } }
            click(context) { it.plannerMinusCenter(3).let { p -> p[0] to p[1] } }
            check(planAmount("chocoberry") == 0 && "chocoberry" !in Storage.profile.greenhouse.planAmounts) { "- stops at 0: ${Storage.profile.greenhouse.planAmounts}" }
            setBox(context, 3, "9998")
            click(context) { it.plannerPlusCenter(3).let { p -> p[0] to p[1] } }
            click(context) { it.plannerPlusCenter(3).let { p -> p[0] to p[1] } }
            check(planAmount("chocoberry") == 9999) { "+ stops at 9999: ${planAmount("chocoberry")}" }
            click(context) { it.clearCenter().let { p -> p[0] to p[1] } }
            waitPlanner(context)
        }

        // Typed amounts: Ashwreath (row 1) and Cheesebite (row 2).
        setBox(context, 1, "3")
        setBox(context, 2, "2")
        waitPlanner(context)
        context.takeScreenshot("gh-31-planner-typed-$tag")
        if (full) context.onClient {
            val s = screenNow()
            check(planAmount("ashwreath") == 3 && planAmount("cheesebite") == 2) { "typed amounts: ${Storage.profile.greenhouse.planAmounts}" }
            check(s.plannerPlaced == mapOf("ashwreath" to 3, "cheesebite" to 2) && s.plannerRounds == 0 && s.plannerUnplaced.isEmpty()) { "40 squares take 3+2: ${s.plannerPlaced} ${s.plannerUnplaced}" }
            check(s.plannerBlockIds.isNotEmpty() && s.iconsDrawn > 0) { "the layout draws blocks and icons" }
            val text = s.plannerText.replace('\n', ' ')
            check(text.contains("Planned: 5 mutations (2 kinds) on 40 squares")) { "summary: $text" }
            // Cheesebite: 4 Wheat + 1 Moonflower each, Ashwreath: 2 Nether Wart each. Wheat is held 640, Nether Wart 12.
            check(text.contains("Crops needed") && text.contains("Wheat 640/8") && text.contains("Nether Wart 12/6") && text.contains("Moonflower ?/2")) { "crop list: $text" }
            check(!text.lowercase().contains("coin") && !text.lowercase().contains("copper")) { "cost text: $text" }
            check(s.plannerRowText()[1] == "Ashwreath 3 found" && s.plannerRowText()[2] == "Cheesebite 2") { "rows: ${s.plannerRowText()}" }
        }

        // One of each, Max over the selected rows only.
        click(context) { it.oneOfEachCenter().let { p -> p[0] to p[1] } }
        context.onClient { check(Storage.profile.greenhouse.planOneOfEach) { "One of each did not switch on" } }
        clickMax(context)
        if (full) context.onClient {
            val s = screenNow()
            val amounts = Storage.profile.greenhouse.planAmounts
            check(amounts == mapOf("ashwreath" to 1, "cheesebite" to 1)) { "Max over the 2 selected rows with one of each: $amounts" }
            check(s.plannerPlaced == amounts && s.plannerText.replace('\n', ' ').contains("Planned: 2 mutations (2 kinds)")) { "plan after Max: ${s.plannerPlaced} ${s.plannerText}" }
        }
        click(context) { it.oneOfEachCenter().let { p -> p[0] to p[1] } }
        context.onClient { check(!Storage.profile.greenhouse.planOneOfEach) { "One of each did not switch off" } }
        clickMax(context)
        if (full) context.onClient {
            val amounts = Storage.profile.greenhouse.planAmounts
            check(setOf("ashwreath", "cheesebite").containsAll(amounts.keys) && amounts.values.sum() > 2) { "Max over the 2 selected rows without the cap: $amounts" }
            check(screenNow().plannerPlaced == amounts) { "the Max layout is the plan: ${screenNow().plannerPlaced}" }
        }
        context.takeScreenshot("gh-32-planner-max-$tag")

        // Main Max with nothing selected uses every row.
        click(context) { it.clearCenter().let { p -> p[0] to p[1] } }
        click(context) { it.oneOfEachCenter().let { p -> p[0] to p[1] } }
        clickMax(context)
        if (full) context.onClient {
            val amounts = Storage.profile.greenhouse.planAmounts
            check(amounts.size >= 3 && amounts.values.all { it == 1 }) { "Max over all rows, one of each: $amounts" }
            check(screenNow().plannerPlaced == amounts)
        }
        context.takeScreenshot("gh-33-planner-max-one-$tag")
        val kinds = context.computeOnClient<Int, RuntimeException> { Storage.profile.greenhouse.planAmounts.size }
        click(context) { it.clearCenter().let { p -> p[0] to p[1] } }
        click(context) { it.oneOfEachCenter().let { p -> p[0] to p[1] } }
        clickMax(context)
        if (full) context.onClient {
            val amounts = Storage.profile.greenhouse.planAmounts
            check(amounts.values.sum() >= kinds && amounts.values.any { it > 1 }) { "Max over all rows without the cap repeats kinds: $amounts" }
        }

        // Per-row max leaves the other rows alone.
        click(context) { it.clearCenter().let { p -> p[0] to p[1] } }
        setBox(context, 2, "1")
        click(context) { it.plannerMaxCenter(1).let { p -> p[0] to p[1] } }
        waitPlanner(context)
        if (full) context.onClient {
            check(planAmount("ashwreath") > 1 && planAmount("cheesebite") == 1 && Storage.profile.greenhouse.planAmounts.size == 2) { "row max: ${Storage.profile.greenhouse.planAmounts}" }
        }

        // Per-row max counts the other rows: 3 Cheesebite typed, max on Ashwreath.
        click(context) { it.clearCenter().let { p -> p[0] to p[1] } }
        click(context) { it.plannerMaxCenter(1).let { p -> p[0] to p[1] } }
        waitPlanner(context)
        val alone = context.computeOnClient<Int, RuntimeException> { planAmount("ashwreath") }
        context.takeScreenshot("gh-35-planner-max-alone-$tag")
        click(context) { it.clearCenter().let { p -> p[0] to p[1] } }
        setBox(context, 2, "3")
        setBox(context, 1, "50")
        waitPlanner(context)
        context.takeScreenshot("gh-36-planner-before-alongside-$tag")
        click(context) { it.plannerMaxCenter(1).let { p -> p[0] to p[1] } }
        waitPlanner(context)
        context.takeScreenshot("gh-37-planner-max-alongside-$tag")
        if (full) context.onClient {
            val amounts = Storage.profile.greenhouse.planAmounts
            val s = screenNow()
            check(amounts["cheesebite"] == 3 && amounts.size == 2) { "the other rows stay as typed: $amounts" }
            val mine = amounts["ashwreath"] ?: 0
            check(mine in 1..alone) { "Ashwreath alongside 3 Cheesebite is between 1 and the solo max $alone: $mine" }
            check(s.plannerPlaced == amounts && s.plannerUnplaced.isEmpty() && s.plannerRounds == 0) { "the plan holds the others and the row: ${s.plannerPlaced} ${s.plannerUnplaced}" }
            NyAddOns.logger.info("[Greenhouse] test row max: Ashwreath $mine alongside 3 Cheesebite, $alone alone")
        }
        click(context) { it.clearCenter().let { p -> p[0] to p[1] } }

        // Amounts that do not fit: place what fits, explain the rest, estimate the rounds. Default 12 squares.
        setSquares(context, null)
        click(context) { it.clearCenter().let { p -> p[0] to p[1] } }
        setBox(context, 1, "9")
        setBox(context, 2, "9")
        setBox(context, 4, "5")
        setBox(context, 7, "3")
        waitPlanner(context)
        context.takeScreenshot("gh-34-planner-nofit-$tag")
        if (full) context.onClient {
            val s = screenNow()
            check(s.plannerRounds >= 2 && s.plannerUnplaced.isNotEmpty()) { "rounds ${s.plannerRounds}, unplaced ${s.plannerUnplaced}" }
            check(s.plannerPlaced.values.sum() in 1 until 26) { "12 squares cannot take 26 blocks: ${s.plannerPlaced}" }
            val text = s.plannerText.replace('\n', ' ')
            check(text.contains("rounds") && text.contains("Not placed") && text.contains("Planned:")) { "side text: $text" }
            val mask = GreenhousePlots.current()
            check(mask.count { it } == 12)
            NyAddOns.logger.info("[Greenhouse] test Planner not fit: ${s.plannerPlaced} rounds=${s.plannerRounds} text=${text.replace('\n', '|')}")
        }
        if (full) {
            // Max on a row while the other rows already do not fit: the row gets 0 and the panel says so.
            click(context) { it.plannerMaxCenter(1).let { p -> p[0] to p[1] } }
            waitPlanner(context)
            context.onClient {
                check(planAmount("ashwreath") == 0 && planAmount("cheesebite") == 9 && planAmount("timestalk") == 3) { "max with crowded rows: ${Storage.profile.greenhouse.planAmounts}" }
                val note = screenNow().plannerText.replace('\n', ' ')
                check(note.contains("other rows already do not fit")) { "side text: $note" }
            }
            setBox(context, 1, "9")
            waitPlanner(context)
        }

        if (full) {
            // Persistence: closing and reopening the window keeps the amounts and the switch, and plans them again.
            click(context) { it.oneOfEachCenter().let { p -> p[0] to p[1] } }
            val saved = context.computeOnClient<Map<String, Int>, RuntimeException> { Storage.profile.greenhouse.planAmounts.toMap() }
            check(saved == mapOf("ashwreath" to 9, "cheesebite" to 9, "devourer" to 5, "timestalk" to 3)) { "saved amounts: $saved" }
            context.setScreen { null }
            context.onClient { NyAddOns.openScreen { GreenhouseScreen() } }
            context.waitForScreen(GreenhouseScreen::class.java)
            waitPlanner(context)
            context.onClient {
                val s = screenNow()
                check(s.view == GreenhouseView.PLANNER && s.plannerRowText()[1] == "Ashwreath 9 found" && s.plannerRowText()[7] == "Timestalk 3") { "reopened rows: ${s.plannerRowText()}" }
                check(Storage.profile.greenhouse.planOneOfEach && s.plannerPlaced.isNotEmpty()) { "reopened plan: ${s.plannerPlaced}" }
            }
            click(context) { it.oneOfEachCenter().let { p -> p[0] to p[1] } }
        }
        blockedAndPin(context, tag, full)
        click(context) { it.clearCenter().let { p -> p[0] to p[1] } }
        context.onClient { Storage.profile.greenhouse.planOneOfEach = false }
        pickView(context, 0)
    }

    private fun overlayNow() = OverlayManager.overlays.first { it.label == "Pinned Plot" }.also { OverlayManager.invalidate() }.current()

    /** Closes the window, takes [name] with the pinned plot on the HUD and opens the window again. */
    private fun hudShot(context: ClientGameTestContext, name: String) {
        context.setScreen { null }
        context.onClient { OverlayManager.invalidate() }
        context.waitTicks(4)
        context.takeScreenshot(name)
        context.onClient { NyAddOns.openScreen { GreenhouseScreen() } }
        context.waitForScreen(GreenhouseScreen::class.java)
        context.waitTicks(3)
    }

    /** Blocked squares in the Plots picker, then pinning the Planner's layout to the screen. 40 squares, 3 Ashwreath and 2 Cheesebite. */
    private fun blockedAndPin(context: ClientGameTestContext, tag: String, full: Boolean) {
        click(context) { it.clearCenter().let { p -> p[0] to p[1] } }
        setSquares(context, 40)
        setBox(context, 1, "3")
        setBox(context, 2, "2")
        waitPlanner(context)
        if (full) context.onClient {
            check(screenNow().plannerText.replace('\n', ' ').contains("on 40 squares")) { "before blocking: ${screenNow().plannerText}" }
            check(overlayNow() == null) { "nothing pinned yet, nothing on the HUD" }
        }

        // Block the four middle squares: they stay unlocked but no layout may use them.
        click(context) { it.plotsButtonCenter().let { p -> p[0] to p[1] } }
        click(context) { it.blockModeCenter().let { p -> p[0] to p[1] } }
        click(context) { it.plotCellCenter(4, 4).let { p -> p[0] to p[1] } }
        if (full) context.onClient {
            check(screenNow().blockModeOn && screenNow().blockedSquares == 1 && Storage.profile.greenhouse.blocked[44] == '1') { "block one: ${screenNow().blockedSquares}" }
        }
        click(context) { it.plotCellCenter(4, 4).let { p -> p[0] to p[1] } }
        if (full) context.onClient { check(screenNow().blockedSquares == 0 && Storage.profile.greenhouse.blocked.isEmpty()) { "click again unblocks: '${Storage.profile.greenhouse.blocked}'" } }
        for ((r, c) in listOf(4 to 4, 4 to 5, 5 to 4, 5 to 5, 0 to 0)) click(context) { it.plotCellCenter(r, c).let { p -> p[0] to p[1] } }
        context.waitTicks(2)
        context.takeScreenshot("gh-40-plots-blocked-$tag")
        if (full) context.onClient {
            val s = screenNow()
            check(s.blockedSquares == 4 && s.unlockedSquares == 40) { "4 blocked of 40 unlocked (a locked square cannot be blocked): ${s.blockedSquares}/${s.unlockedSquares}" }
            check(Storage.profile.greenhouse.plots.count { it == '1' } == 40 && Storage.profile.greenhouse.blocked.count { it == '1' } == 4)
        }
        click(context) { it.blockModeCenter().let { p -> p[0] to p[1] } }
        click(context) { it.donePlotsCenter().let { p -> p[0] to p[1] } }
        waitPlanner(context)
        context.takeScreenshot("gh-41-planner-blocked-$tag")
        if (full) context.onClient {
            val s = screenNow()
            val layout = s.plannerLayout ?: throw AssertionError("no layout with 36 usable squares")
            for (i in listOf(44, 45, 54, 55)) check(layout.cells[i / 10][i % 10] == null) { "blocked square $i is used" }
            check(s.plannerText.replace('\n', ' ').contains("on 36 squares") && s.plannerPlaced == mapOf("ashwreath" to 3, "cheesebite" to 2)) { "blocked plan: ${s.plannerText} ${s.plannerPlaced}" }
        }

        // Pin to screen.
        context.onClient {
            if (full) check(!screenNow().plannerIsPinned)
        }
        click(context) { it.pinPlannerCenter().let { p -> p[0] to p[1] } }
        if (full) context.onClient {
            val pin = Storage.profile.greenhouse.pin ?: throw AssertionError("Pin to screen saved nothing")
            check(pin.title == "Planned layout" && pin.summary.replace(Regex("§."), "").contains("Planned: 5 mutations (2 kinds) on 36 squares") && "ashwreath" in pin.palette) { "pin: ${pin.title} / ${pin.summary} / ${pin.palette}" }
            check(listOf(44, 45, 54, 55).all { pin.idAt(it) == null } && pin.cells.length == 100) { "pinned cells ${pin.cells}" }
            check(screenNow().plannerIsPinned && overlayNow() != null && overlayNow()!!.let { it.width > 40 && it.height > 40 }) { "the pin shows on the HUD" }
            // Saved with the profile: survives a restart.
            val again = com.google.gson.Gson().fromJson(com.google.gson.Gson().toJson(Storage.profile.greenhouse), GreenhouseProfile::class.java)
            check(again.pin!!.sameAs(pin) && again.blocked == Storage.profile.greenhouse.blocked) { "pin and blocked squares did not survive saving" }
        }
        hudShot(context, "gh-42-pinned-hud-$tag")
        context.waitTicks(25)
        waitPlanner(context)
        if (full) {
            context.onClient { check(screenNow().plannerIsPinned && overlayNow() != null) { "still pinned after reopening" } }
            // Options: off, or only on the Garden.
            val config = NyAddOns.config.garden.greenhouse
            context.onClient { config.pinnedEnabled = false }
            check(context.computeOnClient<Boolean, RuntimeException> { overlayNow() == null }) { "turned off in the config" }
            context.onClient { config.pinnedEnabled = true; System.setProperty("nyaddons.devArea", "Hub") }
            context.waitTicks(25)
            check(context.computeOnClient<Boolean, RuntimeException> { overlayNow() == null }) { "Garden only: nothing on the Hub" }
            context.onClient { config.pinnedArea = dev.nytrix.nyaddons.config.PinArea.ALL_ISLANDS }
            check(context.computeOnClient<Boolean, RuntimeException> { overlayNow() != null }) { "All islands: shown on the Hub" }
            context.onClient { config.pinnedArea = dev.nytrix.nyaddons.config.PinArea.GARDEN; System.setProperty("nyaddons.devArea", "Garden") }
            context.waitTicks(25)
            check(context.computeOnClient<Boolean, RuntimeException> { overlayNow() != null }) { "shown on the Garden" }
        }
        click(context) { it.pinPlannerCenter().let { p -> p[0] to p[1] } }
        if (full) context.onClient { check(Storage.profile.greenhouse.pin == null && !screenNow().plannerIsPinned && overlayNow() == null) { "Unpin removes the pin" } }

        // Unblock everything again.
        click(context) { it.plotsButtonCenter().let { p -> p[0] to p[1] } }
        click(context) { it.unblockAllCenter().let { p -> p[0] to p[1] } }
        click(context) { it.donePlotsCenter().let { p -> p[0] to p[1] } }
        if (full) context.onClient { check(screenNow().blockedSquares == 0 && Storage.profile.greenhouse.blocked.isEmpty()) { "Unblock all" } }
    }

    /** Pins what the Rose Dragon panel shows. [hud] also looks at the HUD (which closes the window and loses the tree's selection). */
    private fun rosePin(context: ClientGameTestContext, tag: String, hud: Boolean) {
        context.onClient { check(!screenNow().roseIsPinned) }
        click(context) { it.pinRoseCenter().let { p -> p[0] to p[1] } }
        context.onClient {
            val pin = Storage.profile.greenhouse.pin ?: throw AssertionError("Pin to screen on the Rose Dragon panel saved nothing")
            check(pin.title.startsWith("Glasscorn") || pin.title.startsWith("Ashwreath")) { "pin title ${pin.title}" }
            check(pin.summary.contains("round") && pin.palette.isNotEmpty() && screenNow().roseIsPinned) { "pin: ${pin.summary}" }
            check(overlayNow() != null)
        }
        if (hud) {
            hudShot(context, "gh-43-pinned-hud-rose-$tag")
            context.onClient { GreenhousePin.unpin() }
        } else {
            click(context) { it.pinRoseCenter().let { p -> p[0] to p[1] } }
            context.onClient { check(Storage.profile.greenhouse.pin == null && overlayNow() == null) { "Unpin on the Rose Dragon panel" } }
        }
    }

    private fun checkHeader(context: ClientGameTestContext, found: String) {
        context.onClient {
            val s = screenNow()
            val text = s.headerText()
            check(text[0].startsWith("Rose Dragon ~") && text[0].endsWith("%")) { "Rose Dragon line: ${text[0]}" }
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

    /** The Rose Dragon tree: expanding, counts, picking an item, the side panel and refreshed sack numbers. */
    private fun roseTree(context: ClientGameTestContext) {
        fun tree() = context.computeOnClient<List<String>, RuntimeException> { screenNow().treeText() }
        val start = tree()
        check(start.size == 5 && start[0].startsWith("> Glasscorn 1/1") && start.none { it.contains("Condensed") }) { "collapsed tree (no Condensed Helianthus row): $start" }
        context.onClient {
            check(screenNow().treeIcons() == listOf("glasscorn", "devourer", "all_in_aloe", "phantomleaf", "timestalk")) { "tree icons ${screenNow().treeIcons()}" }
            check(screenNow().iconsDrawn >= 5) { "icons in the tree: ${screenNow().iconsDrawn}" }
        }
        click(context) { it.treeArrowCenter(1).let { p -> p[0] to p[1] } }
        val devourer = tree()
        check(devourer.size == 7 && devourer[1].startsWith("v Devourer") && devourer[2] == "  x2 Wheat 640/2" && devourer[3] == "  > x2 Ashwreath 0/2") { "Devourer expanded: $devourer" }
        click(context) { it.treeArrowCenter(3).let { p -> p[0] to p[1] } }
        val deep = tree()
        check(deep.size == 8 && deep[4] == "    x4 Nether Wart 12/4") { "Ashwreath expanded: $deep" }
        context.onClient { check(screenNow().treeIcons().contains("nether_wart")) }
        context.takeScreenshot("gh-11-rose-tree-854")
        click(context) { it.treeArrowCenter(1).let { p -> p[0] to p[1] } }
        check(tree().size == 5) { "collapsing Devourer should hide its ingredients: ${tree()}" }
        click(context) { it.treeArrowCenter(1).let { p -> p[0] to p[1] } }

        // Picking the two Ashwreath: needs 2, the fake layout makes one per round.
        click(context) { it.treeTextCenter(3).let { p -> p[0] to p[1] } }
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
        context.waitTicks(2)
        context.onClient { check(screenNow().iconsDrawn >= 8) { "the plot grid and legend should draw icons: ${screenNow().iconsDrawn}" } }
        context.takeScreenshot("gh-12-rose-panel-854")
        rosePin(context, "854", false)
        click(context) { it.treeTextCenter(0).let { p -> p[0] to p[1] } }
        context.waitTicks(15)
        context.onClient {
            val s = screenNow()
            check(s.panelItem == "Glasscorn x1" && s.panelRoundCount == 1 && s.panelHasLayout) { "Glasscorn panel: ${s.panelItem} ${s.panelRoundCount}" }
        }
        click(context) { it.treeTextCenter(2).let { p -> p[0] to p[1] } }
        context.waitTicks(3)
        context.onClient {
            val s = screenNow()
            check(!s.panelHasLayout && s.panelMessage.contains("base crop")) { "crop panel: '${s.panelMessage}'" }
        }

        // Fresh sack numbers show without reopening the window.
        context.onClient { fakeStock.items["wheat"] = 7; fakeStock.updated = System.currentTimeMillis() }
        context.waitTicks(4)
        val fresh = tree()
        check(fresh[2] == "  x2 Wheat 7/2") { "tree did not pick up the new Wheat count: $fresh" }
        context.onClient { fakeStock.items["wheat"] = 640; fakeStock.updated = System.currentTimeMillis() - 5 * 60_000 }
        context.waitTicks(4)

        click(context) { it.treeTextCenter(0).let { p -> p[0] to p[1] } }
        click(context) { it.treeTextCenter(0).let { p -> p[0] to p[1] } }
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
        click(context) { it.treeTextCenter(0).let { p -> p[0] to p[1] } }
        context.waitTicks(15)
        context.onClient {
            val s = screenNow()
            check(!s.panelHasLayout && s.panelMessage.contains("5 more squares")) { "panel with 3 squares: '${s.panelMessage}'" }
        }
        context.takeScreenshot("gh-18-rose-nofit-854")
        click(context) { it.treeTextCenter(0).let { p -> p[0] to p[1] } }
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
