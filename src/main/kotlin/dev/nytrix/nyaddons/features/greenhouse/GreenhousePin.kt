package dev.nytrix.nyaddons.features.greenhouse

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.config.PinArea
import dev.nytrix.nyaddons.core.PinnedPlot
import dev.nytrix.nyaddons.core.SkyBlockData
import dev.nytrix.nyaddons.core.Storage
import dev.nytrix.nyaddons.gui.Overlay
import dev.nytrix.nyaddons.gui.OverlayContent
import dev.nytrix.nyaddons.gui.OverlayManager
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The plot pinned to the screen: a snapshot taken from the Planner or Rose Dragon side panel (the layout grid, its summary line and a
 * legend), kept per profile and drawn as a HUD overlay. The drawable content is built once per pin (and again when the Greenhouse
 * data arrives) and drawing only loops over plain arrays.
 */
object GreenhousePin {

    private val config get() = NyAddOns.config.garden.greenhouse

    private var built: PinnedContent? = null
    private var builtFor: PinnedPlot? = null
    private var builtReady = false

    fun register() {
        OverlayManager.register(Overlay("Pinned Plot", { config.pinnedPosition }, { PinnedContent(example(), true) }, ::content))
    }

    /** The pinned plot of the current profile, or null. */
    val current: PinnedPlot? get() = Storage.profile.greenhouse.pin

    fun pin(plot: PinnedPlot) {
        Storage.profile.greenhouse.pin = plot
        Storage.markDirty()
        OverlayManager.invalidate()
    }

    fun unpin() {
        Storage.profile.greenhouse.pin = null
        Storage.markDirty()
        OverlayManager.invalidate()
    }

    /** True when the overlay may be drawn here and now. */
    fun visible(): Boolean = config.pinnedEnabled && (config.pinnedArea == PinArea.ALL_ISLANDS || SkyBlockData.area == "Garden")

    private fun content(): OverlayContent? {
        if (!visible()) return null
        val pin = current ?: return null
        val ready = Greenhouse.data.ready
        if (builtFor !== pin || builtReady != ready) {
            built = PinnedContent(pin, false).takeIf { it.hasBlocks }
            builtFor = pin
            builtReady = ready
        }
        return built
    }

    /** What the position editor shows before anything is pinned. */
    private fun example(): PinnedPlot {
        val cells = Array(10) { arrayOfNulls<String>(10) }
        for (c in 3..5) cells[4][c] = "wheat"
        cells[5][4] = "ashwreath"
        cells[3][4] = "nether_wart"
        return PinnedPlot.of(cells, "Pinned plot", "§fPlanned: §e1 §fmutation (1 kind)")
    }
}

/** A pinned plot ready to draw at 0,0: panel, title, summary, the cropped grid with big icons for 2x2 and 3x3 blocks, and the legend. */
class PinnedContent(pin: PinnedPlot, example: Boolean) : OverlayContent {

    private val font get() = Minecraft.getInstance().font

    private val blockRow: IntArray
    private val blockCol: IntArray
    private val blockSpan: IntArray
    private val blockId: Array<String>
    private val blockMutation: BooleanArray
    private val blockSoil: Array<String>
    private val legendIds: Array<String>
    private val legendNames: Array<String>
    private val soils: Array<String>
    private val soilNames: Array<String>
    private val title: String
    private val summary: List<String>
    private val rows: Int
    private val cols: Int
    private val gridX: Int
    private val gridY: Int
    private val legendY: Int

    override val width: Int
    override val height: Int
    val hasBlocks: Boolean

    init {
        val data = Greenhouse.data
        val seen = BooleanArray(100)
        val rs = ArrayList<Int>()
        val cs = ArrayList<Int>()
        val spans = ArrayList<Int>()
        val ids = ArrayList<String>()
        val legend = LinkedHashSet<String>()
        for (r in 0 until 10) for (c in 0 until 10) {
            val id = pin.idAt(r * 10 + c) ?: continue
            if (seen[r * 10 + c]) continue
            legend += id
            var span = (data.mutation(id)?.size ?: data.crops.firstOrNull { it.id == id }?.size ?: 1).coerceIn(1, 3)
            if (span > 1) {
                var whole = r + span <= 10 && c + span <= 10
                if (whole) for (dr in 0 until span) for (dc in 0 until span) if (pin.idAt((r + dr) * 10 + c + dc) != id || seen[(r + dr) * 10 + c + dc]) whole = false
                if (!whole) span = 1
            }
            for (dr in 0 until span) for (dc in 0 until span) seen[(r + dr) * 10 + c + dc] = true
            rs += r
            cs += c
            spans += span
            ids += id
        }
        hasBlocks = ids.isNotEmpty()
        var r0 = 10
        var r1 = -1
        var c0 = 10
        var c1 = -1
        for (b in ids.indices) {
            r0 = minOf(r0, rs[b])
            r1 = maxOf(r1, rs[b] + spans[b] - 1)
            c0 = minOf(c0, cs[b])
            c1 = maxOf(c1, cs[b] + spans[b] - 1)
        }
        if (r1 < 0) { r0 = 0; r1 = 0; c0 = 0; c1 = 0 }
        rows = r1 - r0 + 1
        cols = c1 - c0 + 1
        blockRow = IntArray(ids.size) { rs[it] - r0 }
        blockCol = IntArray(ids.size) { cs[it] - c0 }
        blockSpan = spans.toIntArray()
        blockId = ids.toTypedArray()
        blockMutation = BooleanArray(ids.size) { data.mutation(ids[it]) != null }
        blockSoil = Array(ids.size) { data.mutation(ids[it])?.soil ?: data.crops.firstOrNull { c -> c.id == ids[it] }?.soil ?: "" }
        legendIds = legend.toTypedArray()
        legendNames = Array(legendIds.size) { if (data.ready) data.nameOf(legendIds[it]) else prettify(legendIds[it]) }
        soils = blockSoil.filter { it.isNotEmpty() }.distinct().toTypedArray()
        soilNames = Array(soils.size) { prettify(soils[it]) }

        val gridW = cols * CELL + 1
        title = if (example) "§7Pinned plot (example)" else "§e${pin.title}"
        var widest = maxOf(gridW, font.width(title))
        // The summary wraps to the width of the grid, but at least 140 pixels so it stays readable.
        val limit = maxOf(gridW, 140)
        val lines = ArrayList<String>(3)
        var current = ""
        for (word in pin.summary.split(' ')) {
            val next = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && font.width(next) > limit) {
                lines += current
                current = word
            } else current = next
        }
        if (current.isNotEmpty()) lines += current
        summary = lines
        for (line in lines) widest = maxOf(widest, font.width(line))
        for (name in legendNames) widest = maxOf(widest, ICON + 3 + font.width(name))
        for (name in soilNames) widest = maxOf(widest, ICON + 3 + font.width(name))
        width = widest + PAD * 2
        gridX = PAD + (widest - gridW) / 2
        gridY = PAD + 11 + lines.size * 9 + 2
        legendY = gridY + rows * CELL + 1 + 4
        height = legendY + (legendIds.size + soils.size) * LEGEND_H + PAD - 1
    }

    override fun draw(graphics: GuiGraphicsExtractor) {
        val font = font
        graphics.fill(0, 0, width, height, BACKGROUND)
        graphics.text(font, title, PAD, PAD, WHITE, true)
        for (i in summary.indices) graphics.text(font, summary[i], PAD, PAD + 11 + i * 9, WHITE, true)
        graphics.fill(gridX, gridY, gridX + cols * CELL + 1, gridY + rows * CELL + 1, GRID_LINE)
        for (r in 0 until rows) for (c in 0 until cols) graphics.fill(gridX + c * CELL + 1, gridY + r * CELL + 1, gridX + c * CELL + CELL, gridY + r * CELL + CELL, EMPTY_CELL)
        for (b in blockId.indices) {
            val size = blockSpan[b] * CELL
            val x = gridX + blockCol[b] * CELL
            val y = gridY + blockRow[b] * CELL
            graphics.fill(x + 1, y + 1, x + size, y + size, GhSoil.color(blockSoil[b]))
            graphics.fill(x + 1, y + 1, x + size, y + 2, GhSoil.LIGHT)
            graphics.fill(x + 1, y + size - 1, x + size, y + size, GhSoil.SHADE)
            if (blockMutation[b]) graphics.fill(x + size - 4, y + 2, x + size - 1, y + 5, GhSoil.MUTATION_PIP)
            if (size >= 28) graphics.text(font, GhSoil.letter(blockSoil[b]), x + 3, y + size - 9, GhSoil.letterColor(blockSoil[b]), false)
            val icon = size - 4
            GhIcons.draw(graphics, blockId[b], x + 2, y + 2, icon)
        }
        for (i in legendIds.indices) {
            val y = legendY + i * LEGEND_H
            GhIcons.draw(graphics, legendIds[i], PAD, y, ICON)
            graphics.text(font, legendNames[i], PAD + ICON + 3, y + 1, WHITE, true)
        }
        // Soil key: HUD blocks are too small for badges and light soils (sand, end stone) look alike, so colour and letter are named here.
        for (i in soils.indices) {
            val y = legendY + (legendIds.size + i) * LEGEND_H
            graphics.fill(PAD, y, PAD + ICON, y + ICON, GhSoil.EDGE)
            graphics.fill(PAD + 1, y + 1, PAD + ICON - 1, y + ICON - 1, GhSoil.color(soils[i]))
            graphics.text(font, GhSoil.letter(soils[i]), PAD + 3, y + 1, GhSoil.letterColor(soils[i]), false)
            graphics.text(font, soilNames[i], PAD + ICON + 3, y + 1, WHITE, true)
        }
    }

    private companion object {
        const val CELL = 14
        const val ICON = 10
        const val PAD = 4
        const val LEGEND_H = 12
        const val WHITE = -1
        const val BACKGROUND = 0xA0101010.toInt()
        const val GRID_LINE = 0xFF3A3A3A.toInt()
        const val EMPTY_CELL = 0xFF2A2A2A.toInt()
    }
}
