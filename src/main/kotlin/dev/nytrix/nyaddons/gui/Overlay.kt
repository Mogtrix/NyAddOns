package dev.nytrix.nyaddons.gui

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.config.Position
import dev.nytrix.nyaddons.core.SkyBlockData
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier

/** Something an overlay can show. Drawn with its top-left corner at 0,0; the overlay places and scales it. */
interface OverlayContent {
    val width: Int
    val height: Int
    fun draw(graphics: GuiGraphicsExtractor)
}

/** Plain shadowed text lines, the way SkyHanni draws its displays. */
class TextContent(private val lines: List<String>) : OverlayContent {
    private val font get() = Minecraft.getInstance().font

    override val width get() = (lines.maxOfOrNull { font.width(it) } ?: 0) + 2
    override val height get() = lines.size * LINE_HEIGHT + 1

    override fun draw(graphics: GuiGraphicsExtractor) {
        lines.forEachIndexed { index, line ->
            graphics.text(font, "§f$line", 1, 1 + index * LINE_HEIGHT, WHITE, true)
        }
    }

    private companion object {
        const val LINE_HEIGHT = 10
        const val WHITE = -1
    }
}

/**
 * Something on screen that the player can move and resize in the position editor.
 *
 * @param label name shown in the position editor
 * @param position where it is stored in the config
 * @param example shown in the position editor when there is nothing to display
 * @param content what to draw right now, or null to hide the overlay
 * @param onHud false for overlays that a feature draws itself, for example only inside a menu
 */
class Overlay(
    val label: String,
    val position: () -> Position,
    val example: () -> OverlayContent,
    val content: () -> OverlayContent?,
    val onHud: Boolean = true,
) {
    /** A text overlay: [lines] returns the lines to draw, or an empty list to hide it. */
    constructor(label: String, position: () -> Position, example: List<String>, lines: () -> List<String>) : this(
        label, position, { TextContent(example) }, { lines().takeIf { it.isNotEmpty() }?.let(::TextContent) },
    )
}

object OverlayManager {

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
            if (overlay.onHud) overlay.content()?.let { draw(graphics, overlay.position(), it) }
        }
    }

    fun scaleOf(position: Position) = position.scale * NyAddOns.config.gui.globalScale

    fun draw(graphics: GuiGraphicsExtractor, position: Position, content: OverlayContent) {
        val scale = scaleOf(position)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(position.x.toFloat(), position.y.toFloat())
        pose.scale(scale, scale)
        content.draw(graphics)
        pose.popMatrix()
    }
}
