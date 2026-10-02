package dev.nytrix.nyaddons.features.magicfind

import com.mojang.brigadier.arguments.StringArgumentType
import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.ChatUtils
import dev.nytrix.nyaddons.features.Command
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style

/** `/ny trackmob [mob|clear]`; `/ny trackmob <mob> #<n>` or `#all` picks the drop to follow. */
object MfTrackCommand {

    private val config get() = NyAddOns.config.combat.magicFind

    var say: (String) -> Unit = { ChatUtils.chat(it) }

    /** Where the clickable drop choices go; tests swap it. */
    var sayComponent: (Component) -> Unit = { ChatUtils.chat(it) }

    private val pickRegex = Regex("^(.*?)\\s*#(\\d+|all)$", RegexOption.IGNORE_CASE)

    /** The drops a player can follow on [mob]: one entry per item, most likely first, specials last. */
    fun choices(mob: MfMob): List<MfDrop> {
        val seen = HashSet<String>()
        return mob.drops.filter { seen.add(it.item) }
    }

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
            if (tracked.isEmpty()) say("Not tracking any mob. Use §e/ny trackmob <mob>§e.")
            else say("Tracking: §a" + tracked.sorted().joinToString("§7, §a") { MagicFind.data.mob(it)?.name ?: it })
            return
        }
        if (query.equals("clear", ignoreCase = true)) {
            tracked.clear()
            config.trackedDrops.clear()
            NyAddOns.saveConfig()
            say("Stopped tracking all mobs.")
            return
        }
        MagicFind.data.request()
        val pick = pickRegex.matchEntire(query)
        val mob = MagicFind.data.mob(pick?.groupValues?.get(1) ?: query)
        if (mob == null) {
            say(if (MagicFind.data.ready) "§cNo mob with Magic Find drops is called \"$query\"." else "§cThe mob list has not loaded yet. Try again in a moment.")
            return
        }
        val options = choices(mob)
        if (pick == null && tracked.contains(mob.id)) {
            tracked.remove(mob.id)
            config.trackedDrops.remove(mob.id)
            say("Stopped tracking ${mob.name}.")
        } else if (pick == null && options.size > 1 && !mob.slayer) {
            ask(mob, options)
            return
        } else if (pick != null) {
            val which = pick.groupValues[2].lowercase()
            val index = if (which == "all") 0 else which.toIntOrNull() ?: -1
            if (index < 0 || index > options.size) {
                say("§cPick a number from 1 to ${options.size}, or #all.")
                return
            }
            tracked += mob.id
            if (index == 0) config.trackedDrops.remove(mob.id) else config.trackedDrops[mob.id] = options[index - 1].item
            say("Tracking ${mob.name}: ${if (index == 0) "all drops" else options[index - 1].item}. Warning: this can spam chat.")
        } else {
            tracked += mob.id
            config.trackedDrops.remove(mob.id)
            say("Tracking ${mob.name}. Warning: this can spam chat.")
        }
        NyAddOns.saveConfig()
    }

    /** Asks which drop to follow: one clickable line per drop plus "all drops". */
    private fun ask(mob: MfMob, options: List<MfDrop>) {
        say("Which drop are you going for on ${mob.name}? Click one:")
        sayComponent(option(mob, "all", "§a[All drops]"))
        for ((i, drop) in options.withIndex()) {
            val odds = if (drop.special) "special" else MfMath.oneIn(drop.chance)
            sayComponent(option(mob, (i + 1).toString(), "§e${i + 1}. ${drop.item} §7$odds"))
        }
    }

    private fun option(mob: MfMob, pick: String, label: String): Component =
        Component.literal(label).withStyle(
            Style.EMPTY
                .withClickEvent(ClickEvent.RunCommand("ny trackmob ${mob.name} #$pick"))
                .withHoverEvent(HoverEvent.ShowText(Component.literal("§7Click to track"))),
        )
}
