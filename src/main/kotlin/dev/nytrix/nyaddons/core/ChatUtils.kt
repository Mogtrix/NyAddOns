package dev.nytrix.nyaddons.core

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.TextColor

object ChatUtils {

    private const val PREFIX = "[Ny]"
    private const val PREFIX_START = 0xFFFF55
    private const val PREFIX_END = 0xFFAA00
    private val colorCode = Regex("§.")

    /** Sends a client-side chat message: gradient prefix, yellow text. § colour codes work in [message]. */
    fun chat(message: String) {
        val mc = Minecraft.getInstance()
        mc.execute {
            mc.gui.chat.addClientSystemMessage(prefix().append(Component.literal(" §e$message")))
        }
    }

    fun stripColor(text: String): String = colorCode.replace(text, "")

    private fun prefix(): MutableComponent {
        val result = Component.empty()
        val last = (PREFIX.length - 1).coerceAtLeast(1)
        PREFIX.forEachIndexed { index, char ->
            val rgb = lerpRgb(PREFIX_START, PREFIX_END, index.toFloat() / last)
            result.append(Component.literal(char.toString()).withStyle { it.withColor(TextColor.fromRgb(rgb)) })
        }
        return result
    }

    private fun lerpRgb(from: Int, to: Int, progress: Float): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * progress).toInt() and 0xFF
        }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
