package dev.nytrix.nyaddons.features.greenhouse

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.ChatUtils
import dev.nytrix.nyaddons.core.SkyBlockData
import dev.nytrix.nyaddons.core.Storage
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.world.entity.player.Inventory

/**
 * What the player has: sack contents (read from sack menus and `[Sacks]` chat lines, only on the Garden) and the
 * player's own inventory (counted on demand, cached for a second).
 */
object GreenhouseStockImpl : GhStock {

    private const val GARDEN = "Garden"
    private const val READ_INTERVAL_TICKS = 4
    private const val MAX_ENTRIES = 400
    private const val INVENTORY_CACHE_NANOS = 1_000_000_000L

    private val sackTitle = Regex("^(.+) Sack(?: \\(\\d+/\\d+\\))?$")
    private val storedLine = Regex("^Stored: ([\\d,]+(?:\\.\\d+)?)([kmbKMB])?(?:/|$)")
    private val deltaLine = Regex("^\\s*([+-])([\\d,]+(?:\\.\\d+)?)([kmbKMB])? (.+?)(?: \\(.*)?\\s*$")

    /** Only these sacks matter to the Greenhouse; everything else is ignored. */
    private val relevantSacks = setOf("mutations", "garden", "agronomy", "enchanted agronomy")

    private val config get() = NyAddOns.config.garden.greenhouse
    private val sacks get() = Storage.profile.greenhouse.sacks

    private fun active() = config.enabled && SkyBlockData.area == GARDEN

    override val sacksUpdatedAt get() = Storage.profile.greenhouse.sacksUpdatedAt

    override fun inSacks(itemName: String): Int? = sacks[key(itemName)]

    override fun inInventory(itemName: String): Int = inventoryCounts()[key(itemName)] ?: 0

    override fun count(itemName: String): Int? {
        val inSacks = inSacks(itemName)
        val inInventory = inInventory(itemName)
        if (inSacks == null && inInventory == 0) return null
        return (inSacks ?: 0) + inInventory
    }

    private fun key(name: String) = ChatUtils.stripColor(name).trim().lowercase()

    // Inventory

    private var inventoryCache: Map<String, Int> = emptyMap()
    private var inventoryCachedAt = 0L

    private fun inventoryCounts(): Map<String, Int> {
        val now = System.nanoTime()
        if (inventoryCachedAt != 0L && now - inventoryCachedAt < INVENTORY_CACHE_NANOS) return inventoryCache
        inventoryCachedAt = now
        val player = Minecraft.getInstance().player
        if (player == null) {
            inventoryCache = emptyMap()
            return inventoryCache
        }
        val counts = HashMap<String, Int>()
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (stack.isEmpty) continue
            counts.merge(key(stack.hoverName.string), stack.count, Int::plus)
        }
        inventoryCache = counts
        return counts
    }

    // Sack menus

    /** How many times a sack menu's items have actually been read. Unchanged menus are skipped. */
    var menuReads = 0
        private set

    private var ticks = 0
    private var openScreen: AbstractContainerScreen<*>? = null
    private var openSack = false
    private var lastContents = 0L

    fun onTick() {
        if (++ticks % READ_INTERVAL_TICKS != 0 || !active()) return
        val screen = Minecraft.getInstance().screen as? AbstractContainerScreen<*>
        if (screen !== openScreen) {
            openScreen = screen
            lastContents = 0
            openSack = screen != null && isRelevantSack(ChatUtils.stripColor(screen.title.string))
        }
        if (screen == null || !openSack) return
        val contents = contentsOf(screen)
        if (contents == lastContents) return
        lastContents = contents
        menuReads++
        readSack(screen)
    }

    private fun isRelevantSack(title: String): Boolean {
        val sack = sackTitle.find(title.trim())?.groupValues?.get(1) ?: return false
        return sack.lowercase().removePrefix("small ").removePrefix("medium ").removePrefix("large ") in relevantSacks
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

    private fun readSack(screen: AbstractContainerScreen<*>) {
        val map = sacks
        var changed = false
        for (slot in screen.menu.slots) {
            if (slot.container is Inventory) continue
            val stack = slot.item
            if (stack.isEmpty) continue
            val lore = stack.get(DataComponents.LORE) ?: continue
            var stored = -1L
            for (line in lore.lines()) {
                val match = storedLine.find(ChatUtils.stripColor(line.string).trim()) ?: continue
                stored = parseAmount(match.groupValues[1], match.groupValues[2])
                break
            }
            if (stored < 0) continue
            val name = key(stack.hoverName.string)
            if (name.isEmpty() || (map.size >= MAX_ENTRIES && name !in map)) continue
            val value = stored.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (map.put(name, value) != value) changed = true
        }
        Storage.profile.greenhouse.sacksUpdatedAt = System.currentTimeMillis()
        if (changed) Storage.markDirty()
    }

    private fun parseAmount(number: String, suffix: String): Long {
        val value = number.replace(",", "").toDoubleOrNull() ?: return -1
        val factor = when (suffix.lowercase()) {
            "k" -> 1_000.0
            "m" -> 1_000_000.0
            "b" -> 1_000_000_000.0
            else -> 1.0
        }
        return Math.round(value * factor)
    }

    // Chat

    fun onChat(message: Component) {
        // Cheap filters first: off the Garden nothing is looked at, and the plain text of nearly every chat line is rejected here.
        if (!active() || !message.string.contains("[Sacks]")) return
        val hover = findHoverText(message, 0) ?: return
        val map = sacks
        var changed = false
        for (line in hover.string.lineSequence()) {
            val match = deltaLine.find(ChatUtils.stripColor(line)) ?: continue
            val amount = parseAmount(match.groupValues[2], match.groupValues[3])
            if (amount < 0) continue
            val name = key(match.groupValues[4])
            val old = map[name] ?: continue // only items a sack menu has shown are tracked
            val delta = if (match.groupValues[1] == "-") -amount else amount
            val value = (old + delta).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            if (value != old) {
                map[name] = value
                changed = true
            }
        }
        if (changed) Storage.markDirty()
    }

    private fun findHoverText(component: Component, depth: Int): Component? {
        val event = component.style.hoverEvent
        if (event is HoverEvent.ShowText) return event.value()
        if (depth > 4) return null
        for (sibling in component.siblings) findHoverText(sibling, depth + 1)?.let { return it }
        return null
    }
}
