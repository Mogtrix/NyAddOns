package dev.nytrix.nyaddons.features.hunting

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.gui.ConfigTheme
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * A chest-shaped grid of every shard, laid out like the Hunting Box. Clicking a shard tracks or
 * untracks it. Shards the player has none of are dimmed.
 */
class ShardPickerScreen : Screen(Component.literal("Shard Picker")) {

    private var left = 0
    private var top = 0
    private var page = 0
    private var query = ""
    private var shown = emptyList<Shard>()

    private val pages get() = ((shown.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)

    override fun init() {
        left = (width - PANEL_WIDTH) / 2
        top = (height - PANEL_HEIGHT) / 2
        val search = EditBox(font, left + 8, top + 18, PANEL_WIDTH - 16, 14, Component.literal("Search"))
        search.setHint(Component.literal("§8Search shards..."))
        search.setMaxLength(40)
        search.value = query
        search.setResponder {
            query = it
            page = 0
            refresh()
        }
        addRenderableWidget(search)
        setInitialFocus(search)
        refresh()
    }

    private fun refresh() {
        val text = query.trim()
        shown = ShardRepo.all
            .filter {
                text.isEmpty() || it.name.contains(text, true) || it.attribute.contains(text, true) || it.code.equals(text, true)
            }
            .sortedWith(
                compareByDescending<Shard> { ShardTracker.isTracked(it) }
                    .thenBy { it.rarity }
                    .thenBy { it.code.drop(1).toIntOrNull() ?: 0 },
            )
        page = page.coerceIn(0, pages - 1)
    }

    private fun slotX(index: Int) = left + 8 + (index % COLUMNS) * SLOT
    private fun slotY(index: Int) = top + 38 + (index / COLUMNS) * SLOT

    private fun shardAt(mouseX: Double, mouseY: Double): Shard? {
        val column = ((mouseX - left - 8) / SLOT).let { if (it < 0) return null else it.toInt() }
        val row = ((mouseY - top - 38) / SLOT).let { if (it < 0) return null else it.toInt() }
        if (column >= COLUMNS || row >= ROWS) return null
        return shown.getOrNull(page * PAGE_SIZE + row * COLUMNS + column)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawPanel(graphics, left, top, PANEL_WIDTH, PANEL_HEIGHT)
        graphics.text(font, "Shard Picker", left + 8, top + 6, TITLE_COLOR, false)
        val pageText = "${page + 1}/$pages"
        graphics.text(font, pageText, left + PANEL_WIDTH - 8 - font.width(pageText), top + 6, TITLE_COLOR, false)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        for (index in 0 until PAGE_SIZE) {
            val x = slotX(index)
            val y = slotY(index)
            val shard = shown.getOrNull(page * PAGE_SIZE + index)
            val tracked = shard != null && ShardTracker.isTracked(shard)
            graphics.fill(x, y, x + SLOT - 1, y + SLOT - 1, if (tracked) ACCENT else SLOT_BORDER)
            graphics.fill(x + 1, y + 1, x + SLOT - 2, y + SLOT - 2, SLOT_BACKGROUND)
            if (shard == null) continue
            graphics.item(ShardIcons.of(shard), x, y)
            val owned = ShardTracker.progress(shard).owned ?: 0
            if (owned == 0) {
                graphics.fill(x + 1, y + 1, x + SLOT - 2, y + SLOT - 2, DIMMED)
            } else {
                graphics.itemDecorations(font, ShardIcons.of(shard), x, y, if (owned > 999) "${owned / 1000}k" else owned.toString())
            }
        }

        val navY = top + PANEL_HEIGHT - 16
        graphics.text(font, if (page > 0) "§f< Previous" else "§8< Previous", left + 8, navY, WHITE, false)
        val next = if (page < pages - 1) "§fNext >" else "§8Next >"
        graphics.text(font, next, left + PANEL_WIDTH - 8 - font.width("Next >"), navY, WHITE, false)
        if (!ShardRepo.loaded) {
            graphics.centeredText(font, "§7Loading the shard list...", left + PANEL_WIDTH / 2, top + 70, WHITE)
        }

        shardAt(mouseX.toDouble(), mouseY.toDouble())?.let { shard ->
            graphics.setComponentTooltipForNextFrame(font, tooltip(shard).map { Component.literal(it) }, mouseX, mouseY)
        }
    }

    private fun tooltip(shard: Shard) = listOf(
        "${shard.coloredName} Shard §8(${shard.code})",
        "§7Attribute: §e${shard.attribute}",
        "§7${ShardTracker.status(shard).replaceFirstChar { it.uppercase() }}",
        "",
        if (ShardTracker.isTracked(shard)) "§eClick to stop tracking!" else "§eClick to track!",
    )

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        shardAt(event.x(), event.y())?.let { shard ->
            ShardTracker.toggle(shard)
            return true
        }
        val navY = top + PANEL_HEIGHT - 18
        if (event.y() >= navY && event.y() < navY + 14) {
            if (event.x() >= left + 8 && event.x() < left + 8 + font.width("< Previous")) return turnPage(-1)
            if (event.x() >= left + PANEL_WIDTH - 8 - font.width("Next >") && event.x() < left + PANEL_WIDTH - 8) return turnPage(1)
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean =
        turnPage(if (scrollY > 0) -1 else 1)

    private fun turnPage(by: Int): Boolean {
        page = (page + by).coerceIn(0, pages - 1)
        return true
    }

    override fun isPauseScreen() = false

    companion object {
        private const val COLUMNS = 9
        private const val ROWS = 5
        private const val PAGE_SIZE = COLUMNS * ROWS
        private const val SLOT = 18
        private const val PANEL_WIDTH = COLUMNS * SLOT + 15
        private const val PANEL_HEIGHT = 38 + ROWS * SLOT + 24

        private const val WHITE = -1
        private const val TITLE_COLOR = 0xFFA0A0A0.toInt()
        private const val PANEL = 0xFF1E1E1E.toInt()
        private const val PANEL_LIGHT = 0xFF323232.toInt()
        private const val PANEL_DARK = 0xFF141414.toInt()
        private const val SLOT_BORDER = 0xFF323232.toInt()
        private const val SLOT_BACKGROUND = 0xFF141414.toInt()
        private const val DIMMED = 0xB0141414.toInt()
        private const val ACCENT = 0xFF000000.toInt() or ConfigTheme.ACCENT

        fun open() {
            NyAddOns.openScreen { ShardPickerScreen() }
        }

        /** The same charcoal panel the config screen uses. */
        private fun drawPanel(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
            graphics.fill(x, y, x + width, y + height, PANEL_LIGHT)
            graphics.fill(x + 1, y + 1, x + width, y + height, PANEL_DARK)
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, PANEL)
        }
    }
}
