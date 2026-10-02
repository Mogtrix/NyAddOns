package dev.nytrix.nyaddons.core

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.player.Inventory

/**
 * Press F8 with any menu open to copy what is in it to the clipboard: the title, and the slot,
 * item, name and lore of everything in the menu. It reads what is already on screen and sends
 * nothing anywhere. Menus that no mod documents can be described exactly this way.
 */
object MenuDump {

    private const val KEY_F8 = 297
    private const val CHEST_COLUMNS = 9

    fun init() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is AbstractContainerScreen<*>) {
                ScreenKeyboardEvents.allowKeyPress(screen).register { _, key ->
                    if (key.key() == KEY_F8) copy(screen)
                    key.key() != KEY_F8
                }
            }
        }
    }

    private fun copy(screen: AbstractContainerScreen<*>) {
        val title = ChatUtils.stripColor(screen.title.string)
        val slots = screen.menu.slots.filter { it.container !is Inventory }
        val text = buildString {
            appendLine("Menu \"$title\", ${slots.size} slots, ${slots.count { !it.item.isEmpty }} with items")
            for (slot in slots) {
                val stack = slot.item
                if (stack.isEmpty) continue
                val id = BuiltInRegistries.ITEM.getKey(stack.item)
                appendLine("slot ${slot.index} (row ${slot.index / CHEST_COLUMNS}, column ${slot.index % CHEST_COLUMNS}): $id x${stack.count}")
                appendLine("  name: ${ChatUtils.stripColor(stack.hoverName.string)}")
                stack.get(DataComponents.LORE)?.lines()?.forEach { appendLine("  lore: ${ChatUtils.stripColor(it.string)}") }
            }
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(text)
        ChatUtils.chat("Copied \"$title\" (${text.lines().count { it.startsWith("slot ") }} items) to your clipboard.")
    }
}
