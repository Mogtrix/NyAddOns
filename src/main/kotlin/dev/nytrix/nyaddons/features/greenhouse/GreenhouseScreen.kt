package dev.nytrix.nyaddons.features.greenhouse

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.config.GreenhouseView
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
    val amount: Int,
    val amountText: String,
    val fitText: String,
    val fitWidth: Int,
    var labelWidth: Int = 0,
)

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
    var busy = false
    var busyText = "Working..."
    var picture: Picture? = null
    var lines = emptyList<String>()
    var extra = emptyList<String>()

    fun clear() {
        title = ""
        icon = null
        busy = false
        picture = null
        lines = emptyList()
        extra = emptyList()
    }
}

private class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun contains(mx: Int, my: Int) = mx >= x && mx < x + w && my >= y && my < y + h
}

/** The Greenhouse helper window: unique mutations checklist, Rose Dragon tree and a mutation browser with a layout planner. */
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
    private var unlocked = 0
    private val fit = GreenhouseFit()
    private var fitSeen = -1
    private var stockSeen = -1L
    private var plotsOpen = false
    private var fillValue = 0
    private var plotsButton = Rect(0, 0, 0, 0)
    private var maxButton = Rect(0, 0, 0, 0)
    private var plotsGrid = Rect(0, 0, 0, 0)
    private var plotsCell = 12
    private var fillBox = Rect(0, 0, 0, 0)
    private var fillMinus = Rect(0, 0, 0, 0)
    private var fillPlus = Rect(0, 0, 0, 0)
    private var fillButton = Rect(0, 0, 0, 0)
    private var allButton = Rect(0, 0, 0, 0)
    private var defaultButton = Rect(0, 0, 0, 0)
    private var doneButton = Rect(0, 0, 0, 0)
    private var setAllBox = Rect(0, 0, 0, 0)
    private var setAllLabelWidth = 0
    private var plotsHint = emptyList<String>()
    private var plotsControlsX = 0

    // Number box being typed in: a mutation's amount, the "set all" box or the "Fill to" box.
    private var focus = FOCUS_NONE
    private var focusId = ""
    private var focusText = ""
    private var focusFresh = false

    // Models, rebuilt once a second and when something is clicked.
    private var unique = emptyList<UniqueRow>()
    private var uniqueBoxX = 0
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

    // The Max solver of the Unique Mutations view: one run at a time, answered on a background thread.
    @Volatile private var maxRunning = false
    @Volatile private var maxAnswer: GhMaxResult? = null
    @Volatile private var maxDone = false
    private var maxGeneration = 0
    private var maxShown = false
    private var maxCounts = emptyMap<String, Int>()
    private var maxSummary = ""
    private val maxSide = Side()

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
        GreenhouseView.ROSE_DRAGON -> 14
        else -> 26
    }
    private val footerY get() = top + panelHeight - 13
    private val listBottom get() = footerY - 3 - disclaimer.size * 9 - 4
    private val listLeft get() = left + 8
    private val sideWidth get() = (panelWidth * 0.42).toInt().coerceIn(150, 330)
    private val sideLeft get() = left + panelWidth - 8 - sideWidth
    private val sideOpen get() = (view == GreenhouseView.ROSE_DRAGON && selPath != null) || (view == GreenhouseView.UNIQUE_MUTATIONS && maxShown)
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
        unlocked = GreenhousePlots.count(mask)
        if (fillValue == 0) fillValue = unlocked
        GhIcons.request()
        placeButtons()
        Greenhouse.data.request()
        rebuild()
        fit.request(Greenhouse.data, mask)
    }

    private fun placeButtons() {
        val dropdownRight = dropdownX + dropdownWidth
        plotsButton = Rect(dropdownRight + 6, top + 5, font.width("Plots") + 14, DD_H)
        maxButton = Rect(plotsButton.x + plotsButton.w + 6, top + 5, font.width("Working...") + 14, DD_H)
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
        doneButton = Rect(cx, y0 + 44, font.width("Done") + 14, BUTTON_H)
        setAllLabelWidth = font.width("set all")
        setAllBox = Rect(left + panelWidth - 8 - BOX_W, linesY + 11, BOX_W, BOX_H)
        plotsHint = wrap("§7Fill unlocks the squares nearest the middle first. Default is the 12 middle squares.", left + panelWidth - 8 - cx)
    }

    override fun tick() {
        ticks++
        val stamp = Greenhouse.stock.sacksUpdatedAt
        // New numbers from a sack menu, or a plan or fit answer arriving, redraw straight away; otherwise once a second.
        if (ticks % 20 == 0) {
            GhIcons.request()
            Greenhouse.data.request()
            fit.request(Greenhouse.data, mask)
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
            finishMax(maxAnswer)
            maxAnswer = null
        }
    }

    override fun removed() {
        planGeneration++
        panelGeneration++
        maxGeneration++
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
        maxSide.clear()
        panelResult = null
        maxAnswer = null
        gridPicture = null
        expanded.clear()
    }

    // Models

    private fun amountOf(id: String) = saved.amounts[id] ?: 1

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
        // Dot at the right edge, the amount box left of it.
        val boxX = right - 10 - BOX_W
        val shortage = HashMap<String, Int>()
        var totalNeed = 0
        var anyKnown = false
        val labelX = listLeft + 17 + iconSize + 4
        unique = order.map { m ->
            val done = m.id in profile.analysed
            val isNext = !done && !nextTaken
            if (isNext) nextTaken = true
            val amount = amountOf(m.id)
            if (!done && amount > 0) {
                for (r in m.requirements) {
                    val name = data.nameOf(r.crop)
                    val need = r.count * amount
                    totalNeed += need
                    val have = stock.count(name)
                    if (have != null) anyKnown = true
                    if (have != null && have < need) shortage.merge(name, need - have, Int::plus)
                }
            }
            val label = "${rarityCode(m.rarity)}${m.name}"
            val labelWidth = font.width(label)
            val avail = boxX - 6 - (labelX + labelWidth + if (isNext) 44 else 0)
            val fitText = fitLabel(fit.check(m.id), avail)
            UniqueRow(m.id, label, done, isNext, ingredientState(m, data, stock, amount), amount, amount.toString(), fitText, font.width(fitText), labelWidth)
        }
        uniqueBoxX = boxX
        headerSummary = ellipsize(summaryText(totalNeed, shortage, anyKnown, stock.sacksUpdatedAt > 0), setAllBox.x - setAllLabelWidth - 12 - listLeft)
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
        if (total <= 0) return "§7Nothing to make: every amount is 0 or already analysed."
        val head = "§fNeeds §e${formatCount(total)} §fin all"
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
        }
        for ((i, line) in disclaimer.withIndex()) graphics.text(font, line, left + 8, footerY - 3 - (disclaimer.size - i) * 9 + 1, WHITE, false)
        graphics.text(font, footer, left + 8, footerY, WHITE, false)
        if (loading) {
            val text = "§eLoading Greenhouse data..."
            graphics.text(font, text, left + panelWidth - 8 - font.width(text), footerY, WHITE, false)
        }
        drawButton(graphics, plotsButton, "Plots", mouseX, mouseY, plotsOpen)
        if (view == GreenhouseView.UNIQUE_MUTATIONS) drawMaxButton(graphics, mouseX, mouseY)
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

    private fun drawNumberBox(graphics: GuiGraphicsExtractor, x: Int, y: Int, text: String, focused: Boolean, dim: Boolean) {
        graphics.fill(x, y, x + BOX_W, y + BOX_H, if (focused) ACCENT else SLOT_BORDER)
        graphics.fill(x + 1, y + 1, x + BOX_W - 1, y + BOX_H - 1, SLOT_BACKGROUND)
        val shown = if (focused && ticks / 10 % 2 == 0) "$text|" else text
        graphics.text(font, shown, x + BOX_W - 3 - font.width(shown), y + (BOX_H - 8) / 2 + 1, if (dim) DIMMED_TEXT else WHITE, false)
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
        // Second line: what the amounts add up to, and the box that sets every amount.
        graphics.text(font, headerSummary, left + 8, linesY + 13, WHITE, false)
        graphics.text(font, "set all", setAllBox.x - 4 - setAllLabelWidth, linesY + 13, TITLE_COLOR, false)
        drawNumberBox(graphics, setAllBox.x, setAllBox.y, if (focus == FOCUS_ALL) focusText else "", focus == FOCUS_ALL, false)
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
            val focused = focus == FOCUS_ROW && focusId == row.id
            drawNumberBox(graphics, uniqueBoxX, y + (rowH - 1 - BOX_H) / 2, if (focused) focusText else row.amountText, focused, row.analysed || row.amount == 0)
            if (row.fitText.isNotEmpty() && !row.analysed) graphics.text(font, row.fitText, uniqueBoxX - 6 - row.fitWidth, ty, BAD_TEXT, false)
        }
        drawScrollbar(graphics, GreenhouseView.UNIQUE_MUTATIONS.ordinal, listRight - 4)
        if (!loading && unique.isEmpty()) graphics.text(font, "§7No mutation data.", listLeft, listTop + 2, WHITE, false)
        if (maxShown) drawSide(graphics, maxSide)
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
        if (sideOpen) drawSide(graphics, rose)
    }

    /** The side panel: a title with its icon, then the layout (or a note), the legend and small extra lines. */
    private fun drawSide(graphics: GuiGraphicsExtractor, s: Side) {
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
    private fun drawPicture(graphics: GuiGraphicsExtractor, shown: Picture, x: Int, y: Int, maxW: Int, maxH: Int, extra: List<String>) {
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
        if (!withLegend) return
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
        if (!withExtra) return
        for ((i, line) in extra.withIndex()) {
            if (i >= 4 || ly + 8 > y + maxH) break
            graphics.text(font, line, x, ly, WHITE, false)
            ly += 9
        }
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
            graphics.fill(cx + 1, cy + 1, cx + cell, cy + cell, if (mask[(r0 + r) * GreenhousePlots.GRID + c0 + c]) EMPTY_CELL else LOCKED)
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
        graphics.text(font, "§fPlots: click a square to unlock or lock it", left + 8, linesY + 1, WHITE, false)
        val g = plotsGrid
        val cell = plotsCell
        val n = GreenhousePlots.GRID
        graphics.fill(g.x, g.y, g.x + n * cell + 1, g.y + n * cell + 1, GRID_LINE)
        for (r in 0 until n) for (c in 0 until n) {
            val cx = g.x + c * cell
            val cy = g.y + r * cell
            val on = mask[r * n + c]
            val hover = mouseX in cx until cx + cell && mouseY in cy until cy + cell
            graphics.fill(cx + 1, cy + 1, cx + cell, cy + cell, if (on) (if (hover) GOOD else CROP_FILL) else if (hover) PANEL_LIGHT else LOCKED)
        }
        val cx = plotsControlsX
        graphics.text(font, "§fUnlocked §b$unlocked§f/${GreenhousePlots.CELLS}", cx, g.y + 1, WHITE, false)
        graphics.text(font, "Fill to", cx, fillBox.y + 4, TITLE_COLOR, false)
        drawNumberBox(graphics, fillBox.x, fillBox.y + 1, if (focus == FOCUS_FILL) focusText else fillValue.toString(), focus == FOCUS_FILL, false)
        drawButton(graphics, fillMinus, "-", mouseX, mouseY)
        drawButton(graphics, fillPlus, "+", mouseX, mouseY)
        drawButton(graphics, fillButton, "Fill", mouseX, mouseY)
        drawButton(graphics, allButton, "All", mouseX, mouseY)
        drawButton(graphics, defaultButton, "Default", mouseX, mouseY)
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
        if (!plotsOpen && view == GreenhouseView.UNIQUE_MUTATIONS && maxButton.contains(mx, my)) {
            endFocus()
            startMax()
            return true
        }
        if (plotsOpen) return plotsClicked(mx, my)
        if (sideOpen && mx >= sideLeft + sideWidth - 12 && mx < sideLeft + sideWidth && my >= listTop && my < listTop + 14) {
            endFocus()
            closeSide()
            return true
        }
        var keepFocus = false
        var handled = false
        when (view) {
            GreenhouseView.UNIQUE_MUTATIONS -> {
                val right = listRight - 8
                if (setAllBox.contains(mx, my)) {
                    beginFocus(FOCUS_ALL, "", "")
                    keepFocus = true
                    handled = true
                } else {
                    val index = rowAt(mx, my, listLeft, right)
                    val row = unique.getOrNull(index)
                    if (row != null) {
                        if (mx >= uniqueBoxX && mx < uniqueBoxX + BOX_W) {
                            beginFocus(FOCUS_ROW, row.id, row.amount.toString())
                            keepFocus = true
                        } else toggleAnalysed(row.id)
                        handled = true
                    }
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
            doneButton.contains(mx, my) -> { endFocus(); plotsOpen = false }
            else -> endFocus()
        }
        return true
    }

    private fun applyMask(next: BooleanArray) {
        mask = next
        unlocked = GreenhousePlots.count(next)
        GreenhousePlots.save(next)
        fit.request(Greenhouse.data, next)
        clearPlan()
        // A Max layout was worked out for the old squares: drop it (the amounts stay).
        maxGeneration++
        maxRunning = false
        maxShown = false
        maxSide.clear()
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
        if (view == GreenhouseView.UNIQUE_MUTATIONS) {
            maxShown = false
            maxSide.clear()
        } else {
            selPath = null
            selId = null
            panelGeneration++
            rose.clear()
        }
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
            FOCUS_ROW -> setAmount(focusId, value)
            FOCUS_ALL -> {
                // Setting every amount: the default of 1 is stored as no entry at all.
                for (m in Greenhouse.data.mutations) if (m.id !in GreenhouseGoals.skippedMutations) setAmount(m.id, value)
            }
            FOCUS_FILL -> fillValue = value.coerceAtMost(GreenhousePlots.CELLS)
        }
        rebuild()
    }

    private fun setAmount(id: String, value: Int) {
        if (value == 1) saved.amounts.remove(id) else saved.amounts[id] = value
        Storage.markDirty()
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

    /** Works out, off the render thread, the most mutations one layout can grow, then fills the amounts in [finishMax]. */
    private fun startMax() {
        val data = Greenhouse.data
        if (maxRunning || !data.ready) return
        val analysed = saved.analysed.toSet()
        val every = uniqueOrder(data.mutations).map { it.id }
        // What is still to analyse; once everything is, all of them.
        val candidates = every.filter { it !in analysed }.ifEmpty { every }
        val copy = mask.copyOf()
        val planner = Greenhouse.planner
        maxRunning = true
        maxShown = true
        maxDone = false
        maxAnswer = null
        maxSide.clear()
        maxSide.title = "Max layout"
        maxSide.busy = true
        val generation = ++maxGeneration
        rebuild()
        Thread({
            val result = try {
                GhMax.solve(planner, data, candidates, copy, analysed)
            } catch (_: Exception) {
                null
            }
            if (generation == maxGeneration) {
                maxAnswer = result
                maxDone = true
            }
        }, "NyAddOns greenhouse max").apply { isDaemon = true }.start()
    }

    private fun finishMax(result: GhMaxResult?) {
        maxSide.busy = false
        val data = Greenhouse.data
        if (result == null) {
            maxCounts = emptyMap()
            maxSummary = "Max: no answer"
            maxSide.lines = wrap("§cNo answer: the planner could not be run.", sideWidth)
            maxSide.picture = null
            rebuild()
            return
        }
        // Overwrite the amount boxes: the counts for the placed mutations, zero for the rest.
        for (m in uniqueOrder(data.mutations)) setAmount(m.id, result.counts[m.id] ?: 0)
        maxCounts = result.counts
        maxSummary = if (result.total <= 0) "Max: nothing fits on $unlocked squares"
        else "Max: ${result.total} mutations (${result.counts.size} ${if (result.counts.size == 1) "kind" else "kinds"}) on $unlocked squares, ${result.totalCells} used"
        maxSide.lines = wrap("§f$maxSummary", sideWidth)
        val shown = result.layout
        maxSide.picture = shown?.let { makePicture(it, null) }
        val extra = ArrayList<String>()
        if (result.stocked.isNotEmpty()) extra += ellipsize("§7Planted from stock: ${result.stocked.joinToString(", ") { data.nameOf(it) }}", sideWidth)
        val rest = result.unplaced.entries.toList()
        for ((i, e) in rest.withIndex()) {
            if (extra.size >= 3 && rest.size - i > 1) {
                extra += "§8+${rest.size - i} not placed"
                break
            }
            extra += ellipsize("§7${data.nameOf(e.key)}: ${e.value.substringBefore(" (")}", sideWidth)
        }
        maxSide.extra = extra
        rebuild()
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
        val copy = mask.copyOf()
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
        val copy = mask.copyOf()
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
        val v = view.ordinal
        scroll[v] = (scroll[v] + if (scrollY > 0) -1 else 1).coerceIn(0, maxScroll(v))
        return true
    }

    override fun isPauseScreen() = false

    // Test hooks: where the mouse must go to click things (GUI coordinates), and what the window currently shows.
    fun checkboxX() = listLeft + 9
    fun uniqueRowY(visibleIndex: Int) = listTop + visibleIndex * rowH + rowH / 2
    fun amountBoxCenter(visibleIndex: Int) = intArrayOf(uniqueBoxX + BOX_W / 2, uniqueRowY(visibleIndex))
    fun setAllCenter() = intArrayOf(setAllBox.x + BOX_W / 2, setAllBox.y + BOX_H / 2)
    fun dropdownCenter() = intArrayOf(dropdownX + dropdownWidth / 2, top + 5 + DD_H / 2)
    fun dropdownOptionCenter(index: Int) = intArrayOf(dropdownX + dropdownWidth / 2, top + 5 + DD_H + index * DD_H + DD_H / 2)
    fun allRowCenter(visibleIndex: Int) = intArrayOf(listLeft + 20, listTop + visibleIndex * rowH + rowH / 2)
    fun planButtonCenter() = intArrayOf(gridLeft + 20, listTop + BUTTON_H / 2)
    fun treeArrowCenter(visibleIndex: Int) = intArrayOf(listLeft + 4 + (tree.getOrNull(scroll[view.ordinal] + visibleIndex)?.row?.depth ?: 0) * 10 + 5, listTop + visibleIndex * rowH + rowH / 2)
    fun treeTextCenter(visibleIndex: Int) = intArrayOf(listLeft + 4 + (tree.getOrNull(scroll[view.ordinal] + visibleIndex)?.row?.depth ?: 0) * 10 + 14 + iconSize + 12, listTop + visibleIndex * rowH + rowH / 2)
    fun plotsButtonCenter() = intArrayOf(plotsButton.x + plotsButton.w / 2, plotsButton.y + plotsButton.h / 2)
    fun maxButtonCenter() = intArrayOf(maxButton.x + maxButton.w / 2, maxButton.y + maxButton.h / 2)
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

    /** How many icons the last drawn frame contained (rows, grid blocks, legend). */
    val iconsDrawn get() = iconsLastFrame

    /** The icon ids of the visible tree rows. */
    fun treeIcons(): List<String> = tree.map { it.row.icon }

    /** The ids of the blocks in the Max layout (empty before a run). */
    val maxBlockIds get() = maxSide.picture?.blockId?.toList() ?: emptyList()
    val maxBusy get() = maxRunning
    val maxPanelShown get() = maxShown
    val maxPlaced get() = maxCounts

    /** The Max summary and the small lines under its layout, as plain text. */
    val maxText get() = strip((maxSide.lines + maxSide.extra).joinToString("\n"))
    val maxSummaryText get() = maxSummary

    /** The visible tree as plain `label counts` strings. */
    fun treeText(): List<String> = tree.map { strip("${"  ".repeat(it.row.depth)}${if (it.row.expandable) (if (it.row.expanded) "v " else "> ") else ""}${it.label} ${it.counts}") }

    /** The visible unique rows as plain `name amount fit` strings. */
    fun uniqueText(): List<String> = unique.map { strip("${it.label} ${it.amount} ${it.fitText}").trim() }

    /** The text of every list row and header line, with colour codes removed (for checks that no cost text is shown). */
    fun allVisibleText(): String = strip((listOf(roseLine, foundLine, headerA, headerSummary, hint, footer) + unique.map { it.label + it.fitText } + tree.map { it.label + it.counts } +
        allLabels + details.map { it.text } + maxSide.lines + maxSide.extra + rose.lines).joinToString("\n"))

    /** The header strings as plain text: Rose Dragon percentage, found count, the totals line and the disclaimer. */
    fun headerText(): List<String> = listOf(strip(roseLine), strip(foundLine), strip(headerSummary), strip(disclaimer.joinToString(" ")))

    /** True when the header lines, bar texts, buttons, disclaimer all stay inside the window and do not overlap. */
    fun headerFits(): Boolean {
        val inner = panelWidth - 16
        val half = (inner - 8) / 2
        val title = font.width("Greenhouse") + 12 + dropdownWidth + 6 + plotsButton.w + 6 + maxButton.w
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
        private const val MAX_DIGITS = 4
        private const val LEGEND_ICON = 10
        private const val LEGEND_H = 12

        private const val FOCUS_NONE = 0
        private const val FOCUS_ROW = 1
        private const val FOCUS_ALL = 2
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
        private const val TARGET_BORDER = 0xFFFFD040.toInt()
        private const val TARGET_FILL = 0xFF6B5512.toInt()
        private const val MUTATION_FILL = 0xFF45305E.toInt()
        private const val CROP_FILL = 0xFF2A5A34.toInt()

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
