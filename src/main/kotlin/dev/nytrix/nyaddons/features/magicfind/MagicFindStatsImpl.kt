package dev.nytrix.nyaddons.features.magicfind

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.ChatUtils
import dev.nytrix.nyaddons.core.Storage
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack

/**
 * The player's Magic Find / Pet Luck (tab list Stats widget, SkyBlock Menu as fallback), the Looting level of the held item,
 * and the per-mob bonus learned from rare-drop chat lines. Nothing here runs per frame; the hooks are only called in SkyBlock.
 */
object MagicFindStatsImpl : MfStats {

    private const val MENU_TITLE = "SkyBlock Menu"
    private const val MENU_STATS_SLOT = 13
    private const val MENU_INTERVAL_TICKS = 5
    private const val LEARN_WINDOW_MS = 5000L

    // Tab list lines (colour codes removed, trimmed): `Magic Find: ✯123`, `Pet Luck: ♣10`. The icon is a private-use glyph, so it is skipped
    // as "neither letter nor digit". Menu lore is `✯ Magic Find 123` (value after the name, optional colon).
    private val tabMagicFind = Regex("^Magic Find\\s*:\\s*[^\\p{L}\\d]*([\\d,]+(?:\\.\\d+)?)")
    private val tabPetLuck = Regex("^Pet Luck\\s*:\\s*[^\\p{L}\\d]*([\\d,]+(?:\\.\\d+)?)")
    private val menuMagicFind = Regex("^[^\\p{L}\\d]*Magic Find\\s*:?\\s*[^\\p{L}\\d]*([\\d,]+(?:\\.\\d+)?)")
    private val menuPetLuck = Regex("^[^\\p{L}\\d]*Pet Luck\\s*:?\\s*[^\\p{L}\\d]*([\\d,]+(?:\\.\\d+)?)")

    // `RARE DROP! Item (+208% ✯ Magic Find)`; VERY RARE / CRAZY RARE end in "RARE DROP!" too. Anchored so player chat cannot fake it.
    private val dropLine = Regex("^\\s*(?:(?:VERY |CRAZY )?RARE|INSANE|PET) DROP!.*\\(\\+([\\d,]+(?:\\.\\d+)?)\\s*%?\\s*[^\\p{L}\\d)]*\\s*Magic Find\\)")

    private val enabled get() = NyAddOns.config.combat.magicFind.enabled

    override var general: Double? = null
        private set
    override var petLuck: Double? = null
        private set

    override fun bonusFor(mob: MfMob): Double? = Storage.profile.magicFind.bonuses[mob.id]

    // Tab list

    private var tabHash = 0

    fun onSecond() {
        if (!enabled) return
        val connection = Minecraft.getInstance().connection ?: return
        val lines = ArrayList<String>(connection.onlinePlayers.size)
        var hash = 1
        for (info in connection.onlinePlayers) {
            val text = info.tabListDisplayName?.string ?: continue
            lines += text
            hash = hash * 31 + text.hashCode()
        }
        if (hash == tabHash) return
        tabHash = hash
        readTab(lines)
    }

    /** Parses tab list lines (with or without colour codes) and updates the cached values. Public so a test can feed fake entries. */
    fun readTab(lines: List<String>) {
        for (raw in lines) {
            val line = ChatUtils.stripColor(raw).trim()
            if (line.isEmpty() || line[0] != 'M' && line[0] != 'P') continue
            tabMagicFind.find(line)?.let { parse(it.groupValues[1])?.let { v -> general = v } }
            tabPetLuck.find(line)?.let { parse(it.groupValues[1])?.let { v -> petLuck = v } }
        }
    }

    // SkyBlock Menu fallback

    private var ticks = 0
    private var menuScreen: AbstractContainerScreen<*>? = null
    private var menuStack: ItemStack? = null

    fun onTick() {
        if (++ticks % MENU_INTERVAL_TICKS != 0) return
        val screen = Minecraft.getInstance().screen as? AbstractContainerScreen<*>
        if (screen == null) {
            menuScreen = null
            menuStack = null
            return
        }
        if (!enabled || ChatUtils.stripColor(screen.title.string).trim() != MENU_TITLE) return
        val stack = screen.menu.slots.getOrNull(MENU_STATS_SLOT)?.item ?: return
        if (screen === menuScreen && stack === menuStack) return // unchanged: the server sends a new stack on every update
        menuScreen = screen
        menuStack = stack
        readMenuStack(stack)
    }

    /** Reads Magic Find (and Pet Luck, if listed) from the stats item of the SkyBlock Menu. */
    fun readMenuStack(stack: ItemStack) {
        val lore = stack.get(DataComponents.LORE) ?: return
        for (component in lore.lines()) {
            val line = ChatUtils.stripColor(component.string).trim()
            menuMagicFind.find(line)?.let { parse(it.groupValues[1])?.let { v -> general = v } }
            menuPetLuck.find(line)?.let { parse(it.groupValues[1])?.let { v -> petLuck = v } }
        }
    }

    private fun parse(text: String) = text.replace(",", "").toDoubleOrNull()

    // Looting: cached on the identity of the held stack (the server sends a new stack on every change) and the hotbar slot.

    private var lootingStack: ItemStack? = null
    private var lootingSlot = -1
    private var lootingLevel = 0

    override val looting: Int
        get() {
            val player = Minecraft.getInstance().player ?: return 0
            val stack = player.mainHandItem
            val slot = player.inventory.selectedSlot
            if (stack === lootingStack && slot == lootingSlot) return lootingLevel
            lootingStack = stack
            lootingSlot = slot
            lootingLevel = lootingOf(stack)
            return lootingLevel
        }

    /** Hypixel keeps enchantments in the custom data: `ExtraAttributes`-style compound `enchantments` -> `looting` (int). */
    fun lootingOf(stack: ItemStack): Int {
        if (stack.isEmpty) return 0
        val data = stack.get(DataComponents.CUSTOM_DATA) ?: return 0
        return data.copyTag().getCompoundOrEmpty("enchantments").getIntOr("looting", 0).coerceAtLeast(0)
    }

    // Learning

    fun onChat(message: String) {
        if (!enabled || message.length < 20 || !message.contains("DROP!")) return
        val match = dropLine.find(message) ?: return
        val printed = parse(match.groupValues[1]) ?: return
        if (printed > MfMath.MF_CAP * 2) return
        val general = general ?: return
        val mob = MagicFind.kills.recent(LEARN_WINDOW_MS) ?: return
        // Pet drops are assumed to print MF + Pet Luck (the game rolls pet drops with both), so both are removed. If the game prints MF only,
        // the stored bonus is too low by the Pet Luck; unverified.
        val known = general + if (message.trimStart().startsWith("PET")) petLuck ?: 0.0 else 0.0
        val bonus = (printed - known).coerceAtLeast(0.0)
        if (bonus > MfMath.MF_CAP) return
        val bonuses = Storage.profile.magicFind.bonuses
        if (bonuses.put(mob.id, bonus) != bonus) Storage.markDirty()
    }
}
