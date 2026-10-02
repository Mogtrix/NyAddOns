package dev.nytrix.nyaddons.features.magicfind

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.ChatUtils
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand

/**
 * Finds kills on the client. Mobs on Hypixel carry a name-tag armor stand (`[Lv100] Minotaur 1.5M/1.5M❤`, `☠ Revenant Horror IV 1.5M❤`).
 * Every [SCAN_TICKS] ticks the stands near the player are read; stands of enabled/tracked mobs are remembered. The mobs the player hit
 * lately (attack events, matched to the stand by position) count as killed when the tag shows 0 health or vanishes close to the player.
 * Nothing runs when no mob is enabled or tracked.
 */
object MfKillTracker : MfKills {

    const val SCAN_TICKS = 5
    private const val SCAN_RANGE_SQ = 40.0 * 40.0
    private const val KILL_RANGE_SQ = 10.0 * 10.0
    private const val HIT_MEMORY_TICKS = 100
    private const val HIT_RING = 8
    private const val NEVER = Long.MIN_VALUE / 2 // far in the past without overflowing when subtracted
    private const val HIT_RADIUS_SQ = 3.0 * 3.0

    private class Tag(val mob: MfMob) {
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var health = 1.0
        var hitTick = NEVER
        var seen = 0
    }

    private val tags = HashMap<Int, Tag>()
    private val dead = HashMap<Int, Long>()
    private val nameCache = HashMap<String, MfMob?>()
    private val hitX = DoubleArray(HIT_RING)
    private val hitY = DoubleArray(HIT_RING)
    private val hitZ = DoubleArray(HIT_RING)
    private val hitAt = LongArray(HIT_RING) { NEVER }
    private var hitNext = 0
    private var ticks = 0L
    private var scanId = 0
    private var lastMob: MfMob? = null
    private var lastKillAt = 0L
    private var lastRequest = 0L

    /** Overridable clock for tests. */
    var clock: () -> Long = System::currentTimeMillis

    private val config get() = NyAddOns.config.combat.magicFind

    override fun recent(withinMillis: Long): MfMob? = lastMob?.takeIf { clock() - lastKillAt <= withinMillis }

    val active get() = config.enabled && (config.enabledMobs.isNotEmpty() || config.trackedMobs.isNotEmpty())

    fun onAttack(target: Entity) {
        if (!active) return
        hitX[hitNext] = target.x
        hitY[hitNext] = target.y
        hitZ[hitNext] = target.z
        hitAt[hitNext] = ticks
        hitNext = (hitNext + 1) % HIT_RING
    }

    fun onTick() {
        if (!active) {
            if (tags.isNotEmpty()) { tags.clear(); dead.clear() }
            return
        }
        if (++ticks % SCAN_TICKS != 0L) return
        // The mob list is freed after a while without use: load it back (at most every 30 s) before scanning.
        if (!MagicFind.data.ready) {
            val now = clock()
            if (now - lastRequest > 30_000) {
                lastRequest = now
                MagicFind.data.request()
            }
            return
        }
        scan()
    }

    private fun scan() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        val id = ++scanId
        for (entity in level.entitiesForRendering()) {
            if (entity !is ArmorStand || entity.isRemoved) continue
            val name = entity.customName ?: continue
            if (entity.distanceToSqr(player) > SCAN_RANGE_SQ) continue
            val parsed = parseTag(name.string) ?: continue
            val mob = resolve(parsed.name) ?: continue
            if (mob.id !in config.enabledMobs && mob.id !in config.trackedMobs) continue
            val key = entity.id
            if (dead.containsKey(key)) { dead[key] = ticks; continue } // a counted corpse: ignore while it lingers
            val tag = tags.getOrPut(key) { Tag(mob) }
            tag.x = entity.x
            tag.y = entity.y
            tag.z = entity.z
            tag.health = parsed.health
            tag.seen = id
            applyHits(tag)
            if (parsed.health <= 0.0) kill(key, tag)
        }
        // Tags gone from the world: a kill if the player hit it lately and it was close.
        val it = tags.entries.iterator()
        val px = player.x
        val py = player.y
        val pz = player.z
        while (it.hasNext()) {
            val (key, tag) = it.next()
            if (tag.seen == id) continue
            it.remove()
            val dx = tag.x - px
            val dy = tag.y - py
            val dz = tag.z - pz
            if (dx * dx + dy * dy + dz * dz <= KILL_RANGE_SQ) countKill(key, tag)
        }
        if (dead.isNotEmpty()) dead.values.removeIf { ticks - it > 40 }
        if (nameCache.size > 500) nameCache.clear()
    }

    private fun applyHits(tag: Tag) {
        for (i in 0 until HIT_RING) {
            if (ticks - hitAt[i] > HIT_MEMORY_TICKS) continue
            val dx = hitX[i] - tag.x
            val dz = hitZ[i] - tag.z
            val dy = tag.y - hitY[i] // the stand floats above the mob
            if (dx * dx + dz * dz <= HIT_RADIUS_SQ && dy > -1.5 && dy < 4.0 && hitAt[i] > tag.hitTick) tag.hitTick = hitAt[i]
        }
    }

    private fun kill(key: Int, tag: Tag) {
        tags.remove(key)
        countKill(key, tag)
    }

    private fun countKill(key: Int, tag: Tag) {
        if (ticks - tag.hitTick > HIT_MEMORY_TICKS) return
        dead[key] = ticks
        for (i in 0 until HIT_RING) { // the hits belonged to this kill
            val dx = hitX[i] - tag.x
            val dz = hitZ[i] - tag.z
            if (dx * dx + dz * dz <= HIT_RADIUS_SQ) hitAt[i] = NEVER
        }
        registerKill(tag.mob)
    }

    /** A kill of [mob] was seen: remembers it and queues it for the report. */
    fun registerKill(mob: MfMob) {
        lastMob = mob
        lastKillAt = clock()
        MfReporter.add(mob)
    }

    private fun resolve(name: String): MfMob? = nameCache.getOrPut(name) {
        val data = MagicFind.data
        data.mob(name) ?: ROMAN.find(name)?.let { data.mob(name.substring(0, it.range.first)) }
    }

    private val ROMAN = Regex("\\s+[IVX]+$")
    private val TAG = Regex("^(?:(?:\\[[^\\]]*]|[^\\p{L}\\p{N}\\s\\[])\\s*)*(.+?)\\s+([\\d.,]+[kKmMbBtT]?)(?:/[\\d.,]+[kKmMbBtT]?)?\\s*❤\\s*$")

    class Parsed(val name: String, val health: Double)

    /** `[Lv100] Minotaur 1.5M/1.5M❤` gives (Minotaur, 1.5e6); null if it is not a mob tag. */
    fun parseTag(raw: String): Parsed? {
        if ('❤' !in raw) return null
        val m = TAG.matchEntire(ChatUtils.stripColor(raw).trim()) ?: return null
        return Parsed(m.groupValues[1], parseNumber(m.groupValues[2]))
    }

    private fun parseNumber(text: String): Double {
        val last = text.last()
        val factor = when (last.lowercaseChar()) {
            'k' -> 1e3; 'm' -> 1e6; 'b' -> 1e9; 't' -> 1e12; else -> 1.0
        }
        val digits = if (factor == 1.0) text else text.dropLast(1)
        return (digits.replace(",", "").toDoubleOrNull() ?: 1.0) * factor
    }

    /** For `/ny trackmob debug`: the health-bar name tags near the player and how each one was read. */
    fun debugLines(): List<String> {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return listOf("No world.")
        val player = mc.player ?: return listOf("No player.")
        val out = ArrayList<String>()
        out += "Mob data loaded: ${MagicFind.data.ready}. Enabled: ${config.enabledMobs.size}, tracked: ${config.trackedMobs.size}."
        for (entity in level.entitiesForRendering()) {
            if (out.size >= 9) { out += "(more tags not shown)"; break }
            if (entity !is ArmorStand) continue
            val name = entity.customName?.string ?: continue
            if ('❤' !in name || entity.distanceToSqr(player) > 30.0 * 30.0) continue
            val text = ChatUtils.stripColor(name)
            val parsed = parseTag(name)
            val mob = parsed?.let { resolve(it.name) }
            val state = when {
                parsed == null -> "§cnot read as a mob tag"
                mob == null -> "§cread as \"${parsed.name}\", no Magic Find mob"
                mob.id in config.trackedMobs || mob.id in config.enabledMobs -> "§aOK: ${mob.name}"
                else -> "§eknown (${mob.name}) but not enabled or tracked"
            }
            out += "§f$text §7-> $state"
        }
        if (out.size == 1) out += "No health-bar name tags within 30 blocks."
        return out
    }

    /** Test hook. */
    fun reset() {
        tags.clear(); dead.clear(); nameCache.clear(); lastMob = null; ticks = 0; scanId = 0
        hitAt.fill(NEVER)
    }
}
