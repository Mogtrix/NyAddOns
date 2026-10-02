package dev.nytrix.nyaddons.features.foraging

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.AlertUtils
import dev.nytrix.nyaddons.core.NyEvents
import dev.nytrix.nyaddons.core.SkyBlockData
import dev.nytrix.nyaddons.core.Storage
import dev.nytrix.nyaddons.core.TimeUtils
import dev.nytrix.nyaddons.features.Feature
import dev.nytrix.nyaddons.gui.Overlay
import dev.nytrix.nyaddons.gui.OverlayManager

/** Counts down the one-hour refill of the Honeyhives in Torrhus Canyon. */
object HoneyhiveTimer : Feature {

    private const val LOOTED = "You stick your hand into the honeyhive and feel around"
    private const val QUEEN_BEE = "QUEEN BEE! The Honeyhive instantly refilled with loot!"
    private const val COOLDOWN = 60 * 60 * 1000L

    private val config get() = NyAddOns.config.foraging.honeyhives
    private val data get() = Storage.data

    override fun init() {
        NyEvents.chat += ::onChat
        NyEvents.second += ::checkReady
        OverlayManager.register(
            Overlay("Honeyhive Timer", { config.position }, listOf("§6Honeyhives§7: §f41m 10s"), ::lines),
        )
    }

    private fun onChat(message: String) {
        if (!config.enabled) return
        val now = System.currentTimeMillis()
        when {
            // The first hive looted starts the hour; looting the rest of the round does not push it back.
            LOOTED in message -> if (now >= data.honeyhiveReadyAt) {
                data.honeyhiveReadyAt = now + COOLDOWN
                data.honeyhiveAlerted = false
                Storage.markDirty()
            }

            QUEEN_BEE in message -> {
                data.honeyhiveReadyAt = now
                data.honeyhiveAlerted = true
                Storage.markDirty()
                AlertUtils.ready(
                    config.alertChat, config.alertSound, config.alertTitle,
                    "§6Queen Bee! §eThe Honeyhive refilled instantly.", "§6Honeyhive Refilled!",
                )
            }
        }
    }

    private fun checkReady() {
        if (!config.enabled || data.honeyhiveAlerted || data.honeyhiveReadyAt == 0L) return
        if (System.currentTimeMillis() < data.honeyhiveReadyAt || !SkyBlockData.allows(config.alertArea)) return
        data.honeyhiveAlerted = true
        Storage.markDirty()
        AlertUtils.ready(
            config.alertChat, config.alertSound, config.alertTitle,
            "§aHoneyhives are ready §eto be looted in §bTorrhus Canyon§e.", "§aHoneyhives Ready!",
        )
    }

    private fun lines(): List<String> {
        if (!config.enabled || data.honeyhiveReadyAt == 0L || !SkyBlockData.allows(config.overlayArea)) return emptyList()
        val left = data.honeyhiveReadyAt - System.currentTimeMillis()
        val time = if (left <= 0) "§aReady" else TimeUtils.timerColor(left) + TimeUtils.format(left)
        return listOf("§6Honeyhives§7: $time")
    }
}
