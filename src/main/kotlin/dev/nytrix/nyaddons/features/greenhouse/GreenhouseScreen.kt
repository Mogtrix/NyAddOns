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
    val cost: String,
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

/** A layout plus what is needed to draw it: a letter per crop and the legend lines. */
private class Picture(val layout: GhLayout, val letters: HashMap<String, Char>, val legend: List<String>)

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
    private var panelTitle = ""
    private var panelRoundsText = ""

    // Number box being typed in: a mutation's amount, the "set all" box or the "Fill to" box.
    private var focus = FOCUS_NONE
    private var focusId = ""
    private var focusText = ""
    private var focusFresh = false

    // Models, rebuilt once a second and when something is clicked.
    private var unique = emptyList<UniqueRow>()
    private var uniqueCostWidth = 0
    private var uniqueBoxX = 0
    private var headerA = ""
    private var headerSummary = ""
    private var roseLine = ""
    private var foundLine = ""
    private var foundWidth = 0
    private var hint = ""
    private var disclaimer = emptyList<String>()
    private var footer = ""
    private var loading = false
    private var tree = emptyList<TreeLine>()
    private val expanded = HashSet<String>()
    private var all = emptyList<GhMutation>()
    private var allLabels = emptyArray<String>()
    private var selected = 0
    private var details = emptyList<String>()

    // The item picked in the Rose Dragon tree, shown as a layout in the side panel.
    private var selPath: String? = null
    private var selId: String? = null
    private var selName = ""
    private var selAmount = 0
    private var panelGeneration = 0
    @Volatile private var panelResult: GhLayout? = null
    @Volatile private var panelDone = false
    private var panelPlanning = false
    private var panelPicture: Picture? = null
    private var panelRounds = 0
    private var panelNote = emptyList<String>()

    // Layout planner of the All Mutations view: only the last plan is kept.
    @Volatile private var planning = false
    @Volatile private var planResult: GhLayout? = null
    @Volatile private var planDone = false
    private var planFor: String? = null
    private var layout: GhLayout? = null
    private var picture: Picture? = null
    private var planGeneration = 0

    private val rowsVisible get() = ((listBottom - listTop) / ROW_H).coerceAtLeast(1)
    private val listTop get() = top + when (view) {
        GreenhouseView.ALL_MUTATIONS -> 26
        GreenhouseView.ROSE_DRAGON -> 47
        else -> 58
    }
    private val footerY get() = top + panelHeight - 13
    private val listBottom get() = footerY - 3 - disclaimer.size * 9 - 4
    private val listLeft get() = left + 8
    private val sideWidth get() = (panelWidth * 0.36).toInt().coerceIn(120, 210)
    private val sideLeft get() = left + panelWidth - 8 - sideWidth
    private val sideOpen get() = view == GreenhouseView.ROSE_DRAGON && selPath != null
    private val listRight get() = when {
        view == GreenhouseView.ALL_MUTATIONS -> left + 8 + allListWidth
        sideOpen -> sideLeft - 4
        else -> left + panelWidth - 8
    }
    private val allListWidth get() = (panelWidth * 0.25).toInt().coerceIn(96, 150)
    private val detailLeft get() = listRight + 12
    private val detailWidth get() = (panelWidth * 0.32).toInt().coerceIn(116, 200)
    private val gridLeft get() = detailLeft + detailWidth + 8

    override fun init() {
        panelWidth = (width - 16).coerceIn(280, 640)
        panelHeight = (height - 16).coerceIn(180, 400)
        left = (width - panelWidth) / 2
        top = (height - panelHeight) / 2
        disclaimer = wrap("§8Shellfruit and Jerryflower are not included yet: the mod is being updated for them.", panelWidth - 16)
        mask = GreenhousePlots.current()
        unlocked = GreenhousePlots.count(mask)
        if (fillValue == 0) fillValue = unlocked
        placeButtons()
        Greenhouse.data.request()
        rebuild()
        fit.request(Greenhouse.data, mask)
        refreshPictures()
    }

    private fun placeButtons() {
        val dropdownRight = dropdownX + dropdownWidth
        plotsButton = Rect(dropdownRight + 6, top + 4, font.width("Plots") + 12, DD_H)
        // Plots picker: the grid on the left, the controls beside it.
        val cell = ((listBottom - (top + 36)) / GreenhousePlots.GRID).coerceIn(9, 16)
        plotsCell = cell
        plotsGrid = Rect(left + 12, top + 36, cell * GreenhousePlots.GRID, cell * GreenhousePlots.GRID)
        val cx = plotsGrid.x + plotsGrid.w + 14
        fillBox = Rect(cx + font.width("Fill to") + 6, top + 50, 34, BUTTON_H)
        fillMinus = Rect(fillBox.x + fillBox.w + 4, top + 50, 14, BUTTON_H)
        fillPlus = Rect(fillMinus.x + 18, top + 50, 14, BUTTON_H)
        fillButton = Rect(fillPlus.x + 18, top + 50, font.width("Fill") + 12, BUTTON_H)
        allButton = Rect(cx, top + 72, font.width("All") + 12, BUTTON_H)
        defaultButton = Rect(allButton.x + allButton.w + 4, top + 72, font.width("Default") + 12, BUTTON_H)
        doneButton = Rect(cx, top + 94, font.width("Done") + 12, BUTTON_H)
        setAllLabelWidth = font.width("set all")
        plotsHint = wrap("§7Fill unlocks the squares nearest the middle first. Default is the 12 middle squares.", left + panelWidth - 8 - cx)
    }

    override fun tick() {
        ticks++
        val stamp = Greenhouse.stock.sacksUpdatedAt
        // New numbers from a sack menu, or a plan or fit answer arriving, redraw straight away; otherwise once a second.
        if (ticks % 20 == 0) {
            Greenhouse.data.request()
            fit.request(Greenhouse.data, mask)
            rebuild()
        } else if (stamp != stockSeen || fit.version != fitSeen) {
            rebuild()
        }
        if (panelDone) {
            panelDone = false
            panelPlanning = false
            finishPanelPlan(panelResult)
            panelResult = null
        }
    }

    override fun removed() {
        planGeneration++
        panelGeneration++
        fit.stop()
        unique = emptyList()
        tree = emptyList()
        all = emptyList()
        allLabels = emptyArray()
        details = emptyList()
        layout = null
        planResult = null
        picture = null
        panelPicture = null
        panelResult = null
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
        var costWidth = 0
        val right = listRight - 8
        // Boxes and fit notes sit left of the cost column, whose width is the widest cost text.
        for (m in order) costWidth = maxOf(costWidth, font.width(costText(m)))
        val costX = right - 4 - costWidth
        val boxX = costX - 12 - 4 - BOX_W
        val shortage = HashMap<String, Int>()
        var totalNeed = 0
        var anyKnown = false
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
            val avail = boxX - 6 - (listLeft + 20 + labelWidth + if (isNext) 44 else 0)
            val fitText = fitLabel(fit.check(m.id), avail)
            UniqueRow(m.id, label, costText(m), done, isNext, ingredientState(m, data, stock, amount), amount, amount.toString(), fitText, font.width(fitText), labelWidth)
        }
        uniqueCostWidth = costWidth
        uniqueBoxX = boxX
        setAllBox = Rect(right - 4 - BOX_W, top + 45, BOX_W, BOX_H)
        headerSummary = summaryText(totalNeed, shortage, anyKnown, stock.sacksUpdatedAt > 0)
        val found = profile.analysed.count { it !in GreenhouseGoals.skippedMutations }.coerceAtMost(GreenhouseGoals.TRACKED_MUTATIONS)
        val milestone = nextMilestone(found)
        val nextRow = unique.firstOrNull { it.next }
        headerA = ellipsize(
            (if (milestone == null) "§aAll DNA milestones reached" else "§7Next milestone §f${milestone.roman} §7at §f${milestone.threshold}§7: §e${milestone.remaining} §7more") +
                if (nextRow == null) "   §7Nothing left to analyse." else "   §7Next up: ${nextRow.label}",
            panelWidth - 16,
        )
        foundLine = "§fMutations found §b$found§f/${GreenhouseGoals.TRACKED_MUTATIONS}"
        foundWidth = font.width(foundLine)
        roseLine = "§fRose Dragon §e~${GreenhouseTree.percent(data, stock)}% §7(rough estimate)"
        hint = ellipsize("§7Arrow expands a row, its name shows the plot layout. Right side: held/needed.", panelWidth - 16)

        val rows = GreenhouseTree.rows(data, stock, expanded)
        val treeRight = right - 4
        tree = rows.map { row ->
            val counts = "${formatCount(row.have)}/${formatCount(row.need)}"
            val countWidth = font.width(counts)
            val label = if (row.depth == 0) row.name else "x${row.need} ${row.name}"
            val labelEnd = listLeft + 4 + row.depth * 10 + 12 + font.width(label)
            val fitText = if (row.id == null) "" else fitLabel(fit.check(row.id), treeRight - countWidth - 8 - labelEnd)
            TreeLine(row, label, counts, countWidth, row.have >= row.need, fitText, font.width(fitText))
        }

        all = data.mutations.sortedBy { it.name }
        allLabels = Array(all.size) { "${rarityCode(all[it].rarity)}${all[it].name}" }
        selected = selected.coerceIn(0, (all.size - 1).coerceAtLeast(0))
        details = all.getOrNull(selected)?.let { detailLines(it, data, stock) } ?: emptyList()

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

    private fun refreshPictures() {
        picture = layout?.let { makePicture(it, left + panelWidth - 8 - gridLeft) }
        panelPicture = panelPicture?.let { makePicture(it.layout, sideWidth) }
    }

    private fun makePicture(shown: GhLayout, maxWidth: Int): Picture {
        val map = LinkedHashMap<String, Char>()
        val used = HashSet<Char>()
        for (row in shown.cells) for (id in row) {
            if (id == null || id in map) continue
            val name = Greenhouse.data.nameOf(id)
            val candidates = name.filter { it.isLetterOrDigit() }
            val pick = candidates.firstOrNull { it.uppercaseChar() !in used }?.uppercaseChar()
                ?: candidates.firstOrNull { it.lowercaseChar() !in used }?.lowercaseChar()
                ?: '?'
            used += pick
            map[id] = pick
        }
        val lines = ArrayList<String>()
        var current = StringBuilder()
        for ((id, letter) in map) {
            val entry = (if (id == shown.target) "§e" else "§f") + letter + " §7" + Greenhouse.data.nameOf(id)
            if (current.isNotEmpty() && font.width("$current   $entry") > maxWidth) {
                lines += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append("   ")
            current.append(entry)
        }
        if (current.isNotEmpty()) lines += current.toString()
        return Picture(shown, HashMap(map), lines)
    }

    // Drawing

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawPanel(graphics, left, top, panelWidth, panelHeight)
        graphics.text(font, "Greenhouse", left + 8, top + 7, TITLE_COLOR, false)
        graphics.text(font, foundLine, left + panelWidth - 8 - foundWidth, top + 7, WHITE, false)
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
        drawDropdown(graphics, mouseX, mouseY)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    private val dropdownX get() = left + 8 + font.width("Greenhouse") + 12
    private val dropdownWidth get() = 112

    private fun drawButton(graphics: GuiGraphicsExtractor, r: Rect, label: String, mouseX: Int, mouseY: Int, active: Boolean = false) {
        graphics.fill(r.x, r.y, r.x + r.w, r.y + r.h, if (active) ACCENT else SLOT_BORDER)
        graphics.fill(r.x + 1, r.y + 1, r.x + r.w - 1, r.y + r.h - 1, if (r.contains(mouseX, mouseY)) PANEL_LIGHT else SLOT_BACKGROUND)
        graphics.text(font, label, r.x + (r.w - font.width(label)) / 2, r.y + (r.h - 8) / 2 + 1, WHITE, false)
    }

    private fun drawNumberBox(graphics: GuiGraphicsExtractor, x: Int, y: Int, text: String, focused: Boolean, dim: Boolean) {
        graphics.fill(x, y, x + BOX_W, y + BOX_H, if (focused) ACCENT else SLOT_BORDER)
        graphics.fill(x + 1, y + 1, x + BOX_W - 1, y + BOX_H - 1, SLOT_BACKGROUND)
        val shown = if (focused && ticks / 10 % 2 == 0) "$text|" else text
        graphics.text(font, shown, x + BOX_W - 3 - font.width(shown), y + 2, if (dim) DIMMED_TEXT else WHITE, false)
    }

    private fun drawDropdown(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val x = dropdownX
        val y = top + 4
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

    private fun drawUnique(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        graphics.text(font, roseLine, left + 8, top + 24, WHITE, false)
        graphics.text(font, headerA, left + 8, top + 35, WHITE, false)
        val right = listRight - 8
        // Third line: what the amounts add up to, and the box that sets every amount.
        graphics.text(font, "set all", setAllBox.x - 4 - setAllLabelWidth, top + 47, TITLE_COLOR, false)
        graphics.text(font, headerSummary, left + 8, top + 47, WHITE, false)
        drawNumberBox(graphics, setAllBox.x, setAllBox.y, if (focus == FOCUS_ALL) focusText else "", focus == FOCUS_ALL, false)
        val first = scroll[GreenhouseView.UNIQUE_MUTATIONS.ordinal]
        for (i in 0 until rowsVisible) {
            val row = unique.getOrNull(first + i) ?: break
            val y = listTop + i * ROW_H
            val hover = mouseX in listLeft until right && mouseY in y until y + ROW_H
            if (row.next) graphics.fill(listLeft, y, right, y + ROW_H - 1, NEXT_BACKGROUND)
            else if (hover) graphics.fill(listLeft, y, right, y + ROW_H - 1, PANEL_LIGHT)
            drawCheckbox(graphics, listLeft + 2, y + 1, row.analysed)
            graphics.text(font, row.label, listLeft + 16, y + 2, if (row.analysed) DIMMED_TEXT else WHITE, false)
            if (row.next) graphics.text(font, "next up", listLeft + 20 + row.labelWidth, y + 2, ACCENT, false)
            val costX = right - 4 - uniqueCostWidth
            graphics.text(font, row.cost, costX, y + 2, if (row.analysed) DIMMED_TEXT else COST_COLOR, false)
            val dot = when (row.ingredients) {
                2 -> GOOD
                1 -> BAD
                else -> UNKNOWN
            }
            graphics.fill(costX - 12, y + 3, costX - 6, y + 9, dot)
            val focused = focus == FOCUS_ROW && focusId == row.id
            drawNumberBox(graphics, uniqueBoxX, y, if (focused) focusText else row.amountText, focused, row.analysed || row.amount == 0)
            if (row.fitText.isNotEmpty() && !row.analysed) graphics.text(font, row.fitText, uniqueBoxX - 6 - row.fitWidth, y + 2, BAD_TEXT, false)
        }
        drawScrollbar(graphics, GreenhouseView.UNIQUE_MUTATIONS.ordinal, listRight - 4)
        if (!loading && unique.isEmpty()) graphics.text(font, "§7No mutation data.", listLeft, listTop + 2, WHITE, false)
    }

    private fun drawCheckbox(graphics: GuiGraphicsExtractor, x: Int, y: Int, checked: Boolean) {
        graphics.fill(x, y, x + 9, y + 9, SLOT_BORDER)
        graphics.fill(x + 1, y + 1, x + 8, y + 8, SLOT_BACKGROUND)
        if (checked) graphics.fill(x + 2, y + 2, x + 7, y + 7, ACCENT)
    }

    private fun drawRose(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        graphics.text(font, roseLine, left + 8, top + 24, WHITE, false)
        graphics.text(font, hint, left + 8, top + 35, WHITE, false)
        val right = listRight - 8
        val first = scroll[GreenhouseView.ROSE_DRAGON.ordinal]
        for (i in 0 until rowsVisible) {
            val line = tree.getOrNull(first + i) ?: break
            val row = line.row
            val y = listTop + i * ROW_H
            val hover = mouseX in listLeft until right && mouseY in y until y + ROW_H
            if (row.path == selPath) graphics.fill(listLeft, y, right, y + ROW_H - 1, NEXT_BACKGROUND)
            else if (hover) graphics.fill(listLeft, y, right, y + ROW_H - 1, PANEL_LIGHT)
            else if (row.depth == 0) graphics.fill(listLeft, y, right, y + ROW_H - 1, PANEL_DARK)
            val color = if (line.covered) GOOD_TEXT else BAD_TEXT
            val ax = listLeft + 4 + row.depth * 10
            if (row.expandable) graphics.text(font, if (row.expanded) "v" else ">", ax + 1, y + 2, ACCENT, false)
            graphics.text(font, line.label, ax + 12, y + 2, if (row.depth == 0) WHITE else TITLE_COLOR, false)
            graphics.text(font, line.counts, right - 4 - line.countWidth, y + 2, color, false)
            if (line.fitText.isNotEmpty()) graphics.text(font, line.fitText, right - 4 - line.countWidth - 8 - line.fitWidth, y + 2, BAD_TEXT, false)
        }
        drawScrollbar(graphics, GreenhouseView.ROSE_DRAGON.ordinal, listRight - 4)
        if (loading) graphics.text(font, "§7Mutation requirements appear once the data loads.", listLeft, listBottom - 10, WHITE, false)
        if (sideOpen) drawSide(graphics)
    }

    private fun drawSide(graphics: GuiGraphicsExtractor) {
        val x = sideLeft
        graphics.fill(x - 3, listTop - 2, x - 2, listBottom, SLOT_BORDER)
        graphics.text(font, panelTitle, x, listTop, WHITE, false)
        var y = listTop + 10
        val shown = panelPicture
        if (panelPlanning) {
            graphics.text(font, "§7Planning...", x, y, WHITE, false)
        } else if (shown == null) {
            for (line in panelNote) {
                graphics.text(font, line, x, y, WHITE, false)
                y += 9
            }
        } else {
            graphics.text(font, panelRoundsText, x, y, WHITE, false)
            for (line in panelNote) {
                y += 9
                graphics.text(font, line, x, y, WHITE, false)
            }
            drawGrid(graphics, shown, x, y + 10, sideWidth, listBottom - (y + 10), 16)
        }
    }

    private fun drawAll(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val v = GreenhouseView.ALL_MUTATIONS.ordinal
        val first = scroll[v]
        for (i in 0 until rowsVisible) {
            val index = first + i
            if (index >= all.size) break
            val y = listTop + i * ROW_H
            val hover = mouseX in listLeft until listRight && mouseY in y until y + ROW_H
            if (index == selected) graphics.fill(listLeft, y, listRight - 5, y + ROW_H - 1, NEXT_BACKGROUND)
            else if (hover) graphics.fill(listLeft, y, listRight - 5, y + ROW_H - 1, PANEL_LIGHT)
            graphics.text(font, allLabels[index], listLeft + 3, y + 2, WHITE, false)
        }
        drawScrollbar(graphics, v, listRight - 4)

        for ((i, line) in details.withIndex()) {
            graphics.text(font, line, detailLeft, listTop + i * 10, WHITE, false)
        }
        val mutation = all.getOrNull(selected) ?: return
        val bx = gridLeft
        val bw = 70
        val hover = mouseX in bx until bx + bw && mouseY in listTop until listTop + BUTTON_H
        graphics.fill(bx, listTop, bx + bw, listTop + BUTTON_H, if (planning) SLOT_BORDER else ACCENT)
        graphics.fill(bx + 1, listTop + 1, bx + bw - 1, listTop + BUTTON_H - 1, if (hover) PANEL_LIGHT else SLOT_BACKGROUND)
        graphics.text(font, if (planning) "Planning..." else "Plan layout", bx + 6, listTop + 4, WHITE, false)

        if (planDone) {
            planDone = false
            layout = planResult
            planResult = null
            planFor = mutation.id
            refreshPictures()
        }
        val shown = picture
        if (planFor == mutation.id && layout == null && !planning) {
            graphics.text(font, "§cNo layout found", bx + bw + 6, listTop + 4, WHITE, false)
        } else if (shown != null && planFor == mutation.id) {
            val gy = listTop + BUTTON_H + 5
            drawGrid(graphics, shown, bx, gy, left + panelWidth - 8 - bx, listBottom - gy, 20)
        }
    }

    /** Draws [shown] in a box of [maxW] x [maxH] pixels with its legend underneath; locked squares are left dark. */
    private fun drawGrid(graphics: GuiGraphicsExtractor, shown: Picture, x: Int, y: Int, maxW: Int, maxH: Int, maxCell: Int) {
        val layout = shown.layout
        val legendLines = shown.legend.size
        val cell = minOf(maxW, maxH - legendLines * 9 - 3).div(layout.size).coerceIn(9, maxCell)
        for (r in 0 until layout.size) for (c in 0 until layout.size) {
            val id = layout.cells[r][c]
            val cx = x + c * cell
            val cy = y + r * cell
            if (id == null && !mask[r * GreenhousePlots.GRID + c]) {
                graphics.fill(cx, cy, cx + cell - 1, cy + cell - 1, LOCKED)
                continue
            }
            val isTarget = id != null && id == layout.target
            val isMutation = id != null && Greenhouse.data.mutation(id) != null
            graphics.fill(cx, cy, cx + cell - 1, cy + cell - 1, if (isTarget) TARGET_BORDER else SLOT_BORDER)
            graphics.fill(cx + 1, cy + 1, cx + cell - 2, cy + cell - 2, when {
                id == null -> SLOT_BACKGROUND
                isTarget -> TARGET_FILL
                isMutation -> MUTATION_FILL
                else -> CROP_FILL
            })
            if (id != null) {
                val letter = shown.letters[id]?.toString() ?: "?"
                graphics.text(font, letter, cx + (cell - 1 - font.width(letter)) / 2, cy + (cell - 8) / 2, WHITE, false)
            }
        }
        var ly = y + layout.size * cell + 3
        for (line in shown.legend) {
            if (ly + 8 > y + maxH) break
            graphics.text(font, line, x, ly, WHITE, false)
            ly += 9
        }
    }

    private fun drawPlots(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        graphics.text(font, "§fPlots: click a square to unlock or lock it", left + 8, top + 24, WHITE, false)
        val g = plotsGrid
        val cell = plotsCell
        for (r in 0 until GreenhousePlots.GRID) for (c in 0 until GreenhousePlots.GRID) {
            val cx = g.x + c * cell
            val cy = g.y + r * cell
            val on = mask[r * GreenhousePlots.GRID + c]
            val hover = mouseX in cx until cx + cell && mouseY in cy until cy + cell
            graphics.fill(cx, cy, cx + cell - 1, cy + cell - 1, if (on) GOOD else SLOT_BORDER)
            graphics.fill(cx + 1, cy + 1, cx + cell - 2, cy + cell - 2, if (on) (if (hover) GOOD else CROP_FILL) else if (hover) PANEL_LIGHT else SLOT_BACKGROUND)
        }
        val cx = g.x + g.w + 14
        graphics.text(font, "§fUnlocked §b$unlocked§f/${GreenhousePlots.CELLS}", cx, top + 36, WHITE, false)
        graphics.text(font, "Fill to", cx, top + 54, TITLE_COLOR, false)
        drawNumberBox(graphics, fillBox.x, fillBox.y, if (focus == FOCUS_FILL) focusText else fillValue.toString(), focus == FOCUS_FILL, false)
        drawButton(graphics, fillMinus, "-", mouseX, mouseY)
        drawButton(graphics, fillPlus, "+", mouseX, mouseY)
        drawButton(graphics, fillButton, "Fill", mouseX, mouseY)
        drawButton(graphics, allButton, "All", mouseX, mouseY)
        drawButton(graphics, defaultButton, "Default", mouseX, mouseY)
        drawButton(graphics, doneButton, "Done", mouseX, mouseY)
        var y = top + 116
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
        val y = top + 4
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
        if (plotsOpen) return plotsClicked(mx, my)
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
                } else if (mx in gridLeft until gridLeft + 70 && my in listTop until listTop + BUTTON_H) {
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
        if (selId != null) startPanelPlan()
        rebuild()
    }

    private fun rowAt(mx: Int, my: Int, from: Int, to: Int): Int {
        if (mx < from || mx >= to || my < listTop || my >= listBottom) return -1
        val i = (my - listTop) / ROW_H
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

    // Plot side panel

    private fun selectItem(row: TreeRow) {
        if (row.path == selPath) {
            // Clicking the picked item again closes the side panel.
            selPath = null
            selId = null
            panelGeneration++
            panelPicture = null
            panelPlanning = false
            rebuild()
            return
        }
        selPath = row.path
        selId = row.id
        selName = row.name
        selAmount = row.need
        panelTitle = ellipsize("§e${row.name} §fx${row.need}", sideWidth)
        rebuild()
        startPanelPlan()
    }

    private fun startPanelPlan() {
        panelGeneration++
        panelDone = false
        panelResult = null
        panelPicture = null
        panelRounds = 0
        val id = selId
        val target = id?.let { Greenhouse.data.mutation(it) }
        if (target == null) {
            panelPlanning = false
            panelNote = wrap(if (id == null) "§7A base crop: it has no plot layout. Grow it on the Garden." else "§7No data for this mutation yet.", sideWidth)
            return
        }
        panelPlanning = true
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
            panelPicture = null
            val state = fit.check(id)
            val why = when {
                state > 0 -> "§cNo room: needs $state more squares than the $unlocked unlocked. Open Plots to unlock more."
                state == GreenhouseFit.NO_FIT -> "§cDoes not fit the $unlocked unlocked squares as they are placed. Open Plots to unlock more."
                id in GreenhouseGoals.skippedMutations -> "§cNot included yet: the mod is being updated for it."
                else -> "§cNo layout found."
            }
            panelNote = wrap(why, sideWidth)
            return
        }
        var made = 0
        val size = Greenhouse.data.mutation(id)?.size?.coerceAtLeast(1) ?: 1
        for (row in result.cells) for (cell in row) if (cell == id) made++
        // Each plant of the target is one mutation; a block of 2x2 or 3x3 counts once.
        val perRound = (made / (size * size)).coerceAtLeast(1)
        panelRounds = (selAmount + perRound - 1) / perRound
        panelRoundsText = if (panelRounds > 1) "§bx$panelRounds rounds" else "§aOne round"
        panelNote = if (panelRounds > 1) listOf("§7$perRound per round") else emptyList()
        panelPicture = makePicture(result, sideWidth)
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
    fun checkboxX() = listLeft + 6
    fun uniqueRowY(visibleIndex: Int) = listTop + visibleIndex * ROW_H + 5
    fun amountBoxCenter(visibleIndex: Int) = intArrayOf(uniqueBoxX + BOX_W / 2, uniqueRowY(visibleIndex))
    fun setAllCenter() = intArrayOf(setAllBox.x + BOX_W / 2, setAllBox.y + 4)
    fun dropdownCenter() = intArrayOf(dropdownX + dropdownWidth / 2, top + 4 + DD_H / 2)
    fun dropdownOptionCenter(index: Int) = intArrayOf(dropdownX + dropdownWidth / 2, top + 4 + DD_H + index * DD_H + DD_H / 2)
    fun allRowCenter(visibleIndex: Int) = intArrayOf(listLeft + 20, listTop + visibleIndex * ROW_H + 5)
    fun planButtonCenter() = intArrayOf(gridLeft + 35, listTop + BUTTON_H / 2)
    fun treeArrowCenter(visibleIndex: Int) = intArrayOf(listLeft + 4 + (tree.getOrNull(scroll[view.ordinal] + visibleIndex)?.row?.depth ?: 0) * 10 + 5, listTop + visibleIndex * ROW_H + 5)
    fun treeTextCenter(visibleIndex: Int) = intArrayOf(listLeft + 4 + (tree.getOrNull(scroll[view.ordinal] + visibleIndex)?.row?.depth ?: 0) * 10 + 20, listTop + visibleIndex * ROW_H + 5)
    fun plotsButtonCenter() = intArrayOf(plotsButton.x + plotsButton.w / 2, plotsButton.y + plotsButton.h / 2)
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

    /** The visible tree as plain `label counts` strings. */
    fun treeText(): List<String> = tree.map { strip("${"  ".repeat(it.row.depth)}${if (it.row.expandable) (if (it.row.expanded) "v " else "> ") else ""}${it.label} ${it.counts}") }

    /** The visible unique rows as plain `name amount fit` strings. */
    fun uniqueText(): List<String> = unique.map { strip("${it.label} ${it.amount} ${it.fitText}").trim() }

    /** The header strings as plain text: Rose Dragon percentage, found count, the totals line and the disclaimer. */
    fun headerText(): List<String> = listOf(strip(roseLine), strip(foundLine), strip(headerSummary), strip(disclaimer.joinToString(" ")))

    /** True when the header lines, disclaimer and the 'Mutations found' text all stay inside the window and do not overlap. */
    fun headerFits(): Boolean {
        val inner = panelWidth - 16
        val title = font.width("Greenhouse") + 12 + dropdownWidth + 6 + plotsButton.w
        return font.width(roseLine) <= inner && font.width(headerA) <= inner && title + 8 + foundWidth <= inner && disclaimer.all { font.width(it) <= inner }
    }

    val panelItem get() = selId?.let { "$selName x$selAmount" }
    val panelRoundCount get() = panelRounds
    val panelHasLayout get() = panelPicture != null
    val panelLayout get() = panelPicture?.layout
    val panelMessage get() = strip(panelNote.joinToString(" "))
    val panelBusy get() = panelPlanning

    private fun strip(text: String) = text.replace(Regex("§."), "")

    companion object {
        private const val ROW_H = 12
        private const val DD_H = 14
        private const val BUTTON_H = 14
        private const val BOX_W = 30
        private const val BOX_H = ROW_H - 2
        private const val MAX_DIGITS = 4

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
        private const val COST_COLOR = 0xFFE0C060.toInt()
        private const val PANEL = 0xFF1E1E1E.toInt()
        private const val PANEL_LIGHT = 0xFF323232.toInt()
        private const val PANEL_DARK = 0xFF141414.toInt()
        private const val SLOT_BORDER = 0xFF323232.toInt()
        private const val SLOT_BACKGROUND = 0xFF141414.toInt()
        private const val LOCKED = 0xFF262626.toInt()
        private const val ACCENT = 0xFF000000.toInt() or ConfigTheme.ACCENT
        private const val NEXT_BACKGROUND = 0xFF1A2A44.toInt()
        private const val GOOD = 0xFF3FBF4F.toInt()
        private const val BAD = 0xFFD04040.toInt()
        private const val UNKNOWN = 0xFF606060.toInt()
        private const val GOOD_TEXT = 0xFF55FF55.toInt()
        private const val BAD_TEXT = 0xFFFF5555.toInt()
        private const val TARGET_BORDER = 0xFFFFD040.toInt()
        private const val TARGET_FILL = 0xFF8A6A10.toInt()
        private const val MUTATION_FILL = 0xFF4A2A6A.toInt()
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
