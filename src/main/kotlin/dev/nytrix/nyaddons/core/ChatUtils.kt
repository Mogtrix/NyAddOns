package dev.nytrix.nyaddons.core

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.TextColor

object ChatUtils {

    // Brackets in the config screen's panel colour, name in its title colour.
    private const val BRACKET_COLOR = 0x1E1E1E
    private const val NAME_COLOR = 0xA0A0A0
    private val colorCode = Regex("§.")

    /** Sends a client-side chat message: prefix, then yellow text. § colour codes work in [message]. */
    fun chat(message: String) {
        val mc = Minecraft.getInstance()
        mc.execute {
            mc.gui.chat.addClientSystemMessage(prefix().append(Component.literal(" §e$message")))
        }
    }

    fun stripColor(text: String): String = colorCode.replace(text, "")

    private fun prefix(): MutableComponent =
        colored("[", BRACKET_COLOR).append(colored("Ny", NAME_COLOR)).append(colored("]", BRACKET_COLOR))

    private fun colored(text: String, rgb: Int): MutableComponent =
        Component.literal(text).withStyle { it.withColor(TextColor.fromRgb(rgb)) }
}
