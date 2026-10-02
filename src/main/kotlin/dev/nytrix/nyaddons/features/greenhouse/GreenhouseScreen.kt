package dev.nytrix.nyaddons.features.greenhouse

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.config.GreenhouseView
import dev.nytrix.nyaddons.core.Storage
import dev.nytrix.nyaddons.core.TimeUtils
import dev.nytrix.nyaddons.gui.ConfigTheme
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
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
    var labelWidth: Int = 0,
)

/** The Greenhouse helper window: unique mutations checklist, Rose Dragon needs and a mutation browser with a layout planner. */
class GreenhouseScreen : Screen(Component.literal("Greenhouse")) {

    private val config get() = NyAddOns.config.garden.greenhouse

    var view: GreenhouseView = config.view
        private set

    private var left = 0
    private var top = 0
    private var panelWidth = 0
    private var panelHeight = 0
    private var ticks = 0
    private var dropdownOpen = false
    private val scroll = IntArray(GreenhouseView.entries.size)

    // Models, rebuilt once a second and when something is clicked.
    private var unique = emptyList<UniqueRow>()
    private var uniqueCostWidth = 0
    private var headerA = ""
    private var headerB = ""
    private var footer = ""
    private var loading = false
    private var needs = emptyList<NeedLine>()
    private var needTexts = emptyArray<String>()
    private var needCounts = emptyArray<String>()
    private var all = emptyList<GhMutation>()
    private var allLabels = emptyArray<String>()
    private var selected = 0
    private var details = emptyList<String>()

    // Layout planner: only the last plan is kept.
    @Volatile private var planning = false
    @Volatile private var planResult: GhLayout? = null
    @Volatile private var planDone = false
    private var planFor: String? = null
    private var layout: GhLayout? = null
    private var legend = emptyList<String>()
    private var letters = HashMap<String, Char>()
    private var planGeneration = 0

    private val rowsVisible get() = ((listBottom - listTop) / ROW_H).coerceAtLeast(1)
    private val listTop get() = top + (if (view == GreenhouseView.ALL_MUTATIONS) 26 else 48)
    private val listBottom get() = top + panelHeight - 20
    private val listLeft get() = left + 8
    private val listRight get() = if (view == GreenhouseView.ALL_MUTATIONS) left + 8 + allListWidth else left + panelWidth - 8
    private val allListWidth get() = (panelWidth * 0.25).toInt().coerceIn(96, 150)
    private val detailLeft get() = listRight + 12
    private val detailWidth get() = (panelWidth * 0.32).toInt().coerceIn(116, 200)
    private val gridLeft get() = detailLeft + detailWidth + 8

    override fun init() {
        panelWidth = (width - 16).coerceIn(280, 640)
        panelHeight = (height - 16).coerceIn(180, 400)
        left = (width - panelWidth) / 2
        top = (height - panelHeight) / 2
        Greenhouse.data.request()
        rebuild()
    }

    override fun tick() {
        if (++ticks % 20 == 0) {
            Greenhouse.data.request()
            rebuild()
        }
    }

    override fun removed() {
        planGeneration++
        unique = emptyList()
        needs = emptyList()
        needTexts = emptyArray()
        needCounts = emptyArray()
        all = emptyList()
        allLabels = emptyArray()
        details = emptyList()
        layout = null
        planResult = null
        legend = emptyList()
        letters = HashMap()
    }

    // Models

    private fun rebuild() {
        val data = Greenhouse.data
        val stock = Greenhouse.stock
        loading = !data.ready
        val profile = Storage.profile.greenhouse
        val order = uniqueOrder(data.mutations)
        var nextTaken = false
        var costWidth = 0
        unique = order.map { m ->
            val done = m.id in profile.analysed
            val isNext = !done && !nextTaken
            if (isNext) nextTaken = true
            val row = UniqueRow(m.id, "${rarityCode(m.rarity)}${m.name}", costText(m), done, isNext, ingredientState(m, data, stock))
            row.labelWidth = font.width(row.label)
            costWidth = maxOf(costWidth, font.width(row.cost))
            row
        }
        uniqueCostWidth = costWidth
        val count = profile.analysed.size
        val milestone = nextMilestone(count)
        headerA = "§fAnalysed §b$count§f/${order.size.coerceAtLeast(38)}   " +
            if (milestone == null) "§aAll DNA milestones reached" else "§7Next milestone §f${milestone.roman} §7at §f${milestone.threshold}§7: §e${milestone.remaining} §7more"
        val nextRow = unique.firstOrNull { it.next }
        headerB = if (nextRow == null) "§7Nothing left to analyse." else "§7Next up: ${nextRow.label}  §8${nextRow.cost}"

        needs = roseDragonNeeds(data, stock)
        needTexts = Array(needs.size) { needs[it].label }
        needCounts = Array(needs.size) { i ->
            val n = needs[i]
            val have = formatCount(n.have ?: 0)
            if (n.need > 0) "$have/${formatCount(n.need)}" else if (n.have != null) "have $have" else ""
        }

        all = data.mutations.sortedBy { it.name }
        allLabels = Array(all.size) { "${rarityCode(all[it].rarity)}${all[it].name}" }
        selected = selected.coerceIn(0, (all.size - 1).coerceAtLeast(0))
        details = all.getOrNull(selected)?.let { detailLines(it, data, stock) } ?: emptyList()

        val updated = stock.sacksUpdatedAt
        footer = if (updated <= 0) "§7Sacks: open your sacks on the Garden" else "§7Sacks last updated: ${TimeUtils.format(System.currentTimeMillis() - updated).let { if (it == "Soon") "just now" else "$it ago" }}"
        for (i in scroll.indices) scroll[i] = scroll[i].coerceIn(0, maxScroll(i))
    }

    private fun formatCount(n: Int) = if (n >= 100_000) formatAmount(n.toLong()) else n.toString()

    private fun rowCount(v: Int) = when (v) {
        GreenhouseView.UNIQUE_MUTATIONS.ordinal -> unique.size
        GreenhouseView.ROSE_DRAGON.ordinal -> needs.size
        else -> all.size
    }

    private fun maxScroll(v: Int) = (rowCount(v) - rowsVisible).coerceAtLeast(0)

    // Drawing

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawPanel(graphics, left, top, panelWidth, panelHeight)
        graphics.text(font, "Greenhouse", left + 8, top + 7, TITLE_COLOR, false)
        when (view) {
            GreenhouseView.UNIQUE_MUTATIONS -> drawUnique(graphics, mouseX, mouseY)
            GreenhouseView.ROSE_DRAGON -> drawRose(graphics)
            GreenhouseView.ALL_MUTATIONS -> drawAll(graphics, mouseX, mouseY)
        }
        graphics.text(font, footer, left + 8, top + panelHeight - 13, WHITE, false)
        if (loading) {
            val text = "§eLoading Greenhouse data..."
            graphics.text(font, text, left + panelWidth - 8 - font.width(text), top + panelHeight - 13, WHITE, false)
        }
        drawDropdown(graphics, mouseX, mouseY)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    private val dropdownX get() = left + 8 + font.width("Greenhouse") + 12
    private val dropdownWidth get() = 112

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
        graphics.text(font, headerA, left + 8, top + 24, WHITE, false)
        graphics.text(font, headerB, left + 8, top + 35, WHITE, false)
        val right = listRight - 8
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
        }
        drawScrollbar(graphics, GreenhouseView.UNIQUE_MUTATIONS.ordinal, listRight - 4)
        if (!loading && unique.isEmpty()) graphics.text(font, "§7No mutation data.", listLeft, listTop + 2, WHITE, false)
    }

    private fun drawCheckbox(graphics: GuiGraphicsExtractor, x: Int, y: Int, checked: Boolean) {
        graphics.fill(x, y, x + 9, y + 9, SLOT_BORDER)
        graphics.fill(x + 1, y + 1, x + 8, y + 8, SLOT_BACKGROUND)
        if (checked) graphics.fill(x + 2, y + 2, x + 7, y + 7, ACCENT)
    }

    private fun drawRose(graphics: GuiGraphicsExtractor) {
        graphics.text(font, "§fRose Dragon: what you need", left + 8, top + 24, WHITE, false)
        graphics.text(font, "§7Green is covered, red is still short.", left + 8, top + 35, WHITE, false)
        val right = listRight - 8
        val first = scroll[GreenhouseView.ROSE_DRAGON.ordinal]
        for (i in 0 until rowsVisible) {
            val index = first + i
            val line = needs.getOrNull(index) ?: break
            val y = listTop + i * ROW_H
            val color = when {
                line.need <= 0 -> if (line.have == null) UNKNOWN_TEXT else TITLE_COLOR
                (line.have ?: 0) >= line.need -> GOOD_TEXT
                else -> BAD_TEXT
            }
            if (line.heading) graphics.fill(listLeft, y, right, y + ROW_H - 1, PANEL_LIGHT)
            graphics.text(font, needTexts[index], listLeft + 4 + line.indent * 12, y + 2, if (line.heading || line.need > 0) color else TITLE_COLOR, false)
            val counts = needCounts[index]
            graphics.text(font, counts, right - 4 - font.width(counts), y + 2, color, false)
        }
        drawScrollbar(graphics, GreenhouseView.ROSE_DRAGON.ordinal, listRight - 4)
        if (loading) graphics.text(font, "§7Mutation requirements appear once the data loads.", listLeft, listBottom - 10, WHITE, false)
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
            buildLegend()
        }
        val shown = layout
        if (planFor == mutation.id && shown == null && !planning) {
            graphics.text(font, "§cNo layout found", bx + bw + 6, listTop + 4, WHITE, false)
        } else if (shown != null && planFor == mutation.id) {
            drawGrid(graphics, shown, bx, listTop + BUTTON_H + 5)
        }
    }

    private fun buildLegend() {
        val shown = layout ?: return
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
        letters = HashMap(map)
        val maxWidth = left + panelWidth - 8 - gridLeft
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
        legend = lines
    }

    private fun drawGrid(graphics: GuiGraphicsExtractor, shown: GhLayout, x: Int, y: Int) {
        val legendHeight = legend.size * 9
        val availableHeight = listBottom - y - legendHeight - 3
        val availableWidth = left + panelWidth - 8 - x
        val cell = minOf(availableHeight, availableWidth).div(shown.size).coerceIn(9, 20)
        for (r in 0 until shown.size) for (c in 0 until shown.size) {
            val id = shown.cells[r][c]
            val cx = x + c * cell
            val cy = y + r * cell
            val isTarget = id != null && id == shown.target
            val isMutation = id != null && Greenhouse.data.mutation(id) != null
            graphics.fill(cx, cy, cx + cell - 1, cy + cell - 1, if (isTarget) TARGET_BORDER else SLOT_BORDER)
            graphics.fill(cx + 1, cy + 1, cx + cell - 2, cy + cell - 2, when {
                id == null -> SLOT_BACKGROUND
                isTarget -> TARGET_FILL
                isMutation -> MUTATION_FILL
                else -> CROP_FILL
            })
            if (id != null) {
                val letter = letters[id]?.toString() ?: "?"
                graphics.text(font, letter, cx + (cell - 1 - font.width(letter)) / 2, cy + (cell - 8) / 2, WHITE, false)
            }
        }
        val ly = y + shown.size * cell + 3
        for ((i, line) in legend.withIndex()) graphics.text(font, line, x, ly + i * 9, WHITE, false)
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
            } else if (mx in x until x + dropdownWidth && my in y until y + DD_H) {
                // Clicking the closed header closes it.
            }
            return true
        }
        if (mx in x until x + dropdownWidth && my in y until y + DD_H) {
            dropdownOpen = true
            return true
        }
        when (view) {
            GreenhouseView.UNIQUE_MUTATIONS -> {
                val index = rowAt(mx, my, listLeft, listRight - 8)
                val row = unique.getOrNull(index)
                if (row != null) {
                    toggleAnalysed(row.id)
                    return true
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
                    return true
                }
                if (mx in gridLeft until gridLeft + 70 && my in listTop until listTop + BUTTON_H) {
                    startPlan()
                    return true
                }
            }
            else -> {}
        }
        return super.mouseClicked(event, doubleClick)
    }

    private fun rowAt(mx: Int, my: Int, from: Int, to: Int): Int {
        if (mx < from || mx >= to || my < listTop || my >= listBottom) return -1
        val i = (my - listTop) / ROW_H
        return if (i < rowsVisible) scroll[view.ordinal] + i else -1
    }

    private fun selectView(next: GreenhouseView) {
        view = next
        config.view = next
        NyAddOns.saveConfig()
        rebuild()
    }

    private fun toggleAnalysed(id: String) {
        val set = Storage.profile.greenhouse.analysed
        if (!set.remove(id)) set.add(id)
        Storage.markDirty()
        rebuild()
    }

    private fun clearPlan() {
        planGeneration++
        planning = false
        planDone = false
        planResult = null
        planFor = null
        layout = null
        legend = emptyList()
    }

    private fun startPlan() {
        val target = all.getOrNull(selected) ?: return
        if (planning) return
        clearPlan()
        planning = true
        val generation = planGeneration
        Thread({
            val result = try {
                Greenhouse.planner.plan(target)
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
        val v = view.ordinal
        scroll[v] = (scroll[v] + if (scrollY > 0) -1 else 1).coerceIn(0, maxScroll(v))
        return true
    }

    override fun isPauseScreen() = false

    // Test hooks: where the mouse must go to click things (GUI coordinates).
    fun checkboxX() = listLeft + 6
    fun uniqueRowY(visibleIndex: Int) = listTop + visibleIndex * ROW_H + 5
    fun dropdownCenter() = intArrayOf(dropdownX + dropdownWidth / 2, top + 4 + DD_H / 2)
    fun dropdownOptionCenter(index: Int) = intArrayOf(dropdownX + dropdownWidth / 2, top + 4 + DD_H + index * DD_H + DD_H / 2)
    fun allRowCenter(visibleIndex: Int) = intArrayOf(listLeft + 20, listTop + visibleIndex * ROW_H + 5)
    fun planButtonCenter() = intArrayOf(gridLeft + 35, listTop + BUTTON_H / 2)
    val hasLayout get() = layout != null

    companion object {
        private const val ROW_H = 12
        private const val DD_H = 14
        private const val BUTTON_H = 14

        private const val WHITE = -1
        private const val TITLE_COLOR = 0xFFA0A0A0.toInt()
        private const val DIMMED_TEXT = 0xFF707070.toInt()
        private const val COST_COLOR = 0xFFE0C060.toInt()
        private const val PANEL = 0xFF1E1E1E.toInt()
        private const val PANEL_LIGHT = 0xFF323232.toInt()
        private const val PANEL_DARK = 0xFF141414.toInt()
        private const val SLOT_BORDER = 0xFF323232.toInt()
        private const val SLOT_BACKGROUND = 0xFF141414.toInt()
        private const val ACCENT = 0xFF000000.toInt() or ConfigTheme.ACCENT
        private const val NEXT_BACKGROUND = 0xFF1A2A44.toInt()
        private const val GOOD = 0xFF3FBF4F.toInt()
        private const val BAD = 0xFFD04040.toInt()
        private const val UNKNOWN = 0xFF606060.toInt()
        private const val GOOD_TEXT = 0xFF55FF55.toInt()
        private const val BAD_TEXT = 0xFFFF5555.toInt()
        private const val UNKNOWN_TEXT = 0xFFA0A0A0.toInt()
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
