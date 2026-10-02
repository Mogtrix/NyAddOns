package dev.nytrix.nyaddons.gui

import dev.nytrix.nyaddons.NyAddOns
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

/** Shows every overlay at once so they can be dragged around and resized, like SkyHanni's `/sh gui`. */
class PositionEditorScreen : Screen(Component.literal("NyAddOns Position Editor")) {

    private var dragging: Overlay? = null
    private var grabX = 0
    private var grabY = 0

    private class Box(val overlay: Overlay, val lines: List<String>, val width: Int, val height: Int) {
        val position get() = overlay.position()
        fun contains(x: Double, y: Double) =
            x >= position.x && x < position.x + width && y >= position.y && y < position.y + height
    }

    private fun boxes() = OverlayManager.overlays.map { overlay ->
        val lines = overlay.lines().ifEmpty { overlay.example }
        val (width, height) = OverlayManager.sizeOf(lines)
        val scale = OverlayManager.scaleOf(overlay.position())
        Box(overlay, lines, (width * scale).roundToInt(), (height * scale).roundToInt())
    }

    private fun boxAt(x: Double, y: Double) = boxes().lastOrNull { it.contains(x, y) }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Keep the game visible behind the editor: no blur, no dimming.
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val font = Minecraft.getInstance().font
        graphics.centeredText(font, "§cNyAddOns Position Editor", width / 2, 8, WHITE)
        graphics.centeredText(font, "§eDrag to move, scroll to resize, arrow keys to nudge", width / 2, 20, WHITE)

        val hovered = dragging ?: boxAt(mouseX.toDouble(), mouseY.toDouble())?.overlay
        for (box in boxes()) {
            val position = box.position
            position.x = position.x.coerceIn(0, (width - box.width).coerceAtLeast(0))
            position.y = position.y.coerceIn(0, (height - box.height).coerceAtLeast(0))
            val color = if (box.overlay === hovered) BOX_HOVERED else BOX
            graphics.fill(position.x, position.y, position.x + box.width, position.y + box.height, color)
            OverlayManager.draw(graphics, position, box.lines)
        }

        if (hovered != null && dragging == null) {
            val position = hovered.position()
            graphics.setComponentTooltipForNextFrame(
                font,
                listOf(
                    Component.literal("§b${hovered.label}"),
                    Component.literal("  §7x: §e${position.x}§7, y: §e${position.y}§7, scale: §e${"%.2f".format(position.scale)}"),
                    Component.literal("§eUse Scroll-Wheel to resize!"),
                ),
                mouseX, mouseY,
            )
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val box = boxAt(event.x(), event.y()) ?: return super.mouseClicked(event, doubleClick)
        dragging = box.overlay
        grabX = event.x().toInt() - box.position.x
        grabY = event.y().toInt() - box.position.y
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val position = dragging?.position() ?: return super.mouseDragged(event, dragX, dragY)
        position.x = event.x().toInt() - grabX
        position.y = event.y().toInt() - grabY
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        dragging = null
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val position = boxAt(mouseX, mouseY)?.position ?: return false
        val steps = (position.scale * 10).roundToInt() + if (scrollY > 0) 1 else -1
        position.scale = (steps / 10f).coerceIn(0.5f, 5f)
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val mouse = Minecraft.getInstance().mouseHandler
        val window = Minecraft.getInstance().window
        val mouseX = mouse.xpos() * window.guiScaledWidth / window.screenWidth
        val mouseY = mouse.ypos() * window.guiScaledHeight / window.screenHeight
        val position = boxAt(mouseX, mouseY)?.position ?: return super.keyPressed(event)
        when (event.key()) {
            KEY_RIGHT -> position.x++
            KEY_LEFT -> position.x--
            KEY_DOWN -> position.y++
            KEY_UP -> position.y--
            else -> return super.keyPressed(event)
        }
        return true
    }

    override fun removed() {
        NyAddOns.saveConfig()
        super.removed()
    }

    override fun isPauseScreen() = false

    companion object {
        private const val WHITE = -1
        private const val BOX = 0x80404040.toInt()
        private const val BOX_HOVERED = 0x80808080.toInt()

        // GLFW key codes
        private const val KEY_RIGHT = 262
        private const val KEY_LEFT = 263
        private const val KEY_DOWN = 264
        private const val KEY_UP = 265

        fun open() {
            NyAddOns.openScreen { PositionEditorScreen() }
        }
    }
}
