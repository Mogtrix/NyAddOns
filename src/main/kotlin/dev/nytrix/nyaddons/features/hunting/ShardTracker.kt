package dev.nytrix.nyaddons.features.hunting

import com.mojang.brigadier.arguments.StringArgumentType
import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.AlertUtils
import dev.nytrix.nyaddons.core.ChatUtils
import dev.nytrix.nyaddons.core.NyEvents
import dev.nytrix.nyaddons.core.ShardProgress
import dev.nytrix.nyaddons.core.SkyBlockData
import dev.nytrix.nyaddons.core.Storage
import dev.nytrix.nyaddons.features.Command
import dev.nytrix.nyaddons.features.Feature
import dev.nytrix.nyaddons.gui.Overlay
import dev.nytrix.nyaddons.gui.OverlayManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack

/**
 * Shows, for each shard the player chose, how many are in the Hunting Box and how many more are
 * needed to max its attribute.
 *
 * Without the Hypixel API the numbers can only come from what the game shows: the Hunting Box and
 * Attribute Menu are read whenever they are open, and chat messages keep the counts current in
 * between. Shards leaving the box in ways chat does not announce (Bazaar sales, taking them out,
 * fusion ingredients) are corrected the next time the Hunting Box is opened.
 */
object ShardTracker : Feature {

    private val boxTitle = Regex("^(?:\\((\\d+)/(\\d+)\\) )?Hunting Box$")
    private val menuTitle = Regex("^(?:\\(\\d+/\\d+\\) )?Attribute Menu$")

    // Lore. Mods disagree on the wording of the syphon line, so both forms are accepted.
    private val ownedLine = Regex("Owned: ([\\d,]+) Shards?")
    private val syphonLine = Regex("Syphon (\\d+) (?:more|shards?) to (?:level up|unlock)!")
    private val abilityLine = Regex("^(.+?)(?: ([IVX]+))? \\([\\w ]+\\)$")
    private val sourceLine = Regex("Source: .+ Shard \\(([A-Z]\\d+)\\)")
    private val levelLine = Regex("Attribute Level: (\\d+)")
    private val nameWithTier = Regex("^(.+?) ([IVX]+)$")
    private const val MAXED_LINE = "Attribute Maxed!"
    private const val SEARCH_LINE = "Query: "

    // Chat.
    private val syphonedMessage = Regex("\\+(\\d+) (.+) Attribute \\(Level (\\d+)\\) - (\\d+) more to upgrade!")
    private val maxedMessage = Regex("\\+(\\d+) (.+) Attribute \\(Level \\d+\\) MAXED")
    private val gainMessages = listOf(
        Regex("CATCH! You caught(?: an?)? (?<name>.+?) Shard(?: x(?<amount>\\d+))?!"),
        Regex("CAPTURE! You .+ (?:an?|(?<amount>\\d+)x) (?<name>.+?) Shards?!"),
        Regex("You caught(?: an?)?(?: x(?<amount>\\d+))? (?<name>.+?) Shards?!"),
        Regex("LOOT SHARE!? You received (?:an?|(?<amount>\\d+)x?) (?<name>.+?) Shards? (?:for assisting|from) "),
        Regex("CHARM! You charmed .+ and received (?<amount>\\d+) (?<name>.+?) Shards?!"),
        Regex("FUSION! You obtained(?: an?)? (?<name>.+?) Shard(?: x(?<amount>\\d+))?!"),
        Regex("You sent (?:an?|(?<amount>\\d+)) (?<name>.+?) Shards? to your Hunting Box"),
        Regex("FLOOR DROP! You found (?<name>.+?) Shard on the ground!"),
        Regex("SHARD! Your contribution earned you the (?<name>.+?) Shard!"),
    )

    private const val READ_INTERVAL_TICKS = 4
    private const val CHEST_COLUMNS = 9
    private val romanNumerals = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")

    private val config get() = NyAddOns.config.hunting.shardTracker
    private val tracked get() = Storage.profile.trackedShards

    // While the Hunting Box is open: which pages have been read, and which shards were on them.
    private val pagesRead = HashSet<Int>()
    private val shardsSeen = HashSet<String>()
    private var searching = false
    private var ticks = 0

    override fun init() {
        ShardRepo.load(NyAddOns.directory)
        NyEvents.tick += ::onTick
        NyEvents.second += ::sendAlerts
        NyEvents.chat += ::onChat
        OverlayManager.register(
            Overlay(
                "Shard Tracker", { config.position },
                listOf("§6§lShard Tracker", " §9Hideonring§7: §b15 §7in box, §eLv 6§7, §c16 more to max", " §fGrove§7: §b3 §7in box, §a§lMAXED"),
                ::lines,
            ),
        )
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is AbstractContainerScreen<*>) {
                ScreenKeyboardEvents.allowKeyPress(screen).register { _, key -> !onMenuKey(screen, key.key()) }
            }
        }
    }

    override fun commands(): List<Command> = if (config.shortCommand) listOf(huntCommand()) else emptyList()

    override fun subcommands(): List<Command> = listOf(huntCommand())

    fun progress(shard: Shard): ShardProgress = Storage.profile.shards.getOrPut(shard.id) { ShardProgress() }

    fun isTracked(shard: Shard) = shard.id in tracked

    fun toggle(shard: Shard) {
        if (tracked.remove(shard.id)) {
            ChatUtils.chat("Stopped tracking ${shard.coloredName}§e.")
        } else {
            tracked += shard.id
            ChatUtils.chat("Now tracking ${shard.coloredName}§e.")
        }
        Storage.markDirty()
    }

    /** How many more shards have to be caught to max the attribute, or null if that is not known yet. */
    fun neededToMax(shard: Shard): Int? {
        val progress = progress(shard)
        val syphoned = progress.syphoned ?: return null
        return (ShardRepo.totalToMax(shard) - syphoned - (progress.owned ?: 0)).coerceAtLeast(0)
    }

    fun isMaxed(shard: Shard) = (progress(shard).syphoned ?: 0) >= ShardRepo.totalToMax(shard)

    // Commands

    private fun huntCommand(): Command = ClientCommands.literal("hunt")
        .executes { ShardPickerScreen.open(); 1 }
        .then(
            ClientCommands.argument("shard", StringArgumentType.greedyString())
                .suggests { _, builder ->
                    val typed = builder.remaining.lowercase()
                    (listOf("clear") + ShardRepo.all.map { it.name })
                        .filter { it.lowercase().startsWith(typed) }
                        .forEach { builder.suggest(it) }
                    builder.buildFuture()
                }
                .executes { context -> onCommand(StringArgumentType.getString(context, "shard")); 1 },
        )

    private fun onCommand(argument: String) {
        val query = argument.trim().removeSuffix(" Shard").removeSuffix(" shard")
        if (query.equals("clear", ignoreCase = true)) {
            tracked.clear()
            Storage.markDirty()
            ChatUtils.chat("Stopped tracking all shards.")
            return
        }
        if (!ShardRepo.loaded) {
            ChatUtils.chat("§cThe shard list has not loaded yet. Check your connection and try again in a moment.")
            return
        }
        val matches = ShardRepo.byName(query)?.let { listOf(it) }
            ?: ShardRepo.all.filter { it.name.contains(query, ignoreCase = true) }
        when (matches.size) {
            0 -> ChatUtils.chat("§cNo shard is called \"$query\".")
            1 -> toggle(matches[0])
            else -> ChatUtils.chat(
                "§c\"$query\" matches ${matches.size} shards: §e" + matches.take(6).joinToString("§7, §e") { it.name } +
                    if (matches.size > 6) "§7, ..." else "",
            )
        }
    }

    // Reading the menus

    private fun onTick() {
        if (!config.enabled || ++ticks % READ_INTERVAL_TICKS != 0) return
        val screen = Minecraft.getInstance().screen as? AbstractContainerScreen<*>
        if (screen == null) {
            endBoxVisit()
            return
        }
        val title = ChatUtils.stripColor(screen.title.string)
        val box = boxTitle.find(title)
        when {
            box != null -> readHuntingBox(screen, box.groupValues[1].toIntOrNull() ?: 1, box.groupValues[2].toIntOrNull() ?: 1)

            menuTitle.containsMatchIn(title) -> {
                endBoxVisit()
                readAttributeMenu(screen)
            }

            else -> endBoxVisit()
        }
    }

    private fun endBoxVisit() {
        pagesRead.clear()
        shardsSeen.clear()
        searching = false
    }

    private fun menuItems(screen: AbstractContainerScreen<*>): List<IndexedValue<ItemStack>> =
        screen.menu.slots.filter { it.container !is Inventory && !it.item.isEmpty }.map { IndexedValue(it.index, it.item) }

    private fun loreOf(stack: ItemStack): List<String> =
        stack.get(DataComponents.LORE)?.lines().orEmpty().map { ChatUtils.stripColor(it.string).trim() }

    private fun nameOf(stack: ItemStack) = ChatUtils.stripColor(stack.hoverName.string).trim()

    private fun readHuntingBox(screen: AbstractContainerScreen<*>, page: Int, pages: Int) {
        var changed = false
        var itemsOnPage = 0
        for ((index, stack) in menuItems(screen)) {
            val lore = loreOf(stack)
            if (lore.any { it.startsWith(SEARCH_LINE) }) searching = true
            // Shards sit inside the border of the chest.
            if (index < CHEST_COLUMNS || index % CHEST_COLUMNS == 0 || index % CHEST_COLUMNS == CHEST_COLUMNS - 1) continue
            val shard = ShardRepo.byName(nameOf(stack)) ?: continue
            val owned = lore.firstNotNullOfOrNull { ownedLine.find(it) }?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
                ?: continue
            itemsOnPage++
            shardsSeen += shard.id
            val level = lore.firstNotNullOfOrNull { line ->
                abilityLine.find(line)?.takeIf { it.groupValues[1].equals(shard.attribute, ignoreCase = true) }
            }?.let { romanNumerals.indexOf(it.groupValues[2]) + 1 } ?: 0
            changed = update(shard, owned, syphonedFrom(shard, level, lore)) || changed
        }

        // Shards the player has none of are not listed, so once every page has been read the
        // rest are known to be zero. A search hides shards too, so nothing is concluded then.
        if (itemsOnPage > 0 || pages == 1) pagesRead += page
        if (!searching && pagesRead.size >= pages && ShardRepo.loaded) {
            for (shard in ShardRepo.all) {
                if (shard.id !in shardsSeen) changed = update(shard, 0, null) || changed
            }
        }
        if (changed) Storage.markDirty()
    }

    private fun readAttributeMenu(screen: AbstractContainerScreen<*>) {
        var changed = false
        for ((_, stack) in menuItems(screen)) {
            val lore = loreOf(stack)
            val code = lore.firstNotNullOfOrNull { sourceLine.find(it) }?.groupValues?.get(1) ?: continue
            val shard = ShardRepo.byCode(code) ?: continue
            val level = lore.firstNotNullOfOrNull { levelLine.find(it) }?.groupValues?.get(1)?.toIntOrNull()
                ?: nameWithTier.find(nameOf(stack))?.let { romanNumerals.indexOf(it.groupValues[2]) + 1 }
                ?: 0
            changed = update(shard, null, syphonedFrom(shard, level, lore)) || changed
        }
        if (changed) Storage.markDirty()
    }

    /** Total shards put into the attribute, from its level and the "Syphon N more" line. */
    private fun syphonedFrom(shard: Shard, level: Int, lore: List<String>): Int? {
        if (!shard.consumable) return null
        if (MAXED_LINE in lore || lore.any { "(MAX!)" in it }) return ShardRepo.totalToMax(shard)
        val reached = ShardRepo.totalForLevel(shard, level)
        val toNext = lore.firstNotNullOfOrNull { syphonLine.find(it) }?.groupValues?.get(1)?.toIntOrNull() ?: return reached
        return reached + (ShardRepo.stepAfter(shard, level) - toNext).coerceAtLeast(0)
    }

    private fun update(shard: Shard, owned: Int?, syphoned: Int?): Boolean {
        val progress = progress(shard)
        val changed = (owned != null && progress.owned != owned) || (syphoned != null && progress.syphoned != syphoned)
        if (owned != null) progress.owned = owned
        if (syphoned != null) progress.syphoned = syphoned
        return changed
    }

    private fun shardOf(stack: ItemStack): Shard? =
        ShardRepo.byName(nameOf(stack))
            ?: loreOf(stack).firstNotNullOfOrNull { sourceLine.find(it) }?.let { ShardRepo.byCode(it.groupValues[1]) }

    /** The track key, pressed while hovering a shard in the Hunting Box or Attribute Menu. */
    private fun onMenuKey(screen: AbstractContainerScreen<*>, key: Int): Boolean {
        if (!config.enabled || !SkyBlockData.onSkyBlock || key != config.trackKey) return false
        val title = ChatUtils.stripColor(screen.title.string)
        if (!boxTitle.containsMatchIn(title) && !menuTitle.containsMatchIn(title)) return false
        val slot = screen.hoveredSlot ?: return false
        if (slot.container is Inventory) return false
        toggle(shardOf(slot.item) ?: return false)
        return true
    }

    // Chat

    private fun onChat(message: String) {
        if (!config.enabled || !ShardRepo.loaded) return

        syphonedMessage.find(message)?.let { match ->
            val shard = ShardRepo.byAttribute(match.groupValues[2]) ?: return
            val level = match.groupValues[3].toInt()
            val syphoned = ShardRepo.totalForLevel(shard, level) +
                (ShardRepo.stepAfter(shard, level) - match.groupValues[4].toInt()).coerceAtLeast(0)
            spend(shard, match.groupValues[1].toInt(), syphoned)
            return
        }
        maxedMessage.find(message)?.let { match ->
            val shard = ShardRepo.byAttribute(match.groupValues[2]) ?: return
            spend(shard, match.groupValues[1].toInt(), ShardRepo.totalToMax(shard))
            return
        }
        for (pattern in gainMessages) {
            val match = pattern.find(message) ?: continue
            val shard = ShardRepo.byName(match.groups["name"]?.value ?: continue) ?: continue
            val progress = progress(shard)
            // Without a starting count from the Hunting Box there is nothing to add to.
            progress.owned = (progress.owned ?: return) + (match.groups["amount"]?.value?.toIntOrNull() ?: 1)
            Storage.markDirty()
            return
        }
    }

    private fun spend(shard: Shard, amount: Int, syphoned: Int) {
        val progress = progress(shard)
        progress.owned = progress.owned?.let { (it - amount).coerceAtLeast(0) }
        progress.syphoned = syphoned
        Storage.markDirty()
    }

    // Alerts and overlay

    private fun sendAlerts() {
        if (!config.enabled) return
        for (shard in tracked.mapNotNull { ShardRepo.byId(it) }) {
            val progress = progress(shard)
            val maxable = shard.consumable && !isMaxed(shard) && neededToMax(shard) == 0
            if (maxable && !progress.alertedMaxable) {
                AlertUtils.ready(
                    config.alertChat, config.alertSound, false,
                    "§aYou have enough ${shard.coloredName} §ashards to max §e${shard.attribute}§a!", "",
                )
            }
            if (progress.alertedMaxable != maxable) {
                progress.alertedMaxable = maxable
                Storage.markDirty()
            }
        }
    }

    private fun lines(): List<String> {
        if (!config.enabled || tracked.isEmpty()) return emptyList()
        if (!ShardRepo.loaded) return listOf("§6§lShard Tracker", " §7Loading the shard list...")
        return buildList {
            add("§6§lShard Tracker")
            for (shard in tracked.mapNotNull { ShardRepo.byId(it) }) add(" ${shard.coloredName}§7: ${status(shard)}")
        }
    }

    /** `15 in box, Lv 6, 16 more to max`, or what is missing to say so. */
    fun status(shard: Shard): String {
        val progress = progress(shard)
        val owned = progress.owned ?: return "§7open your Hunting Box"
        val box = "§b$owned §7in box"
        if (!shard.consumable) return box
        val syphoned = progress.syphoned ?: return "$box, §7open the Attribute Menu"
        if (isMaxed(shard)) return "$box, §a§lMAXED"
        val needed = neededToMax(shard) ?: 0
        val level = "§eLv ${ShardRepo.levelOf(shard, syphoned)}"
        return if (needed == 0) "$box, $level§7, §aenough to max" else "$box, $level§7, §c$needed more to max"
    }
}
