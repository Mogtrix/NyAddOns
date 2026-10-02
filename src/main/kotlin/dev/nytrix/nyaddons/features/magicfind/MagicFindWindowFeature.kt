package dev.nytrix.nyaddons.features.magicfind

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.NyEvents
import dev.nytrix.nyaddons.features.Command
import dev.nytrix.nyaddons.features.Feature
import net.fabricmc.fabric.api.client.command.v2.ClientCommands

/** The `/mf` window, its commands and the one-time default selection. */
object MagicFindWindowFeature : Feature {

    override fun init() {
        NyEvents.second += { applyDefaults() }
    }

    override fun commands(): List<Command> = listOf(command("mf"))

    override fun subcommands(): List<Command> = listOf(command("magicfind"))

    private fun command(name: String): Command = ClientCommands.literal(name).executes { MagicFindScreen.open(); 1 }

    /** Adds every `defaultOn` mob to the selection, once, as soon as the data is there. Returns true if it ran now. */
    fun applyDefaults(): Boolean {
        val config = NyAddOns.config.combat.magicFind
        if (config.defaultsApplied) return false
        val data = MagicFind.data
        if (!data.ready) return false
        for (category in data.categories) for (mob in category.mobs) if (mob.defaultOn) config.enabledMobs.add(mob.id)
        config.defaultsApplied = true
        NyAddOns.saveConfig()
        return true
    }
}
