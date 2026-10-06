package dev.nytrix.nyaddons.features.greenhouse

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.config.GreenhouseView
import dev.nytrix.nyaddons.config.PlannerStyle
import dev.nytrix.nyaddons.config.RoseTreeStyle
import dev.nytrix.nyaddons.core.Safe
import dev.nytrix.nyaddons.core.Storage
import dev.nytrix.nyaddons.core.TimeUtils
import dev.nytrix.nyaddons.gui.ConfigTheme
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/** One row of the unique mutations checklist, with every string prepared so drawing allocates nothing. */
private class UniqueRow(
    val id: String,
    val label: String,
    val analysed: Boolean,
    val next: Boolean,
    val ingredients: Int,
    val fitText: String,
    val fitWidth: Int,
    var labelWidth: Int = 0,
)

/** One row of the Planner list: a mutation with its amount box. */
private class PlannerRow(val id: String, val label: String, val labelWidth: Int, val analysed: Boolean, val amount: Int, val amountText: String, val problem: Boolean, val cap: Int = CAP_UNKNOWN) {
    /** True when no more of this row fits alongside the other rows (as far as the cached search knows). */
    val atCap get() = cap >= 0 && amount >= cap
}

/** [PlannerRow.cap] while the search for it has not answered. */
private const val CAP_UNKNOWN = -2

/** One line under the Planner layout: a heading or note ([heading], no icon column), else an icon, a text and optional counts at the right edge. */
private class SideRow(val icon: String?, val text: String, val heading: Boolean = false, val counts: String = "", val countColor: Int = 0, val countWidth: Int = 0)

/** One line of the Rose Dragon tree, prepared for drawing. */
private class TreeLine(val row: TreeRow, val label: String, val counts: String, val countWidth: Int, val covered: Boolean, val fitText: String, val fitWidth: Int, val pill: Pill? = null)

/** The pill-style look of a [TreeLine]: where the pill sits, its texts and which connector columns run past it. */
private class Pill(
    val x0: Int, val x1: Int, val badge: String, val badgeWidth: Int, val name: String, val crops: String, val cropsWidth: Int, val summary: String,
    val outline: Int, val guides: BooleanArray,
)

/** A layout plus what is needed to draw it: the blocks (a 2x2 or 3x3 mutation is one block) and the legend entries. */
private class Picture(
    val layout: GhLayout,
    val highlight: String?,
    val blockRow: IntArray,
    val blockCol: IntArray,
    val blockSpan: IntArray,
    val blockId: Array<String>,
    val legendIds: Array<String>,
    val legendNames: Array<String>,
    val legendWidths: IntArray,
    val r0: Int,
    val r1: Int,
    val c0: Int,
    val c1: Int,
)

/** What the side panel shows: a title with an icon, the plot layout (or a busy / explanatory text) and small extra lines. */
private class Side {
    var title = ""
    var icon: String? = null
    var summary = ""
    var busy = false
    var busyText = "Working..."
    var picture: Picture? = null
    var lines = emptyList<String>()
    var extra = emptyList<String>()
    var rows = emptyList<SideRow>()

    fun clear() {
        title = ""
        icon = null
        summary = ""
        busy = false
        picture = null
        lines = emptyList()
        extra = emptyList()
        rows = emptyList()
    }
}

private class Rect(var x: Int, var y: Int, var w: Int, var h: Int) {
    fun set(nx: Int, ny: Int, nw: Int, nh: Int) {
        x = nx
        y = ny
        w = nw
        h = nh
    }

    fun contains(mx: Int, my: Int) = mx >= x && mx < x + w && my >= y && my < y + h
}

/** The Greenhouse helper window: unique mutations checklist, Rose Dragon tree, a mutation browser with a layout planner and an amounts planner. */
class GreenhouseScreen : Screen(Component.literal("Greenhouse")) {

    private val site = Safe.site("Greenhouse")
    private val config get() = NyAddOns.config.garden.greenhouse
    private val saved get() = Storage.profile.greenhouse

    var view: GreenhouseView = config.view
        private set

    private var left = 0
    private var top = 0
    private var panelWidth = 0
    private var panelHeight = 0
    private var ticks = 0
    private var dropdownOpen = false
    private val scroll = IntArray(GreenhouseView.entries.size)

    // Unlocked squares and how mutations fit on them.
    private var mask = GreenhousePlots.default()
    private var blocked = BooleanArray(GreenhousePlots.CELLS)
    private var usableMask = mask
    private var unlocked = 0
    private var blockedCount = 0
    private var blockMode = false
    private val fit = GreenhouseFit()
    private var fitSeen = -1
    private var stockSeen = -1L
    private var plotsOpen = false
    private var fillValue = 0
    private var plotsButton = Rect(0, 0, 0, 0)
    private var maxButton = Rect(0, 0, 0, 0)
    private var oneRect = Rect(0, 0, 0, 0)
    private var clearButton = Rect(0, 0, 0, 0)
    private var plannerHint = ""
    private var plotsGrid = Rect(0, 0, 0, 0)
    private var plotsCell = 12
    private var fillBox = Rect(0, 0, 0, 0)
    private var fillMinus = Rect(0, 0, 0, 0)
    private var fillPlus = Rect(0, 0, 0, 0)
    private var fillButton = Rect(0, 0, 0, 0)
    private var allButton = Rect(0, 0, 0, 0)
    private var defaultButton = Rect(0, 0, 0, 0)
    private var doneButton = Rect(0, 0, 0, 0)
    private var blockButton = Rect(0, 0, 0, 0)
    private var unblockButton = Rect(0, 0, 0, 0)
    private var plotsHint = emptyList<String>()
    private var plotsControlsX = 0

    // Number box being typed in: a Planner amount or the "Fill to" box.
    private var focus = FOCUS_NONE
    private var focusId = ""
    private var focusText = ""
    private var focusFresh = false

    // Models, rebuilt once a second and when something is clicked.
    private var unique = emptyList<UniqueRow>()
    private var plannerRows = emptyList<PlannerRow>()
    private var plannerBoxX = 0
    private var plannerMaxX = 0
    private var capMarkW = 0
    private var plannerMinusX = 0
    private var plannerPlusX = 0
    private var plannerMaxW = 0
    private var headerA = ""
    private var headerSummary = ""
    private var roseLine = ""
    private var roseFraction = 0f
    private var foundLine = ""
    private var foundFraction = 0f
    private var hint = ""
    private var disclaimer = emptyList<String>()
    private var footer = ""
    private var loading = false
    private var tree = emptyList<TreeLine>()
    private val expanded = hashSetOf(GreenhouseTree.ROOT_PATH)
    private var all = emptyList<GhMutation>()
    private var allLabels = emptyArray<String>()
    private var selected = 0
    private var details = emptyList<DetailLine>()

    // The item picked in the Rose Dragon tree, shown as a layout in the side panel.
    private var selPath: String? = null
    private var selId: String? = null
    private var selName = ""
    private var selAmount = 0
    private var panelGeneration = 0
    @Volatile private var panelResult: GhLayout? = null
    @Volatile private var panelDone = false
    @Volatile private var panelError: String? = null
    private val rose = Side()
    private var panelRounds = 0

    // The Max solver of the Planner view (the main button or one row's "max"): one run at a time, answered on a background thread.
    @Volatile private var maxRunning = false
    private var keyBottomDrawn = 0
    @Volatile private var maxAnswer: GhMaxResult? = null
    @Volatile private var alongAnswer: GhPlaceResult? = null
    @Volatile private var maxDone = false
    @Volatile private var maxPlan: GhPlaceResult? = null
    @Volatile private var maxFallbackAt = 0L
    private var maxGeneration = 0
    private var maxRow: String? = null

    // The Planner's shared layout for the typed amounts: worked out on a background thread a moment after the last change.
    @Volatile private var mixResult: GhPlaceResult? = null
    @Volatile private var mixDone = false
    @Volatile private var mixWorking = false
    @Volatile private var mixGeneration = 0
    private var mixStale = true
    private var mixDirtyAt = 0
    private var mixShown: GhPlaceResult? = null
    // When [mixShown] was worked out (epoch millis): the "Saved plan from <time>" label when SkyShards then fails.
    private var mixShownAt = 0L
    private var lastEdited: String? = null
    private var retryButton = Rect(0, 0, 0, 0)
    private var mixPictureFor: GhPlaceResult? = null
    private var reuseBase: GhLayout? = null
    private var reuseLine = ""

    // Row caps: the most a row can take alongside the OTHER rows' amounts (the row max computation), found in the background and
    // cached per (other rows' amounts, squares, One of each). Only a heuristic: "no more fit" means our greedy search found none.
    private class CapRequest(val key: Long, val id: String, val amounts: Map<String, Int>, val mask: BooleanArray)
    private class CapAnswer(val key: Long, val id: String, val cap: Int, val failed: Boolean = false)
    private val capCache = object : LinkedHashMap<Long, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Int>?) = size > CAP_CACHE_SIZE
    }
    private val capAnswers = java.util.concurrent.ConcurrentLinkedQueue<CapAnswer>()
    private var capRunning = false
    private var capPending: CapRequest? = null
    private var capTouched: String? = null
    private val capBase = HashMap<String, Int>() // amount before an increase that the cap has not checked yet
    private var capNote = ""
    private var maxKey = 0L
    // SkyShards: what the running planner solve says it is doing, and why the last one could not be answered (null: no error).
    @Volatile private var skyProgress = ""
    @Volatile private var skyError: String? = null
    private val plannerSide = Side()
    private var sideScroll = 0
    private var sideContentH = 0

    // Layout planner of the All Mutations view: only the last plan is kept.
    @Volatile private var planning = false
    @Volatile private var planResult: GhLayout? = null
    @Volatile private var planDone = false
    @Volatile private var planError: String? = null
    private var planFor: String? = null
    private var layout: GhLayout? = null
    private var picture: Picture? = null
    private var planGeneration = 0

    // Drawing helpers: the grid drawn last (for the hover name) and how many icons the last frame drew.
    private var gridPicture: Picture? = null
    private var gridX = 0
    private var gridY = 0
    private var gridCell = 0
    private var gridR0 = 0
    private var gridC0 = 0
    private var gridRows = 0
    private var gridCols = 0
    private var iconCount = 0
    private var iconsLastFrame = 0

    private val compact get() = panelHeight < 300
    private val rowH get() = if (compact) 14 else 18
    private val iconSize get() = rowH - 2
    private val barH get() = if (compact) 11 else 14
    private val barsY get() = top + 24
    private val linesY get() = barsY + barH + 4
    private val rowsVisible get() = ((listBottom - listTop) / rowH).coerceAtLeast(1)
    private val listTop get() = linesY + when (view) {
        GreenhouseView.ALL_MUTATIONS -> 2
        GreenhouseView.PLANNER -> 18
        GreenhouseView.ROSE_DRAGON -> 14
        else -> 26
    }
    private val footerY get() = top + panelHeight - 13
    private val listBottom get() = footerY - 3 - disclaimer.size * 9 - 4 - keyH
    /** Height of the one-line soil key under the compact Planner list. */
    private val keyH get() = if (view == GreenhouseView.PLANNER && config.plannerStyle == PlannerStyle.COMPACT) KEY_H else 0
    private val listLeft get() = left + 8
    private val sideWidth get() = (panelWidth * 0.42).toInt().coerceIn(150, 330)
    private val sideLeft get() = left + panelWidth - 8 - sideWidth
    private val sideOpen get() = (view == GreenhouseView.ROSE_DRAGON && selPath != null) || view == GreenhouseView.PLANNER
    private val listRight get() = when {
        view == GreenhouseView.ALL_MUTATIONS -> left + 8 + allListWidth
        sideOpen -> sideLeft - 6
        else -> left + panelWidth - 8
    }
    private val allListWidth get() = (panelWidth * 0.26).toInt().coerceIn(110, 210)
    private val detailLeft get() = listRight + 12
    private val detailWidth get() = (panelWidth * 0.30).toInt().coerceIn(116, 230)
    private val gridLeft get() = detailLeft + detailWidth + 8

    override fun init() = site.screen(this) { setUp() }

    private fun setUp() {
        panelWidth = (width - 12).coerceIn(280, 780)
        panelHeight = (height - 12).coerceIn(180, 450)
        left = (width - panelWidth) / 2
        top = (height - panelHeight) / 2
        disclaimer = wrap("§7Shellfruit and Jerryflower are not included yet: the mod is being updated for them.", panelWidth - 16)
        mask = GreenhousePlots.current()
        blocked = GreenhousePlots.currentBlocked()
        updateCounts()
        if (fillValue == 0) fillValue = unlocked
        GhIcons.request()
        placeButtons()
        Greenhouse.data.request()
        rebuild()
        fit.request(Greenhouse.data, usableMask)
    }

    /** The unlocked and blocked counts and the squares plans may use, after either set changed. */
    private fun updateCounts() {
        for (i in blocked.indices) if (blocked[i] && !mask[i]) blocked[i] = false
        unlocked = GreenhousePlots.count(mask)
        blockedCount = GreenhousePlots.count(blocked)
        usableMask = GreenhousePlots.usable(mask, blocked)
    }

    private fun placeButtons() {
        val dropdownRight = dropdownX + dropdownWidth
        plotsButton = Rect(dropdownRight + 6, top + 5, font.width("Plots") + 14, DD_H)
        // Planner controls sit on their own row under the bars, so they stay visible in small windows.
        maxButton = Rect(left + 8, linesY, font.width("Working...") + 14, BUTTON_H)
        oneRect = Rect(maxButton.x + maxButton.w + 8, linesY, 13 + font.width("One of each"), BUTTON_H)
        clearButton = Rect(oneRect.x + oneRect.w + 8, linesY, font.width("Clear") + 14, BUTTON_H)
        val hintX = clearButton.x + clearButton.w + 10
        plannerHint = "§7Type amounts, or Max for the best mix".let { if (font.width(it) <= left + panelWidth - 8 - hintX) it else "§7Type amounts or Max" }
        if (font.width(plannerHint) > left + panelWidth - 8 - hintX) plannerHint = ""
        // Plots picker: the grid on the left, the controls beside it.
        val gridTop = linesY + 12
        val cell = ((listBottom - gridTop) / GreenhousePlots.GRID).coerceIn(9, 26)
        plotsCell = cell
        plotsGrid = Rect(left + 12, gridTop, cell * GreenhousePlots.GRID, cell * GreenhousePlots.GRID)
        val cx = plotsGrid.x + plotsGrid.w + 18
        plotsControlsX = cx
        val y0 = gridTop + 14
        fillBox = Rect(cx + font.width("Fill to") + 6, y0, 34, BUTTON_H)
        fillMinus = Rect(fillBox.x + fillBox.w + 4, y0, 14, BUTTON_H)
        fillPlus = Rect(fillMinus.x + 18, y0, 14, BUTTON_H)
        fillButton = Rect(fillPlus.x + 18, y0, font.width("Fill") + 14, BUTTON_H)
        allButton = Rect(cx, y0 + 22, font.width("All") + 14, BUTTON_H)
        defaultButton = Rect(allButton.x + allButton.w + 4, y0 + 22, font.width("Default") + 14, BUTTON_H)
        unblockButton = Rect(defaultButton.x + defaultButton.w + 4, y0 + 22, font.width("Unblock all") + 14, BUTTON_H)
        blockButton = Rect(cx, y0 + 44, font.width("Block mode") + 14, BUTTON_H)
        doneButton = Rect(blockButton.x + blockButton.w + 4, y0 + 44, font.width("Done") + 14, BUTTON_H)
        plotsHint = wrap("§7Fill unlocks the squares nearest the middle first. Block mode: click an unlocked square to keep it empty.", left + panelWidth - 8 - cx)
        capMarkW = font.width("at max")
        // Planner: Retry sits at the right end of the side panel's title row while SkyShards is down.
        val retryW = font.width("Retry") + 10
        retryButton = Rect(sideLeft + sideWidth - retryW, linesY + 17, retryW, BUTTON_H - 2)
    }

    override fun tick() = site.screen(this) { update() }

    private fun update() {
        ticks++
        val stamp = Greenhouse.stock.sacksUpdatedAt
        // New numbers from a sack menu, or a plan or fit answer arriving, redraw straight away; otherwise once a second.
        if (ticks % 20 == 0) {
            GhIcons.request()
            Greenhouse.data.request()
            fit.request(Greenhouse.data, usableMask)
            rebuild()
        } else if (stamp != stockSeen || fit.version != fitSeen) {
            rebuild()
        }
        if (panelDone) {
            panelDone = false
            rose.busy = false
            finishPanelPlan(panelResult)
            panelResult = null
        }
        if (maxDone) {
            maxDone = false
            maxRunning = false
            finishMax(maxAnswer, alongAnswer, maxRow)
            maxAnswer = null
            alongAnswer = null
            maxPlan = null
        }
        drainCaps()
        if (mixDone) {
            mixDone = false
            mixWorking = false
            // SkyShards failed (an error is showing): keep the last good plan, greyed, instead of blanking it.
            if (mixResult != null || skyError == null) {
                mixShown = mixResult
                mixShownAt = System.currentTimeMillis()
            }
            mixResult = null
            buildPlannerSide()
        }
        if (mixStale && view == GreenhouseView.PLANNER && !maxRunning && ticks - mixDirtyAt >= MIX_DEBOUNCE_TICKS) {
            checkCap()
            startMix()
        }
        // While SkyShards works (Max or the shared plan) the side panel shows how far it is.
        if (maxRunning || mixWorking) {
            plannerSide.busy = true
            plannerSide.busyText = skyProgress.ifEmpty { "Working..." }
        } else if (plannerSide.busy) {
            plannerSide.busy = false
        }
    }

    override fun removed() {
        planGeneration++
        panelGeneration++
        maxGeneration++
        mixGeneration++
        fit.stop()
        unique = emptyList()
        tree = emptyList()
        all = emptyList()
        allLabels = emptyArray()
        details = emptyList()
        layout = null
        planResult = null
        picture = null
        rose.clear()
        plannerSide.clear()
        plannerRows = emptyList()
        mixShown = null
        mixResult = null
        mixPictureFor = null
        panelResult = null
        maxAnswer = null
        gridPicture = null
        expanded.clear()
        expanded.add(GreenhouseTree.ROOT_PATH)
    }

    // Models

    private fun rebuild() {
        val data = Greenhouse.data
        val stock = Greenhouse.stock
        loading = !data.ready
        stockSeen = stock.sacksUpdatedAt
        fitSeen = fit.version
        val profile = saved
        val order = uniqueOrder(data.mutations)
        var nextTaken = false
        val right = listRight - 8
        // Dot at the right edge, the "does not fit" note left of it.
        val fitRight = right - 12
        val shortage = HashMap<String, Int>()
        var totalNeed = 0
        var anyKnown = false
        val labelX = listLeft + 17 + iconSize + 4
        unique = order.map { m ->
            val done = m.id in profile.analysed
            val isNext = !done && !nextTaken
            if (isNext) nextTaken = true
            if (!done) {
                for (r in m.requirements) {
                    val name = data.nameOf(r.crop)
                    totalNeed += r.count
                    val have = stock.count(name)
                    if (have != null) anyKnown = true
                    if (have != null && have < r.count) shortage.merge(name, r.count - have, Int::plus)
                }
            }
            val label = "${rarityCode(m.rarity)}${m.name}"
            val labelWidth = font.width(label)
            val avail = fitRight - 6 - (labelX + labelWidth + if (isNext) 44 else 0)
            val fitText = fitLabel(fit.check(m.id), avail)
            UniqueRow(m.id, label, done, isNext, ingredientState(m, data, stock), fitText, font.width(fitText), labelWidth)
        }
        headerSummary = ellipsize(summaryText(totalNeed, shortage, anyKnown, stock.sacksUpdatedAt > 0), panelWidth - 16)
        val found = profile.analysed.count { it !in GreenhouseGoals.skippedMutations }.coerceAtMost(GreenhouseGoals.TRACKED_MUTATIONS)
        val milestone = nextMilestone(found)
        val nextRow = unique.firstOrNull { it.next }
        headerA = ellipsize(
            (if (milestone == null) "§aAll DNA milestones reached" else "§7Next milestone §f${milestone.roman} §7at §f${milestone.threshold}§7: §e${milestone.remaining} §7more") +
                if (nextRow == null) "   §7Nothing left to analyse." else "   §7Next up: ${nextRow.label}",
            panelWidth - 16,
        )
        foundLine = "Mutations found $found/${GreenhouseGoals.TRACKED_MUTATIONS}"
        foundFraction = found.toFloat() / GreenhouseGoals.TRACKED_MUTATIONS
        val percent = GreenhouseTree.percent(data, stock)
        roseLine = "Rose Dragon ~$percent%"
        roseFraction = percent / 100f
        val pills = config.roseTreeStyle == RoseTreeStyle.PILLS
        hint = (if (pills) arrayOf(
            "§7Click a pill for its plot layout, - collapses it and + expands it. Right side: held/needed. The percentage is a rough estimate.",
            "§7Click a pill for its layout, - / + collapse. Right: held/needed. Percentage is rough.",
            "§7Click a pill for its layout. - collapses, + expands.",
        ) else arrayOf(
            "§7Arrow expands a row, its name shows the plot layout. Right side: held/needed. The percentage is a rough estimate.",
            "§7Arrow expands a row, name shows the layout. Right: held/needed. Percentage is rough.",
            "§7Arrow expands, name shows the layout. Right: held/needed.",
        )).firstOrNull { font.width(it) <= panelWidth - 16 } ?: ellipsize("§7Arrow expands, name shows the layout.", panelWidth - 16)

        val rows = GreenhouseTree.rows(data, stock, expanded, pills)
        val treeRight = right - 4
        if (pills) {
            tree = pillLines(rows, treeRight)
        } else tree = rows.map { row ->
            val counts = "${formatCount(row.have)}/${formatCount(row.need)}"
            val countWidth = font.width(counts)
            val label = if (row.depth == 0) row.name else "x${row.need} ${row.name}"
            val labelEnd = listLeft + 4 + row.depth * 10 + 12 + iconSize + 3 + font.width(label)
            val fitText = if (row.id == null) "" else fitLabel(fit.check(row.id), treeRight - countWidth - 8 - labelEnd)
            TreeLine(row, label, counts, countWidth, row.have >= row.need, fitText, font.width(fitText))
        }

        // Planner list: every plannable mutation by name, with the amount typed for it.
        plannerPlusX = right - 4 - STEP_W
        val boxX = plannerPlusX - 1 - BOX_W
        plannerBoxX = boxX
        plannerMinusX = boxX - 1 - STEP_W
        // The compact style prints the row's cap after "max" (so it does not read like the top Max button), which needs more room.
        plannerMaxW = font.width(if (config.plannerStyle == PlannerStyle.COMPACT) "max 99" else "max") + 6
        plannerMaxX = plannerMinusX - 3 - plannerMaxW
        val plan = profile.planAmounts
        val unplaced = mixShown?.unplaced
        val capSeed = capBaseHash()
        val shownPlaced = mixShown?.placed
        plannerRows = data.mutations.filter { it.id !in GreenhouseGoals.skippedMutations }.sortedBy { it.name }.map { m ->
            val label = "${rarityCode(m.rarity)}${m.name}"
            val amount = plan[m.id] ?: 0
            PlannerRow(m.id, label, font.width(label), m.id in profile.analysed, amount, amount.toString(), amount > 0 && unplaced != null && m.id in unplaced, provenCap(capCache[capKey(m.id, capSeed)] ?: CAP_UNKNOWN, amount, shownPlaced?.get(m.id) ?: 0))
        }
        buildPlannerSide()

        all = data.mutations.sortedBy { it.name }
        allLabels = Array(all.size) { "${rarityCode(all[it].rarity)}${all[it].name}" }
        selected = selected.coerceIn(0, (all.size - 1).coerceAtLeast(0))
        details = all.getOrNull(selected)?.let { m -> detailLines(m, data, stock).map { DetailLine(it.icon, ellipsize(it.text, detailWidth - if (it.icon != null) 12 else 0)) } } ?: emptyList()

        val updated = stock.sacksUpdatedAt
        footer = if (updated <= 0) "§7Sacks: open your sacks on the Garden" else "§7Sacks last updated: ${TimeUtils.format(System.currentTimeMillis() - updated).let { if (it == "Soon") "just now" else "$it ago" }}"
        for (i in scroll.indices) scroll[i] = scroll[i].coerceIn(0, maxScroll(i))
    }

    /** Lays out the pill tree: each pill's width and texts, and which connector columns run past it. */
    private fun pillLines(rows: List<TreeRow>, treeRight: Int): List<TreeLine> {
        // Going backwards, `later[c]` says the next row at depth c or shallower is at exactly depth c, so column c's line carries on.
        val later = BooleanArray(MAX_PILL_DEPTH + 2)
        val guides = arrayOfNulls<BooleanArray>(rows.size)
        for (i in rows.indices.reversed()) {
            val d = rows[i].depth.coerceAtMost(MAX_PILL_DEPTH)
            guides[i] = BooleanArray(d + 1) { it in 1..d && later[it] }
            later[d] = true
            for (c in d + 1 until later.size) later[c] = false
        }
        val ph = rowH - 1
        return rows.mapIndexed { i, row ->
            val x0 = listLeft + 4 + row.depth.coerceAtMost(MAX_PILL_DEPTH) * PILL_INDENT
            val badge = "§l${row.need}x"
            val badgeW = font.width(badge) + 6
            val counts = "${formatCount(row.have)}/${formatCount(row.need)}"
            val countW = font.width(counts)
            val isRoot = row.path == GreenhouseTree.ROOT_PATH
            val nameColor = if (isRoot) "§c" else if (row.id == null) "§7" else if (row.rarity.isEmpty()) "§f" else rarityCode(row.rarity)
            // Greyed (under an intermediate you already hold): no colour codes, so the draw colour dims the whole pill.
            val name = if (row.greyed) row.name else (if (row.farmNext) "§6★ " else "") + nameColor + row.name
            val button = if (row.expandable) 6 + font.width("+") else 0
            val fixed = 3 + badgeW + 3 + (ph - 2) + 3 + font.width(name) + 6 + countW + button + 4
            var room = treeRight - x0 - fixed
            // Root pill: raw crops held/needed for the whole tree, kept whole while the ingredient list shrinks.
            // When even that does not fit the tree column (side panel open), it loses the "Crops" word, then goes, so the capsule end stays inside.
            val cropCount = "${if (row.cropHave >= row.cropNeed) "§a" else "§c"}${formatCount(row.cropHave)}/${formatCount(row.cropNeed)}"
            val crops = if (!isRoot || row.cropNeed <= 0) "" else listOf("§6Crops $cropCount", cropCount).firstOrNull { font.width(it) + 6 <= room } ?: ""
            if (crops.isNotEmpty()) room -= font.width(crops) + 6
            val fitText = if (row.id == null) "" else fitLabel(fit.check(row.id), room - 6)
            if (fitText.isNotEmpty()) room -= font.width(fitText) + 6
            // Too tight for "3x Wheat": drop the "x" ("3 Wheat") before cutting the text short.
            val summaryStyle = if (row.greyed) "§o" else "§7§o"
            val summary = if (row.summary.isEmpty() || room < 30) "" else "$summaryStyle${row.summary}".let { full ->
                if (font.width(full) <= room - 6) full else ellipsize("$summaryStyle${row.summary.replace(COUNT_X, "$1 ")}", room - 6)
            }
            val width = fixed + (if (crops.isEmpty()) 0 else 6 + font.width(crops)) + (if (summary.isEmpty()) 0 else 6 + font.width(summary)) + (if (fitText.isEmpty()) 0 else 6 + font.width(fitText))
            val outline = if (row.greyed) PILL_CROP_OUTLINE else if (isRoot) PILL_ROOT_OUTLINE else if (row.farmNext) PILL_FARM_NEXT_OUTLINE else if (row.id == null) PILL_CROP_OUTLINE else rarityColor(row.rarity)
            TreeLine(row, "x${row.need} ${row.name}", counts, countW, row.have >= row.need, fitText, font.width(fitText), Pill(x0, x0 + width, badge, badgeW, name, crops, font.width(crops), summary, outline, guides[i]!!))
        }
    }

    private fun rarityColor(rarity: String): Int = when (rarity.lowercase()) {
        "uncommon" -> 0xFF55FF55.toInt()
        "rare" -> 0xFF5555FF.toInt()
        "epic" -> 0xFFAA00AA.toInt()
        "legendary" -> 0xFFFFAA00.toInt()
        "mythic" -> 0xFFFF55FF.toInt()
        "divine" -> 0xFF55FFFF.toInt()
        else -> 0xFFE0E0E0.toInt()
    }

    /** The red note for a mutation that does not fit the unlocked squares, in the longest wording that fits [avail] pixels. */
    private fun fitLabel(state: Int, avail: Int): String {
        if (state == GreenhouseFit.FITS || state == GreenhouseFit.UNKNOWN) return ""
        val options = if (state == GreenhouseFit.NO_FIT) arrayOf("does not fit", "no fit") else arrayOf("needs $state more squares", "needs $state more", "+$state sq")
        for (o in options) if (font.width(o) <= avail) return o
        return ""
    }

    private fun summaryText(total: Int, shortage: Map<String, Int>, anyKnown: Boolean, sacksRead: Boolean): String {
        if (total <= 0) return "§7Nothing left to make: every mutation is analysed."
        val head = "§fThe rest need §e${formatCount(total)} §fin all"
        if (shortage.isEmpty()) return head + if (anyKnown) "§a, you hold all of it" else if (sacksRead) "" else "§7, open your sacks to compare"
        val worst = shortage.entries.sortedByDescending { it.value }
        val parts = worst.take(3).joinToString(", ") { "${it.key} ${formatCount(it.value)}" }
        return "$head§f, short: §c$parts" + if (worst.size > 3) "§7, +${worst.size - 3} more" else ""
    }

    private fun formatCount(n: Int) = if (n >= 100_000) formatAmount(n.toLong()) else n.toString()

    private fun ellipsize(text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        var cut = text.length
        while (cut > 0 && font.width(text.substring(0, cut) + "...") > maxWidth) cut--
        if (cut > 0 && text[cut - 1] == '§') cut--
        return text.substring(0, cut) + "..."
    }

    private fun wrap(text: String, maxWidth: Int): List<String> {
        val lines = ArrayList<String>(2)
        val color = if (text.startsWith("§")) text.substring(0, 2) else ""
        var current = ""
        for (word in text.split(' ')) {
            val next = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && font.width(next) > maxWidth) {
                lines += current
                current = color + word
            } else current = next
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private fun rowCount(v: Int) = when (v) {
        GreenhouseView.UNIQUE_MUTATIONS.ordinal -> unique.size
        GreenhouseView.ROSE_DRAGON.ordinal -> tree.size
        GreenhouseView.PLANNER.ordinal -> plannerRows.size
        else -> all.size
    }

    private fun maxScroll(v: Int) = (rowCount(v) - rowsVisible).coerceAtLeast(0)

    /** The side length in squares of [id]: 2 or 3 for the big mutations and crops, else 1. */
    private fun sizeOf(id: String): Int {
        val data = Greenhouse.data
        return (data.mutation(id)?.size ?: data.crops.firstOrNull { it.id == id }?.size ?: 1).coerceIn(1, 3)
    }

    /** Works out the blocks and legend of [shown] once, so drawing only loops over plain arrays. */
    private fun makePicture(shown: GhLayout, highlight: String?): Picture {
        val n = shown.size
        val seen = BooleanArray(n * n)
        val rows = ArrayList<Int>()
        val cols = ArrayList<Int>()
        val spans = ArrayList<Int>()
        val ids = ArrayList<String>()
        val legend = LinkedHashSet<String>()
        for (r in 0 until n) for (c in 0 until n) {
            val id = shown.cells[r][c] ?: continue
            if (seen[r * n + c]) continue
            legend += id
            var span = sizeOf(id)
            if (span > 1) {
                var whole = r + span <= n && c + span <= n
                if (whole) for (dr in 0 until span) for (dc in 0 until span) if (shown.cells[r + dr][c + dc] != id || seen[(r + dr) * n + c + dc]) whole = false
                if (!whole) span = 1
            }
            for (dr in 0 until span) for (dc in 0 until span) seen[(r + dr) * n + c + dc] = true
            rows += r
            cols += c
            spans += span
            ids += id
        }
        // The part of the grid worth showing when space is tight: every block plus one square around it.
        var r0 = n
        var r1 = -1
        var c0 = n
        var c1 = -1
        for (b in ids.indices) {
            r0 = minOf(r0, rows[b])
            r1 = maxOf(r1, rows[b] + spans[b] - 1)
            c0 = minOf(c0, cols[b])
            c1 = maxOf(c1, cols[b] + spans[b] - 1)
        }
        if (r1 < 0) { r0 = 0; r1 = n - 1; c0 = 0; c1 = n - 1 }
        val legendIds = legend.toTypedArray()
        val names = Array(legendIds.size) { Greenhouse.data.nameOf(legendIds[it]) }
        return Picture(shown, highlight, rows.toIntArray(), cols.toIntArray(), spans.toIntArray(), ids.toTypedArray(), legendIds, names, IntArray(names.size) { font.width(names[it]) }, (r0 - 1).coerceAtLeast(0), (r1 + 1).coerceAtMost(n - 1), (c0 - 1).coerceAtLeast(0), (c1 + 1).coerceAtMost(n - 1))
    }

    // Drawing

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) =
        site.screen(this) { render(graphics, mouseX, mouseY, partialTick) }

    private fun render(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        iconCount = 0
        gridPicture = null
        drawPanel(graphics, left, top, panelWidth, panelHeight)
        graphics.text(font, "Greenhouse", left + 8, top + 9, TITLE_COLOR, false)
        val half = (panelWidth - 16 - 8) / 2
        drawBar(graphics, left + 8, barsY, half, roseFraction, ROSE_BAR, roseLine)
        drawBar(graphics, left + 8 + half + 8, barsY, half, foundFraction, ACCENT, foundLine)
        if (plotsOpen) drawPlots(graphics, mouseX, mouseY)
        else when (view) {
            GreenhouseView.UNIQUE_MUTATIONS -> drawUnique(graphics, mouseX, mouseY)
            GreenhouseView.ROSE_DRAGON -> drawRose(graphics, mouseX, mouseY)
            GreenhouseView.ALL_MUTATIONS -> drawAll(graphics, mouseX, mouseY)
            GreenhouseView.PLANNER -> drawPlanner(graphics, mouseX, mouseY)
        }
        if (!plotsOpen && view == GreenhouseView.PLANNER && !maxRunning && maxButton.contains(mouseX, mouseY)) drawTextTip(graphics, GhMax.MAX_TIP, mouseX, mouseY)
        for ((i, line) in disclaimer.withIndex()) graphics.text(font, line, left + 8, footerY - 3 - (disclaimer.size - i) * 9 + 1, WHITE, false)
        graphics.text(font, footer, left + 8, footerY, WHITE, false)
        if (loading) {
            val text = "§eLoading Greenhouse data..."
            graphics.text(font, text, left + panelWidth - 8 - font.width(text), footerY, WHITE, false)
        }
        drawButton(graphics, plotsButton, "Plots", mouseX, mouseY, plotsOpen)
        drawGridName(graphics, mouseX, mouseY)
        drawDropdown(graphics, mouseX, mouseY)
        iconsLastFrame = iconCount
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    private val dropdownX get() = left + 8 + font.width("Greenhouse") + 12
    private val dropdownWidth get() = 112

    private fun icon(graphics: GuiGraphicsExtractor, id: String, x: Int, y: Int, size: Int) {
        iconCount++
        GhIcons.draw(graphics, id, x, y, size)
    }

    private fun drawBar(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, fraction: Float, color: Int, text: String) {
        graphics.fill(x, y, x + w, y + barH, SLOT_BORDER)
        graphics.fill(x + 1, y + 1, x + w - 1, y + barH - 1, SLOT_BACKGROUND)
        val filled = ((w - 2) * fraction.coerceIn(0f, 1f)).toInt()
        if (filled > 0) graphics.fill(x + 1, y + 1, x + 1 + filled, y + barH - 1, color)
        // A thin fill must not run through the first letters: start the text just past it.
        val textX = if (filled in 1 until font.width(text) + 10) x + 1 + filled + 4 else x + 5
        graphics.text(font, text, textX, y + (barH - 8) / 2 + 1, WHITE, true)
    }

    private fun drawButton(graphics: GuiGraphicsExtractor, r: Rect, label: String, mouseX: Int, mouseY: Int, active: Boolean = false) {
        graphics.fill(r.x, r.y, r.x + r.w, r.y + r.h, if (active) ACCENT else SLOT_BORDER)
        graphics.fill(r.x + 1, r.y + 1, r.x + r.w - 1, r.y + r.h - 1, if (r.contains(mouseX, mouseY)) PANEL_LIGHT else SLOT_BACKGROUND)
        graphics.text(font, label, r.x + (r.w - font.width(label)) / 2, r.y + (r.h - 8) / 2 + 1, WHITE, false)
    }

    private fun drawMaxButton(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val r = maxButton
        val hover = !maxRunning && r.contains(mouseX, mouseY)
        graphics.fill(r.x, r.y, r.x + r.w, r.y + r.h, if (maxRunning) SLOT_BORDER else ACCENT)
        graphics.fill(r.x + 1, r.y + 1, r.x + r.w - 1, r.y + r.h - 1, if (hover) PANEL_LIGHT else SLOT_BACKGROUND)
        val label = if (maxRunning) "Working..." else "Max"
        graphics.text(font, label, r.x + (r.w - font.width(label)) / 2, r.y + (r.h - 8) / 2 + 1, if (maxRunning) TITLE_COLOR else WHITE, false)
    }

    private fun drawNumberBox(graphics: GuiGraphicsExtractor, x: Int, y: Int, text: String, focused: Boolean, dim: Boolean, problem: Boolean = false) {
        graphics.fill(x, y, x + BOX_W, y + BOX_H, if (focused) ACCENT else if (problem) BAD else SLOT_BORDER)
        graphics.fill(x + 1, y + 1, x + BOX_W - 1, y + BOX_H - 1, SLOT_BACKGROUND)
        val shown = if (focused && ticks / 10 % 2 == 0) "$text|" else text
        graphics.text(font, shown, x + BOX_W - 3 - font.width(shown), y + (BOX_H - 8) / 2 + 1, if (dim) DIMMED_TEXT else WHITE, false)
    }

    /** A small square - or + button next to an amount box. */
    private fun drawStepper(graphics: GuiGraphicsExtractor, x: Int, y: Int, label: String, mouseX: Int, mouseY: Int, greyed: Boolean = false) {
        val over = !greyed && mouseX in x until x + STEP_W && mouseY in y until y + BOX_H
        graphics.fill(x, y, x + STEP_W, y + BOX_H, SLOT_BORDER)
        graphics.fill(x + 1, y + 1, x + STEP_W - 1, y + BOX_H - 1, if (over) PANEL_LIGHT else SLOT_BACKGROUND)
        graphics.text(font, label, x + (STEP_W - font.width(label)) / 2, y + (BOX_H - 8) / 2 + 1, if (greyed) DIMMED_TEXT else WHITE, false)
    }

    private fun drawDropdown(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val x = dropdownX
        val y = top + 5
        val w = dropdownWidth
        graphics.fill(x, y, x + w, y + DD_H, if (dropdownOpen) ACCENT else SLOT_BORDER)
        graphics.fill(x + 1, y + 1, x + w - 1, y + DD_H - 1, SLOT_BACKGROUND)
        graphics.text(font, view.toString(), x + 5, y + 4, WHITE, false)
        graphics.text(font, if (dropdownOpen) "^" else "v", x + w - 10, y + 4, TITLE_COLOR, false)
        if (!dropdownOpen) return
        val entries = GreenhouseView.entries
        graphics.fill(x, y + DD_H, x + w, y + DD_H + entries.size * DD_H + 1, SLOT_BORDER)
        for ((i, entry) in entries.withIndex()) {
            val oy = y + DD_H + i * DD_H
            val hover = mouseX in x until x + w && mouseY in oy until oy + DD_H
            graphics.fill(x + 1, oy, x + w - 1, oy + DD_H, if (hover) PANEL_LIGHT else SLOT_BACKGROUND)
            graphics.text(font, entry.toString(), x + 5, oy + 4, if (entry == view) ACCENT else WHITE, false)
        }
    }

    private fun drawScrollbar(graphics: GuiGraphicsExtractor, v: Int, x: Int) {
        val total = rowCount(v)
        val visible = rowsVisible
        if (total <= visible) return
        val h = listBottom - listTop
        graphics.fill(x, listTop, x + 3, listBottom, SLOT_BACKGROUND)
        val thumb = (h * visible / total).coerceAtLeast(8)
        val y = listTop + (h - thumb) * scroll[v] / maxScroll(v)
        graphics.fill(x, y, x + 3, y + thumb, ACCENT)
    }

    /** A row card: a subtle background, lighter under the mouse, blue with an edge bar when [marked]. */
    private fun drawCard(graphics: GuiGraphicsExtractor, x: Int, y: Int, right: Int, hover: Boolean, marked: Boolean, dark: Boolean = false) {
        graphics.fill(x, y, right, y + rowH - 1, if (marked) NEXT_BACKGROUND else if (hover) PANEL_LIGHT else if (dark) CARD_DARK else CARD)
        if (marked) graphics.fill(x, y, x + 2, y + rowH - 1, ACCENT)
    }

    private fun drawUnique(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        graphics.text(font, headerA, left + 8, linesY + 1, WHITE, false)
        // Second line: what the rest of the list needs in all.
        graphics.text(font, headerSummary, left + 8, linesY + 13, WHITE, false)
        val right = listRight - 8
        val first = scroll[GreenhouseView.UNIQUE_MUTATIONS.ordinal]
        val labelX = listLeft + 17 + iconSize + 4
        for (i in 0 until rowsVisible) {
            val row = unique.getOrNull(first + i) ?: break
            val y = listTop + i * rowH
            val hover = mouseX in listLeft until right && mouseY in y until y + rowH
            drawCard(graphics, listLeft, y, right, hover, row.next)
            drawCheckbox(graphics, listLeft + 5, y + (rowH - 9) / 2, row.analysed)
            icon(graphics, row.id, listLeft + 17, y + (rowH - 1 - iconSize) / 2, iconSize)
            val ty = y + (rowH - 8) / 2
            graphics.text(font, row.label, labelX, ty, if (row.analysed) DIMMED_TEXT else WHITE, false)
            if (row.next) graphics.text(font, "next up", labelX + row.labelWidth + 6, ty, ACCENT, false)
            val dot = when (row.ingredients) {
                2 -> GOOD
                1 -> BAD
                else -> UNKNOWN
            }
            graphics.fill(right - 9, y + (rowH - 7) / 2, right - 3, y + (rowH - 7) / 2 + 6, dot)
            if (row.fitText.isNotEmpty() && !row.analysed) graphics.text(font, row.fitText, right - 12 - row.fitWidth, ty, BAD_TEXT, false)
        }
        drawScrollbar(graphics, GreenhouseView.UNIQUE_MUTATIONS.ordinal, listRight - 4)
        if (!loading && unique.isEmpty()) graphics.text(font, "§7No mutation data.", listLeft, listTop + 2, WHITE, false)
    }

    /** A flat text button for the compact Planner: no box, brighter under the mouse. */
    private fun drawFlatButton(graphics: GuiGraphicsExtractor, r: Rect, label: String, mouseX: Int, mouseY: Int, color: Int) {
        val over = r.contains(mouseX, mouseY)
        graphics.text(font, label, r.x + (r.w - font.width(label)) / 2, r.y + (r.h - 8) / 2 + 1, if (over) WHITE else color, false)
        if (over) graphics.fill(r.x + 3, r.y + r.h - 2, r.x + r.w - 3, r.y + r.h - 1, color)
    }

    /** The Planner list in the compact style: flat rows, a small coloured soil tag after each name, a plain amount and a tooltip on hover. */
    private fun drawPlannerCompact(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val busy = maxRunning
        drawFlatButton(graphics, maxButton, if (busy) "Working..." else "Max", mouseX, mouseY, if (busy) DIMMED_TEXT else ACCENT)
        val one = oneRect
        drawCheckbox(graphics, one.x, one.y + (one.h - 9) / 2, saved.planOneOfEach)
        graphics.text(font, "One of each", one.x + 13, one.y + (one.h - 8) / 2 + 1, if (one.contains(mouseX, mouseY)) WHITE else TITLE_COLOR, false)
        drawFlatButton(graphics, clearButton, "Clear", mouseX, mouseY, TITLE_COLOR)
        if (plannerHint.isNotEmpty()) graphics.text(font, plannerHint, clearButton.x + clearButton.w + 10, linesY + 4, DIMMED_TEXT, false)
        val right = listRight - 8
        val first = scroll[GreenhouseView.PLANNER.ordinal]
        val labelX = listLeft + 4 + iconSize + 4
        var tip: PlannerRow? = null
        for (i in 0 until rowsVisible) {
            val row = plannerRows.getOrNull(first + i) ?: break
            val y = listTop + i * rowH
            val hover = mouseX in listLeft until right && mouseY in y until y + rowH
            if (hover) {
                graphics.fill(listLeft, y, right, y + rowH - 1, PANEL_LIGHT)
                if (mouseX < plannerMaxX - 2) tip = row
            }
            if (row.amount > 0) graphics.fill(listLeft, y, listLeft + 1, y + rowH - 1, ACCENT)
            icon(graphics, row.id, listLeft + 4, y + (rowH - 1 - iconSize) / 2, iconSize)
            val ty = y + (rowH - 8) / 2
            graphics.text(font, row.label, labelX, ty, WHITE, false)
            var nx = labelX + row.labelWidth + 5
            val soil = soilOf(row.id)
            if (soil.isNotEmpty() && nx + TAG_W <= plannerMaxX - 2) {
                val tagY = y + (rowH - 1 - TAG_H) / 2
                graphics.fill(nx, tagY, nx + TAG_W, tagY + TAG_H, GhSoil.EDGE)
                graphics.fill(nx + 1, tagY + 1, nx + TAG_W - 1, tagY + TAG_H - 1, GhSoil.color(soil))
                val letter = GhSoil.letter(soil)
                graphics.text(font, letter, nx + (TAG_W - font.width(letter)) / 2, ty, GhSoil.letterColor(soil), false)
                nx += TAG_W + 4
            }
            val capped = row.atCap && row.amount > 0
            if (row.analysed) {
                if (!capped && nx + FOUND_W <= plannerMaxX - 4) {
                    graphics.text(font, "found", nx, ty, GOOD, false)
                } else if (nx + 5 <= plannerMaxX - 2) {
                    graphics.fill(nx, y + (rowH - 5) / 2, nx + 5, y + (rowH - 5) / 2 + 4, GOOD)
                    nx += 9
                }
            }
            if (capped) {
                if (nx + capMarkW <= plannerMaxX - 3) graphics.text(font, "at max", nx, ty, CAP_COLOR, false)
                else if (nx + 5 <= plannerMaxX - 2) graphics.fill(nx, y + (rowH - 5) / 2, nx + 5, y + (rowH - 5) / 2 + 4, CAP_COLOR)
            }
            // Same hit areas as the classic style, drawn as plain text.
            val by = y + (rowH - 1 - BOX_H) / 2
            val textY = by + (BOX_H - 8) / 2 + 1
            val overMax = !busy && mouseX in plannerMaxX until plannerMaxX + plannerMaxW && mouseY in by until by + BOX_H
            val maxLabel = if (row.cap >= 0) "max ${row.cap}" else "max"
            graphics.text(font, maxLabel, plannerMaxX + plannerMaxW - 3 - font.width(maxLabel), textY, if (busy) DIMMED_TEXT else if (overMax) WHITE else CAP_COLOR, false)
            val focused = focus == FOCUS_ROW && focusId == row.id
            fun glyph(x: Int, text: String, greyed: Boolean) {
                val over = !greyed && mouseX in x until x + STEP_W && mouseY in by until by + BOX_H
                graphics.text(font, text, x + (STEP_W - font.width(text)) / 2, textY, if (greyed) UNKNOWN else if (over) WHITE else TITLE_COLOR, false)
            }
            glyph(plannerMinusX, "-", row.amount == 0)
            val shown = if (focused) focusText.let { if (ticks / 10 % 2 == 0) "$it|" else it } else row.amountText
            val color = if (row.problem) BAD_TEXT else if (row.amount == 0 && !focused) DIMMED_TEXT else WHITE
            graphics.text(font, shown, plannerBoxX + BOX_W - 3 - font.width(shown), textY, color, false)
            if (focused) graphics.fill(plannerBoxX + 2, by + BOX_H, plannerBoxX + BOX_W - 1, by + BOX_H + 1, ACCENT)
            glyph(plannerPlusX, "+", row.atCap)
        }
        drawScrollbar(graphics, GreenhouseView.PLANNER.ordinal, listRight - 4)
        if (!loading && plannerRows.isEmpty()) graphics.text(font, "§7No mutation data.", listLeft, listTop + 2, WHITE, false)
        drawPlannerSide(graphics, mouseX, mouseY)
        keyBottomDrawn = drawSoilChips(graphics, plannerRows.map { soilOf(it.id) }.filter { it.isNotEmpty() }.distinct(), listLeft + 4, listBottom + 1, right - listLeft - 4)
        tip?.let { drawRowTip(graphics, it, mouseX, mouseY) }
    }

    /** A small tooltip box with [text] wrapped to fit, below the mouse. */
    private fun drawTextTip(graphics: GuiGraphicsExtractor, text: String, mouseX: Int, mouseY: Int) {
        val lines = wrap("§7$text", 170)
        val w = lines.maxOf { font.width(it) }
        val h = lines.size * 10
        val x = (mouseX + 6).coerceAtMost(left + panelWidth - w - 10).coerceAtLeast(left + 2)
        val y = (mouseY + 16).coerceAtMost(top + panelHeight - h - 4)
        graphics.fill(x - 4, y - 3, x + w + 4, y + h + 1, ACCENT)
        graphics.fill(x - 3, y - 2, x + w + 3, y + h, SLOT_BACKGROUND)
        for ((i, line) in lines.withIndex()) graphics.text(font, line, x, y + i * 10, WHITE, false)
    }

    /** The tooltip for a compact Planner row: facts, soil and what must grow next to it. */
    private fun drawRowTip(graphics: GuiGraphicsExtractor, row: PlannerRow, mouseX: Int, mouseY: Int) {
        val data = Greenhouse.data
        val m = data.mutation(row.id) ?: return
        val lines = ArrayList<String>()
        lines.add(row.label)
        lines.add("§7Soil §f${prettify(m.soil)}§7, size §f${m.size}x${m.size}")
        for (r in m.requirements) {
            val name = data.nameOf(r.crop)
            val have = Greenhouse.stock.count(name)
            // What the player holds, red when short; "?" until a sack menu has been read.
            val held = if (have == null) "§8have ?" else if (have < r.count) "§chave ${formatCount(have)}" else "§ahave ${formatCount(have)}"
            lines.add("§7${r.count}x §f$name §8- $held")
        }
        if (row.atCap && row.amount > 0) lines.add("§6No more fit alongside the other rows")
        else if (row.cap >= 0) lines.add("§7Up to §f${row.cap}§7 fit")
        if (row.analysed) lines.add("§aAlready analysed")
        val w = lines.maxOf { font.width(it) }
        val h = lines.size * 10
        val x = (mouseX + 10).coerceAtMost(left + panelWidth - w - 10).coerceAtLeast(left + 2)
        // Above the row when that stays inside the list (so the Max / One of each / Clear controls stay visible), else below it.
        val rowTop = mouseY - (mouseY - listTop) % rowH
        val above = rowTop - h - 6
        val y = if (above >= listTop) above else (rowTop + rowH + 5).coerceAtMost(top + panelHeight - h - 4)
        graphics.fill(x - 4, y - 3, x + w + 4, y + h + 1, ACCENT)
        graphics.fill(x - 3, y - 2, x + w + 3, y + h, SLOT_BACKGROUND)
        for ((i, line) in lines.withIndex()) graphics.text(font, line, x, y + i * 10, WHITE, false)
    }

    private fun drawPlanner(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (config.plannerStyle == PlannerStyle.COMPACT) return drawPlannerCompact(graphics, mouseX, mouseY)
        // Controls row: the main Max button, the "One of each" switch, Clear and a hint.
        drawMaxButton(graphics, mouseX, mouseY)
        val one = oneRect
        drawCheckbox(graphics, one.x, one.y + (one.h - 9) / 2, saved.planOneOfEach)
        graphics.text(font, "One of each", one.x + 13, one.y + (one.h - 8) / 2 + 1, if (one.contains(mouseX, mouseY)) WHITE else TITLE_COLOR, false)
        drawButton(graphics, clearButton, "Clear", mouseX, mouseY)
        if (plannerHint.isNotEmpty()) graphics.text(font, plannerHint, clearButton.x + clearButton.w + 10, linesY + 4, WHITE, false)
        val right = listRight - 8
        val first = scroll[GreenhouseView.PLANNER.ordinal]
        val labelX = listLeft + 4 + iconSize + 4
        for (i in 0 until rowsVisible) {
            val row = plannerRows.getOrNull(first + i) ?: break
            val y = listTop + i * rowH
            val hover = mouseX in listLeft until right && mouseY in y until y + rowH
            drawCard(graphics, listLeft, y, right, hover, row.amount > 0)
            icon(graphics, row.id, listLeft + 4, y + (rowH - 1 - iconSize) / 2, iconSize)
            val ty = y + (rowH - 8) / 2
            graphics.text(font, row.label, labelX, ty, WHITE, false)
            // "found" is worded out only when the row has room; a capped row shows it as a pip so the "at max" marker fits.
            val capped = row.atCap && row.amount > 0
            var nx = labelX + row.labelWidth + 6
            if (row.analysed) {
                if (!capped && nx + FOUND_W <= plannerMaxX - 4) {
                    graphics.text(font, "found", nx, ty, GOOD, false)
                } else if (nx + 5 <= plannerMaxX - 2) {
                    graphics.fill(nx, y + (rowH - 5) / 2, nx + 5, y + (rowH - 5) / 2 + 4, GOOD)
                    nx += 9
                }
            }
            // At the cap: no more fit alongside the other rows (a pip when there is no room for the words).
            if (capped) {
                if (nx + capMarkW <= plannerMaxX - 3) graphics.text(font, "at max", nx, ty, CAP_COLOR, false)
                else if (nx + 5 <= plannerMaxX - 2) graphics.fill(nx, y + (rowH - 5) / 2, nx + 5, y + (rowH - 5) / 2 + 4, CAP_COLOR)
            }
            // The row's own max button, then its amount box (red edge when part of it did not fit).
            val by = y + (rowH - 1 - BOX_H) / 2
            val busy = maxRunning
            val over = !busy && mouseX in plannerMaxX until plannerMaxX + plannerMaxW && mouseY in by until by + BOX_H
            graphics.fill(plannerMaxX, by, plannerMaxX + plannerMaxW, by + BOX_H, if (busy) SLOT_BORDER else ACCENT)
            graphics.fill(plannerMaxX + 1, by + 1, plannerMaxX + plannerMaxW - 1, by + BOX_H - 1, if (over) PANEL_LIGHT else SLOT_BACKGROUND)
            graphics.text(font, "max", plannerMaxX + 3, by + (BOX_H - 8) / 2 + 1, if (busy) DIMMED_TEXT else WHITE, false)
            val focused = focus == FOCUS_ROW && focusId == row.id
            drawStepper(graphics, plannerMinusX, by, "-", mouseX, mouseY)
            drawNumberBox(graphics, plannerBoxX, by, if (focused) focusText else row.amountText, focused, row.amount == 0, row.problem)
            drawStepper(graphics, plannerPlusX, by, "+", mouseX, mouseY, row.atCap)
        }
        drawScrollbar(graphics, GreenhouseView.PLANNER.ordinal, listRight - 4)
        if (!loading && plannerRows.isEmpty()) graphics.text(font, "§7No mutation data.", listLeft, listTop + 2, WHITE, false)
        drawPlannerSide(graphics, mouseX, mouseY)
    }

    /** The Planner's side panel: summary, the shared layout, what did not fit and the crops needed. Scrolls with the wheel when it is taller than the window. */
    private fun drawPlannerSide(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val s = plannerSide
        val x = sideLeft
        val w = sideWidth
        graphics.fill(x - 4, listTop - 2, x - 3, listBottom, SLOT_BORDER)
        graphics.text(font, s.title, x, listTop + 3, WHITE, false)
        val top = listTop + 16
        if (skyError != null && !s.busy) drawButton(graphics, retryButton, "Retry", mouseX, mouseY)
        if (s.busy) {
            // Progress such as "SkyShards: queued (position 12)..." wraps instead of running out of the panel.
            for ((i, line) in wrap("§7${s.busyText}", w - 6).withIndex()) graphics.text(font, line, x, top + i * 9, WHITE, false)
            return
        }
        val textW = w - 6
        graphics.enableScissor(x - 2, top, x + w + 2, listBottom)
        var y = top - sideScroll
        for (line in s.lines) {
            graphics.text(font, line, x, y, WHITE, false)
            y += 9
        }
        val shown = s.picture
        if (shown != null) {
            val pictureTop = y + 3
            // Never ask for less than the smallest grid (6px cells) plus the soil key, so the legend is not left half outside the panel.
            val minH = 6 * shown.layout.size + soilKeyHeight(shown, textW) + 4
            y = drawPicture(graphics, shown, x, pictureTop, textW, (listBottom - pictureTop - 2).coerceAtLeast(minH), emptyList()) + 4
            // A saved plan (SkyShards is down) is dimmed.
            if (skyError != null) graphics.fill(x - 1, pictureTop - 1, x + textW + 1, y - 3, 0xB0202020.toInt())
        }
        for (row in s.rows) {
            if (row.heading) {
                graphics.text(font, row.text, x, y, WHITE, false)
            } else {
                if (row.icon != null) icon(graphics, row.icon, x, y - 1, 10)
                graphics.text(font, row.text, x + 13, y, WHITE, false)
                if (row.counts.isNotEmpty()) graphics.text(font, row.counts, x + textW - row.countWidth, y, row.countColor, false)
            }
            y += 11
        }
        sideContentH = y + sideScroll - top
        graphics.disableScissor()
        val visible = listBottom - top
        if (sideContentH > visible) {
            val thumb = (visible * visible / sideContentH).coerceAtLeast(8)
            val range = sideContentH - visible
            graphics.fill(x + w - 2, top, x + w + 1, listBottom, SLOT_BACKGROUND)
            graphics.fill(x + w - 2, top + (visible - thumb) * sideScroll / range, x + w + 1, top + (visible - thumb) * sideScroll / range + thumb, ACCENT)
        }
    }

    private fun drawCheckbox(graphics: GuiGraphicsExtractor, x: Int, y: Int, checked: Boolean) {
        graphics.fill(x, y, x + 9, y + 9, SLOT_BORDER)
        graphics.fill(x + 1, y + 1, x + 8, y + 8, SLOT_BACKGROUND)
        if (checked) graphics.fill(x + 2, y + 2, x + 7, y + 7, ACCENT)
    }

    private fun drawRose(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        graphics.text(font, hint, left + 8, linesY + 1, WHITE, false)
        val right = listRight - 8
        val first = scroll[GreenhouseView.ROSE_DRAGON.ordinal]
        for (i in 0 until rowsVisible) {
            val line = tree.getOrNull(first + i) ?: break
            val row = line.row
            val y = listTop + i * rowH
            val pill = line.pill
            if (pill != null) {
                drawPill(graphics, line, pill, y, mouseX, mouseY)
                continue
            }
            val hover = mouseX in listLeft until right && mouseY in y until y + rowH
            drawCard(graphics, listLeft, y, right, hover, row.path == selPath, row.depth == 0)
            val color = if (line.covered) GOOD_TEXT else BAD_TEXT
            val ax = listLeft + 4 + row.depth * 10
            val ty = y + (rowH - 8) / 2
            if (row.expandable) graphics.text(font, if (row.expanded) "v" else ">", ax + 1, ty, ACCENT, false)
            icon(graphics, row.icon, ax + 12, y + (rowH - 1 - iconSize) / 2, iconSize)
            graphics.text(font, line.label, ax + 12 + iconSize + 3, ty, if (row.depth == 0) WHITE else TITLE_COLOR, false)
            graphics.text(font, line.counts, right - 4 - line.countWidth, ty, color, false)
            if (line.fitText.isNotEmpty()) graphics.text(font, line.fitText, right - 4 - line.countWidth - 8 - line.fitWidth, ty, BAD_TEXT, false)
        }
        drawScrollbar(graphics, GreenhouseView.ROSE_DRAGON.ordinal, listRight - 4)
        if (loading) graphics.text(font, "§7Mutation requirements appear once the data loads.", listLeft, listBottom - 10, WHITE, false)
        if (sideOpen) drawSide(graphics, rose, mouseX, mouseY)
    }

    /** A rounded box: cut corners, [fill] in the middle, one pixel of [outline] around it. */
    private fun roundBox(graphics: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, outline: Int, fill: Int) {
        // Full capsule: the ends are half circles as tall as the pill.
        fun shape(a: Int, b: Int, c: Int, d: Int, color: Int) {
            val r = (d - b) / 2.0
            for (row in b until d) {
                val dy = row + 0.5 - (b + r)
                val inset = Math.round(r - Math.sqrt((r * r - dy * dy).coerceAtLeast(0.0))).toInt()
                graphics.fill(a + inset, row, c - inset, row + 1, color)
            }
        }
        shape(x0, y0, x1, y1, outline)
        shape(x0 + 1, y0 + 1, x1 - 1, y1 - 1, fill)
    }

    /** One pill of the Rose Dragon tree: connector lines, then count badge, icon, name, ingredients, held/needed and the -/+ button. */
    private fun drawPill(graphics: GuiGraphicsExtractor, line: TreeLine, pill: Pill, y: Int, mouseX: Int, mouseY: Int) {
        val row = line.row
        val ph = rowH - 1
        val mid = y + ph / 2
        // Column c's line sits under the left end of the pill one level up.
        for (c in 1..row.depth.coerceAtMost(MAX_PILL_DEPTH)) {
            val lx = listLeft + 4 + (c - 1) * PILL_INDENT + 6
            if (pill.guides[c]) graphics.fill(lx, y, lx + 1, y + rowH, GRID_LINE)
            else if (c == row.depth) graphics.fill(lx, y, lx + 1, mid + 1, GRID_LINE)
            if (c == row.depth) graphics.fill(lx, mid, pill.x0, mid + 1, GRID_LINE)
        }
        val hover = mouseX in pill.x0 until pill.x1 && mouseY in y until y + ph
        roundBox(graphics, pill.x0, y, pill.x1, y + ph, pill.outline, if (row.path == selPath) NEXT_BACKGROUND else if (hover) PANEL_LIGHT else CARD)
        val ty = y + (ph - 8) / 2 + 1
        var x = pill.x0 + 3
        // Dark box with a thin dark-blue border and clipped corners.
        graphics.fill(x, y + 2, x + pill.badgeWidth, y + ph - 2, BADGE_BORDER)
        graphics.fill(x + 1, y + 3, x + pill.badgeWidth - 1, y + ph - 3, SLOT_BACKGROUND)
        for (cx in intArrayOf(x, x + pill.badgeWidth - 1)) for (cy in intArrayOf(y + 2, y + ph - 3)) graphics.fill(cx, cy, cx + 1, cy + 1, CARD)
        val textColor = if (row.greyed) DIMMED_TEXT else WHITE
        graphics.text(font, pill.badge, x + 3, ty, textColor, false)
        x += pill.badgeWidth + 3
        icon(graphics, row.icon, x, y + 1, ph - 2)
        x += ph - 2 + 3
        graphics.text(font, pill.name, x, ty, textColor, false)
        x += font.width(pill.name) + 6
        if (pill.crops.isNotEmpty()) {
            graphics.text(font, pill.crops, x, ty, WHITE, false)
            x += pill.cropsWidth + 6
        }
        if (pill.summary.isNotEmpty()) {
            graphics.text(font, pill.summary, x, ty, textColor, false)
            x += font.width(pill.summary) + 6
        }
        if (line.fitText.isNotEmpty()) {
            graphics.text(font, line.fitText, x, ty, BAD_TEXT, false)
            x += line.fitWidth + 6
        }
        graphics.text(font, line.counts, x, ty, if (row.greyed) DIMMED_TEXT else if (line.covered) GOOD_TEXT else BAD_TEXT, false)
        if (row.expandable) graphics.text(font, if (row.expanded) "-" else "+", pill.x1 - 4 - font.width("+"), ty, if (hover && mouseX >= pill.x1 - 8 - font.width("+")) WHITE else TITLE_COLOR, false)
    }

    /** The side panel: a title with its icon, then the layout (or a note), the legend and small extra lines. */
    private fun drawSide(graphics: GuiGraphicsExtractor, s: Side, mouseX: Int, mouseY: Int) {
        val x = sideLeft
        val w = sideWidth
        graphics.fill(x - 4, listTop - 2, x - 3, listBottom, SLOT_BORDER)
        graphics.text(font, "x", x + w - 8, listTop + 3, TITLE_COLOR, false)
        var y = listTop
        val sideIcon = s.icon
        if (sideIcon != null) {
            icon(graphics, sideIcon, x, y, 14)
            graphics.text(font, s.title, x + 18, y + 3, WHITE, false)
        } else {
            graphics.text(font, s.title, x, y + 3, WHITE, false)
        }
        y += 18
        if (s.busy) {
            graphics.text(font, "§7${s.busyText}", x, y, WHITE, false)
            return
        }
        for (line in s.lines) {
            graphics.text(font, line, x, y, WHITE, false)
            y += 9
        }
        val shown = s.picture ?: return
        drawPicture(graphics, shown, x, y + 3, w, listBottom - y - 3, s.extra)
    }

    private fun drawAll(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val v = GreenhouseView.ALL_MUTATIONS.ordinal
        val first = scroll[v]
        for (i in 0 until rowsVisible) {
            val index = first + i
            if (index >= all.size) break
            val y = listTop + i * rowH
            val hover = mouseX in listLeft until listRight && mouseY in y until y + rowH
            drawCard(graphics, listLeft, y, listRight - 5, hover, index == selected)
            icon(graphics, all[index].id, listLeft + 4, y + (rowH - 1 - iconSize) / 2, iconSize)
            graphics.text(font, allLabels[index], listLeft + 4 + iconSize + 4, y + (rowH - 8) / 2, WHITE, false)
        }
        drawScrollbar(graphics, v, listRight - 4)

        val mutation = all.getOrNull(selected) ?: return
        var dy = listTop
        icon(graphics, mutation.id, detailLeft, dy, 16)
        graphics.text(font, ellipsize(allLabels[selected], detailWidth - 22), detailLeft + 22, dy + 4, WHITE, false)
        dy += 22
        for (line in details) {
            if (line.icon != null) {
                icon(graphics, line.icon, detailLeft, dy - 1, 10)
                graphics.text(font, line.text, detailLeft + 13, dy, WHITE, false)
            } else graphics.text(font, line.text, detailLeft, dy, WHITE, false)
            dy += 11
        }
        val bx = gridLeft
        val bw = font.width("Plan layout") + 14
        val hover = !planning && mouseX in bx until bx + bw && mouseY in listTop until listTop + BUTTON_H
        graphics.fill(bx, listTop, bx + bw, listTop + BUTTON_H, if (planning) SLOT_BORDER else ACCENT)
        graphics.fill(bx + 1, listTop + 1, bx + bw - 1, listTop + BUTTON_H - 1, if (hover) PANEL_LIGHT else SLOT_BACKGROUND)
        graphics.text(font, if (planning) "Planning..." else "Plan layout", bx + 7, listTop + 4, if (planning) TITLE_COLOR else WHITE, false)

        if (planDone) {
            planDone = false
            layout = planResult
            planResult = null
            planFor = mutation.id
            picture = layout?.let { makePicture(it, it.target) }
            // Only a layout counts; a failed plan leaves the overlay on the last good one.
            if (layout != null) GreenhouseOverlay.show(layout)
        }
        val shown = picture
        if (planFor == mutation.id && layout == null && !planning) {
            graphics.text(font, "§c${ellipsize(planError ?: "No layout found", left + panelWidth - 8 - (bx + bw + 8))}", bx + bw + 8, listTop + 4, WHITE, false)
        } else if (shown != null && planFor == mutation.id) {
            val gy = listTop + BUTTON_H + 6
            drawPicture(graphics, shown, bx, gy, left + panelWidth - 8 - bx, listBottom - gy, emptyList())
        }
    }

    /** Height in pixels the legend of [shown] takes in a [maxW] wide box. */
    private fun legendHeight(shown: Picture, maxW: Int): Int {
        var lines = 1
        var x = 0
        for (i in shown.legendIds.indices) {
            val entry = LEGEND_ICON + 3 + shown.legendWidths[i]
            if (x > 0 && x + entry > maxW) {
                lines++
                x = 0
            }
            x += entry + 8
        }
        if (shown.legendIds.isEmpty()) return 0
        return lines * LEGEND_H + soilKeyHeight(shown, maxW)
    }

    /** Height in pixels of the soil key of [shown] in a [maxW] wide box (0 when no soil is known). */
    private fun soilKeyHeight(shown: Picture, maxW: Int): Int {
        val soils = soilsOf(shown)
        if (soils.isEmpty()) return 0
        var soilLines = 1
        var x = 0
        for (soil in soils) {
            val entry = LEGEND_ICON + 3 + font.width(prettify(soil))
            if (x > 0 && x + entry > maxW) {
                soilLines++
                x = 0
            }
            x += entry + 8
        }
        return soilLines * LEGEND_H
    }

    /** The soil a crop or mutation grows on, from the Greenhouse data ("" when unknown). */
    private fun soilOf(id: String): String {
        val data = Greenhouse.data
        return data.mutation(id)?.soil ?: data.crops.firstOrNull { it.id == id }?.soil ?: ""
    }

    /** The distinct soils under the plants of [shown], in order of first appearance. */
    private fun soilsOf(shown: Picture): List<String> = shown.blockId.map { soilOf(it) }.filter { it.isNotEmpty() }.distinct()

    /**
     * Draws [shown] in a box of [maxW] x [maxH] pixels: the grid with square cells as large as fits, then the legend (icon and
     * name) and [extra] lines when there is room. Locked squares are dark, empty unlocked ones lighter, the target is gold.
     */
    private fun drawPicture(graphics: GuiGraphicsExtractor, shown: Picture, x: Int, y: Int, maxW: Int, maxH: Int, extra: List<String>): Int {
        val n = shown.layout.size
        val extraH = if (extra.isEmpty()) 0 else minOf(extra.size, 4) * 9 + 4
        val legendH = legendHeight(shown, maxW) + 4
        // Prefer the whole grid with legend and extras; with little room show only the part the plants are in, then drop the extras, then the legend.
        var rows = n
        var cols = n
        var r0 = 0
        var c0 = 0
        var cell = minOf(maxW / n, (maxH - legendH - extraH) / n).coerceAtMost(30)
        var withLegend = true
        var withExtra = true
        if (cell < 14) {
            r0 = shown.r0
            c0 = shown.c0
            rows = shown.r1 - r0 + 1
            cols = shown.c1 - c0 + 1
            cell = minOf(maxW / cols, (maxH - legendH - extraH) / rows).coerceAtMost(30)
            if (cell < 12) {
                withExtra = false
                cell = minOf(maxW / cols, (maxH - legendH) / rows).coerceAtMost(30)
            }
            if (cell < 10) {
                // No room for the crop names (they show on hover), but the soil key stays: the colours need it.
                withLegend = false
                cell = minOf(maxW / cols, (maxH - soilKeyHeight(shown, maxW) - 4) / rows).coerceIn(5, 30)
            }
        }
        drawGrid(graphics, shown, x, y, cell, r0, rows, c0, cols)
        var ly = y + rows * cell + 4
        var lx = x
        if (!withLegend) return drawSoilKey(graphics, shown, x, ly, maxW)
        for (i in shown.legendIds.indices) {
            val entry = LEGEND_ICON + 3 + shown.legendWidths[i]
            if (lx > x && lx + entry > x + maxW) {
                lx = x
                ly += LEGEND_H
            }
            icon(graphics, shown.legendIds[i], lx, ly, LEGEND_ICON)
            graphics.text(font, shown.legendNames[i], lx + LEGEND_ICON + 3, ly + 2, if (shown.legendIds[i] == shown.highlight) TARGET_BORDER else WHITE, false)
            lx += entry + 8
        }
        ly += LEGEND_H
        ly = drawSoilKey(graphics, shown, x, ly, maxW) + 2
        if (!withExtra) return ly
        for ((i, line) in extra.withIndex()) {
            if (i >= 4 || ly + 8 > y + maxH) break
            graphics.text(font, line, x, ly, WHITE, false)
            ly += 9
        }
        return ly
    }

    /** Draws the soil key (colour chip, letter, name) at [x],[y]; returns the y below it. */
    private fun drawSoilKey(graphics: GuiGraphicsExtractor, shown: Picture, x: Int, y: Int, maxW: Int): Int = drawSoilChips(graphics, soilsOf(shown), x, y, maxW)

    private fun drawSoilChips(graphics: GuiGraphicsExtractor, soils: List<String>, x: Int, y: Int, maxW: Int): Int {
        if (soils.isEmpty()) return y
        var ly = y
        var sx = x
        for (soil in soils) {
            val label = prettify(soil)
            val entry = LEGEND_ICON + 3 + font.width(label)
            if (sx > x && sx + entry > x + maxW) {
                sx = x
                ly += LEGEND_H
            }
            graphics.fill(sx, ly + 1, sx + LEGEND_ICON, ly + 1 + LEGEND_ICON, GhSoil.EDGE)
            graphics.fill(sx + 1, ly + 2, sx + LEGEND_ICON - 1, ly + LEGEND_ICON, GhSoil.color(soil))
            graphics.text(font, GhSoil.letter(soil), sx + 3, ly + 2, GhSoil.letterColor(soil), false)
            graphics.text(font, label, sx + LEGEND_ICON + 3, ly + 2, TITLE_COLOR, false)
            sx += entry + 8
        }
        return ly + LEGEND_H
    }

    private fun drawGrid(graphics: GuiGraphicsExtractor, shown: Picture, x: Int, y: Int, cell: Int, r0: Int, rows: Int, c0: Int, cols: Int) {
        gridPicture = shown
        gridX = x
        gridY = y
        gridCell = cell
        gridR0 = r0
        gridC0 = c0
        gridRows = rows
        gridCols = cols
        graphics.fill(x, y, x + cols * cell + 1, y + rows * cell + 1, GRID_LINE)
        for (r in 0 until rows) for (c in 0 until cols) {
            val cx = x + c * cell
            val cy = y + r * cell
            val i = (r0 + r) * GreenhousePlots.GRID + c0 + c
            graphics.fill(cx + 1, cy + 1, cx + cell, cy + cell, if (blocked[i]) BLOCKED_CELL else if (mask[i]) EMPTY_CELL else LOCKED)
        }
        val data = Greenhouse.data
        for (b in shown.blockId.indices) {
            val id = shown.blockId[b]
            val span = shown.blockSpan[b]
            val cx = x + (shown.blockCol[b] - c0) * cell
            val cy = y + (shown.blockRow[b] - r0) * cell
            val size = span * cell
            val isTarget = id == shown.highlight
            // The block is painted as the soil it grows on; the target keeps a gold frame.
            graphics.fill(cx, cy, cx + size + 1, cy + size + 1, if (isTarget) TARGET_BORDER else GhSoil.EDGE)
            val inset = if (isTarget) 2 else 1
            graphics.fill(cx + inset, cy + inset, cx + size + 1 - inset, cy + size + 1 - inset, GhSoil.color(soilOf(id)))
            // A light top edge and a dark bottom edge give the soil a little depth.
            graphics.fill(cx + inset, cy + inset, cx + size + 1 - inset, cy + inset + 1, GhSoil.LIGHT)
            graphics.fill(cx + inset, cy + size - inset, cx + size + 1 - inset, cy + size + 1 - inset, GhSoil.SHADE)
            // A gold pip in the top right corner marks a mutation; a soil letter bottom left helps colour-blind players.
            if (data.mutation(id) != null) graphics.fill(cx + size - 3 - inset, cy + inset + 1, cx + size - inset, cy + inset + 4, GhSoil.MUTATION_PIP)
            // The icon fills the block, with a little margin.
            val iconPx = (size - 4).coerceAtLeast(6)
            val pad = (size - iconPx + 1) / 2
            icon(graphics, id, cx + pad, cy + pad, iconPx)
            // The soil letter sits on top of the icon, on a small dark chip so it reads on any crop.
            if (size >= 18) {
                val letter = GhSoil.letter(soilOf(id))
                val chipX = cx + inset
                val chipY = cy + size - inset - 9
                graphics.fill(chipX, chipY, chipX + font.width(letter) + 2, chipY + 9, GhSoil.EDGE)
                graphics.text(font, letter, chipX + 1, chipY + 1, -1, false)
            }
        }
    }

    /** The name of the plant under the mouse, as a small label (cells are too small for text). */
    private fun drawGridName(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val shown = gridPicture ?: return
        if (mouseY < listTop || mouseY >= listBottom) return
        val cell = gridCell
        if (mouseX < gridX || mouseY < gridY || mouseX >= gridX + gridCols * cell || mouseY >= gridY + gridRows * cell) return
        val id = shown.layout.cells[gridR0 + (mouseY - gridY) / cell][gridC0 + (mouseX - gridX) / cell] ?: return
        val soil = soilOf(id)
        val name = Greenhouse.data.nameOf(id) + if (soil.isEmpty()) "" else " §7on ${prettify(soil)}"
        val w = font.width(name)
        val x = (mouseX + 8).coerceAtMost(left + panelWidth - w - 8)
        val y = mouseY - 14
        graphics.fill(x - 3, y - 2, x + w + 3, y + 10, SLOT_BORDER)
        graphics.fill(x - 2, y - 1, x + w + 2, y + 9, SLOT_BACKGROUND)
        graphics.text(font, name, x, y, WHITE, false)
    }

    private fun drawPlots(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        graphics.text(font, if (blockMode) "§cBlock mode: §fclick an unlocked square to block or unblock it" else "§fPlots: click a square to unlock or lock it", left + 8, linesY + 1, WHITE, false)
        val g = plotsGrid
        val cell = plotsCell
        val n = GreenhousePlots.GRID
        graphics.fill(g.x, g.y, g.x + n * cell + 1, g.y + n * cell + 1, GRID_LINE)
        for (r in 0 until n) for (c in 0 until n) {
            val cx = g.x + c * cell
            val cy = g.y + r * cell
            val on = mask[r * n + c]
            val hover = mouseX in cx until cx + cell && mouseY in cy until cy + cell
            val block = blocked[r * n + c]
            graphics.fill(cx + 1, cy + 1, cx + cell, cy + cell, when {
                block -> if (hover) BAD else BLOCKED_CELL
                on -> if (hover) (if (blockMode) BAD else GOOD) else CROP_FILL
                hover -> PANEL_LIGHT
                else -> LOCKED
            })
            if (block) {
                // A cross over the blocked square.
                val inner = cell - 5
                for (k in 0 until inner) {
                    graphics.fill(cx + 3 + k, cy + 3 + k, cx + 4 + k, cy + 4 + k, BLOCKED_CROSS)
                    graphics.fill(cx + 3 + inner - 1 - k, cy + 3 + k, cx + 4 + inner - k, cy + 4 + k, BLOCKED_CROSS)
                }
            }
        }
        val cx = plotsControlsX
        graphics.text(font, "§fUnlocked §b$unlocked§f/${GreenhousePlots.CELLS}§f, blocked ${if (blockedCount > 0) "§c" else "§7"}$blockedCount", cx, g.y + 1, WHITE, false)
        graphics.text(font, "Fill to", cx, fillBox.y + 4, TITLE_COLOR, false)
        drawNumberBox(graphics, fillBox.x, fillBox.y + 1, if (focus == FOCUS_FILL) focusText else fillValue.toString(), focus == FOCUS_FILL, false)
        drawButton(graphics, fillMinus, "-", mouseX, mouseY)
        drawButton(graphics, fillPlus, "+", mouseX, mouseY)
        drawButton(graphics, fillButton, "Fill", mouseX, mouseY)
        drawButton(graphics, allButton, "All", mouseX, mouseY)
        drawButton(graphics, defaultButton, "Default", mouseX, mouseY)
        drawButton(graphics, unblockButton, "Unblock all", mouseX, mouseY)
        drawButton(graphics, blockButton, "Block mode", mouseX, mouseY, blockMode)
        drawButton(graphics, doneButton, "Done", mouseX, mouseY, true)
        var y = doneButton.y + BUTTON_H + 10
        for (line in plotsHint) {
            graphics.text(font, line, cx, y, WHITE, false)
            y += 9
        }
    }

    // Input

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean =
        site.screenCall(this, false) { click(event, doubleClick) }

    private fun click(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mx = event.x().toInt()
        val my = event.y().toInt()
        val x = dropdownX
        val y = top + 5
        if (dropdownOpen) {
            dropdownOpen = false
            if (mx in x until x + dropdownWidth && my >= y + DD_H) {
                val index = (my - y - DD_H) / DD_H
                GreenhouseView.entries.getOrNull(index)?.let { selectView(it) }
            }
            return true
        }
        if (mx in x until x + dropdownWidth && my in y until y + DD_H) {
            dropdownOpen = true
            return true
        }
        if (plotsButton.contains(mx, my)) {
            plotsOpen = !plotsOpen
            endFocus()
            return true
        }
        if (!plotsOpen && view == GreenhouseView.PLANNER) {
            val hit = when {
                maxButton.contains(mx, my) -> 1
                oneRect.contains(mx, my) -> 2
                clearButton.contains(mx, my) -> 3
                else -> 0
            }
            if (hit != 0) {
                endFocus()
                when (hit) {
                    1 -> startMax(null)
                    2 -> toggleOneOfEach()
                    else -> clearPlanner()
                }
                return true
            }
        }
        if (plotsOpen) return plotsClicked(mx, my)
        if (view == GreenhouseView.PLANNER && skyError != null && !plannerSide.busy && retryButton.contains(mx, my)) {
            endFocus()
            retry()
            return true
        }
        if (view == GreenhouseView.ROSE_DRAGON && sideOpen && mx >= sideLeft + sideWidth - 12 && mx < sideLeft + sideWidth && my >= listTop && my < listTop + 14) {
            endFocus()
            closeSide()
            return true
        }
        var keepFocus = false
        var handled = false
        when (view) {
            GreenhouseView.UNIQUE_MUTATIONS -> {
                val row = unique.getOrNull(rowAt(mx, my, listLeft, listRight - 8))
                if (row != null) {
                    toggleAnalysed(row.id)
                    handled = true
                }
            }
            GreenhouseView.PLANNER -> {
                val row = plannerRows.getOrNull(rowAt(mx, my, listLeft, listRight - 8))
                if (row != null) {
                    if (mx >= plannerBoxX && mx < plannerBoxX + BOX_W) {
                        beginFocus(FOCUS_ROW, row.id, row.amount.toString())
                        keepFocus = true
                    } else if (mx >= plannerMinusX && mx < plannerMinusX + STEP_W) step(row, -1)
                    else if (mx >= plannerPlusX && mx < plannerPlusX + STEP_W) step(row, 1)
                    else if (mx >= plannerMaxX && mx < plannerMaxX + plannerMaxW) startMax(row.id)
                    handled = true
                }
            }
            GreenhouseView.ROSE_DRAGON -> {
                val index = rowAt(mx, my, listLeft, listRight - 8)
                val line = tree.getOrNull(index)
                if (line != null) {
                    val row = line.row
                    val pill = line.pill
                    if (pill != null) {
                        if (mx in pill.x0 until pill.x1) {
                            if (row.expandable && (row.id == null && row.path == GreenhouseTree.ROOT_PATH || mx >= pill.x1 - 8 - font.width("+"))) toggleExpanded(row.path) else selectItem(row)
                        }
                    } else if (row.expandable && mx < listLeft + 4 + row.depth * 10 + 12) toggleExpanded(row.path) else selectItem(row)
                    handled = true
                }
            }
            GreenhouseView.ALL_MUTATIONS -> {
                val index = rowAt(mx, my, listLeft, listRight - 5)
                if (index in all.indices) {
                    if (index != selected) {
                        selected = index
                        clearPlan()
                        rebuild()
                    }
                    handled = true
                } else if (mx in gridLeft until gridLeft + font.width("Plan layout") + 14 && my in listTop until listTop + BUTTON_H) {
                    startPlan()
                    handled = true
                }
            }
        }
        if (!keepFocus) endFocus()
        return handled || super.mouseClicked(event, doubleClick)
    }

    private fun plotsClicked(mx: Int, my: Int): Boolean {
        val g = plotsGrid
        if (g.contains(mx, my)) {
            endFocus()
            val i = (my - g.y) / plotsCell * GreenhousePlots.GRID + (mx - g.x) / plotsCell
            if (blockMode) {
                // Only unlocked squares can be blocked; a locked one is left alone.
                if (mask[i]) {
                    val next = blocked.copyOf()
                    next[i] = !next[i]
                    applyBlocked(next)
                }
                return true
            }
            val next = mask.copyOf()
            next[i] = !next[i]
            applyMask(next)
            return true
        }
        when {
            fillBox.contains(mx, my) -> beginFocus(FOCUS_FILL, "", fillValue.toString())
            fillMinus.contains(mx, my) -> { endFocus(); fillValue = (fillValue - 1).coerceAtLeast(0); applyMask(GreenhousePlots.fill(fillValue)) }
            fillPlus.contains(mx, my) -> { endFocus(); fillValue = (fillValue + 1).coerceAtMost(GreenhousePlots.CELLS); applyMask(GreenhousePlots.fill(fillValue)) }
            fillButton.contains(mx, my) -> { endFocus(); applyMask(GreenhousePlots.fill(fillValue)) }
            allButton.contains(mx, my) -> { endFocus(); fillValue = GreenhousePlots.CELLS; applyMask(GreenhousePlots.all()) }
            defaultButton.contains(mx, my) -> { endFocus(); applyMask(GreenhousePlots.default()); fillValue = unlocked }
            unblockButton.contains(mx, my) -> { endFocus(); applyBlocked(BooleanArray(GreenhousePlots.CELLS)) }
            blockButton.contains(mx, my) -> { endFocus(); blockMode = !blockMode }
            doneButton.contains(mx, my) -> { endFocus(); plotsOpen = false }
            else -> endFocus()
        }
        return true
    }

    private fun applyMask(next: BooleanArray) {
        mask = next
        val before = blockedCount
        updateCounts()
        GreenhousePlots.save(next)
        if (blockedCount != before) GreenhousePlots.saveBlocked(blocked)
        squaresChanged()
    }

    private fun applyBlocked(next: BooleanArray) {
        blocked = next
        updateCounts()
        GreenhousePlots.saveBlocked(blocked)
        squaresChanged()
    }

    /** The squares plans may use changed: plan everything again for them. */
    private fun squaresChanged() {
        fit.request(Greenhouse.data, usableMask)
        clearPlan()
        // A Max run was for the old squares: drop it (the amounts stay) and plan the amounts again.
        maxGeneration++
        maxRunning = false
        plannerSide.busy = false
        capNote = ""
        markMixStale(true)
        if (selId != null) startPanelPlan()
        rebuild()
    }

    private fun rowAt(mx: Int, my: Int, from: Int, to: Int): Int {
        if (mx < from || mx >= to || my < listTop || my >= listBottom) return -1
        val i = (my - listTop) / rowH
        return if (i < rowsVisible) scroll[view.ordinal] + i else -1
    }

    private fun selectView(next: GreenhouseView) {
        view = next
        config.view = next
        plotsOpen = false
        endFocus()
        if (next == GreenhouseView.PLANNER) markMixStale(true)
        NyAddOns.saveConfig()
        rebuild()
    }

    private fun toggleAnalysed(id: String) {
        val set = saved.analysed
        if (!set.remove(id)) set.add(id)
        Storage.markDirty()
        rebuild()
    }

    private fun toggleExpanded(path: String) {
        if (!expanded.remove(path)) expanded.add(path)
        rebuild()
    }

    private fun closeSide() {
        selPath = null
        selId = null
        panelGeneration++
        rose.clear()
        rebuild()
    }

    // Number boxes

    private fun beginFocus(kind: Int, id: String, text: String) {
        focus = kind
        focusId = id
        focusText = text
        focusFresh = true
    }

    private fun endFocus() {
        focus = FOCUS_NONE
        focusText = ""
    }

    private fun focusChanged() {
        val value = focusText.toIntOrNull() ?: 0
        when (focus) {
            FOCUS_ROW -> {
                putAmount(focusId, value)
                capBase[focusId] = 0 // typed: a number over the cap snaps down to the cap itself
                capTouched = focusId
                capNote = ""
                markMixStale(false)
            }
            FOCUS_FILL -> fillValue = value.coerceAtMost(GreenhousePlots.CELLS)
        }
        rebuild()
    }

    /** One press of a row's - or + button. */
    private fun step(row: PlannerRow, delta: Int) {
        if (delta > 0 && row.atCap) return // nothing more fits alongside the other rows
        putAmount(row.id, (row.amount + delta).coerceIn(0, MAX_AMOUNT))
        if (delta > 0 && row.cap == CAP_UNKNOWN) capBase.putIfAbsent(row.id, row.amount) // checked when the cap arrives
        capTouched = row.id
        capNote = ""
        markMixStale(true)
        rebuild()
    }

    // Row caps

    /** A cap is a heuristic search result: an amount the shown plan really placed proves the row fits at least that many. */
    private fun provenCap(cap: Int, amount: Int, placed: Int): Int = if (cap >= 0 && amount > cap && placed >= amount) amount else cap

    private fun mixHash(x: Long): Long {
        var z = x + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Hash of the squares plans may use and the One of each switch: the part of a cap's key that does not depend on the row. */
    private fun capBaseHash(): Long = mixHash(usableMask.contentHashCode().toLong() * 2 + if (saved.planOneOfEach) 1 else 0)

    /** The cap's cache key: the amounts typed in every row but [id], the squares and One of each. Order independent. */
    private fun capKey(id: String, base: Long = capBaseHash()): Long {
        var h = base
        for ((k, n) in saved.planAmounts) if (k != id && n > 0) h += mixHash(k.hashCode().toLong() * 10007 + n)
        return h
    }

    /** After the debounce: apply the cap of the row just changed when it is known, else look for it in the background. */
    private fun checkCap() {
        val id = capTouched ?: return
        capTouched = null
        val data = Greenhouse.data
        if (!data.ready) return
        val key = capKey(id)
        val known = capCache[key]
        if (known != null) {
            applyCap(id, known)
            return
        }
        val others = HashMap<String, Int>()
        for ((k, n) in saved.planAmounts) if (k != id && n > 0) others[k] = n
        val req = CapRequest(key, id, others, usableMask.copyOf())
        if (capRunning) capPending = req else startCap(req)
    }

    private fun startCap(req: CapRequest) {
        capRunning = true
        val data = Greenhouse.data
        Safe.background("greenhouse cap") {
            var failed = false
            val cap = try {
                GhMax.capAlongside(data, req.amounts, req.id, req.mask) ?: NO_CAP
            } catch (e: SkyShardsException) {
                skyError = e.text
                failed = true
                NO_CAP
            } catch (_: Throwable) {
                failed = true
                NO_CAP
            }
            capAnswers.add(CapAnswer(req.key, req.id, cap, failed))
        }
    }

    private fun drainCaps() {
        var any = false
        while (true) {
            val a = capAnswers.poll() ?: break
            any = true
            capRunning = false
            // A failed solve is not remembered: the next change asks again.
            if (a.failed) continue
            capCache[a.key] = a.cap
            // Only a cap for what is typed now counts; a newer change has asked for its own.
            if (capKey(a.id) == a.key) applyCap(a.id, a.cap)
        }
        if (any) {
            val next = capPending
            capPending = null
            if (next != null) {
                if (capCache[next.key] == null) startCap(next)
                else if (capKey(next.id) == next.key) applyCap(next.id, capCache.getValue(next.key))
            }
            rebuild()
        }
    }

    /**
     * A row above its [cap] snaps down. A typed number goes to the cap; an increase that was not checked yet goes back to the cap or
     * to what it was before, whichever is higher, so a row that ANOTHER row's increase pushed over its cap never changes by itself
     * (that other row's increase is what gets capped). [NO_CAP]: the other rows do not fit on their own, so there is nothing to apply.
     */
    private fun applyCap(id: String, cap: Int) {
        val base = capBase.remove(id) ?: 0
        if (cap < 0) return
        val now = saved.planAmounts[id] ?: 0
        if (now <= cap) return
        val snap = maxOf(cap, minOf(base, now))
        if (snap >= now) return
        putAmount(id, snap)
        val name = Greenhouse.data.nameOf(id)
        capNote = if (snap == cap) "$name capped at $cap: no more fit alongside the other rows." else "$name stays at $snap: no more fit alongside the other rows."
        markMixStale(true)
    }

    /** Stores a Planner amount; 0 is the default and is not stored. */
    private fun putAmount(id: String, value: Int) {
        if (value <= 0) saved.planAmounts.remove(id) else saved.planAmounts[id] = value
        lastEdited = id
        Storage.markDirty()
    }

    /** The amounts or the unlocked squares changed: plan them again once typing pauses ([immediate] for a view switch). */
    private fun markMixStale(immediate: Boolean) {
        mixStale = true
        mixDirtyAt = if (immediate) ticks - MIX_DEBOUNCE_TICKS else ticks
        // The solve still running is out of date: stop it now (its job is cancelled on the server), the new one starts after the pause.
        mixGeneration++
    }

    /** The Retry button: ask SkyShards again for what is typed. */
    private fun retry() {
        skyError = null
        markMixStale(true)
        buildPlannerSide()
    }

    private fun toggleOneOfEach() {
        saved.planOneOfEach = !saved.planOneOfEach
        Storage.markDirty()
        capNote = ""
        markMixStale(true)
        rebuild()
    }

    private fun clearPlanner() {
        saved.planAmounts.clear()
        Storage.markDirty()
        capBase.clear()
        capTouched = null
        capPending = null
        capNote = ""
        skyError = null
        maxGeneration++
        maxRunning = false
        mixGeneration++
        mixStale = false
        mixWorking = false
        mixShown = null
        plannerSide.busy = false
        sideScroll = 0
        rebuild()
    }

    override fun charTyped(event: CharacterEvent): Boolean = site.screenCall(this, false) { typed(event) }

    private fun typed(event: CharacterEvent): Boolean {
        if (focus == FOCUS_NONE) return super.charTyped(event)
        val ch = event.codepoint()
        if (ch < '0'.code || ch > '9'.code) return true
        if (focusFresh) focusText = ""
        focusFresh = false
        if (focusText.length < MAX_DIGITS) focusText += Char(ch)
        focusText = focusText.toInt().toString()
        focusChanged()
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean = site.screenCall(this, false) { key(event) }

    private fun key(event: KeyEvent): Boolean {
        val key = event.key()
        if (focus != FOCUS_NONE) {
            when (key) {
                KEY_BACKSPACE -> {
                    focusFresh = false
                    focusText = focusText.dropLast(1)
                    focusChanged()
                    return true
                }
                KEY_ENTER, KEY_KP_ENTER, KEY_ESCAPE -> {
                    if (focus == FOCUS_FILL && key != KEY_ESCAPE) applyMask(GreenhousePlots.fill(fillValue))
                    endFocus()
                    return true
                }
            }
        } else if (key == KEY_ESCAPE && plotsOpen) {
            plotsOpen = false
            return true
        }
        return super.keyPressed(event)
    }

    // Max

    /**
     * Works out, off the render thread, the most mutations one layout can grow, then fills the amounts in [finishMax].
     * [row] null is the main button: the best mix of the rows with an amount (all rows when none has), honouring "One of each".
     * A row id asks for the most of that one mutation that fits together with what is typed in the other rows ([GhMax.maxAlongside])
     * and only changes that row.
     */
    private fun startMax(row: String?) {
        val data = Greenhouse.data
        if (maxRunning || !data.ready) return
        val every = uniqueOrder(data.mutations).map { it.id }
        val plan = saved.planAmounts
        val candidates = every.filter { (plan[it] ?: 0) > 0 }.ifEmpty { every }
        val typed = if (row != null) HashMap(plan) else null
        val copy = usableMask.copyOf()
        val oneOfEach = row == null && saved.planOneOfEach
        maxKey = if (row != null) capKey(row) else 0L
        capNote = ""
        capTouched = null
        skyError = null
        skyProgress = ""
        maxRunning = true
        maxRow = row
        maxDone = false
        maxAnswer = null
        alongAnswer = null
        maxPlan = null
        mixGeneration++
        mixStale = false
        mixWorking = false
        plannerSide.busy = true
        plannerSide.busyText = "Working..."
        val generation = ++maxGeneration
        val progress = { text: String -> if (generation == maxGeneration) skyProgress = text }
        Safe.background("greenhouse max") {
            var along: GhPlaceResult? = null
            var result: GhMaxResult? = null
            var plan: GhPlaceResult? = null
            val stale = { generation != maxGeneration }
            try {
                // What max claims is then planned again from scratch; whichever places more (never fewer than claimed) is shown.
                if (row != null) {
                    along = GhMax.maxAlongside(data, typed!!, row, copy, stale, progress)
                    plan = GhMax.settle(data, along, copy, stale, progress)
                } else {
                    result = GhMax.solve(data, candidates, copy, oneOfEach, stale, progress)
                    plan = GhMax.settle(data, GhPlaceResult(result.counts, result.layout, emptyMap(), result.counts, result.stocked, result.totalCells), copy, stale, progress)
                }
            } catch (e: SkyShardsException) {
                if (generation == maxGeneration) skyError = e.text
                along = null
                result = null
                plan = null
                // A Max that timed out offers the last cached layout for these squares, greyed like any other failure.
                if (e.timedOut) SkyShards.lastCached(copy)?.let { saved ->
                    val counts = saved.solution.counts.filterValues { it > 0 }
                    plan = GhPlaceResult(counts, counts.keys.firstOrNull()?.let(saved.solution::layout), emptyMap(), counts, emptyList(), saved.solution.usedCells)
                    maxFallbackAt = saved.at
                }
            } catch (_: Throwable) {
            }
            if (generation == maxGeneration) {
                maxPlan = plan
                maxAnswer = result
                alongAnswer = along
                maxDone = true
            }
        }
    }

    private fun finishMax(result: GhMaxResult?, along: GhPlaceResult?, row: String?) {
        plannerSide.busy = false
        if (row != null && along != null) {
            // One row: only its own box changes, and the combined plan it was found in is the layout shown.
            putAmount(row, along.placed[row] ?: 0)
            capBase.remove(row)
            // The row's max is its cap for these other rows: remember it (-1 when the others do not fit on their own).
            capCache[maxKey] = if (along.unplaced.keys.any { it != row }) NO_CAP else (along.placed[row] ?: 0)
            mixShown = maxPlan ?: along
            mixShownAt = System.currentTimeMillis()
            mixStale = false
            rebuild()
            return
        }
        if (result == null && skyError != null) {
            // SkyShards failed: keep the amounts and the last good plan (greyed, with Retry). A timed-out Max brings its cached layout.
            maxPlan?.let {
                mixShown = it
                mixShownAt = maxFallbackAt
                mixStale = false
            }
            buildPlannerSide()
            return
        }
        if (result == null) {
            // No answer: keep the amounts and show the old plan again.
            mixShown = null
            plannerSide.lines = wrap("§c${skyError ?: "No answer: the planner could not be run."}", sideWidth)
            plannerSide.picture = null
            plannerSide.rows = emptyList()
            return
        }
        // Overwrite the amount boxes: the counts for the placed mutations, zero for the rest. The Max layout is the plan.
        for (m in Greenhouse.data.mutations) if (m.id !in GreenhouseGoals.skippedMutations) putAmount(m.id, result.counts[m.id] ?: 0)
        capBase.clear()
        mixShown = maxPlan ?: GhPlaceResult(result.counts, result.layout, emptyMap(), result.counts, result.stocked, result.totalCells)
        mixShownAt = System.currentTimeMillis()
        mixStale = false
        rebuild()
    }

    // Planner layout

    /** Plans the typed amounts into one shared layout on a background thread; the answer lands in [tick]. */
    private fun startMix() {
        mixStale = false
        val data = Greenhouse.data
        val amounts = HashMap<String, Int>()
        for ((id, n) in saved.planAmounts) if (n > 0) amounts[id] = n
        mixGeneration++
        if (amounts.isEmpty()) {
            mixWorking = false
            mixShown = null
            sideScroll = 0
            rebuild()
            return
        }
        if (!data.ready) {
            mixStale = true
            mixDirtyAt = ticks
            return
        }
        val copy = usableMask.copyOf()
        val generation = mixGeneration
        val edited = lastEdited
        skyError = null
        skyProgress = ""
        mixWorking = true
        mixDone = false
        Safe.background("greenhouse mix") {
            val result = try {
                GhMax.replan(data, amounts, copy, { generation != mixGeneration }, { if (generation == mixGeneration) skyProgress = it }, edited)
            } catch (e: SkyShardsException) {
                if (generation == mixGeneration) skyError = e.text
                null
            } catch (_: Throwable) {
                null
            }
            if (generation == mixGeneration) {
                mixResult = result
                mixDone = true
            }
        }
    }

    /** Fills the Planner side panel from the shown plan: summary, rounds, layout, what did not fit and the crops needed. */
    private fun buildPlannerSide() {
        val s = plannerSide
        val result = mixShown
        val data = Greenhouse.data
        val stock = Greenhouse.stock
        val w = sideWidth - 6
        s.title = "Planned layout"
        s.icon = null
        s.extra = emptyList()
        s.summary = ""
        val failure = skyError?.let { wrap("§c$it", w) } ?: emptyList()
        if (result == null || result.requestedTotal <= 0 || !data.ready) {
            s.lines = failure + (if (capNote.isEmpty()) emptyList() else wrap("§e$capNote", w)) + wrap("§7Type an amount for a mutation, or press Max for the best mix. The layout, what does not fit and the crops needed show here.", w)
            s.picture = null
            s.rows = emptyList()
            mixPictureFor = null
            // No plan to show: the overlay clears too (a SkyShards failure never gets here, it keeps the saved plan).
            // Only the Planner view owns the overlay here: the Rose panel's layout must survive a planner rebuild.
            if (skyError == null && view == GreenhouseView.PLANNER) GreenhouseOverlay.show(null)
            return
        }
        val kinds = result.placed.size
        val lines = ArrayList<String>(4)
        val usable = unlocked - blockedCount
        lines += failure
        // The old plan stays, greyed, with the time it was worked out.
        val offline = skyError != null
        if (offline) lines +="§7Saved plan from ${java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(mixShownAt), java.time.ZoneId.systemDefault()).withNano(0).toString().take(5)}"
        val greyFrom = lines.size
        if (capNote.isNotEmpty()) lines += wrap("§e$capNote", w)
        if (result.note.isNotEmpty()) lines += wrap("§c${result.note}", w)
        if (result.placedTotal <= 0) lines += "§cPlanned: nothing fits on $usable squares"
        else {
            s.summary = "§fPlanned: §e${result.placedTotal} §fmutations ($kinds ${if (kinds == 1) "kind" else "kinds"}) on $usable squares, ${result.totalCells} used"
            lines += wrap(s.summary, w)
        }
        if (result.rounds > 0) lines += "§bx${result.rounds} rounds §7(${result.placedTotal} of ${result.requestedTotal} fit at once)"
        if (offline) for (i in greyFrom until lines.size) lines[i] = "§8" + strip(lines[i])
        s.lines = lines
        if (mixPictureFor !== result) {
            mixPictureFor = result
            s.picture = result.layout?.let { makePicture(it, null) }
            val layout = result.layout
            GreenhouseOverlay.show(layout)
            reuseLine = if (layout == null) "" else GhReuse.of(reuseBase, layout)?.text() ?: ""
            if (layout != null) reuseBase = layout
        }
        s.extra = if (reuseLine.isEmpty()) emptyList() else listOf(reuseLine)
        val rows = ArrayList<SideRow>(16)
        if (result.unplaced.isNotEmpty()) {
            rows += SideRow(null, "§cNot placed", heading = true)
            for ((id, reason) in result.unplaced) {
                val missing = (result.requested[id] ?: 0) - (result.placed[id] ?: 0)
                val wrapped = wrap("§7§c${data.nameOf(id)} x$missing§7: ${reason.substringBefore(" (")}", w - 13)
                for ((i, line) in wrapped.withIndex()) rows += SideRow(if (i == 0) id else null, line)
            }
        }
        if (result.stocked.isNotEmpty()) {
            rows += SideRow(null, "§7Planted from stock:", heading = true)
            for (line in wrap("§7${result.stocked.joinToString(", ") { data.nameOf(it) }}", w)) rows += SideRow(null, line, heading = true)
        }
        // Totals over everything placed. Mutation ingredients (Devourer needs Ashwreath) are listed apart from the base crops.
        val crops = HashMap<String, Int>()
        val mutations = HashMap<String, Int>()
        for ((id, n) in result.placed) {
            val m = data.mutation(id) ?: continue
            for (r in m.requirements) (if (data.mutation(r.crop) != null) mutations else crops).merge(r.crop, r.count * n, Int::plus)
        }
        neededRows(rows, "Crops needed", crops, data, stock, w)
        neededRows(rows, "Mutations needed", mutations, data, stock, w)
        if (offline) for (i in rows.indices) rows[i] = rows[i].let { SideRow(it.icon, "§8" + strip(it.text), it.heading, it.counts, DIMMED_TEXT, it.countWidth) }
        s.rows = rows
    }

    private fun neededRows(rows: MutableList<SideRow>, title: String, need: Map<String, Int>, data: GhData, stock: GhStock, w: Int) {
        if (need.isEmpty()) return
        rows += SideRow(null, "§f$title", heading = true)
        for ((id, n) in need.entries.sortedWith(compareBy({ -it.value }, { data.nameOf(it.key) }))) {
            val have = stock.count(data.nameOf(id))
            val counts = "${if (have == null) "?" else formatCount(have)}/${formatCount(n)}"
            val countWidth = font.width(counts)
            rows += SideRow(id, ellipsize(data.nameOf(id), w - 13 - countWidth - 6), counts = counts, countColor = if (have == null) UNKNOWN_TEXT else if (have >= n) GOOD_TEXT else BAD_TEXT, countWidth = countWidth)
        }
    }

    // Plot side panel

    private fun selectItem(row: TreeRow) {
        if (row.path == selPath) {
            // Clicking the picked item again closes the side panel.
            selPath = null
            selId = null
            panelGeneration++
            rose.clear()
            rebuild()
            return
        }
        selPath = row.path
        selId = row.id
        selName = row.name
        selAmount = row.need
        rose.title = ellipsize("§e${row.name} §fx${row.need}", sideWidth - 28)
        rose.icon = row.icon.ifEmpty { null }
        rebuild()
        startPanelPlan()
    }

    private fun startPanelPlan() {
        panelGeneration++
        panelDone = false
        panelResult = null
        rose.picture = null
        rose.lines = emptyList()
        rose.extra = emptyList()
        rose.summary = ""
        rose.busyText = "Planning..."
        panelRounds = 0
        val id = selId
        val target = id?.let { Greenhouse.data.mutation(it) }
        if (target == null) {
            rose.busy = false
            rose.lines = wrap(if (id == null) "§7A base crop: it has no plot layout. Grow it on the Garden." else "§7No data for this mutation yet.", sideWidth)
            return
        }
        rose.busy = true
        panelError = null
        val generation = panelGeneration
        val copy = usableMask.copyOf()
        Safe.background("greenhouse panel") {
            val result = try {
                Greenhouse.planner.plan(target, copy)
            } catch (e: SkyShardsException) {
                if (generation == panelGeneration) panelError = e.text
                null
            } catch (_: Throwable) {
                null
            }
            if (generation == panelGeneration) {
                panelResult = result
                panelDone = true
            }
        }
    }

    private fun finishPanelPlan(result: GhLayout?) {
        val id = selId ?: return
        if (result == null) {
            rose.picture = null
            val state = fit.check(id)
            val why = when {
                panelError != null -> "§c$panelError"
                state > 0 -> "§cNo room: needs $state more squares than the $unlocked unlocked. Open Plots to unlock more."
                state == GreenhouseFit.NO_FIT -> "§cDoes not fit the $unlocked unlocked squares as they are placed. Open Plots to unlock more."
                id in GreenhouseGoals.skippedMutations -> "§cNot included yet: the mod is being updated for it."
                else -> "§cNo layout found."
            }
            rose.lines = wrap(why, sideWidth)
            return
        }
        var made = 0
        val size = Greenhouse.data.mutation(id)?.size?.coerceAtLeast(1) ?: 1
        for (row in result.cells) for (cell in row) if (cell == id) made++
        // Each plant of the target is one mutation; a block of 2x2 or 3x3 counts once.
        val perRound = (made / (size * size)).coerceAtLeast(1)
        panelRounds = (selAmount + perRound - 1) / perRound
        rose.lines = if (panelRounds > 1) listOf("§bx$panelRounds rounds", "§7$perRound per round") else listOf("§aOne round")
        rose.summary = if (panelRounds > 1) "§bx$panelRounds rounds§7, $perRound per round" else "§aOne round"
        rose.picture = makePicture(result, id)
        GreenhouseOverlay.show(result)
    }

    private fun clearPlan() {
        planGeneration++
        planning = false
        planDone = false
        planResult = null
        planFor = null
        layout = null
        picture = null
    }

    private fun startPlan() {
        val target = all.getOrNull(selected) ?: return
        if (planning) return
        clearPlan()
        planning = true
        planError = null
        val generation = planGeneration
        val copy = usableMask.copyOf()
        Safe.background("greenhouse planner") {
            val result = try {
                Greenhouse.planner.plan(target, copy)
            } catch (e: SkyShardsException) {
                if (generation == planGeneration) planError = e.text
                null
            } catch (_: Throwable) {
                null
            }
            if (generation == planGeneration) {
                planResult = result
                planDone = true
                planning = false
            }
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean =
        site.screenCall(this, false) { scrolled(mouseX, mouseY, scrollY) }

    private fun scrolled(mouseX: Double, mouseY: Double, scrollY: Double): Boolean {
        if (plotsOpen) return true
        if (view == GreenhouseView.PLANNER && mouseX >= sideLeft - 4 && mouseY >= listTop && mouseY < listBottom) {
            val range = (sideContentH - (listBottom - listTop - 16)).coerceAtLeast(0)
            sideScroll = (sideScroll + if (scrollY > 0) -12 else 12).coerceIn(0, range)
            return true
        }
        val v = view.ordinal
        scroll[v] = (scroll[v] + if (scrollY > 0) -1 else 1).coerceIn(0, maxScroll(v))
        return true
    }

    override fun isPauseScreen() = false

    // Test hooks: where the mouse must go to click things (GUI coordinates), and what the window currently shows.
    fun checkboxX() = listLeft + 9
    fun uniqueRowY(visibleIndex: Int) = listTop + visibleIndex * rowH + rowH / 2
    fun plannerBoxCenter(visibleIndex: Int) = intArrayOf(plannerBoxX + BOX_W / 2, uniqueRowY(visibleIndex))
    fun plannerMaxCenter(visibleIndex: Int) = intArrayOf(plannerMaxX + plannerMaxW / 2, uniqueRowY(visibleIndex))
    fun oneOfEachCenter() = intArrayOf(oneRect.x + oneRect.w / 2, oneRect.y + oneRect.h / 2)
    fun clearCenter() = intArrayOf(clearButton.x + clearButton.w / 2, clearButton.y + clearButton.h / 2)
    fun dropdownCenter() = intArrayOf(dropdownX + dropdownWidth / 2, top + 5 + DD_H / 2)
    fun dropdownOptionCenter(index: Int) = intArrayOf(dropdownX + dropdownWidth / 2, top + 5 + DD_H + index * DD_H + DD_H / 2)
    fun allRowCenter(visibleIndex: Int) = intArrayOf(listLeft + 20, listTop + visibleIndex * rowH + rowH / 2)
    fun planButtonCenter() = intArrayOf(gridLeft + 20, listTop + BUTTON_H / 2)
    fun treeArrowCenter(visibleIndex: Int): IntArray {
        val pill = tree.getOrNull(scroll[view.ordinal] + visibleIndex)?.pill
        if (pill != null) return intArrayOf(pill.x1 - 4 - font.width("+") / 2, listTop + visibleIndex * rowH + rowH / 2)
        return intArrayOf(listLeft + 4 + (tree.getOrNull(scroll[view.ordinal] + visibleIndex)?.row?.depth ?: 0) * 10 + 5, listTop + visibleIndex * rowH + rowH / 2)
    }
    fun treeTextCenter(visibleIndex: Int): IntArray {
        val pill = tree.getOrNull(scroll[view.ordinal] + visibleIndex)?.pill
        if (pill != null) return intArrayOf(pill.x0 + 3 + pill.badgeWidth + 6 + rowH + 4, listTop + visibleIndex * rowH + rowH / 2)
        return intArrayOf(listLeft + 4 + (tree.getOrNull(scroll[view.ordinal] + visibleIndex)?.row?.depth ?: 0) * 10 + 14 + iconSize + 12, listTop + visibleIndex * rowH + rowH / 2)
    }
    fun plotsButtonCenter() = intArrayOf(plotsButton.x + plotsButton.w / 2, plotsButton.y + plotsButton.h / 2)
    fun maxButtonCenter() = intArrayOf(maxButton.x + maxButton.w / 2, maxButton.y + maxButton.h / 2)
    fun plannerMinusCenter(visibleIndex: Int) = intArrayOf(plannerMinusX + STEP_W / 2, uniqueRowY(visibleIndex))
    fun plannerPlusCenter(visibleIndex: Int) = intArrayOf(plannerPlusX + STEP_W / 2, uniqueRowY(visibleIndex))
    fun blockModeCenter() = intArrayOf(blockButton.x + blockButton.w / 2, blockButton.y + blockButton.h / 2)
    fun unblockAllCenter() = intArrayOf(unblockButton.x + unblockButton.w / 2, unblockButton.y + unblockButton.h / 2)
    fun plotCellCenter(row: Int, col: Int) = intArrayOf(plotsGrid.x + col * plotsCell + plotsCell / 2, plotsGrid.y + row * plotsCell + plotsCell / 2)
    fun fillBoxCenter() = intArrayOf(fillBox.x + fillBox.w / 2, fillBox.y + fillBox.h / 2)
    fun fillPlusCenter() = intArrayOf(fillPlus.x + fillPlus.w / 2, fillPlus.y + fillPlus.h / 2)
    fun fillButtonCenter() = intArrayOf(fillButton.x + fillButton.w / 2, fillButton.y + fillButton.h / 2)
    fun allPlotsCenter() = intArrayOf(allButton.x + allButton.w / 2, allButton.y + allButton.h / 2)
    fun defaultPlotsCenter() = intArrayOf(defaultButton.x + defaultButton.w / 2, defaultButton.y + defaultButton.h / 2)
    fun donePlotsCenter() = intArrayOf(doneButton.x + doneButton.w / 2, doneButton.y + doneButton.h / 2)
    val hasLayout get() = layout != null
    val plotsShown get() = plotsOpen
    val unlockedSquares get() = unlocked
    val blockedSquares get() = blockedCount
    val blockModeOn get() = blockMode

    /** How many icons the last drawn frame contained (rows, grid blocks, legend). */
    val iconsDrawn get() = iconsLastFrame

    /** The icon ids of the visible tree rows. */
    fun treeIcons(): List<String> = tree.map { it.row.icon }

    /** The ids of the blocks in the Planner's layout (empty when there is none). */
    val plannerLayout get() = plannerSide.picture?.layout
    val plannerBlockIds get() = plannerSide.picture?.blockId?.toList() ?: emptyList()
    val maxBusy get() = maxRunning

    /** Compact Planner geometry for the layout checks: where the soil key ended, where the disclaimer starts, the widest "max N" label and the room for it. */
    val plannerKeyBottom get() = keyBottomDrawn
    val disclaimerTop get() = footerY - 3 - disclaimer.size * 9 + 1
    val plannerMaxLabelWidest get() = plannerRows.maxOfOrNull { font.width(if (it.cap >= 0) "max ${it.cap}" else "max") } ?: 0
    val plannerMaxRoom get() = plannerMaxW - 3

    /** True while the Planner is working out a layout (Max or typed amounts). */
    val plannerBusy get() = maxRunning || mixWorking || mixStale || capRunning || capPending != null || capTouched != null || capAnswers.isNotEmpty()

    /** What the Planner's plan placed (id to count), its "xN rounds" estimate (0 when everything fits) and what did not fit. */
    val plannerPlaced get() = mixShown?.placed ?: emptyMap()
    /** The cached cap of a Planner row (null when unknown, -1 when the other rows do not fit on their own) and the cap note. */
    fun plannerCap(id: String): Int? = capCache[capKey(id)]
    fun plannerAtCap(id: String): Boolean = plannerRows.firstOrNull { it.id == id }?.atCap ?: false
    val plannerCapNote get() = capNote
    val plannerRounds get() = mixShown?.rounds ?: 0
    val plannerUnplaced get() = mixShown?.unplaced ?: emptyMap()

    /** The Planner side panel as plain text: summary lines, then the not placed and crop rows. */
    val plannerText get() = strip((plannerSide.lines + plannerSide.rows.map { if (it.counts.isEmpty()) it.text else "${it.text} ${it.counts}" }).joinToString("\n"))

    /** The Planner rows as plain `name amount` strings, and the amount of one mutation. */
    fun plannerRowText(): List<String> = plannerRows.map { strip("${it.label} ${it.amount}${if (it.analysed) " found" else ""}") }

    /** True when every Planner row, button and side line is inside the window. */
    fun plannerFits(): Boolean = clearButton.x + clearButton.w <= left + panelWidth - 8 && maxButton.x >= left

    /** The visible tree as plain `label counts` strings. */
    fun treeText(): List<String> = tree.map { strip("${"  ".repeat(it.row.depth)}${if (it.row.expandable) (if (it.row.expanded) "v " else "> ") else ""}${it.label} ${it.counts}") }

    /** The root pill's crop-total text ("Crops 12/340"), or empty when it is not shown. */
    fun rootCropText(): String = strip(tree.firstOrNull { it.row.path == GreenhouseTree.ROOT_PATH }?.pill?.crops ?: "")

    /** The visible unique rows as plain `name fit` strings. */
    fun uniqueText(): List<String> = unique.map { strip("${it.label} ${it.fitText}").trim() }

    /** The text of every list row and header line, with colour codes removed (for checks that no cost text is shown). */
    fun allVisibleText(): String = strip((listOf(roseLine, foundLine, headerA, headerSummary, hint, footer) + unique.map { it.label + it.fitText } + tree.map { it.label + it.counts } +
        allLabels + details.map { it.text } + plannerRows.map { it.label } + plannerSide.lines + plannerSide.rows.map { it.text } + rose.lines).joinToString("\n"))

    /** The header strings as plain text: Rose Dragon percentage, found count, the totals line and the disclaimer. */
    fun headerText(): List<String> = listOf(strip(roseLine), strip(foundLine), strip(headerSummary), strip(disclaimer.joinToString(" ")))

    /** True when the header lines, bar texts, buttons, disclaimer all stay inside the window and do not overlap. */
    fun headerFits(): Boolean {
        val inner = panelWidth - 16
        val half = (inner - 8) / 2
        val title = font.width("Greenhouse") + 12 + dropdownWidth + 6 + plotsButton.w
        return font.width(roseLine) + 10 <= half && font.width(foundLine) + 10 <= half && font.width(headerA) <= inner && title <= inner && disclaimer.all { font.width(it) <= inner }
    }

    val panelItem get() = selId?.let { "$selName x$selAmount" }
    val panelRoundCount get() = panelRounds
    val panelHasLayout get() = rose.picture != null
    val panelLayout get() = rose.picture?.layout
    val panelMessage get() = strip(rose.lines.joinToString(" "))
    val panelBusy get() = rose.busy

    private fun strip(text: String) = text.replace(Regex("§."), "")

    companion object {
        private const val DD_H = 14
        private const val BUTTON_H = 14
        private const val BOX_W = 30
        private const val BOX_H = 11
        private const val STEP_W = 11
        private const val MAX_DIGITS = 4
        private const val MAX_AMOUNT = 9999
        private const val LEGEND_ICON = 10
        private const val LEGEND_H = 12
        private const val KEY_H = 12
        private const val FOUND_W = 28
        private const val TAG_W = 11
        private const val TAG_H = 10
        private const val MIX_DEBOUNCE_TICKS = 8
        private const val CAP_CACHE_SIZE = 256
        private const val NO_CAP = -1
        private const val CAP_COLOR = 0xFFE0B040.toInt()

        private const val FOCUS_NONE = 0
        private const val FOCUS_ROW = 1
        private const val FOCUS_FILL = 3

        private const val KEY_ESCAPE = 256
        private const val KEY_ENTER = 257
        private const val KEY_BACKSPACE = 259
        private const val KEY_KP_ENTER = 335

        private const val MAX_PILL_DEPTH = 7
        private const val PILL_INDENT = 12
        private val COUNT_X = Regex("(\\d)x ")
        private const val BADGE_BORDER = 0xFF1B2A4A.toInt()
        private const val PILL_ROOT_OUTLINE = 0xFFFF5555.toInt()
        private const val PILL_CROP_OUTLINE = 0xFF454545.toInt()
        private const val PILL_FARM_NEXT_OUTLINE = 0xFFFFAA00.toInt()

        private const val WHITE = -1
        private const val TITLE_COLOR = 0xFFA0A0A0.toInt()
        private const val DIMMED_TEXT = 0xFF707070.toInt()
        private const val PANEL = 0xFF1E1E1E.toInt()
        private const val PANEL_LIGHT = 0xFF323232.toInt()
        private const val PANEL_DARK = 0xFF141414.toInt()
        private const val CARD = 0xFF272727.toInt()
        private const val CARD_DARK = 0xFF2E2E2E.toInt()
        private const val SLOT_BORDER = 0xFF323232.toInt()
        private const val SLOT_BACKGROUND = 0xFF141414.toInt()
        private const val GRID_LINE = 0xFF3A3A3A.toInt()
        private const val EMPTY_CELL = 0xFF2A2A2A.toInt()
        private const val LOCKED = 0xFF0F0F0F.toInt()
        private const val ACCENT = 0xFF000000.toInt() or ConfigTheme.ACCENT
        private const val ROSE_BAR = 0xFF9C7A1E.toInt()
        private const val NEXT_BACKGROUND = 0xFF1A2A44.toInt()
        private const val GOOD = 0xFF3FBF4F.toInt()
        private const val BAD = 0xFFD04040.toInt()
        private const val UNKNOWN = 0xFF606060.toInt()
        private const val GOOD_TEXT = 0xFF55FF55.toInt()
        private const val BAD_TEXT = 0xFFFF5555.toInt()
        private const val UNKNOWN_TEXT = 0xFF909090.toInt()
        private const val TARGET_BORDER = 0xFFFFD040.toInt()
        private const val CROP_FILL = 0xFF2A5A34.toInt()
        private const val BLOCKED_CELL = 0xFF4A1818.toInt()
        private const val BLOCKED_CROSS = 0xFFD04040.toInt()

        fun open() {
            NyAddOns.openScreen { GreenhouseScreen() }
        }

        private fun drawPanel(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
            graphics.fill(x, y, x + width, y + height, PANEL_LIGHT)
            graphics.fill(x + 1, y + 1, x + width, y + height, PANEL_DARK)
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, PANEL)
        }
    }
}
