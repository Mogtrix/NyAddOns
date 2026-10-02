package dev.nytrix.nyaddons.features.magicfind

import com.mojang.brigadier.arguments.StringArgumentType
import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.ChatUtils
import dev.nytrix.nyaddons.features.Command
import net.fabricmc.fabric.api.client.command.v2.ClientCommands

/** `/trackmob [mob|clear]`. */
object MfTrackCommand {

    private val config get() = NyAddOns.config.combat.magicFind

    var say: (String) -> Unit = { ChatUtils.chat(it) }

    fun command(): Command = ClientCommands.literal("trackmob")
        .executes { run(""); 1 }
        .then(
            ClientCommands.argument("mob", StringArgumentType.greedyString())
                .suggests { _, builder ->
                    val typed = builder.remaining.lowercase()
                    (listOf("clear") + MagicFind.data.mobNames())
                        .filter { it.lowercase().startsWith(typed) }
                        .forEach { builder.suggest(it) }
                    builder.buildFuture()
                }
                .executes { context -> run(StringArgumentType.getString(context, "mob")); 1 },
        )

    fun run(argument: String) {
        val query = argument.trim()
        val tracked = config.trackedMobs
        if (query.isEmpty()) {
            if (tracked.isEmpty()) say("Not tracking any mob. Use §e/trackmob <mob>§e.")
            else say("Tracking: §a" + tracked.sorted().joinToString("§7, §a") { MagicFind.data.mob(it)?.name ?: it })
            return
        }
        if (query.equals("clear", ignoreCase = true)) {
            tracked.clear()
            NyAddOns.saveConfig()
            say("Stopped tracking all mobs.")
            return
        }
        MagicFind.data.request()
        val mob = MagicFind.data.mob(query)
        if (mob == null) {
            say(if (MagicFind.data.ready) "§cNo mob with Magic Find drops is called \"$query\"." else "§cThe mob list has not loaded yet. Try again in a moment.")
            return
        }
        if (tracked.remove(mob.id)) {
            say("Stopped tracking ${mob.name}.")
        } else {
            tracked += mob.id
            say("Tracking ${mob.name}. Warning: this can spam chat.")
        }
        NyAddOns.saveConfig()
    }
}
