package dev.nytrix.nyaddons.features.greenhouse

import dev.nytrix.nyaddons.core.Storage
import kotlin.math.atan2

/** Which of the 10x10 Greenhouse squares the player has unlocked, saved per profile as a 100 character string. */
object GreenhousePlots {

    const val GRID = 10
    const val CELLS = GRID * GRID

    /**
     * A new Greenhouse starts with 12 squares in the middle. Assumption: they form a 2 x 6 block (rows 4-5, columns 2-7),
     * the real game's exact shape was not checked.
     */
    fun default(): BooleanArray = BooleanArray(CELLS).also { for (r in 4..5) for (c in 2..7) it[r * GRID + c] = true }

    fun all(): BooleanArray = BooleanArray(CELLS) { true }

    fun count(mask: BooleanArray): Int = mask.count { it }

    /** Cell indexes (row * 10 + column), the nearest to the middle first and then spiralling outwards. */
    private val centreOrder: IntArray = (0 until CELLS).sortedWith(
        compareBy<Int>({ val dr = it / GRID - 4.5; val dc = it % GRID - 4.5; (dr * dr + dc * dc).toFloat() })
            .thenBy { atan2(it / GRID - 4.5, it % GRID - 4.5) },
    ).toIntArray()

    /** The [n] squares nearest the middle unlocked. */
    fun fill(n: Int): BooleanArray = BooleanArray(CELLS).also { for (i in 0 until n.coerceIn(0, CELLS)) it[centreOrder[i]] = true }

    fun parse(text: String): BooleanArray =
        if (text.length != CELLS) default() else BooleanArray(CELLS) { text[it] == '1' }

    fun encode(mask: BooleanArray): String = String(CharArray(CELLS) { if (mask[it]) '1' else '0' })

    /** The current profile's mask (a fresh array each call). */
    fun current(): BooleanArray = parse(Storage.profile.greenhouse.plots)

    fun save(mask: BooleanArray) {
        Storage.profile.greenhouse.plots = encode(mask)
        Storage.markDirty()
    }
}
