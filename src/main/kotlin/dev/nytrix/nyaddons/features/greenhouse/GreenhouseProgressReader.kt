package dev.nytrix.nyaddons.features.greenhouse

import dev.nytrix.nyaddons.core.ChatUtils
import dev.nytrix.nyaddons.core.Storage
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.player.Inventory

/**
 * Reads which mutations the player has analysed from the "All Mutations" menu (the Carpenter's list, also reached from
 * the Crop Analyzer): each mutation's item ends its lore with `ANALYZED` or `UNKNOWN`. The menu is the authority, so it
 * overrides ticks made by hand for the mutations it shows. Works anywhere in SkyBlock, since the Crop Analyzer is in the
 * lab, not on the Garden, and only does anything while that menu is open and has changed.
 */
object GreenhouseProgressReader {

    private val menuTitle = Regex("^(?:\\(\\d+/\\d+\\) )?All Mutations$")
    private const val ANALYSED = "ANALYZED"
    private const val UNKNOWN = "UNKNOWN"
    private const val CHECK_INTERVAL_TICKS = 4

    private var ticks = 0
    private var openScreen: AbstractContainerScreen<*>? = null
    private var isMenu = false
    private var lastContents = 0L

    /** How many times the menu's items have actually been read. */
    var menuReads = 0
        private set

    fun onTick() {
        if (++ticks % CHECK_INTERVAL_TICKS != 0) return
        val screen = Minecraft.getInstance().screen as? AbstractContainerScreen<*>
        if (screen !== openScreen) {
            openScreen = screen
            isMenu = screen != null && menuTitle.matches(ChatUtils.stripColor(screen.title.string).trim())
            lastContents = 0
        }
        if (screen == null || !isMenu) return

        var hash = screen.menu.stateId.toLong() + 1
        for (slot in screen.menu.slots) hash = hash * 31 + System.identityHashCode(slot.item) + slot.item.count
        if (hash == lastContents) return

        // The names need the mutation list, which loads in the background; read again once it is there.
        val data = Greenhouse.data
        data.request()
        if (!data.ready) return
        lastContents = hash
        menuReads++
        read(screen, data)
    }

    private fun read(screen: AbstractContainerScreen<*>, data: GhData) {
        val byName = HashMap<String, GhMutation>(data.mutations.size * 2)
        for (mutation in data.mutations) byName[normalise(mutation.name)] = mutation
        val analysed = Storage.profile.greenhouse.analysed
        var changed = false
        for (slot in screen.menu.slots) {
            if (slot.container is Inventory || slot.item.isEmpty) continue
            val mutation = byName[normalise(ChatUtils.stripColor(slot.item.hoverName.string))] ?: continue
            val lore = slot.item.get(DataComponents.LORE)?.lines() ?: continue
            val state = lore.firstNotNullOfOrNull {
                when (ChatUtils.stripColor(it.string).trim()) {
                    ANALYSED -> true
                    UNKNOWN -> false
                    else -> null
                }
            } ?: continue
            changed = (if (state) analysed.add(mutation.id) else analysed.remove(mutation.id)) || changed
        }
        if (changed) Storage.markDirty()
    }

    /** Names differ in punctuation and case between the menu and the data file (`Do-not-eat-shroom`). */
    private fun normalise(name: String) = buildString(name.length) { for (c in name) if (c.isLetterOrDigit()) append(c.lowercaseChar()) }
}
