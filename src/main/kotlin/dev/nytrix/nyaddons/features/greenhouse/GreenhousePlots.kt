package dev.nytrix.nyaddons.features.greenhouse

import dev.nytrix.nyaddons.core.Storage
import kotlin.math.atan2

/** Which of the 10x10 Greenhouse squares the player has unlocked, saved per profile as a 100 character string. */
object GreenhousePlots {

    const val GRID = 10
    const val CELLS = GRID * GRID

    /**
     * A new Greenhouse starts with 12 squares in the middle, a rounded blob of 2 + 4 + 4 + 2 squares: rows 3 to 6 with the columns
     * 4-5, 3-6, 3-6, 4-5, counted from 0. That is the shape recorded from the reference planner's UI (docs/skymutations-behaviour.md);
     * reading the numbers from 0 is the reading that centres it on the 10x10 (rows 3-6 and columns 3-6 have the middle at 4.5).
     * The real game's shape was not checked in game.
     */
    private val DEFAULT_COLUMNS = arrayOf(4..5, 3..6, 3..6, 4..5)

    fun default(): BooleanArray = BooleanArray(CELLS).also { for ((i, cols) in DEFAULT_COLUMNS.withIndex()) for (c in cols) it[(3 + i) * GRID + c] = true }

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

    /** The blocked squares from their saved text: none unless it has exactly 100 characters. */
    fun parseBlocked(text: String): BooleanArray = BooleanArray(CELLS) { text.length == CELLS && text[it] == '1' }

    /** The squares a layout may use: unlocked and not blocked. */
    fun usable(unlocked: BooleanArray, blocked: BooleanArray): BooleanArray = BooleanArray(CELLS) { unlocked[it] && !blocked[it] }

    fun encode(mask: BooleanArray): String = String(CharArray(CELLS) { if (mask[it]) '1' else '0' })

    /** The current profile's mask (a fresh array each call). */
    fun current(): BooleanArray = parse(Storage.profile.greenhouse.plots)

    /** The current profile's blocked squares (a fresh array each call). */
    fun currentBlocked(): BooleanArray = parseBlocked(Storage.profile.greenhouse.blocked)

    fun save(mask: BooleanArray) {
        Storage.profile.greenhouse.plots = encode(mask)
        Storage.markDirty()
    }

    fun saveBlocked(blocked: BooleanArray) {
        Storage.profile.greenhouse.blocked = if (blocked.none { it }) "" else encode(blocked)
        Storage.markDirty()
    }
}
