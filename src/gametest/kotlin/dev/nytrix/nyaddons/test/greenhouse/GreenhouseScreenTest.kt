package dev.nytrix.nyaddons.test.greenhouse

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.config.GreenhouseView
import dev.nytrix.nyaddons.config.PlannerStyle
import dev.nytrix.nyaddons.core.Storage
import dev.nytrix.nyaddons.features.greenhouse.GhCrop
import dev.nytrix.nyaddons.features.greenhouse.GhData
import dev.nytrix.nyaddons.features.greenhouse.GhLayout
import dev.nytrix.nyaddons.features.greenhouse.GhMutation
import dev.nytrix.nyaddons.features.greenhouse.GhPlanner
import dev.nytrix.nyaddons.features.greenhouse.SkyGoal
import dev.nytrix.nyaddons.features.greenhouse.SkyShards
import dev.nytrix.nyaddons.features.greenhouse.SkySolution
import dev.nytrix.nyaddons.features.greenhouse.SkyShardsException
import dev.nytrix.nyaddons.features.greenhouse.GhRequirement
import dev.nytrix.nyaddons.features.greenhouse.GreenhousePlots
import dev.nytrix.nyaddons.core.GreenhouseProfile
import dev.nytrix.nyaddons.core.OverlayOrigin
import dev.nytrix.nyaddons.features.greenhouse.GreenhouseOverlay
import dev.nytrix.nyaddons.gui.OverlayManager
import net.minecraft.client.Minecraft
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
        GhMutation(id, name, rarity, 1, "soul_sand", req.map { GhRequirement(it.first, it.second) }, 3, id.length % 2 == 0, coins, copper)

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

    /**
     * Stands in for SkyShards so the Planner tests need no network: a block of a mutation costs 3 squares, and each kind needs its
     * ingredients once (the sum of its counts). Exact counts that do not fit give null (SkyShards: no solution); "maximize" goals
     * take blocks round-robin until the squares run out.
     */
    private fun fakeSolve(goals: List<SkyGoal>, unlocked: BooleanArray?): SkySolution? {
        val free = (0 until 100).filter { unlocked == null || unlocked[it] }
        var left = free.size
        val counts = LinkedHashMap<String, Int>()
        val paid = HashSet<String>()
        fun take(id: String): Boolean {
            val cost = 3 + if (id in paid) 0 else mutations.first { it.id == id }.requirements.sumOf { it.count }
            if (cost > left) return false
            left -= cost
            paid += id
            counts.merge(id, 1, Int::plus)
            return true
        }
        for (g in goals) repeat(g.count ?: 0) { if (!take(g.id)) return null }
        val maximised = goals.filter { it.count == null }.map { it.id }
        do {
            var took = false
            for (id in maximised) if (take(id)) took = true
        } while (took)
        val cells = Array(10) { arrayOfNulls<String>(10) }
        val order = free.iterator()
        for ((id, n) in counts) {
            repeat(n) {
                val first = order.next()
                cells[first / 10][first % 10] = id
                repeat(2) { val c = order.next(); cells[c / 10][c % 10] = "wheat" }
            }
            for (r in mutations.first { it.id == id }.requirements) repeat(r.count) { val c = order.next(); cells[c / 10][c % 10] = r.crop }
        }
        return SkySolution(cells, counts, free.size - left)
    }

    override fun runTest(context: ClientGameTestContext) {
        val oldData = Greenhouse.data
        val oldStock = Greenhouse.stock
        val oldPlanner = Greenhouse.planner
        val oldView = NyAddOns.config.garden.greenhouse.view
        val oldStyle = NyAddOns.config.garden.greenhouse.plannerStyle
        val oldAnalysed = context.computeOnClient<Set<String>, RuntimeException> { Storage.profile.greenhouse.analysed.toSet() }
        val oldAmounts = context.computeOnClient<Map<String, Int>, RuntimeException> { Storage.profile.greenhouse.planAmounts.toMap() }
        val oldOne = context.computeOnClient<Boolean, RuntimeException> { Storage.profile.greenhouse.planOneOfEach }
        val oldPlots = context.computeOnClient<String, RuntimeException> { Storage.profile.greenhouse.plots }
        val oldBlocked = context.computeOnClient<String, RuntimeException> { Storage.profile.greenhouse.blocked }
        val oldOrigin = context.computeOnClient<OverlayOrigin?, RuntimeException> { Storage.profile.greenhouse.overlayOrigin }
        val oldOverlay = NyAddOns.config.garden.greenhouse.overlayEnabled
        System.setProperty("nyaddons.devArea", "Garden")
        SkyShards.testSolver = ::fakeSolve
        try {
            context.worldBuilder().create().use { run(context) }
        } finally {
            SkyShards.testSolver = null
            context.onClient {
                Greenhouse.data = oldData
                Greenhouse.stock = oldStock
                Greenhouse.planner = oldPlanner
                NyAddOns.config.garden.greenhouse.view = oldView
                NyAddOns.config.garden.greenhouse.plannerStyle = oldStyle
                Storage.profile.greenhouse.analysed = oldAnalysed.toMutableSet()
                Storage.profile.greenhouse.planAmounts = oldAmounts.toMutableMap()
                Storage.profile.greenhouse.planOneOfEach = oldOne
                Storage.profile.greenhouse.plots = oldPlots
                Storage.profile.greenhouse.blocked = oldBlocked
                Storage.profile.greenhouse.overlayOrigin = oldOrigin
                NyAddOns.config.garden.greenhouse.overlayEnabled = oldOverlay
                GreenhouseOverlay.show(null)
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
            Storage.profile.greenhouse.overlayOrigin = null
            GreenhouseOverlay.show(null)
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
        roseTreePills(context)
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
        roseOverlay(context)
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
        roseOverlay(context)
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
        compactPlanner(context)
        maxTimeout(context)
    }

    private fun field(target: Any, name: String): java.lang.reflect.Field {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                return c.getDeclaredField(name).also { it.isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        error("no field $name")
    }

    private fun bits(mask: BooleanArray) = mask.joinToString("") { if (it) "1" else "0" }

    /** Replaces the SkyShards disk cache with [entries] (key, solution, time) and makes SkyShards read it again. A null file text deletes it. */
    private fun resetCache(text: String?) {
        val file = java.io.File(NyAddOns.directory, "skyshards-cache.json")
        if (text == null) file.delete() else file.writeText(text)
        field(SkyShards, "diskLoaded").setBoolean(SkyShards, false)
        (field(SkyShards, "cache").get(SkyShards) as MutableMap<*, *>).clear()
    }

    private fun cacheJson(entries: List<Triple<String, SkySolution?, Long>>): String {
        val list = com.google.gson.JsonArray()
        for ((key, solution, at) in entries) list.add(com.google.gson.JsonObject().apply {
            addProperty("key", key)
            addProperty("at", at)
            if (solution != null) add("solution", solution.javaClass.declaredMethods.first { it.name.startsWith("toJson") }.also { it.isAccessible = true }.invoke(solution) as com.google.gson.JsonObject)
        })
        return com.google.gson.JsonObject().apply { addProperty("version", 1); add("entries", list) }.toString()
    }

    /**
     * Max that cannot get an answer: a timeout with a cached layout for the same unlocked squares shows that layout (greyed, "Saved
     * plan from <time>", Retry, red line), one without a cache entry keeps what was there, busy (429/5xx) shows the busy line and
     * never the cache. The solver is faked, so this covers the screen and [SkyShards.lastCached], not the HTTP calls.
     */
    private fun maxTimeout(context: ClientGameTestContext) {
        val cacheFile = java.io.File(NyAddOns.directory, "skyshards-cache.json")
        val backup = if (cacheFile.isFile) cacheFile.readText() else null
        val timeout: (List<SkyGoal>, BooleanArray?) -> SkySolution? = { _, _ -> throw SkyShardsException("no answer within 30 seconds", timedOut = true) }
        val busy: (List<SkyGoal>, BooleanArray?) -> SkySolution? = { _, _ -> throw SkyShardsException("HTTP 429", busy = true) }
        fun text() = context.computeOnClient<String, RuntimeException> { screenNow().plannerText.replace(Regex("\\s+"), " ") }
        fun placed() = context.computeOnClient<Map<String, Int>, RuntimeException> { screenNow().plannerPlaced.filterValues { it > 0 } }
        fun failed() = context.computeOnClient<Boolean, RuntimeException> { field(screenNow(), "skyError").get(screenNow()) != null }
        fun clickRetry() = click(context) {
            val r = field(it, "retryButton").get(it)
            fun v(n: String) = field(r, n).getInt(r)
            (v("x") + v("w") / 2) to (v("y") + v("h") / 2)
        }
        fun retryWorks() {
            SkyShards.testSolver = ::fakeSolve
            clickRetry()
            waitPlanner(context)
            check(!failed()) { "Retry did not clear the error: ${text()}" }
        }
        fun maxAndWait(row: Int?): Int {
            if (row == null) click(context) { it.maxButtonCenter().let { p -> p[0] to p[1] } }
            else click(context) { it.plannerMaxCenter(row).let { p -> p[0] to p[1] } }
            val ticks = ticksBusy(context, 200)
            check(ticks < 60) { "Max with a failing solver stayed on Working... for $ticks ticks" }
            context.waitTicks(3)
            return ticks
        }
        try {
            context.onClient {
                Storage.profile.greenhouse.planAmounts = mutableMapOf()
                NyAddOns.openScreen { GreenhouseScreen() }
            }
            context.waitForScreen(GreenhouseScreen::class.java)
            context.waitTicks(10)
            pickView(context, 3)
            setSquares(context, 40)
            click(context) { it.clearCenter().let { p -> p[0] to p[1] } }
            waitPlanner(context)
            val mask = context.computeOnClient<BooleanArray, RuntimeException> { (field(screenNow(), "usableMask").get(screenNow()) as BooleanArray).copyOf() }
            val other = mask.copyOf().also { it[0] = !it[0] }
            val saved = fakeSolve(listOf(SkyGoal("cheesebite", 2)), mask)!!
            val older = fakeSolve(listOf(SkyGoal("ashwreath", 1)), mask)!!
            val foreign = fakeSolve(listOf(SkyGoal("ashwreath", 1)), other)!!
            val savedAt = java.time.LocalDateTime.of(2026, 10, 5, 8, 15).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val tail = "|" + bits(mask)

            // (1a) No cache entry: nothing to show, the red timeout line and Retry, no "Saved plan".
            resetCache(null)
            check(SkyShards.lastCached(mask) == null) { "lastCached with an empty cache gave a layout" }
            SkyShards.testSolver = timeout
            maxAndWait(null)
            check(failed() && "unreachable: no answer within 30 seconds" in text()) { "no red timeout line: ${text()}" }
            check("Saved plan" !in text() && placed().isEmpty()) { "no-cache timeout showed a plan: ${text()}" }
            context.takeScreenshot("verify-max-timeout-no-cache")
            retryWorks()

            // (1b) A cache for the same squares: the newest layout with a solution wins (a "nothing fits" entry and another set of squares are skipped).
            resetCache(cacheJson(listOf(
                Triple("ashwreath=1;$tail", older, savedAt - 3_600_000L),
                Triple("cheesebite=2;$tail", saved, savedAt),
                Triple("chocoberry=1;$tail", null, savedAt + 60_000L),
                Triple("ashwreath=1;|" + bits(other), foreign, savedAt + 120_000L),
            )))
            val got = SkyShards.lastCached(mask)
            check(got != null && got.at == savedAt && got.solution.counts == saved.counts) { "lastCached: ${got?.solution?.counts} at ${got?.at}, wanted ${saved.counts} at $savedAt" }
            check(SkyShards.lastCached(BooleanArray(100) { true }) == null) { "lastCached for squares nothing was cached for gave a layout" }
            check(SkyShards.lastCached(other)?.solution?.counts == foreign.counts) { "lastCached did not separate the squares" }

            setBox(context, 1, "3")
            waitPlanner(context)
            val typed = placed()
            check(typed.isNotEmpty() && typed != saved.counts) { "setup: typed plan $typed" }
            SkyShards.testSolver = timeout
            maxAndWait(null)
            check(placed() == saved.counts.filterValues { it > 0 }) { "main Max timeout showed ${placed()}, wanted ${saved.counts}" }
            check("Saved plan from 08:15" in text() && "unreachable: no answer within 30 seconds" in text() && failed()) { "fallback text: ${text()}" }
            check(planAmount("ashwreath") == 3) { "amounts not kept: ${Storage.profile.greenhouse.planAmounts}" }
            context.takeScreenshot("verify-max-timeout-cached-main")
            retryWorks()
            check(placed() == typed) { "after Retry: ${placed()}, wanted $typed" }

            // The same from a row's Max.
            SkyShards.testSolver = timeout
            maxAndWait(1)
            check(placed() == saved.counts.filterValues { it > 0 } && "Saved plan from 08:15" in text()) { "row Max timeout: ${placed()} / ${text()}" }
            context.takeScreenshot("verify-max-timeout-cached-row")
            retryWorks()

            // (2) Busy: the busy line, no unreachable line, and the cache is not offered (a timeout-only fallback).
            SkyShards.testSolver = busy
            maxAndWait(null)
            check(failed() && SkyShards.BUSY_TEXT in text() && "unreachable" !in text()) { "busy text: ${text()}" }
            check(placed() == typed) { "busy Max replaced the plan with ${placed()}" }
            context.takeScreenshot("verify-max-busy")
            retryWorks()

            // Squares with no cache entry keep the old plan; the cached layout of other squares is not used.
            resetCache(cacheJson(listOf(Triple("ashwreath=1;|" + bits(other), foreign, savedAt))))
            SkyShards.testSolver = timeout
            maxAndWait(null)
            check(failed() && placed() == typed) { "timeout without an entry for these squares showed ${placed()}, wanted $typed" }
            retryWorks()
        } finally {
            SkyShards.testSolver = ::fakeSolve
            resetCache(backup)
            context.setScreen { null }
        }
    }

    /** The compact Planner style: the same Planner checks as the classic one, then a hovered row for its tooltip. */
    private fun compactPlanner(context: ClientGameTestContext) {
        context.onClient { NyAddOns.config.garden.greenhouse.plannerStyle = PlannerStyle.COMPACT }
        context.onClient { NyAddOns.openScreen { GreenhouseScreen() } }
        context.waitForScreen(GreenhouseScreen::class.java)
        context.waitTicks(10)
        plannerTab(context, "compact854", true)
        context.onClient { NyAddOns.openScreen { GreenhouseScreen() } }
        context.waitForScreen(GreenhouseScreen::class.java)
        context.waitTicks(10)
        pickView(context, 3)
        check(screen(context).view == GreenhouseView.PLANNER)
        val (x, y, scale) = context.computeOnClient<Triple<Int, Int, Double>, RuntimeException> {
            val p = screenNow().plannerBoxCenter(2)
            Triple(p[0] - 150, p[1], it.window.screenWidth.toDouble() / it.window.guiScaledWidth)
        }
        context.input.setCursorPos(x * scale, y * scale)
        context.waitTicks(3)
        context.takeScreenshot("gh-compact-tooltip-854")
        context.setScreen { null }
        optimizerUi(context)
    }

    /** Ticks from now until the Planner stops working (Max or a typed change), at most [limit]. */
    private fun ticksBusy(context: ClientGameTestContext, limit: Int): Int {
        var t = 0
        while (context.computeOnClient<Boolean, RuntimeException> { screenNow().maxBusy } && t < limit) { context.waitTicks(1); t++ }
        return t
    }

    /**
     * Optimizer checks in the compact Planner at 1920x1080 with GUI scale 1 (small): Max and row Max end within the budget (the
     * client keeps ticking meanwhile), typing a new amount aborts a running Max, the same inputs give the same layout again, and the
     * "max 99" label and the soil key fit.
     */
    private fun optimizerUi(context: ClientGameTestContext) {
        context.input.resizeWindow(1920, 1080)
        context.onClient { it.options.guiScale().set(1); it.resizeGui() }
        context.onClient { NyAddOns.config.garden.greenhouse.plannerStyle = PlannerStyle.COMPACT }
        context.onClient { NyAddOns.openScreen { GreenhouseScreen() } }
        context.waitForScreen(GreenhouseScreen::class.java)
        context.waitTicks(10)
        pickView(context, 3)
        setSquares(context, 100)
        click(context) { it.clearCenter().let { p -> p[0] to p[1] } }
        waitPlanner(context)
        // Layout checks with every row's cap filled in: a Max first so "max N" labels exist, then the geometry.
        clickMax(context)
        context.waitTicks(5)
        context.onClient {
            val s = screenNow()
            check(s.plannerMaxLabelWidest <= s.plannerMaxRoom) { "'max N' label clipped: widest ${s.plannerMaxLabelWidest}px, room ${s.plannerMaxRoom}px" }
            check(s.plannerKeyBottom <= s.disclaimerTop) { "soil key ends at y=${s.plannerKeyBottom}, disclaimer starts at y=${s.disclaimerTop}" }
        }
        context.takeScreenshot("gh-compact-1080-gui1")

        // Main Max: the budget is 400 ms plus a 400 ms settle, so well under 3 s (60 ticks) with a margin for a loaded machine.
        click(context) { it.clearCenter().let { p -> p[0] to p[1] } }
        waitPlanner(context)
        click(context) { it.maxButtonCenter().let { p -> p[0] to p[1] } }
        val t0 = System.nanoTime()
        val ticks = ticksBusy(context, 200)
        val ms = (System.nanoTime() - t0) / 1_000_000
        check(ticks < 60) { "main Max stayed on Working... for $ticks ticks (${ms}ms)" }
        waitPlanner(context)
        val first = context.computeOnClient<String, RuntimeException> { layoutKey(screenNow()) }

        // Row Max on a row after setting another row: busy ends in time.
        setBox(context, 0, "2")
        waitPlanner(context)
        click(context) { it.plannerMaxCenter(1).let { p -> p[0] to p[1] } }
        val rowTicks = ticksBusy(context, 200)
        check(rowTicks < 60) { "row Max stayed on Working... for $rowTicks ticks" }
        waitPlanner(context)

        // Typing a new amount while Max runs aborts it.
        click(context) { it.maxButtonCenter().let { p -> p[0] to p[1] } }
        context.waitTicks(2)
        setBox(context, 2, "3")
        val abortTicks = ticksBusy(context, 60)
        check(abortTicks < 10) { "typing while Max ran did not abort it ($abortTicks ticks)" }
        waitPlanner(context)
        check(context.computeOnClient<Int, RuntimeException> { screenNow().plannerPlaced.values.sum() } > 0) { "nothing placed after typing over a running Max" }

        // Same inputs again: setting a row to the value it has must leave the same layout (no flicker between re-plans).
        val keys = ArrayList<String>()
        repeat(3) {
            setBox(context, 2, "3")
            waitPlanner(context)
            keys += context.computeOnClient<String, RuntimeException> { layoutKey(screenNow()) }
        }
        check(keys.distinct().size == 1) { "same inputs gave ${keys.distinct().size} different layouts" }
        check(first.isNotEmpty())
        context.setScreen { null }
        context.onClient { it.options.guiScale().set(0); it.resizeGui() }
        context.input.resizeWindow(854, 480)
    }

    private fun layoutKey(s: GreenhouseScreen): String = s.plannerLayout?.cells?.joinToString("/") { row -> row.joinToString(",") { it ?: "." } } ?: ""

    /** No coin or copper text anywhere in the current view. */
    private fun noCosts(context: ClientGameTestContext) {
        context.onClient {
            val text = screenNow().allVisibleText().lowercase()
            check(!text.contains("coin") && !text.contains("copper")) { "cost text is still shown: $text" }
        }
    }

    /** Row caps: a typed overflow snaps down to the cap with a note, + is greyed at the cap and does nothing, the cap rises when another row is lowered. */
    private fun caps(context: ClientGameTestContext, tag: String) {
        setBox(context, 2, "3")
        setBox(context, 1, "60")
        // the number stays as typed until the background check answers
        waitPlanner(context)
        val capped = context.computeOnClient<Int, RuntimeException> { planAmount("ashwreath") }
        context.onClient {
            val s = screenNow()
            check(capped in 1..59) { "60 Ashwreath alongside 3 Cheesebite must snap down: $capped" }
            check(s.plannerCapNote.contains("capped at $capped") && s.plannerText.contains("capped at $capped")) { "cap note: ${s.plannerCapNote} / ${s.plannerText}" }
            check(s.plannerAtCap("ashwreath") && s.plannerCap("ashwreath") == capped) { "the row is at its cap: ${s.plannerCap("ashwreath")}" }
            check(s.plannerPlaced == Storage.profile.greenhouse.planAmounts && s.plannerUnplaced.isEmpty()) { "the capped amounts all fit: ${s.plannerPlaced} ${s.plannerUnplaced}" }
        }
        context.takeScreenshot("gh-38-planner-capped-$tag")
        click(context) { it.plannerPlusCenter(1).let { p -> p[0] to p[1] } }
        waitPlanner(context)
        context.onClient { check(planAmount("ashwreath") == capped) { "+ at the cap does nothing: ${planAmount("ashwreath")} vs $capped" } }
        // Lowering the other row raises the cap: + works again and keeps the extra block.
        setBox(context, 2, "0")
        waitPlanner(context)
        click(context) { it.plannerPlusCenter(1).let { p -> p[0] to p[1] } }
        waitPlanner(context)
        context.onClient {
            val s = screenNow()
            check(planAmount("ashwreath") == capped + 1 && s.plannerPlaced == Storage.profile.greenhouse.planAmounts) { "cap rose: ${planAmount("ashwreath")} vs $capped, placed ${s.plannerPlaced}" }
        }
        // A row above its cap because ANOTHER row grew keeps its amount; the growing row is the one that gets capped.
        setBox(context, 1, "5")
        waitPlanner(context)
        val mine = 5
        setBox(context, 2, "40")
        waitPlanner(context)
        context.onClient {
            check(planAmount("ashwreath") == mine && planAmount("cheesebite") in 1..39) { "the other row is capped: ashwreath ${planAmount("ashwreath")} (was $mine), cheesebite ${planAmount("cheesebite")}" }
            check(screenNow().plannerPlaced == Storage.profile.greenhouse.planAmounts) { "nothing is left unplaced: ${screenNow().plannerPlaced}" }
        }
        click(context) { it.clearCenter().let { p -> p[0] to p[1] } }
        waitPlanner(context)

        // Every change plans the whole set again: add one mutation after another with rapid + presses; all fit, the layout holds them all.
        setBox(context, 1, "3")
        setBox(context, 2, "2")
        waitPlanner(context)
        repeat(3) { click(context) { it.plannerPlusCenter(5).let { p -> p[0] to p[1] } } }
        click(context) { it.plannerPlusCenter(4).let { p -> p[0] to p[1] } }
        waitPlanner(context)
        context.onClient {
            val s = screenNow()
            val amounts = Storage.profile.greenhouse.planAmounts
            check(amounts.size >= 3 && s.plannerPlaced == amounts && s.plannerUnplaced.isEmpty()) { "re-plan after adding mutations: $amounts placed ${s.plannerPlaced} ${s.plannerUnplaced}" }
        }
        context.takeScreenshot("gh-39-planner-replanned-$tag")
        click(context) { it.clearCenter().let { p -> p[0] to p[1] } }
        waitPlanner(context)
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
            check(old.planAmounts.isEmpty() && !old.planOneOfEach && old.plots.isEmpty() && old.blocked.isEmpty() && old.overlayOrigin == null && "a" in old.analysed) { "old profile data did not load with defaults" }
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
            click(context) { it.clearCenter().let { p -> p[0] to p[1] } }
            waitPlanner(context)
        }
        caps(context, tag)

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
            // The fake solver's 3 squares per block plus ingredients fill 40 squares with one block per kind, so repeats are not expected here.
            check(amounts.values.sum() >= kinds && amounts.values.all { it >= 1 } && screenNow().plannerPlaced == amounts) { "Max over all rows without the cap: $amounts" }
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
        // (Typed amounts are capped to what fits, so the amounts are stored directly and the squares shrink under them.)
        click(context) { it.clearCenter().let { p -> p[0] to p[1] } }
        context.onClient {
            val plan = Storage.profile.greenhouse.planAmounts
            plan["ashwreath"] = 9; plan["cheesebite"] = 9; plan["devourer"] = 5; plan["timestalk"] = 3
        }
        setSquares(context, null)
        waitPlanner(context)
        context.takeScreenshot("gh-34-planner-nofit-$tag")
        if (full) context.onClient {
            val s = screenNow()
            // SkyShards has no partial answer: a set that does not fit places nothing and every row says so (no rounds estimate).
            check(s.plannerRounds == 0 && s.plannerPlaced.isEmpty() && s.plannerUnplaced.keys == setOf("ashwreath", "cheesebite", "devourer", "timestalk")) { "rounds ${s.plannerRounds}, placed ${s.plannerPlaced}, unplaced ${s.plannerUnplaced}" }
            val text = s.plannerText.replace('\n', ' ')
            check(text.contains("Not placed")) { "side text: $text" }
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
                check(Storage.profile.greenhouse.planOneOfEach && s.plannerUnplaced.size == 4) { "reopened plan: ${s.plannerPlaced} ${s.plannerUnplaced}" }
            }
            click(context) { it.oneOfEachCenter().let { p -> p[0] to p[1] } }
        }
        blockedAndOverlay(context, tag, full)
        click(context) { it.clearCenter().let { p -> p[0] to p[1] } }
        context.onClient { Storage.profile.greenhouse.planOneOfEach = false }
        pickView(context, 0)
    }

    /** Closes the window, takes [name] in the world with the overlay on and opens the window again. */
    private fun worldShot(context: ClientGameTestContext, name: String) {
        context.setScreen { null }
        context.waitTicks(15)
        context.takeScreenshot(name)
        context.onClient { NyAddOns.openScreen { GreenhouseScreen() } }
        context.waitForScreen(GreenhouseScreen::class.java)
        context.waitTicks(3)
    }

    /** Blocked squares in the Plots picker, then the Planner's layout reaching the world overlay. 40 squares, 3 Ashwreath and 2 Cheesebite. */
    private fun blockedAndOverlay(context: ClientGameTestContext, tag: String, full: Boolean) {
        click(context) { it.clearCenter().let { p -> p[0] to p[1] } }
        setSquares(context, 40)
        setBox(context, 1, "3")
        setBox(context, 2, "2")
        waitPlanner(context)
        if (full) context.onClient {
            check(screenNow().plannerText.replace('\n', ' ').contains("on 40 squares")) { "before blocking: ${screenNow().plannerText}" }
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

        // The shown layout reaches the world overlay; Align stores where the plan's top-left square is.
        if (full) context.onClient {
            check(GreenhouseOverlay.plannedBlocks > 0) { "the planner layout did not reach the overlay" }
            // plannerTab runs twice with full = true, so an earlier pass's Align may still be stored.
            Storage.profile.greenhouse.overlayOrigin = null
            GreenhouseOverlay.align()
            val origin = Storage.profile.greenhouse.overlayOrigin ?: throw AssertionError("Align saved nothing")
            val feet = Minecraft.getInstance().player!!.blockPosition()
            check(origin.x == feet.x && origin.y == feet.y && origin.z == feet.z) { "aligned at ${origin.x} ${origin.y} ${origin.z}, standing at $feet" }
            // Saved with the profile: survives a restart.
            val again = com.google.gson.Gson().fromJson(com.google.gson.Gson().toJson(Storage.profile.greenhouse), GreenhouseProfile::class.java)
            check(again.overlayOrigin!!.x == origin.x && again.blocked == Storage.profile.greenhouse.blocked) { "origin and blocked squares did not survive saving" }
        }
        // Other window sizes only take the shot: align and enable the overlay there too, so it is not blank.
        context.onClient {
            if (!full) {
                Storage.profile.greenhouse.overlayOrigin = null
                GreenhouseOverlay.align()
            }
            NyAddOns.config.garden.greenhouse.overlayEnabled = true
        }
        worldShot(context, "gh-42-overlay-world-$tag")
        context.onClient { NyAddOns.config.garden.greenhouse.overlayEnabled = false }

        // Unblock everything again.
        click(context) { it.plotsButtonCenter().let { p -> p[0] to p[1] } }
        click(context) { it.unblockAllCenter().let { p -> p[0] to p[1] } }
        click(context) { it.donePlotsCenter().let { p -> p[0] to p[1] } }
        if (full) context.onClient { check(screenNow().blockedSquares == 0 && Storage.profile.greenhouse.blocked.isEmpty()) { "Unblock all" } }
    }

    /** The overlay follows the layout the Rose Dragon panel shows. */
    private fun roseOverlay(context: ClientGameTestContext) {
        context.onClient {
            check(screenNow().panelHasLayout && GreenhouseOverlay.plannedBlocks > 0) { "the Rose Dragon layout did not reach the overlay: ${GreenhouseOverlay.plannedBlocks} blocks" }
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

    /** The farm-next marker: one raw crop with the biggest shortfall, never under a held intermediate, none when nothing is short. */
    private fun farmNext(context: ClientGameTestContext) {
        val everything = setOf(GreenhouseTree.ROOT_PATH) + fakeData.mutations.map { it.id } + listOf("devourer/ashwreath", "timestalk/devourer", "timestalk/devourer/ashwreath", "phantomleaf/glasscorn")
        fun marked() = GreenhouseTree.rows(fakeData, fakeStock, everything, true).filter { it.farmNext }
        val saved = fakeStock.items.toMap()
        try {
            // Needed: Nether Wart 20 (held 12, short 8) beats Moonflower 6 (held 0); Glasscorn is held, so nothing under it counts.
            check(GreenhouseTree.farmNext(fakeData, fakeStock) == "Nether Wart") { "biggest shortfall: ${GreenhouseTree.farmNext(fakeData, fakeStock)}" }
            val first = marked()
            check(first.size == 1 && first[0].name == "Nether Wart" && first[0].id == null && !first[0].greyed) { "exactly one marker on the first Nether Wart: ${first.map { it.path }}" }
            check(first[0].path == "devourer/ashwreath/nether_wart") { "first in tree order: ${first[0].path}" }

            // Holding Devourer x1 greys what is under it and drops its need; the marker follows the new shortfall.
            val needBefore = GreenhouseTree.cropTotals(fakeData, fakeStock).second
            context.onClient { fakeStock.items["devourer"] = 1; fakeStock.updated = System.currentTimeMillis() }
            check(GreenhouseTree.rows(fakeData, fakeStock, everything, true).filter { it.path.startsWith("devourer/") }.all { it.greyed }) { "children of a held Devourer should be greyed" }
            val needAfter = GreenhouseTree.cropTotals(fakeData, fakeStock).second
            check(needAfter < needBefore) { "greyed crops leave the Crops total: $needBefore -> $needAfter" }
            // Greyed pills stay in the tree and readable (a held intermediate itself is not greyed).
            check(GreenhouseTree.rows(fakeData, fakeStock, everything, true).first { it.path == "devourer" }.let { !it.greyed })
            context.waitTicks(4)
            context.takeScreenshot("rose-tree-pills-held-intermediate")
            check(marked().none { it.greyed }) { "a greyed node is never marked" }

            // Moonflower 6 short beats Nether Wart now (needs 12 in all, 12 held).
            check(marked().single().name == "Moonflower") { "after holding Devourer: ${marked().map { it.name }}" }
            context.onClient { fakeStock.items["moonflower"] = 100 }
            check(GreenhouseTree.farmNext(fakeData, fakeStock) != "Moonflower") { "held amounts change the marker" }

            // Nothing short: no marker.
            context.onClient { for (c in fakeData.crops) fakeStock.items[c.name.lowercase()] = 100_000 }
            check(GreenhouseTree.farmNext(fakeData, fakeStock) == null && marked().isEmpty()) { "no marker when nothing is short: ${marked().map { it.name }}" }
        } finally {
            context.onClient { fakeStock.items.clear(); fakeStock.items.putAll(saved) }
        }
        context.waitTicks(4)
        context.takeScreenshot("rose-tree-pills-farm-next")
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
        roseOverlay(context)
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

    /** The pill style of the Rose Dragon tree: body opens the side panel, - collapses, + expands. */
    private fun roseTreePills(context: ClientGameTestContext) {
        val config = NyAddOns.config.garden.greenhouse
        val oldTreeStyle = config.roseTreeStyle
        context.onClient { config.roseTreeStyle = dev.nytrix.nyaddons.config.RoseTreeStyle.PILLS }
        try {
            // Reopen so the tree is laid out in the new style.
            context.setScreen { null }
            context.onClient { NyAddOns.openScreen { GreenhouseScreen() } }
            context.waitForScreen(GreenhouseScreen::class.java)
            context.waitTicks(25)
            fun tree() = context.computeOnClient<List<String>, RuntimeException> { screenNow().treeText() }
            val start = tree()
            // Row 0 is the expanded root "Rose Dragon Egg", then its five children.
            check(start.size == 6 && start.all { it.contains("x") }) { "collapsed pill tree: $start" }
            val crops = context.computeOnClient<String, RuntimeException> { screenNow().rootCropText() }
            val (cropHave, cropNeed) = Regex("""Crops (\d+)/(\d+)""").matchEntire(crops)?.destructured?.let { it.component1().toInt() to it.component2().toInt() }
                ?: error("root pill should show a crop total like 'Crops 12/340': '$crops'")
            check(cropNeed > 0 && cropHave in 0..cropNeed) { "root crop total: $crops" }
            context.takeScreenshot("rose-tree-pills-collapsed")

            click(context) { it.treeArrowCenter(2).let { p -> p[0] to p[1] } }
            check(tree().size == 8) { "+ on Devourer should expand it: ${tree()}" }
            click(context) { it.treeArrowCenter(4).let { p -> p[0] to p[1] } }
            check(tree().size == 9) { "+ on Ashwreath should expand it: ${tree()}" }
            context.waitTicks(3)
            context.takeScreenshot("rose-tree-pills-expanded")
            click(context) { it.treeArrowCenter(2).let { p -> p[0] to p[1] } }
            check(tree().size == 6) { "- on Devourer should collapse it: ${tree()}" }
            click(context) { it.treeArrowCenter(2).let { p -> p[0] to p[1] } }
            check(tree().size == 9) { "+ again should expand it, with Ashwreath still open inside: ${tree()}" }

            // Clicking a pill body opens the side-panel layout and leaves the branch as it was.
            click(context) { it.treeTextCenter(4).let { p -> p[0] to p[1] } }
            context.waitTicks(15)
            context.onClient {
                val s = screenNow()
                check(s.panelItem == "Ashwreath x2" && s.panelHasLayout) { "pill body should open the layout: ${s.panelItem} ${s.panelHasLayout}" }
            }
            check(tree().size == 9) { "clicking a pill body must not expand or collapse it: ${tree()}" }
            context.takeScreenshot("rose-tree-pills-panel")
            click(context) { it.treeTextCenter(4).let { p -> p[0] to p[1] } }
            context.waitTicks(3)
            check(context.computeOnClient<String?, RuntimeException> { screenNow().panelItem } == null) { "clicking the picked pill again should close the panel" }

            // Held stock: Wheat 640/2 is covered, Ashwreath 0/2 is short, both in the expanded Devourer.
            context.takeScreenshot("rose-tree-pills-stock")

            farmNext(context)

            // Tall panel (GUI scale 1), then back to the default.
            context.onClient { it.options.guiScale().set(1); it.resizeGui() }
            context.waitTicks(10)
            context.takeScreenshot("rose-tree-pills-tall")
            context.onClient { it.options.guiScale().set(0); it.resizeGui() }
            context.waitTicks(5)
        } finally {
            context.onClient { config.roseTreeStyle = oldTreeStyle }
            context.setScreen { null }
            context.onClient { NyAddOns.openScreen { GreenhouseScreen() } }
            context.waitForScreen(GreenhouseScreen::class.java)
            context.waitTicks(10)
        }
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
