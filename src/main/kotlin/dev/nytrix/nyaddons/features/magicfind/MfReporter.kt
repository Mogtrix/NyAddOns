package dev.nytrix.nyaddons.features.magicfind

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.ChatUtils
import dev.nytrix.nyaddons.config.MfBreakdown
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style

/** Collects kills for the configured window, then prints one batch: a line per (mob, MF) pair, plus drop odds for tracked mobs. */
object MfReporter {

    private class Entry(val mob: MfMob, val general: Double?, val bonus: Double?) {
        var count = 0
        val mf get() = general?.let { it + (bonus ?: 0.0) }
    }

    private val entries = ArrayList<Entry>()
    private var windowEnd = 0L
    private var unknownShown = false
    private var slayerNoteShown = false

    /** Where lines go; tests swap it to capture them. */
    var sink: (Component) -> Unit = { ChatUtils.chat(it) }

    private val config get() = NyAddOns.config.combat.magicFind

    fun add(mob: MfMob) {
        val now = MfKillTracker.clock()
        val stats = MagicFind.stats
        val general = stats.general
        val bonus = stats.bonusFor(mob)
        val entry = entries.firstOrNull { it.mob === mob && it.general == general && it.bonus == bonus }
            ?: Entry(mob, general, bonus).also { entries += it }
        if (entries.size == 1 && entry.count == 0) windowEnd = now + (config.windowSeconds * 1000).toLong()
        entry.count++
    }

    fun onTick() {
        if (entries.isEmpty() || MfKillTracker.clock() < windowEnd) return
        flush()
    }

    fun flush() {
        if (entries.isEmpty()) return
        val batch = ArrayList(entries)
        entries.clear()
        val tracked = HashSet<MfMob>()
        for (entry in batch) {
            val mf = entry.mf
            if (mf == null) {
                if (!unknownShown) {
                    unknownShown = true
                    sink(Component.literal("§eMagic Find unknown (open the SkyBlock menu or show the Stats tab widget)"))
                }
                continue
            }
            sink(line(entry, mf))
            if (entry.mob.id in config.trackedMobs && config.trackOdds && tracked.add(entry.mob)) odds(entry.mob, mf)
        }
    }

    private fun line(entry: Entry, mf: Double): Component {
        val mob = entry.mob
        val times = if (entry.count > 1) " x${entry.count}" else ""
        val general = "General ${fmt(entry.general ?: 0.0)}%"
        val parts = if (entry.bonus != null) "$general + ${mob.name} ${fmt(entry.bonus)}% (learned)" else "$general (no ${mob.name} bonus learned yet)"
        val suffix = if (entry.bonus == null) " (general only)" else ""
        val base = "§e${mob.name}$times: §a${fmt(mf)}%§e Magic Find$suffix"
        return when (config.breakdown) {
            MfBreakdown.OFF -> Component.literal(base)
            MfBreakdown.INLINE -> Component.literal("$base §7($parts)")
            else -> Component.literal(base).withStyle(Style.EMPTY.withHoverEvent(HoverEvent.ShowText(Component.literal("§7$parts"))))
        }
    }

    private fun odds(mob: MfMob, mf: Double) {
        if (mob.slayer) {
            if (!slayerNoteShown) {
                slayerNoteShown = true
                sink(Component.literal("§7Odds are not shown for slayer bosses (weighted loot pools)."))
            }
            return
        }
        val stats = MagicFind.stats
        val looting = stats.looting
        val petLuck = stats.petLuck ?: 0.0
        val sorted = mob.drops.sortedWith(compareBy({ it.special }, { it.chance }))
        for ((i, drop) in sorted.withIndex()) {
            if (i == MAX_DROPS) {
                sink(Component.literal("§7+${sorted.size - MAX_DROPS} more"))
                break
            }
            if (drop.special) {
                sink(Component.literal("§e${drop.item}: §7special${drop.note?.let { " ($it)" } ?: ""}"))
                continue
            }
            val withMf = MfMath.chance(drop.chance, mf, looting, drop.pet, petLuck)
            val extra = when {
                drop.pet -> " (+Pet Luck)"
                looting > 0 && MfMath.lootingMultiplier(looting) > 1.0 -> " (+Looting ${roman(looting)})"
                else -> ""
            }
            sink(Component.literal("§e${drop.item}: §7${MfMath.oneIn(drop.chance)} base §e→ §a${MfMath.oneIn(withMf)}§7 with Magic Find$extra"))
        }
    }

    private const val MAX_DROPS = 12

    private fun fmt(value: Double) = if (value == Math.rint(value)) "%,.0f".format(value) else "%,.1f".format(value)

    private fun roman(n: Int): String {
        val table = listOf("", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")
        return table.getOrNull(n) ?: n.toString()
    }

    /** Test hook. */
    fun reset() {
        entries.clear(); unknownShown = false; slayerNoteShown = false
    }
}
