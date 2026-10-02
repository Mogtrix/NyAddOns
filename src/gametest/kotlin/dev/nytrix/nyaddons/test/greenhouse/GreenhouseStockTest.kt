package dev.nytrix.nyaddons.test.greenhouse

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.Storage
import dev.nytrix.nyaddons.features.greenhouse.Greenhouse
import dev.nytrix.nyaddons.features.greenhouse.GreenhouseDataImpl
import dev.nytrix.nyaddons.features.greenhouse.GreenhouseStockImpl
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.world.SimpleContainer
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore

/** In-game checks for the Greenhouse stock readers (sack menus, `[Sacks]` chat, inventory). */
@Suppress("UnstableApiUsage")
class GreenhouseStockTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        Greenhouse.stock = GreenhouseStockImpl
        val stock = GreenhouseStockImpl
        System.setProperty("nyaddons.devArea", "Hub")
        try {
            context.worldBuilder().create().use { world ->
                context.onClient { Storage.reset() }
                val sacks = { Storage.profile.greenhouse.sacks }

                // Which mutations are analysed is read from the All Mutations menu anywhere (items as in a real dump).
                context.onClient {
                    GreenhouseDataImpl.loadFrom(GreenhouseStockTest::class.java.getResourceAsStream("/greenhouse/data.json")!!.reader())
                    Storage.profile.greenhouse.analysed.add("cindershade") // ticked by hand, but the game says UNKNOWN
                }
                openMenu(context, "(1/2) All Mutations", mapOf(
                    10 to head("Ashwreath", "Mutation Crop", "", "Size: 1x1", "", "ANALYZED", "", "Click to preview mutation layout!"),
                    29 to head("Cindershade", "Mutation Crop", "", "Size: 1x1", "", "UNKNOWN", "", "Click to preview mutation layout!"),
                    33 to head("Do-not-eat-shroom", "Mutation Crop", "", "ANALYZED", ""),
                    34 to ItemStack(Items.STONE),
                ))
                context.waitTicks(40)
                context.onClient {
                    val analysed = Storage.profile.greenhouse.analysed
                    check("ashwreath" in analysed && "cindershade" !in analysed) { "analysed after the menu: $analysed" }
                    check(analysed.any { it.startsWith("do") }) { "punctuated name was not matched: $analysed" }
                }

                // Off the Garden nothing is read.
                openMenu(context, "Mutations Sack", mapOf(10 to head("Phantomleaf", "Stored: 12/1,024")))
                context.waitTicks(40)
                context.onClient {
                    check(sacks().isEmpty() && stock.menuReads == 0) { "a sack was read off the Garden" }
                    check(stock.count("Phantomleaf") == null) { "count should be null for unseen items" }
                }

                // On the Garden it is.
                System.setProperty("nyaddons.devArea", "Garden")
                context.waitTicks(40) // SkyBlockData.update runs once a second
                context.onClient {
                    check(sacks()["phantomleaf"] == 12) { "Phantomleaf: ${sacks()}" }
                    check(stock.inSacks("§aPhantomleaf") == 12 && stock.count("PHANTOMLEAF") == 12) { "lookup" }
                    check(stock.sacksUpdatedAt > 0) { "sacksUpdatedAt not set" }
                }

                // Unchanged menu: not read again.
                val reads = stock.menuReads
                context.waitTicks(40)
                context.onClient { check(stock.menuReads == reads) { "unchanged sack menu was read again" } }

                // k suffixes and a second item; Sack of Sacks and unrelated sacks are ignored.
                openMenu(context, "Garden Sack", mapOf(
                    10 to head("§9Helianthus", "§7Stored: §a60.5k§7/640k"),
                    11 to head("Timestalk", "Stored: 1,234/9,000"),
                    12 to ItemStack(Items.STONE),
                ))
                context.waitTicks(20)
                context.onClient {
                    check(sacks()["helianthus"] == 60_500) { "k suffix: ${sacks()}" }
                    check(sacks()["timestalk"] == 1234) { "Timestalk: ${sacks()}" }
                    check(sacks().size == 3) { "stone should not be stored: ${sacks()}" }
                }
                // The game spells sacks with a size.
                openMenu(context, "Small Mutations Sack", mapOf(10 to head("Godseed", "Stored: 3/64")))
                context.waitTicks(20)
                openMenu(context, "Large Enchanted Agronomy Sack", mapOf(10 to head("Enchanted Wheat", "Stored: 7/20,160")))
                context.waitTicks(20)
                context.onClient { check(sacks()["godseed"] == 3 && sacks()["enchanted wheat"] == 7) { "sized sack names: ${sacks()}" } }
                context.onClient { check(sacks().size == 5) { "sack map: ${sacks()}" } }
                openMenu(context, "Sack of Sacks", mapOf(10 to head("Wheat", "Stored: 99/100")))
                context.waitTicks(20)
                openMenu(context, "Mining Sack", mapOf(10 to head("Cobblestone", "Stored: 99/100")))
                context.waitTicks(20)
                context.onClient { check(sacks().size == 5) { "ignored sacks were read: ${sacks()}" } }

                // Chat deltas.
                val hover = Component.literal("§a +5 Phantomleaf §7(Mutations)\n§c -20 Timestalk\n§a +7 Wheat\n -99,999 Helianthus§7 (x)")
                val line = Component.literal("[Sacks] +3 items. (Last 5s.)").withStyle(Style.EMPTY.withHoverEvent(HoverEvent.ShowText(hover)))
                context.onClient {
                    ClientReceiveMessageEvents.GAME.invoker().onReceiveGameMessage(Component.literal("hello"), false)
                    ClientReceiveMessageEvents.GAME.invoker().onReceiveGameMessage(line, false)
                    check(sacks()["phantomleaf"] == 17) { "Phantomleaf after chat: ${sacks()}" }
                    check(sacks()["timestalk"] == 1214) { "Timestalk after chat: ${sacks()}" }
                    check(sacks()["helianthus"] == 0) { "never below 0: ${sacks()}" }
                    check("wheat" !in sacks()) { "unknown item stored from chat" }
                }
                System.setProperty("nyaddons.devArea", "Hub")
                context.waitTicks(40)
                context.onClient {
                    ClientReceiveMessageEvents.GAME.invoker().onReceiveGameMessage(line, false)
                    check(sacks()["phantomleaf"] == 17) { "chat applied off the Garden" }
                }

                // Inventory.
                context.setScreen { null }
                world.server.runCommand("give @p minecraft:wheat 12")
                world.server.runCommand("give @p minecraft:wheat 5")
                context.waitTicks(40)
                context.onClient {
                    check(stock.inInventory("Wheat") == 17) { "inventory wheat: ${stock.inInventory("Wheat")}" }
                    check(stock.count("wheat") == 17) { "count of an inventory-only item" }
                    check(stock.count("Phantomleaf") == 17) { "sacks + inventory" }
                    check(stock.inSacks("wheat") == null) { "wheat is not in sacks" }
                }

                // Cost of one unchanged-menu tick and one chat line.
                System.setProperty("nyaddons.devArea", "Garden")
                openMenu(context, "Mutations Sack", mapOf(10 to head("Phantomleaf", "Stored: 12/1,024")))
                context.waitTicks(40)
                context.onClient {
                    val runs = 100_000
                    repeat(runs / 10) { stock.onTick() }
                    var start = System.nanoTime()
                    repeat(runs) { stock.onTick() }
                    NyAddOns.logger.info("[NyBench] greenhouse stock, one tick with an unchanged sack menu open: ${(System.nanoTime() - start) / runs} ns")
                    val plain = Component.literal("<Player> hello there")
                    start = System.nanoTime()
                    repeat(runs) { stock.onChat(plain) }
                    NyAddOns.logger.info("[NyBench] greenhouse stock, one ordinary chat line: ${(System.nanoTime() - start) / runs} ns")
                    start = System.nanoTime()
                    repeat(runs / 10) { stock.onChat(line) }
                    NyAddOns.logger.info("[NyBench] greenhouse stock, one [Sacks] chat line: ${(System.nanoTime() - start) / (runs / 10)} ns")
                }
            }
        } finally {
            System.clearProperty("nyaddons.devArea")
        }
    }

    private fun head(name: String, vararg lore: String) = ItemStack(Items.PLAYER_HEAD).apply {
        set(DataComponents.CUSTOM_NAME, Component.literal(name))
        set(DataComponents.LORE, ItemLore(lore.map { Component.literal(it) }))
    }

    private fun openMenu(context: ClientGameTestContext, title: String, items: Map<Int, ItemStack>) {
        context.setScreen {
            val inventory = Minecraft.getInstance().player!!.inventory
            val container = SimpleContainer(54)
            items.forEach { (slot, stack) -> container.setItem(slot, stack) }
            ContainerScreen(ChestMenu.sixRows(0, inventory, container), inventory, Component.literal(title))
        }
        context.waitForScreen(ContainerScreen::class.java)
    }

    private fun ClientGameTestContext.onClient(block: (Minecraft) -> Unit) {
        runOnClient<RuntimeException> { block(it) }
    }
}
