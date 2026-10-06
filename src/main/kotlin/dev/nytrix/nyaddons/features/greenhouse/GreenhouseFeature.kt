package dev.nytrix.nyaddons.features.greenhouse

import com.mojang.blaze3d.platform.InputConstants
import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.ChatUtils
import dev.nytrix.nyaddons.core.NyEvents
import dev.nytrix.nyaddons.core.Safe
import dev.nytrix.nyaddons.core.SkyBlockData
import dev.nytrix.nyaddons.features.Command
import dev.nytrix.nyaddons.features.Feature
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

/** The Greenhouse window, its commands and keybind. */
object GreenhouseFeature : Feature {

    private val site = Safe.site("Greenhouse")
    private val config get() = NyAddOns.config.garden.greenhouse
    private var keyWasDown = false

    override fun init() {
        NyEvents.second += {
            if (config.enabled && SkyBlockData.area == "Garden") Greenhouse.data.request()
        }
        ClientTickEvents.END_CLIENT_TICK.register { mc -> site { pollKey(mc) } }
        GreenhouseOverlay.init()
        ClientTickEvents.END_CLIENT_TICK.register { mc -> site { GreenhouseOverlay.tick(mc) } }
    }

    override fun commands(): List<Command> = if (config.shortCommand) listOf(command("gh")) else emptyList()

    override fun subcommands(): List<Command> = listOf(command("greenhouse").then(ClientCommands.literal("bench").executes { site { bench() }; 1 }))

    /** `/ny greenhouse bench`: times SkyShards (cold, again, cached, three at once) and prints each run in chat; every run is also in skyshards-timing.log. */
    private fun bench() {
        val data = Greenhouse.data
        if (!data.ready) {
            ChatUtils.chat("§cThe Greenhouse data is not loaded yet.")
            return
        }
        val ids = data.mutations.map { it.id }.filter { it !in GreenhouseGoals.skippedMutations }.distinct()
        if (ids.size < 5) return
        val sets = listOf(
            "one mutation" to listOf(SkyGoal(ids[0], 1)),
            "five mutations" to ids.take(5).map { SkyGoal(it, 1) },
            "max of all" to ids.map { SkyGoal(it, null) },
        )
        ChatUtils.chat("§eTiming SkyShards: ${sets.size} sets, a few minutes at most. Results also go to config/nyaddons/skyshards-timing.log.")
        val mask = BooleanArray(100) { true }
        Safe.background("greenhouse bench") {
            SkyShards.benchmark(sets, mask) { line -> Minecraft.getInstance().execute { ChatUtils.chat("§7$line") } }
            Minecraft.getInstance().execute { ChatUtils.chat("§aSkyShards timing finished.") }
        }
    }

    private fun command(name: String): Command = ClientCommands.literal(name).executes { site { open() }; 1 }

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
