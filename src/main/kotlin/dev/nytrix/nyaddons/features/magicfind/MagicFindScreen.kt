package dev.nytrix.nyaddons.features.magicfind

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.gui.ConfigTheme
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/** One prepared row: strings and widths are computed when the model is rebuilt, not while drawing. */
private class MobRow(val id: String, val name: String, val hint: String, val nameWidth: Int, val drop: String? = null, val expandable: Boolean = false)

/** The `/mf` window: a category dropdown and a checkbox list of the mobs whose Magic Find is reported on kill. */
class MagicFindScreen : Screen(Component.literal("Magic Find")) {

    private val config get() = NyAddOns.config.combat.magicFind

    private var left = 0
    private var top = 0
    private var panelWidth = 0
    private var panelHeight = 0
    private var ticks = 0
    private var dropdownOpen = false
    private var category = 0
    private var scroll = 0
    private var version = 0
    private val expanded = HashSet<String>()

    // Model cache: rebuilt only when this key changes.
    private var builtFor: List<MfCategory>? = null
    private var builtVersion = -1
    private var builtCategory = -1
    private var builtWidth = -1
    private var builtReady = false
    private var rows = emptyList<MobRow>()
    private var categories = emptyList<MfCategory>()
    private var categoryNames = emptyArray<String>()
    var header = ""
        private set
    var footer = ""
        private set
    private var loading = false

    private val listTop get() = top + 40
    private val listBottom get() = top + panelHeight - 20
    private val listLeft get() = left + 8
    private val listRight get() = left + panelWidth - 8
    private val rowsVisible get() = ((listBottom - listTop) / ROW_H).coerceAtLeast(1)
    private val maxScroll get() = (rows.size - rowsVisible).coerceAtLeast(0)
    private val dropdownX get() = left + 8 + font.width("Magic Find") + 12
    private val dropdownWidth get() = (categoryNames.maxOfOrNull { font.width(it) } ?: 80).plus(24).coerceIn(112, 200)
    private val allOffX get() = listRight - BUTTON_W
    private val allOnX get() = allOffX - 6 - BUTTON_W
    private val buttonY get() = top + 22

    override fun init() {
        panelWidth = (width - 16).coerceIn(280, 560)
        panelHeight = (height - 16).coerceIn(180, 400)
        left = (width - panelWidth) / 2
        top = (height - panelHeight) / 2
        builtWidth = -1
        MagicFindWindowFeature.applyDefaults()
        MagicFind.data.request()
        refresh()
    }

    override fun tick() {
        if (++ticks % 20 == 0) {
            MagicFind.data.request()
            MagicFindWindowFeature.applyDefaults()
            refresh()
        }
    }

    override fun removed() {
        expanded.clear()
        rows = emptyList()
        categories = emptyList()
        categoryNames = emptyArray()
        builtFor = null
        header = ""
        footer = ""
    }

    // Model

    private fun refresh() {
        val data = MagicFind.data
        val ready = data.ready
        val list = data.categories
        if (list === builtFor && version == builtVersion && category == builtCategory && width == builtWidth && ready == builtReady) return
        builtFor = list
        builtVersion = version
        builtCategory = category
        builtWidth = width
        builtReady = ready
        loading = !ready
        categories = list
        categoryNames = Array(list.size) { list[it].name }
        category = category.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        val mobs = list.getOrNull(category)?.mobs ?: emptyList()
        val maxHint = listRight - listLeft - 40
        val built = ArrayList<MobRow>(mobs.size)
        for (mob in mobs) {
            val hint = hintFor(mob)
            val nameWidth = font.width(mob.name)
            val room = maxHint - nameWidth - 14 - ARROW_W
            val shown = if (hint.isEmpty() || room < 24) "" else if (font.width(hint) <= room) hint else font.plainSubstrByWidth(hint, room - font.width("...")) + "..."
            built += MobRow(mob.id, mob.name, shown, nameWidth, null, mob.drops.isNotEmpty())
            if (mob.id in expanded) {
                val chosen = config.trackedDrops[mob.id]
                for (drop in MfTrackCommand.choices(mob)) {
                    val variants = mob.drops.filter { it.item == drop.item && !it.special }
                    val odds = when {
                        drop.special -> "special"
                        variants.size > 1 -> MfMath.oneIn(variants.minOf { it.chance }) + " - " + MfMath.oneIn(variants.maxOf { it.chance })
                        else -> MfMath.oneIn(drop.chance)
                    }
                    val mark = if (mob.id in config.trackedMobs && (chosen == null || chosen == drop.item)) "§a* " else ""
                    built += MobRow(mob.id, mark + drop.item, odds, font.width(mark + drop.item), drop.item)
                }
            }
        }
        rows = built
        val on = mobs.count { it.id in config.enabledMobs }
        header = "§f$on §7of §f${mobs.size} §7enabled"
        footer = "§7Click > for a mob's drops, click a drop to track it (§e/trackmob§7): §f${config.trackedMobs.size} §7tracked"
        scroll = scroll.coerceIn(0, maxScroll)
    }

    private fun hintFor(mob: MfMob): String {
        val best = mob.drops.filter { !it.special && it.chance > 0 }.minByOrNull { it.chance } ?: return ""
        return "1 in ${"%,d".format(Math.round(1.0 / best.chance))}  ${best.item}"
    }

    // Drawing

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawPanel(graphics, left, top, panelWidth, panelHeight)
        graphics.text(font, "Magic Find", left + 8, top + 7, TITLE_COLOR, false)
        graphics.text(font, header, left + 8, buttonY + 3, WHITE, false)
        drawButton(graphics, allOnX, "All on", mouseX, mouseY)
        drawButton(graphics, allOffX, "All off", mouseX, mouseY)
        drawRows(graphics, mouseX, mouseY)
        graphics.text(font, footer, left + 8, top + panelHeight - 13, WHITE, false)
        if (loading) {
            val text = "§eLoading Magic Find data..."
            graphics.text(font, text, listLeft + 2, listTop + 2, WHITE, false)
        }
        drawDropdown(graphics, mouseX, mouseY)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    private fun drawButton(graphics: GuiGraphicsExtractor, x: Int, label: String, mouseX: Int, mouseY: Int) {
        val y = buttonY
        val hover = !dropdownOpen && mouseX in x until x + BUTTON_W && mouseY in y until y + BUTTON_H
        graphics.fill(x, y, x + BUTTON_W, y + BUTTON_H, ACCENT)
        graphics.fill(x + 1, y + 1, x + BUTTON_W - 1, y + BUTTON_H - 1, if (hover) PANEL_LIGHT else SLOT_BACKGROUND)
        graphics.text(font, label, x + (BUTTON_W - font.width(label)) / 2, y + 3, WHITE, false)
    }

    private fun drawRows(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val right = listRight - 6
        for (i in 0 until rowsVisible) {
            val row = rows.getOrNull(scroll + i) ?: break
            val y = listTop + i * ROW_H
            val sub = row.drop != null
            val on = row.id in config.enabledMobs
            val hover = !dropdownOpen && mouseX in listLeft until right && mouseY in y until y + ROW_H
            if (hover) graphics.fill(listLeft, y, right, y + ROW_H - 1, PANEL_LIGHT)
            if (sub) {
                graphics.text(font, row.name, listLeft + 30, y + 2, WHITE, false)
                graphics.text(font, row.hint, listLeft + 30 + row.nameWidth + 10, y + 2, DIMMED_TEXT, false)
                continue
            }
            drawCheckbox(graphics, listLeft + 2, y + 1, on)
            graphics.text(font, row.name, listLeft + 16, y + 2, if (on) WHITE else TITLE_COLOR, false)
            if (row.hint.isNotEmpty()) graphics.text(font, row.hint, listLeft + 16 + row.nameWidth + 10, y + 2, DIMMED_TEXT, false)
            if (row.expandable) graphics.text(font, if (row.id in expanded) "v" else ">", right - ARROW_W + 4, y + 2, ACCENT, false)
        }
        val total = rows.size
        if (total > rowsVisible) {
            val h = listBottom - listTop
            val x = listRight - 4
            graphics.fill(x, listTop, x + 3, listBottom, SLOT_BACKGROUND)
            val thumb = (h * rowsVisible / total).coerceAtLeast(8)
            val y = listTop + (h - thumb) * scroll / maxScroll
            graphics.fill(x, y, x + 3, y + thumb, ACCENT)
        }
        if (!loading && rows.isEmpty()) graphics.text(font, "§7No mobs.", listLeft + 2, listTop + 2, WHITE, false)
    }

    private fun drawCheckbox(graphics: GuiGraphicsExtractor, x: Int, y: Int, checked: Boolean) {
        graphics.fill(x, y, x + 9, y + 9, SLOT_BORDER)
        graphics.fill(x + 1, y + 1, x + 8, y + 8, SLOT_BACKGROUND)
        if (checked) graphics.fill(x + 2, y + 2, x + 7, y + 7, ACCENT)
    }

    private fun drawDropdown(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val x = dropdownX
        val y = top + 4
        val w = dropdownWidth
        graphics.fill(x, y, x + w, y + DD_H, if (dropdownOpen) ACCENT else SLOT_BORDER)
        graphics.fill(x + 1, y + 1, x + w - 1, y + DD_H - 1, SLOT_BACKGROUND)
        graphics.text(font, categoryNames.getOrNull(category) ?: "...", x + 5, y + 4, WHITE, false)
        graphics.text(font, if (dropdownOpen) "^" else "v", x + w - 10, y + 4, TITLE_COLOR, false)
        if (!dropdownOpen) return
        graphics.fill(x, y + DD_H, x + w, y + DD_H + categoryNames.size * DD_H + 1, SLOT_BORDER)
        for (i in categoryNames.indices) {
            val oy = y + DD_H + i * DD_H
            val hover = mouseX in x until x + w && mouseY in oy until oy + DD_H
            graphics.fill(x + 1, oy, x + w - 1, oy + DD_H, if (hover) PANEL_LIGHT else SLOT_BACKGROUND)
            graphics.text(font, categoryNames[i], x + 5, oy + 4, if (i == category) ACCENT else WHITE, false)
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
                if (index in categoryNames.indices && index != category) {
                    category = index
                    scroll = 0
                    refresh()
                }
            }
            return true
        }
        if (mx in x until x + dropdownWidth && my in y until y + DD_H) {
            dropdownOpen = true
            return true
        }
        if (my in buttonY until buttonY + BUTTON_H) {
            if (mx in allOnX until allOnX + BUTTON_W) { setAll(true); return true }
            if (mx in allOffX until allOffX + BUTTON_W) { setAll(false); return true }
        }
        if (mx in listLeft until listRight - 6 && my in listTop until listBottom) {
            val i = (my - listTop) / ROW_H
            if (i < rowsVisible) rows.getOrNull(scroll + i)?.let { row ->
                when {
                    row.drop != null -> toggleDrop(row.id, row.drop)
                    row.expandable && mx >= listRight - 6 - ARROW_W -> toggleExpanded(row.id)
                    else -> toggle(row.id)
                }
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    private fun toggle(id: String) {
        if (!config.enabledMobs.remove(id)) config.enabledMobs.add(id)
        changed()
    }

    private fun toggleExpanded(id: String) {
        if (!expanded.remove(id)) expanded.add(id)
        version++
        refresh()
    }

    /** Clicking a drop follows exactly that drop for the mob; clicking the followed drop again stops tracking the mob. */
    private fun toggleDrop(id: String, item: String) {
        if (id in config.trackedMobs && config.trackedDrops[id] == item) {
            config.trackedMobs.remove(id)
            config.trackedDrops.remove(id)
        } else {
            config.trackedMobs.add(id)
            config.trackedDrops[id] = item
        }
        changed()
    }

    private fun setAll(on: Boolean) {
        val ids = categories.getOrNull(category)?.mobs?.map { it.id } ?: return
        if (on) config.enabledMobs.addAll(ids) else config.enabledMobs.removeAll(ids.toSet())
        changed()
    }

    private fun changed() {
        NyAddOns.saveConfig()
        version++
        refresh()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        scroll = (scroll + if (scrollY > 0) -1 else 1).coerceIn(0, maxScroll)
        return true
    }

    override fun isPauseScreen() = false

    // Test hooks (GUI coordinates).
    fun dropdownCenter() = intArrayOf(dropdownX + dropdownWidth / 2, top + 4 + DD_H / 2)
    fun dropdownOptionCenter(index: Int) = intArrayOf(dropdownX + dropdownWidth / 2, top + 4 + DD_H + index * DD_H + DD_H / 2)
    fun checkboxCenter(visibleIndex: Int) = intArrayOf(listLeft + 6, listTop + visibleIndex * ROW_H + 5)
    fun arrowCenter(visibleIndex: Int) = intArrayOf(listRight - 6 - ARROW_W / 2, listTop + visibleIndex * ROW_H + 5)
    fun dropRowCenter(visibleIndex: Int) = intArrayOf(listLeft + 40, listTop + visibleIndex * ROW_H + 5)
    fun allOnCenter() = intArrayOf(allOnX + BUTTON_W / 2, buttonY + BUTTON_H / 2)
    fun allOffCenter() = intArrayOf(allOffX + BUTTON_W / 2, buttonY + BUTTON_H / 2)
    val rowCount get() = rows.size

    companion object {
        private const val ROW_H = 12
        private const val ARROW_W = 14
        private const val DD_H = 14
        private const val BUTTON_H = 14
        private const val BUTTON_W = 46

        private const val WHITE = -1
        private const val TITLE_COLOR = 0xFFA0A0A0.toInt()
        private const val DIMMED_TEXT = 0xFF707070.toInt()
        private const val PANEL = 0xFF1E1E1E.toInt()
        private const val PANEL_LIGHT = 0xFF323232.toInt()
        private const val PANEL_DARK = 0xFF141414.toInt()
        private const val SLOT_BORDER = 0xFF323232.toInt()
        private const val SLOT_BACKGROUND = 0xFF141414.toInt()
        private const val ACCENT = 0xFF000000.toInt() or ConfigTheme.ACCENT

        fun open() {
            NyAddOns.openScreen { MagicFindScreen() }
        }

        private fun drawPanel(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
            graphics.fill(x, y, x + width, y + height, PANEL_LIGHT)
            graphics.fill(x + 1, y + 1, x + width, y + height, PANEL_DARK)
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, PANEL)
        }
    }
}
