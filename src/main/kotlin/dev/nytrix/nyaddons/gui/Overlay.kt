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
class TextContent(lines: List<String>) : OverlayContent {
    private val font get() = Minecraft.getInstance().font
    private val lines = lines.map { "§f$it" }

    override val width by lazy { (this.lines.maxOfOrNull { font.width(it) } ?: 0) + 2 }
    override val height = lines.size * LINE_HEIGHT + 1

    override fun draw(graphics: GuiGraphicsExtractor) {
        for (index in lines.indices) {
            graphics.text(font, lines[index], 1, 1 + index * LINE_HEIGHT, WHITE, true)
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
    private var cached: OverlayContent? = null
    private var cachedAt = Int.MIN_VALUE
    private var cachedVersion = -1

    /**
     * What to draw this frame. The content is rebuilt a few times a second, not every frame:
     * nothing an overlay shows changes faster than that.
     */
    fun current(): OverlayContent? {
        val now = OverlayManager.ticks
        if (cachedVersion != OverlayManager.version || now - cachedAt >= OverlayManager.REFRESH_TICKS) {
            cached = content()
            cachedAt = now
            cachedVersion = OverlayManager.version
        }
        return cached
    }

    /** A text overlay: [lines] returns the lines to draw, or an empty list to hide it. */
    constructor(label: String, position: () -> Position, example: List<String>, lines: () -> List<String>) : this(
        label, position, { TextContent(example) }, { lines().takeIf { it.isNotEmpty() }?.let(::TextContent) },
    )
}

object OverlayManager {

    /** How long drawn content is reused for. */
    const val REFRESH_TICKS = 5

    val overlays = mutableListOf<Overlay>()

    /** Client ticks since the game started. */
    var ticks = 0
        private set

    /** Changes whenever something should be redrawn straight away. */
    var version = 0
        private set

    fun tick() {
        ticks++
    }

    /** Makes every overlay rebuild on the next frame, for changes the player expects to see at once. */
    fun invalidate() {
        version++
    }

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
            if (overlay.onHud) overlay.current()?.let { draw(graphics, overlay.position(), it) }
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
