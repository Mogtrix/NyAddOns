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
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
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
        Regex("You sent (?:an?|(?<amount>\\d+)) (?<name>.+?) Shards? to your Hunting Box"),
        Regex("FLOOR DROP! You found (?<name>.+?) Shard on the ground!"),
        Regex("SHARD! Your contribution earned you the (?<name>.+?) Shard!"),
    )

    private val fusionMessage = Regex("FUSION! You obtained(?: an?)? (?<name>.+?) Shard(?: x(?<amount>\\d+))?!")
    private const val DOUBLE_FUSION_MESSAGE = "You received double shards from the fusion"

    private val fusionBoxTitle = Regex("^(?:\\(\\d+/\\d+\\) )?Fusion Box$")
    private const val SHARD_FUSION_TITLE = "Shard Fusion"
    private const val CONFIRM_FUSION_TITLE = "Confirm Fusion"
    private val requiredToFuseLine = Regex("Required to fuse: (\\d+)")
    private const val FIRST_INGREDIENT_SLOT = 12
    private const val SECOND_INGREDIENT_SLOT = 14

    private const val HINT_TOP_MARGIN = 6
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

    // The two ingredients last seen in the Confirm Fusion screen, and what the last fusion produced.
    // Chat only names the result, so the ingredients are taken from here when it arrives.
    private var fusionIngredients: List<Pair<Shard, Int>> = emptyList()
    private var lastFusionResult: Pair<Shard, Int>? = null

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
        // The track key and its hint only exist in the menus that list shards.
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            val title = ChatUtils.stripColor(screen.title.string)
            if (boxTitle.containsMatchIn(title) || menuTitle.containsMatchIn(title)) {
                ScreenKeyboardEvents.allowKeyPress(screen).register { _, key -> !onMenuKey(screen, key.key()) }
                ScreenEvents.afterExtract(screen).register { _, graphics, _, _, _ -> drawHint(screen, graphics) }
            }
        }
    }

    override fun commands(): List<Command> = if (config.shortCommand) listOf(huntCommand()) else emptyList()

    override fun subcommands(): List<Command> = listOf(huntCommand())

    fun progress(shard: Shard): ShardProgress = Storage.profile.shards.getOrPut(shard.id) { ShardProgress() }

    fun isTracked(shard: Shard) = shard.id in tracked

    fun trackedShards(): List<Shard> = tracked.mapNotNull { ShardRepo.byId(it) }

    fun toggle(shard: Shard) {
        if (tracked.remove(shard.id)) {
            ChatUtils.chat("Stopped tracking ${shard.coloredName}§e.")
        } else {
            tracked += shard.id
            ChatUtils.chat("Now tracking ${shard.coloredName}§e.")
        }
        Storage.markDirty()
        OverlayManager.invalidate()
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
            OverlayManager.invalidate()
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

    private enum class Menu { OTHER, HUNTING_BOX, ATTRIBUTE_MENU, CONFIRM_FUSION }

    // The open screen, what kind of menu it is, and a fingerprint of what it held when last read.
    private var openScreen: AbstractContainerScreen<*>? = null
    private var openMenu = Menu.OTHER
    private var boxPage = 1
    private var boxPages = 1
    private var lastContents = 0L

    /** How many times a menu's items have actually been read. Unchanged menus are skipped. */
    var menuReads = 0
        private set

    private fun onTick() {
        if (!config.enabled || ++ticks % READ_INTERVAL_TICKS != 0) return
        val screen = Minecraft.getInstance().screen as? AbstractContainerScreen<*>
        if (screen !== openScreen) {
            openScreen = screen
            lastContents = 0
            openMenu = Menu.OTHER
            if (screen != null) {
                val title = ChatUtils.stripColor(screen.title.string)
                val box = boxTitle.find(title)
                when {
                    box != null -> {
                        openMenu = Menu.HUNTING_BOX
                        boxPage = box.groupValues[1].toIntOrNull() ?: 1
                        boxPages = box.groupValues[2].toIntOrNull() ?: 1
                    }

                    menuTitle.containsMatchIn(title) -> openMenu = Menu.ATTRIBUTE_MENU
                    title == CONFIRM_FUSION_TITLE -> openMenu = Menu.CONFIRM_FUSION
                }
            }
            if (openMenu != Menu.HUNTING_BOX) endBoxVisit()
        }
        if (screen == null || openMenu == Menu.OTHER) return

        // Reading every item's name and lore is the costly part, so it only happens when an item changed.
        val contents = contentsOf(screen)
        if (contents == lastContents) return
        lastContents = contents
        menuReads++
        when (openMenu) {
            Menu.HUNTING_BOX -> readHuntingBox(screen, boxPage, boxPages)
            Menu.ATTRIBUTE_MENU -> readAttributeMenu(screen)
            Menu.CONFIRM_FUSION -> {
                val items = menuItems(screen).associate { it.index to it.value }
                readConfirmFusion(items[FIRST_INGREDIENT_SLOT], items[SECOND_INGREDIENT_SLOT])
            }

            Menu.OTHER -> {}
        }
    }

    /** Changes whenever the server replaces an item in the menu: each update arrives as a new stack. */
    private fun contentsOf(screen: AbstractContainerScreen<*>): Long {
        var hash = screen.menu.stateId.toLong() + 1
        for (slot in screen.menu.slots) {
            val item = slot.item
            hash = hash * 31 + System.identityHashCode(item) + item.count
        }
        return hash
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

    /** True in the two fusion screens that list shards to pick from. */
    fun isFusionPicker(title: String) = fusionBoxTitle.containsMatchIn(title) || title == SHARD_FUSION_TITLE

    /** True in any of the three fusion screens. */
    fun isFusionMenu(title: String) = isFusionPicker(title) || title == CONFIRM_FUSION_TITLE

    /** Remembers the two shards about to be fused, so they can be taken off the counts when the fusion happens. */
    fun readConfirmFusion(first: ItemStack?, second: ItemStack?) {
        fusionIngredients = listOf(ingredientOf(first ?: return) ?: return, ingredientOf(second ?: return) ?: return)
    }

    private fun ingredientOf(stack: ItemStack): Pair<Shard, Int>? {
        val lore = loreOf(stack)
        // This screen names the shard in the lore, not always in the item name.
        val shard = shardOf(stack)
            ?: (lore + nameOf(stack)).firstNotNullOfOrNull { ShardRepo.byName(it.removeSuffix(" Shard")) }
            ?: return null
        val amount = lore.firstNotNullOfOrNull { requiredToFuseLine.find(it) }?.groupValues?.get(1)?.toIntOrNull()
            ?: FusionRepo.data?.let { data -> data.indexOf[shard.code]?.let { data.fuseAmount[it] } }
            ?: return null
        return shard to amount
    }

    /** The shard an item stands for. Fusion menus name their items differently from the Hunting Box, so several spellings are tried. */
    fun shardOf(stack: ItemStack): Shard? {
        val name = nameOf(stack)
        ShardRepo.byName(name)?.let { return it }
        ShardRepo.byName(name.removeSuffix(" Shard"))?.let { return it }
        val lore = loreOf(stack)
        lore.firstNotNullOfOrNull { sourceLine.find(it) }?.let { match -> ShardRepo.byCode(match.groupValues[1])?.let { return it } }
        return lore.firstNotNullOfOrNull { ShardRepo.byName(it.removeSuffix(" Shard").removeSuffix(" NEW SHARD")) }
    }

    /** The two shards last seen on the Confirm Fusion screen. */
    fun confirmIngredients(): List<Shard> = fusionIngredients.map { it.first }

    private fun drawHint(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor) {
        if (!config.enabled || !config.showHint || !SkyBlockData.onSkyBlock) return
        val key = InputConstants.Type.KEYSYM.getOrCreate(config.trackKey).displayName.string
        val y = HINT_TOP_MARGIN
        graphics.centeredText(Minecraft.getInstance().font, "§8[§7Ny§8] §ePress §b$key §eto track a shard", screen.width / 2, y, -1)
    }

    /** The track key, pressed while hovering a shard in the Hunting Box or Attribute Menu. */
    private fun onMenuKey(screen: AbstractContainerScreen<*>, key: Int): Boolean {
        if (!config.enabled || !SkyBlockData.onSkyBlock || key != config.trackKey) return false
        val slot = screen.hoveredSlot ?: return false
        if (slot.container is Inventory) return false
        toggle(shardOf(slot.item) ?: return false)
        return true
    }

    // Chat

    private fun onChat(message: String) {
        if (!config.enabled || !ShardRepo.loaded) return
        // Every line this cares about has one of these words; the rest of chat stops here.
        if ("Shard" !in message && "Attribute" !in message && "fusion" !in message) return

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
        fusionMessage.find(message)?.let { match ->
            val shard = ShardRepo.byName(match.groups["name"]!!.value) ?: return
            val amount = match.groups["amount"]?.value?.toIntOrNull() ?: 1
            addToBox(shard, amount)
            for ((ingredient, used) in fusionIngredients) addToBox(ingredient, -used)
            lastFusionResult = shard to amount
            return
        }
        if (DOUBLE_FUSION_MESSAGE in message) {
            lastFusionResult?.let { (shard, amount) -> addToBox(shard, amount) }
            return
        }
        for (pattern in gainMessages) {
            val match = pattern.find(message) ?: continue
            val shard = ShardRepo.byName(match.groups["name"]?.value ?: continue) ?: continue
            addToBox(shard, match.groups["amount"]?.value?.toIntOrNull() ?: 1)
            return
        }
    }

    private fun addToBox(shard: Shard, amount: Int) {
        val progress = progress(shard)
        // Without a starting count from the Hunting Box there is nothing to add to.
        progress.owned = ((progress.owned ?: return) + amount).coerceAtLeast(0)
        Storage.markDirty()
        // So the overlays and the fusion counters follow the change as it happens.
        FusionTracker.requestRefresh()
    }

    private fun spend(shard: Shard, amount: Int, syphoned: Int) {
        val progress = progress(shard)
        progress.owned = progress.owned?.let { (it - amount).coerceAtLeast(0) }
        progress.syphoned = syphoned
        Storage.markDirty()
        FusionTracker.requestRefresh()
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
