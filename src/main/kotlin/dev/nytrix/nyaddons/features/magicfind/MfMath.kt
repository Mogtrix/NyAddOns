package dev.nytrix.nyaddons.features.magicfind

import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Every number the Magic Find rules depend on lives here, so a change by Hypixel (a Magic Find Rework is announced) touches one file.
 * The data task verifies [lootingMultiplier] against the wiki and owns this file; the signatures are shared.
 */
object MfMath {

    /** The Magic Find stat is capped here. */
    const val MF_CAP = 900.0

    /** Magic Find only affects drops whose chance is strictly below this. */
    const val MF_THRESHOLD = 0.05

    /** Looting multiplies the base chance before Magic Find. Placeholder until verified against the wiki. */
    fun lootingMultiplier(level: Int): Double = 1.0 + 0.15 * level.coerceAtLeast(0)

    /**
     * The chance of a drop with base chance [base] (a fraction) for a player with [mf] Magic Find and [looting] on the weapon.
     * Pet drops use MF + [petLuck] and ignore Looting. A drop that is already 5% or more after Looting is not boosted.
     */
    fun chance(base: Double, mf: Double, looting: Int, pet: Boolean, petLuck: Double): Double {
        val afterLooting = if (pet) base else base * lootingMultiplier(looting)
        if (afterLooting >= MF_THRESHOLD) return min(afterLooting, 1.0)
        val effective = min(mf + if (pet) petLuck else 0.0, MF_CAP).coerceAtLeast(0.0)
        return min(afterLooting * (1 + effective / 100), 1.0)
    }

    /** `1 in 1,234` for a chance, or `always` at 100%. */
    fun oneIn(chance: Double): String {
        if (chance <= 0) return "never"
        if (chance >= 1) return "always"
        return "1 in %,d".format((1 / chance).roundToLong())
    }
}
