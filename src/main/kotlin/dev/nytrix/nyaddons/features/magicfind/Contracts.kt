package dev.nytrix.nyaddons.features.magicfind

/**
 * One drop of a mob. [chance] is the base chance as a fraction (0.02 = 2%). A drop with a special rule ("per hit", "per Summoning Eye",
 * no usable number) has [special] set and a chance of 0; it is listed without odds.
 */
class MfDrop(val item: String, val chance: Double, val pet: Boolean, val special: Boolean, val note: String?)

/**
 * A mob family as the Bestiary names it (levels merged). [id] is the lower-case name without punctuation, matched against name tags.
 * [drops] holds only drops MF can affect (chance below 5%) plus special ones. [slayerTier] is 0 unless the mob is a slayer boss;
 * [defaultOn] is true for King Minos, Minos Inquisitor and max-tier slayer bosses.
 */
class MfMob(
    val id: String,
    val name: String,
    val category: String,
    val drops: List<MfDrop>,
    val slayerTier: Int,
    val defaultOn: Boolean,
) {
    val slayer get() = slayerTier > 0
}

/** A menu category (a Bestiary category, or the virtual "Slayer Bosses"). */
class MfCategory(val id: String, val name: String, val mobs: List<MfMob>)

/** Mob and drop data, loaded on demand and freed when idle. */
interface MfData {
    val ready: Boolean

    /** The categories shown in the menu: curated, only mobs with a drop under 5%. */
    val categories: List<MfCategory>

    /** Any mob in the game with an MF-affected drop (also ones not in the menu), by name-tag name (case/punctuation/level ignored). */
    fun mob(name: String): MfMob?

    /** Every known mob name, for /trackmob tab completion. */
    fun mobNames(): List<String>

    /** Starts the background load / daily refresh if needed. Cheap to call repeatedly. */
    fun request()
}

/** The player's Magic Find numbers. All cheap to read; nothing is computed in here at kill time. */
interface MfStats {
    /** General MF from the tab list Stats widget (or the SkyBlock Menu), null if never seen. */
    val general: Double?
    val petLuck: Double?

    /** The bonus learned for [mob] from rare-drop messages, null until it has dropped something. */
    fun bonusFor(mob: MfMob): Double?

    /** Looting level on the held item (0 if none). */
    val looting: Int
}

/** Tells who was killed lately. Set by the kill detector, read by the stats learner. */
fun interface MfKills {
    /** The mob killed within [withinMillis], or null. */
    fun recent(withinMillis: Long): MfMob?
}

/** The single place the parts meet. The real implementations are assigned here at merge. */
object MagicFind {
    var data: MfData = EmptyData
    var stats: MfStats = EmptyStats
    var kills: MfKills = MfKills { null }

    private object EmptyData : MfData {
        override val ready = false
        override val categories = emptyList<MfCategory>()
        override fun mob(name: String): MfMob? = null
        override fun mobNames() = emptyList<String>()
        override fun request() {}
    }

    private object EmptyStats : MfStats {
        override val general: Double? = null
        override val petLuck: Double? = null
        override fun bonusFor(mob: MfMob): Double? = null
        override val looting = 0
    }
}
