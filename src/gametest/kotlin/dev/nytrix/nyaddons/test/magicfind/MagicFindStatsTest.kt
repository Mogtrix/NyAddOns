package dev.nytrix.nyaddons.test.magicfind

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.Storage
import dev.nytrix.nyaddons.features.magicfind.MagicFind
import dev.nytrix.nyaddons.features.magicfind.MagicFindStatsImpl
import dev.nytrix.nyaddons.features.magicfind.MfKills
import dev.nytrix.nyaddons.features.magicfind.MfMob
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.SimpleContainer
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore

/**
 * In-game checks for the Magic Find stats reader. The tab list is a server-sent packet, so the real tab path (`onSecond`) is only timed;
 * its parser `readTab` takes the lines directly and is fed fake entries here.
 */
@Suppress("UnstableApiUsage")
class MagicFindStatsTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        val stats = MagicFindStatsImpl
        MagicFind.stats = stats
        System.setProperty("nyaddons.devArea", "Hub")
        try {
            context.worldBuilder().create().use { world ->
                context.onClient { Storage.reset() }
                val minotaur = mob("minotaur")
                val yeti = mob("yeti")
                var killed: MfMob? = minotaur
                MagicFind.kills = MfKills { killed }
                val bonuses = { Storage.profile.magicFind.bonuses }

                // Nothing known yet; learning does nothing without a general MF.
                context.onClient {
                    check(stats.general == null && stats.petLuck == null) { "values before any read" }
                    stats.onChat("RARE DROP! Enchanted Book (+208% ✯ Magic Find)")
                    check(bonuses().isEmpty()) { "learned without a general MF: ${bonuses()}" }
                }

                // Tab list line shapes.
                context.onClient {
                    stats.readTab(listOf("§e§lStats:", " §r§7Strength: §c❁1,200", " §r§7Magic Find: §b✯123", " §r§7Pet Luck: §d♣15.5"))
                    check(stats.general == 123.0 && stats.petLuck == 15.5) { "colour coded: ${stats.general} ${stats.petLuck}" }
                    stats.readTab(listOf(" Magic Find:  1,234.5%", " Pet Luck: 7%"))
                    check(stats.general == 1234.5 && stats.petLuck == 7.0) { "commas/%/private use: ${stats.general} ${stats.petLuck}" }
                    stats.readTab(listOf(" Magic Find: ✯300"))
                    check(stats.general == 300.0 && stats.petLuck == 7.0) { "only MF line: ${stats.general} ${stats.petLuck}" }
                    stats.readTab(listOf("Notch", "Magic Find is nice", "Magic Find: none"))
                    check(stats.general == 300.0) { "garbage changed the value: ${stats.general}" }
                }

                // SkyBlock Menu fallback via a stand-in menu with the stats item in slot 13.
                context.onClient { stats.readTab(emptyList()) }
                context.setScreen {
                    val inventory = Minecraft.getInstance().player!!.inventory
                    val container = SimpleContainer(54)
                    container.setItem(13, ItemStack(Items.PLAYER_HEAD).apply {
                        set(DataComponents.CUSTOM_NAME, Component.literal("Your SkyBlock Profile"))
                        set(DataComponents.LORE, ItemLore(listOf("§7Strength §c❁ 100", "§b✯ Magic Find §f412.5", "§d♣ Pet Luck §f20").map { Component.literal(it) }))
                    })
                    ContainerScreen(ChestMenu.sixRows(0, inventory, container), inventory, Component.literal("SkyBlock Menu"))
                }
                context.waitForScreen(ContainerScreen::class.java)
                context.waitTicks(20)
                context.onClient { check(stats.general == 412.5 && stats.petLuck == 20.0) { "menu fallback: ${stats.general} ${stats.petLuck}" } }
                context.setScreen { null }

                // Looting on the held item.
                context.onClient {
                    check(stats.looting == 0) { "empty hand looting ${stats.looting}" }
                    hold(sword(5))
                    check(stats.looting == 5) { "looting V read as ${stats.looting}" }
                    hold(sword(0, other = true))
                    check(stats.looting == 0) { "no looting read as ${stats.looting}" }
                    hold(sword(3))
                    check(stats.looting == 3) { "looting III read as ${stats.looting}" }
                    hold(ItemStack(Items.STICK))
                    check(stats.looting == 0) { "plain item looting ${stats.looting}" }
                }

                // Learning.
                context.onClient {
                    stats.readTab(listOf(" Magic Find: ✯300", " Pet Luck: ♣20"))
                    stats.onChat("RARE DROP! Enchanted Book (+408% ✯ Magic Find)")
                    check(stats.bonusFor(minotaur) == 108.0) { "rare drop bonus ${stats.bonusFor(minotaur)}" }
                    stats.onChat("VERY RARE DROP! Thing (+350 Magic Find)")
                    check(stats.bonusFor(minotaur) == 50.0) { "very rare, no icon/percent: ${stats.bonusFor(minotaur)}" }
                    stats.onChat("CRAZY RARE DROP! Thing (+300.5%  Magic Find)")
                    check(stats.bonusFor(minotaur) == 0.5) { "crazy rare ${stats.bonusFor(minotaur)}" }
                    stats.onChat("INSANE DROP! Thing (+100% ✯ Magic Find)")
                    check(stats.bonusFor(minotaur) == 0.0) { "bonus must never be negative: ${stats.bonusFor(minotaur)}" }
                    check(bonuses().size == 1) { "one mob only: ${bonuses()}" }

                    killed = yeti
                    stats.onChat("PET DROP! Baby Yeti (+368% ✯ Magic Find)") // 368 - 300 - 20
                    check(stats.bonusFor(yeti) == 48.0) { "pet drop bonus ${stats.bonusFor(yeti)}" }

                    // Spoofed, absurd, unrelated.
                    killed = mob("zealot")
                    stats.onChat("Player: RARE DROP! x (+400% ✯ Magic Find)")
                    stats.onChat("RARE DROP! x (+99999% ✯ Magic Find)")
                    stats.onChat("RARE DROP! no magic find here")
                    check(stats.bonusFor(killed!!) == null) { "learned from junk: ${stats.bonusFor(killed!!)}" }

                    // No recent kill: nothing is stored.
                    killed = null
                    val before = bonuses().toMap()
                    stats.onChat("RARE DROP! Enchanted Book (+408% ✯ Magic Find)")
                    check(bonuses() == before) { "learned without a kill: ${bonuses()}" }
                }

                // Only when enabled.
                context.onClient {
                    killed = minotaur
                    NyAddOns.config.combat.magicFind.enabled = false
                    stats.onChat("RARE DROP! Enchanted Book (+408% ✯ Magic Find)")
                    check(stats.bonusFor(minotaur) == 0.0) { "learned while disabled" }
                    NyAddOns.config.combat.magicFind.enabled = true
                }

                // Cost with nothing changed.
                context.onClient {
                    val runs = 100_000
                    repeat(runs / 10) { stats.onTick() }
                    var start = System.nanoTime()
                    repeat(runs) { stats.onTick() }
                    NyAddOns.logger.info("[NyBench] mf stats, one tick, nothing changed: ${(System.nanoTime() - start) / runs} ns")
                    stats.onSecond()
                    start = System.nanoTime()
                    repeat(1000) { stats.onSecond() }
                    NyAddOns.logger.info("[NyBench] mf stats, one second hook, tab list unchanged: ${(System.nanoTime() - start) / 1000} ns")
                    start = System.nanoTime()
                    repeat(runs) { stats.looting }
                    NyAddOns.logger.info("[NyBench] mf stats, looting read, same held item: ${(System.nanoTime() - start) / runs} ns")
                    start = System.nanoTime()
                    repeat(runs) { stats.onChat("<Player> hello there, how are you doing today") }
                    NyAddOns.logger.info("[NyBench] mf stats, one ordinary chat line: ${(System.nanoTime() - start) / runs} ns")
                }
            }
        } finally {
            System.clearProperty("nyaddons.devArea")
            MagicFind.kills = MfKills { null }
        }
    }

    private fun mob(id: String) = MfMob(id, id, "test", emptyList(), 0, false)

    private fun sword(looting: Int, other: Boolean = false) = ItemStack(Items.DIAMOND_SWORD).apply {
        val enchants = CompoundTag()
        if (looting > 0) enchants.putInt("looting", looting)
        if (other) enchants.putInt("sharpness", 7)
        val tag = CompoundTag()
        tag.put("enchantments", enchants)
        set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
    }

    private fun hold(stack: ItemStack) {
        val player = Minecraft.getInstance().player!!
        player.inventory.setItem(player.inventory.selectedSlot, stack)
    }

    private fun ClientGameTestContext.onClient(block: (Minecraft) -> Unit) {
        runOnClient<RuntimeException> { block(it) }
    }
}
