package dev.nytrix.nyaddons.gui

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.config.Position
import dev.nytrix.nyaddons.core.SkyBlockData
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier

/**
 * A block of text lines on the HUD that the player can move and resize in the position editor.
 *
 * @param label name shown in the position editor
 * @param position where it is stored in the config
 * @param example lines shown in the position editor when there is nothing to display
 * @param lines the lines to draw right now; return an empty list to hide the overlay
 */
class Overlay(
    val label: String,
    val position: () -> Position,
    val example: List<String>,
    val lines: () -> List<String>,
)

object OverlayManager {

    private const val LINE_HEIGHT = 10
    private const val WHITE = -1

    val overlays = mutableListOf<Overlay>()

    fun register(overlay: Overlay) {
        overlays += overlay
    }

    fun init() {
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath(NyAddOns.MOD_ID, "overlays"),
        ) { graphics, _ -> render(graphics) }
    }

    private fun render(graphics: GuiGraphicsExtractor) {
        val mc = Minecraft.getInstance()
        if (!SkyBlockData.onSkyBlock || mc.options.hideGui || mc.screen is PositionEditorScreen) return
        for (overlay in overlays) {
            val lines = overlay.lines()
            if (lines.isNotEmpty()) draw(graphics, overlay.position(), lines)
        }
    }

    fun scaleOf(position: Position) = position.scale * NyAddOns.config.gui.globalScale

    /** Unscaled width and height of a block of lines. */
    fun sizeOf(lines: List<String>): Pair<Int, Int> {
        val font = Minecraft.getInstance().font
        return (lines.maxOfOrNull { font.width(it) } ?: 0) + 2 to lines.size * LINE_HEIGHT + 1
    }

    fun draw(graphics: GuiGraphicsExtractor, position: Position, lines: List<String>) {
        val font = Minecraft.getInstance().font
        val scale = scaleOf(position)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(position.x.toFloat(), position.y.toFloat())
        pose.scale(scale, scale)
        lines.forEachIndexed { index, line ->
            graphics.text(font, "§f$line", 1, 1 + index * LINE_HEIGHT, WHITE, true)
        }
        pose.popMatrix()
    }
}
