package dev.nytrix.nyaddons.features.greenhouse

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.config.GreenhouseView
import dev.nytrix.nyaddons.core.PinnedPlot
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
private class PlannerRow(val id: String, val label: String, val labelWidth: Int, val analysed: Boolean, val amount: Int, val amountText: String, val problem: Boolean)

/** One line under the Planner layout: a heading or note ([heading], no icon column), else an icon, a text and optional counts at the right edge. */
private class SideRow(val icon: String?, val text: String, val heading: Boolean = false, val counts: String = "", val countColor: Int = 0, val countWidth: Int = 0)

/** One line of the Rose Dragon tree, prepared for drawing. */
private class TreeLine(val row: TreeRow, val label: String, val counts: String, val countWidth: Int, val covered: Boolean, val fitText: String, val fitWidth: Int)

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
    private var pinPlanner = Rect(0, 0, 0, 0)
    private val pinRose = Rect(0, 0, 0, 0)
    private var pinLabel = "Pin to screen"
    private var plannerPinned = false
    private var rosePinned = false
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
    private val expanded = HashSet<String>()
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
    private val rose = Side()
    private var panelRounds = 0

    // The Max solver of the Planner view (the main button or one row's "max"): one run at a time, answered on a background thread.
    @Volatile private var maxRunning = false
    @Volatile private var maxAnswer: GhMaxResult? = null
    @Volatile private var alongAnswer: GhPlaceResult? = null
    @Volatile private var maxDone = false
    private var maxGeneration = 0
    private var maxRow: String? = null

    // The Planner's shared layout for the typed amounts: worked out on a background thread a moment after the last change.
    @Volatile private var mixResult: GhPlaceResult? = null
    @Volatile private var mixDone = false
    @Volatile private var mixWorking = false
    private var mixGeneration = 0
    private var mixStale = true
    private var mixDirtyAt = 0
    private var mixShown: GhPlaceResult? = null
    private var mixPictureFor: GhPlaceResult? = null
    private val plannerSide = Side()
    private var sideScroll = 0
    private var sideContentH = 0

    // Layout planner of the All Mutations view: only the last plan is kept.
    @Volatile private var planning = false
    @Volatile private var planResult: GhLayout? = null
    @Volatile private var planDone = false
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
    private val listBottom get() = footerY - 3 - disclaimer.size * 9 - 4
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

    override fun init() {
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
        // Planner: the pin button sits at the right end of the side panel's title row.
        pinLabel = if (font.width("Planned layout") + 8 + font.width("Pin to screen") + 10 <= sideWidth) "Pin to screen" else "Pin"
        val pinW = font.width(pinLabel) + 10
        pinPlanner = Rect(sideLeft + sideWidth - pinW, linesY + 17, pinW, BUTTON_H - 2)
    }

    override fun tick() {
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
        }
        if (mixDone) {
            mixDone = false
            mixWorking = false
            mixShown = mixResult
            mixResult = null
            buildPlannerSide()
        }
        if (mixStale && view == GreenhouseView.PLANNER && !maxRunning && ticks - mixDirtyAt >= MIX_DEBOUNCE_TICKS) startMix()
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
        hint = arrayOf(
            "§7Arrow expands a row, its name shows the plot layout. Right side: held/needed. The percentage is a rough estimate.",
            "§7Arrow expands a row, name shows the layout. Right: held/needed. Percentage is rough.",
            "§7Arrow expands, name shows the layout. Right: held/needed.",
        ).firstOrNull { font.width(it) <= panelWidth - 16 } ?: ellipsize("§7Arrow expands, name shows the layout.", panelWidth - 16)

        val rows = GreenhouseTree.rows(data, stock, expanded)
        val treeRight = right - 4
        tree = rows.map { row ->
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
        plannerMaxW = font.width("max") + 6
        plannerMaxX = plannerMinusX - 3 - plannerMaxW
        val plan = profile.planAmounts
        val unplaced = mixShown?.unplaced
        plannerRows = data.mutations.filter { it.id !in GreenhouseGoals.skippedMutations }.sortedBy { it.name }.map { m ->
            val label = "${rarityCode(m.rarity)}${m.name}"
            val amount = plan[m.id] ?: 0
            PlannerRow(m.id, label, font.width(label), m.id in profile.analysed, amount, amount.toString(), amount > 0 && unplaced != null && m.id in unplaced)
        }
        buildPlannerSide()
        refreshPinFlags()

        all = data.mutations.sortedBy { it.name }
        allLabels = Array(all.size) { "${rarityCode(all[it].rarity)}${all[it].name}" }
        selected = selected.coerceIn(0, (all.size - 1).coerceAtLeast(0))
        details = all.getOrNull(selected)?.let { m -> detailLines(m, data, stock).map { DetailLine(it.icon, ellipsize(it.text, detailWidth - if (it.icon != null) 12 else 0)) } } ?: emptyList()

        val updated = stock.sacksUpdatedAt
        footer = if (updated <= 0) "§7Sacks: open your sacks on the Garden" else "§7Sacks last updated: ${TimeUtils.format(System.currentTimeMillis() - updated).let { if (it == "Soon") "just now" else "$it ago" }}"
        for (i in scroll.indices) scroll[i] = scroll[i].coerceIn(0, maxScroll(i))
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

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
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
        graphics.text(font, text, x + 5, y + (barH - 8) / 2 + 1, WHITE, true)
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
    private fun drawStepper(graphics: GuiGraphicsExtractor, x: Int, y: Int, label: String, mouseX: Int, mouseY: Int) {
        val over = mouseX in x until x + STEP_W && mouseY in y until y + BOX_H
        graphics.fill(x, y, x + STEP_W, y + BOX_H, SLOT_BORDER)
        graphics.fill(x + 1, y + 1, x + STEP_W - 1, y + BOX_H - 1, if (over) PANEL_LIGHT else SLOT_BACKGROUND)
        graphics.text(font, label, x + (STEP_W - font.width(label)) / 2, y + (BOX_H - 8) / 2 + 1, WHITE, false)
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

    private fun drawPlanner(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
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
            if (row.analysed) {
                val fx = labelX + row.labelWidth + 6
                if (fx + FOUND_W <= plannerMaxX - 4) graphics.text(font, "found", fx, ty, GOOD, false)
                else if (fx + 5 <= plannerMaxX - 2) graphics.fill(fx, y + (rowH - 5) / 2, fx + 5, y + (rowH - 5) / 2 + 4, GOOD)
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
            drawStepper(graphics, plannerPlusX, by, "+", mouseX, mouseY)
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
        if (s.picture != null && !s.busy) drawPinButton(graphics, pinPlanner, plannerPinned, mouseX, mouseY)
        if (s.busy) {
            graphics.text(font, "§7${s.busyText}", x, top, WHITE, false)
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
        if (shown != null) y = drawPicture(graphics, shown, x, y + 3, textW, ((listBottom - top) / 2).coerceAtLeast(100), emptyList()) + 4
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

    private fun drawPinButton(graphics: GuiGraphicsExtractor, r: Rect, pinned: Boolean, mouseX: Int, mouseY: Int) {
        drawButton(graphics, r, if (pinned) "Unpin" else pinLabel, mouseX, mouseY, pinned)
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
        // The pin button sits between the round lines and the grid.
        val label = if (rosePinned) "Unpin" else pinLabel
        pinRose.set(x, y + 1, font.width(label) + 10, BUTTON_H - 2)
        drawPinButton(graphics, pinRose, rosePinned, mouseX, mouseY)
        y += BUTTON_H
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
        }
        val shown = picture
        if (planFor == mutation.id && layout == null && !planning) {
            graphics.text(font, "§cNo layout found", bx + bw + 8, listTop + 4, WHITE, false)
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
        return if (shown.legendIds.isEmpty()) 0 else lines * LEGEND_H
    }

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
                // No room for the legend either: the grid gets everything and the names show on hover.
                withLegend = false
                cell = minOf(maxW / cols, maxH / rows).coerceIn(5, 30)
            }
        }
        drawGrid(graphics, shown, x, y, cell, r0, rows, c0, cols)
        if (!withLegend) return y + rows * cell + 1
        var ly = y + rows * cell + 4
        var lx = x
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
        ly += LEGEND_H + 2
        if (!withExtra) return ly
        for ((i, line) in extra.withIndex()) {
            if (i >= 4 || ly + 8 > y + maxH) break
            graphics.text(font, line, x, ly, WHITE, false)
            ly += 9
        }
        return ly
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
            if (isTarget) graphics.fill(cx, cy, cx + size + 1, cy + size + 1, TARGET_BORDER)
            graphics.fill(cx + 1, cy + 1, cx + size, cy + size, when {
                isTarget -> TARGET_FILL
                data.mutation(id) != null -> MUTATION_FILL
                else -> CROP_FILL
            })
            // The icon fills the block, with a little margin.
            val iconPx = (size - 4).coerceAtLeast(6)
            val pad = (size - iconPx + 1) / 2
            icon(graphics, id, cx + pad, cy + pad, iconPx)
        }
    }

    /** The name of the plant under the mouse, as a small label (cells are too small for text). */
    private fun drawGridName(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val shown = gridPicture ?: return
        if (mouseY < listTop || mouseY >= listBottom) return
        val cell = gridCell
        if (mouseX < gridX || mouseY < gridY || mouseX >= gridX + gridCols * cell || mouseY >= gridY + gridRows * cell) return
        val id = shown.layout.cells[gridR0 + (mouseY - gridY) / cell][gridC0 + (mouseX - gridX) / cell] ?: return
        val name = Greenhouse.data.nameOf(id)
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

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
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
        if (view == GreenhouseView.PLANNER && plannerSide.picture != null && !plannerSide.busy && pinPlanner.contains(mx, my)) {
            endFocus()
            togglePin(plannerSide, plannerPinned)
            return true
        }
        if (view == GreenhouseView.ROSE_DRAGON && sideOpen && rose.picture != null && !rose.busy && pinRose.contains(mx, my)) {
            endFocus()
            togglePin(rose, rosePinned)
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
                    if (row.expandable && mx < listLeft + 4 + row.depth * 10 + 12) toggleExpanded(row.path) else selectItem(row)
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
        markMixStale(false)
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
                markMixStale(false)
            }
            FOCUS_FILL -> fillValue = value.coerceAtMost(GreenhousePlots.CELLS)
        }
        rebuild()
    }

    /** One press of a row's - or + button. */
    private fun step(row: PlannerRow, delta: Int) {
        putAmount(row.id, (row.amount + delta).coerceIn(0, MAX_AMOUNT))
        markMixStale(false)
        rebuild()
    }

    /** Stores a Planner amount; 0 is the default and is not stored. */
    private fun putAmount(id: String, value: Int) {
        if (value <= 0) saved.planAmounts.remove(id) else saved.planAmounts[id] = value
        Storage.markDirty()
    }

    /** The amounts or the unlocked squares changed: plan them again once typing pauses ([immediate] for a view switch). */
    private fun markMixStale(immediate: Boolean) {
        mixStale = true
        mixDirtyAt = if (immediate) ticks - MIX_DEBOUNCE_TICKS else ticks
    }

    private fun toggleOneOfEach() {
        saved.planOneOfEach = !saved.planOneOfEach
        Storage.markDirty()
    }

    private fun clearPlanner() {
        saved.planAmounts.clear()
        Storage.markDirty()
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

    override fun charTyped(event: CharacterEvent): Boolean {
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

    override fun keyPressed(event: KeyEvent): Boolean {
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
        val analysed = saved.analysed.toSet()
        val every = uniqueOrder(data.mutations).map { it.id }
        val plan = saved.planAmounts
        val candidates = every.filter { (plan[it] ?: 0) > 0 }.ifEmpty { every }
        val typed = if (row != null) HashMap(plan) else null
        val copy = usableMask.copyOf()
        val planner = Greenhouse.planner
        val oneOfEach = row == null && saved.planOneOfEach
        val budget = if (row != null) GhMax.ALONGSIDE_BUDGET_MILLIS else GhMax.DEFAULT_BUDGET_MILLIS
        maxRunning = true
        maxRow = row
        maxDone = false
        maxAnswer = null
        alongAnswer = null
        mixGeneration++
        mixStale = false
        mixWorking = false
        plannerSide.busy = true
        plannerSide.busyText = "Working..."
        val generation = ++maxGeneration
        Thread({
            var along: GhPlaceResult? = null
            var result: GhMaxResult? = null
            try {
                if (row != null) along = GhMax.maxAlongside(planner, data, typed!!, row, copy, budget)
                else result = GhMax.solve(planner, data, candidates, copy, analysed, budget, oneOfEach)
            } catch (_: Exception) {
            }
            if (generation == maxGeneration) {
                maxAnswer = result
                alongAnswer = along
                maxDone = true
            }
        }, "NyAddOns greenhouse max").apply { isDaemon = true }.start()
    }

    private fun finishMax(result: GhMaxResult?, along: GhPlaceResult?, row: String?) {
        plannerSide.busy = false
        if (row != null && along != null) {
            // One row: only its own box changes, and the combined plan it was found in is the layout shown.
            putAmount(row, along.placed[row] ?: 0)
            mixShown = along
            mixStale = false
            rebuild()
            return
        }
        if (result == null) {
            // No answer: keep the amounts and show the old plan again.
            mixShown = null
            plannerSide.lines = wrap("§cNo answer: the planner could not be run.", sideWidth)
            plannerSide.picture = null
            plannerSide.rows = emptyList()
            return
        }
        // Overwrite the amount boxes: the counts for the placed mutations, zero for the rest. The Max layout is the plan.
        for (m in Greenhouse.data.mutations) if (m.id !in GreenhouseGoals.skippedMutations) putAmount(m.id, result.counts[m.id] ?: 0)
        mixShown = GhPlaceResult(result.counts, result.layout, emptyMap(), result.counts, result.stocked, result.totalCells)
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
        val planner = Greenhouse.planner
        val generation = mixGeneration
        mixWorking = true
        mixDone = false
        Thread({
            val result = try {
                GhMax.place(planner, data, amounts, copy)
            } catch (_: Exception) {
                null
            }
            if (generation == mixGeneration) {
                mixResult = result
                mixDone = true
            }
        }, "NyAddOns greenhouse mix").apply { isDaemon = true }.start()
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
        if (result == null || result.requestedTotal <= 0 || !data.ready) {
            s.lines = wrap("§7Type an amount for a mutation, or press Max for the best mix. The layout, what does not fit and the crops needed show here.", w)
            s.picture = null
            s.rows = emptyList()
            mixPictureFor = null
            return
        }
        val kinds = result.placed.size
        val lines = ArrayList<String>(4)
        val usable = unlocked - blockedCount
        if (result.note.isNotEmpty()) lines += wrap("§c${result.note}", w)
        if (result.placedTotal <= 0) lines += "§cPlanned: nothing fits on $usable squares"
        else {
            s.summary = "§fPlanned: §e${result.placedTotal} §fmutations ($kinds ${if (kinds == 1) "kind" else "kinds"}) on $usable squares, ${result.totalCells} used"
            lines += wrap(s.summary, w)
        }
        if (result.rounds > 0) lines += "§bx${result.rounds} rounds §7(${result.placedTotal} of ${result.requestedTotal} fit at once)"
        s.lines = lines
        if (mixPictureFor !== result) {
            mixPictureFor = result
            s.picture = result.layout?.let { makePicture(it, null) }
        }
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

    // Pin to screen

    /** What [side] shows right now as a pin, or null when it has no layout. The title is the panel's own, without colour codes. */
    private fun snapshot(side: Side): PinnedPlot? {
        val shown = side.picture?.layout ?: return null
        return PinnedPlot.of(shown.cells, strip(if (side === rose) rose.title else "Planned layout"), side.summary)
    }

    /** Pins what [side] shows, or removes the pin when [pinned] says this panel is the pinned one. */
    private fun togglePin(side: Side, pinned: Boolean) {
        if (pinned) GreenhousePin.unpin() else GreenhousePin.pin(snapshot(side) ?: return)
        refreshPinFlags()
    }

    /** Whether the pin on the screen is the same as what each panel shows; recomputed when the panels or the pin change. */
    private fun refreshPinFlags() {
        val pin = GreenhousePin.current
        plannerPinned = pin != null && snapshot(plannerSide)?.sameAs(pin) == true
        rosePinned = pin != null && snapshot(rose)?.sameAs(pin) == true
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
        val generation = panelGeneration
        val copy = usableMask.copyOf()
        Thread({
            val result = try {
                Greenhouse.planner.plan(target, copy)
            } catch (_: Exception) {
                null
            }
            if (generation == panelGeneration) {
                panelResult = result
                panelDone = true
            }
        }, "NyAddOns greenhouse panel").apply { isDaemon = true }.start()
    }

    private fun finishPanelPlan(result: GhLayout?) {
        val id = selId ?: return
        if (result == null) {
            rose.picture = null
            val state = fit.check(id)
            val why = when {
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
        val generation = planGeneration
        val copy = usableMask.copyOf()
        Thread({
            val result = try {
                Greenhouse.planner.plan(target, copy)
            } catch (_: Exception) {
                null
            }
            if (generation == planGeneration) {
                planResult = result
                planDone = true
                planning = false
            }
        }, "NyAddOns greenhouse planner").apply { isDaemon = true }.start()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
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
    fun treeArrowCenter(visibleIndex: Int) = intArrayOf(listLeft + 4 + (tree.getOrNull(scroll[view.ordinal] + visibleIndex)?.row?.depth ?: 0) * 10 + 5, listTop + visibleIndex * rowH + rowH / 2)
    fun treeTextCenter(visibleIndex: Int) = intArrayOf(listLeft + 4 + (tree.getOrNull(scroll[view.ordinal] + visibleIndex)?.row?.depth ?: 0) * 10 + 14 + iconSize + 12, listTop + visibleIndex * rowH + rowH / 2)
    fun plotsButtonCenter() = intArrayOf(plotsButton.x + plotsButton.w / 2, plotsButton.y + plotsButton.h / 2)
    fun maxButtonCenter() = intArrayOf(maxButton.x + maxButton.w / 2, maxButton.y + maxButton.h / 2)
    fun plannerMinusCenter(visibleIndex: Int) = intArrayOf(plannerMinusX + STEP_W / 2, uniqueRowY(visibleIndex))
    fun plannerPlusCenter(visibleIndex: Int) = intArrayOf(plannerPlusX + STEP_W / 2, uniqueRowY(visibleIndex))
    fun pinPlannerCenter() = intArrayOf(pinPlanner.x + pinPlanner.w / 2, pinPlanner.y + pinPlanner.h / 2)
    fun pinRoseCenter() = intArrayOf(pinRose.x + pinRose.w / 2, pinRose.y + pinRose.h / 2)
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
    val plannerIsPinned get() = plannerPinned
    val roseIsPinned get() = rosePinned

    /** How many icons the last drawn frame contained (rows, grid blocks, legend). */
    val iconsDrawn get() = iconsLastFrame

    /** The icon ids of the visible tree rows. */
    fun treeIcons(): List<String> = tree.map { it.row.icon }

    /** The ids of the blocks in the Planner's layout (empty when there is none). */
    val plannerLayout get() = plannerSide.picture?.layout
    val plannerBlockIds get() = plannerSide.picture?.blockId?.toList() ?: emptyList()
    val maxBusy get() = maxRunning

    /** True while the Planner is working out a layout (Max or typed amounts). */
    val plannerBusy get() = maxRunning || mixWorking || mixStale

    /** What the Planner's plan placed (id to count), its "xN rounds" estimate (0 when everything fits) and what did not fit. */
    val plannerPlaced get() = mixShown?.placed ?: emptyMap()
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
        private const val FOUND_W = 28
        private const val MIX_DEBOUNCE_TICKS = 8

        private const val FOCUS_NONE = 0
        private const val FOCUS_ROW = 1
        private const val FOCUS_FILL = 3

        private const val KEY_ESCAPE = 256
        private const val KEY_ENTER = 257
        private const val KEY_BACKSPACE = 259
        private const val KEY_KP_ENTER = 335

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
        private const val TARGET_FILL = 0xFF6B5512.toInt()
        private const val MUTATION_FILL = 0xFF45305E.toInt()
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
