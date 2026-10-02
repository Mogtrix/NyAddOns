package dev.nytrix.nyaddons.gui

import dev.nytrix.nyaddons.NyAddOns
import io.github.notenoughupdates.moulconfig.GuiTextures
import io.github.notenoughupdates.moulconfig.common.IFontRenderer
import io.github.notenoughupdates.moulconfig.common.IMinecraft
import io.github.notenoughupdates.moulconfig.common.MyResourceLocation
import io.github.notenoughupdates.moulconfig.common.RenderContext
import io.github.notenoughupdates.moulconfig.common.text.StructuredText
import io.github.notenoughupdates.moulconfig.platform.MoulConfigPlatform
import io.github.notenoughupdates.moulconfig.platform.MoulConfigRenderContext
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Recolours the MoulConfig screen: neutral charcoal panels with a blue accent, the palette SBO
 * uses, while leaving the layout alone. MoulConfig draws its panels in code with fixed colours,
 * so the colours are swapped on their way to the screen; its textures are replaced by the
 * recoloured copies in `assets/nyaddons/moulconfig`.
 */
object ConfigTheme {

    const val ACCENT = 0x006EFA

    private const val PANEL = 0xFF1E1E1E.toInt()
    private const val PANEL_LIGHT = 0xFF323232.toInt()
    private const val PANEL_DARK = 0xFF141414.toInt()
    private const val SHADOW = 0x70000000

    // The colour MoulConfig gives the "Categories" heading.
    private const val HEADING = 0xA368EF

    fun init() {
        GuiTextures.setTextureRoot(MyResourceLocation(NyAddOns.MOD_ID, "moulconfig"))
        if (IMinecraft.INSTANCE !is NyPlatform) {
            NyAddOns.logger.warn("Config theme is not active, the config screen keeps MoulConfig's colours")
        }
    }

    /** MoulConfig's greys carry a slight blue tint; this flattens them to neutral grey. */
    fun neutral(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        if (r != g || b - r !in 1..8) return color
        val grey = r + (b - r) / 2
        return (color and 0xFF000000.toInt()) or (grey shl 16) or (grey shl 8) or grey
    }

    fun text(color: Int): Int = if (color and 0xFFFFFF == HEADING) (color and 0xFF000000.toInt()) or ACCENT else color

    class Context(graphics: GuiGraphicsExtractor) : MoulConfigRenderContext(graphics) {

        override fun drawDarkRect(x: Int, y: Int, width: Int, height: Int, shadow: Boolean) {
            val graphics = drawContext
            graphics.fill(x, y, x + 1, y + height, PANEL_LIGHT)
            graphics.fill(x + 1, y, x + width, y + 1, PANEL_LIGHT)
            graphics.fill(x + width - 1, y + 1, x + width, y + height, PANEL_DARK)
            graphics.fill(x + 1, y + height - 1, x + width - 1, y + height, PANEL_DARK)
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, PANEL)
            if (shadow) {
                graphics.fill(x + width, y + 2, x + width + 2, y + height + 2, SHADOW)
                graphics.fill(x + 2, y + height, x + width, y + height + 2, SHADOW)
            }
        }

        override fun drawColoredRect(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
            super.drawColoredRect(left, top, right, bottom, neutral(color))
        }

        override fun drawString(fontRenderer: IFontRenderer, text: StructuredText, x: Int, y: Int, color: Int, shadow: Boolean) {
            super.drawString(fontRenderer, text, x, y, text(color), shadow)
        }
    }
}

/** Registered through `META-INF/services` so MoulConfig draws with [ConfigTheme.Context]. */
class NyPlatform : MoulConfigPlatform() {
    override fun provideTopLevelRenderContext(): RenderContext = ConfigTheme.Context(makeDrawContext())
}
