package dev.nytrix.nyaddons.features.greenhouse

import com.mojang.blaze3d.platform.InputConstants
import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.ChatUtils
import dev.nytrix.nyaddons.core.NyEvents
import dev.nytrix.nyaddons.core.SkyBlockData
import dev.nytrix.nyaddons.features.Command
import dev.nytrix.nyaddons.features.Feature
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

/** The Greenhouse window, its commands and keybind. */
object GreenhouseFeature : Feature {

    private val config get() = NyAddOns.config.garden.greenhouse
    private var keyWasDown = false

    override fun init() {
        NyEvents.second += {
            if (config.enabled && SkyBlockData.area == "Garden") Greenhouse.data.request()
        }
        ClientTickEvents.END_CLIENT_TICK.register { mc -> pollKey(mc) }
        GreenhousePin.register()
    }

    override fun commands(): List<Command> = if (config.shortCommand) listOf(command("gh")) else emptyList()

    override fun subcommands(): List<Command> = listOf(command("greenhouse"))

    private fun command(name: String): Command = ClientCommands.literal(name).executes { open(); 1 }

    fun open() {
        if (!config.enabled) {
            ChatUtils.chat("§cThe Greenhouse helper is turned off in the config.")
            return
        }
        GreenhouseScreen.open()
    }

    private fun pollKey(mc: Minecraft) {
        val key = config.openKey
        if (key == 0) {
            keyWasDown = false
            return
        }
        val down = mc.screen == null && mc.player != null && InputConstants.isKeyDown(mc.window, key)
        if (down && !keyWasDown && config.enabled) GreenhouseScreen.open()
        keyWasDown = down
    }
}
