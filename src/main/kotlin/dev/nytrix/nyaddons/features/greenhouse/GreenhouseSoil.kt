package dev.nytrix.nyaddons.features.greenhouse

import dev.nytrix.nyaddons.NyAddOns

/** How a soil is drawn in the planner and the pinned plot, so both views always agree. */
object GhSoil {

    const val EDGE = 0xFF101010.toInt()
    const val LIGHT = 0x30FFFFFF
    const val SHADE = 0x40000000
    const val MUTATION_PIP = 0xFFFFD040.toInt()
    const val UNKNOWN = 0xFF3A3A3A.toInt()

    private val colors = mapOf(
        "farmland" to 0xFF7A5230.toInt(),
        "soul_sand" to 0xFF4A3A33.toInt(),
        "sand" to 0xFFB8A56A.toInt(),
        "mycelium" to 0xFF6A5A78.toInt(),
        "end_stone" to 0xFFC9C68F.toInt(),
        "netherrack" to 0xFF8A3030.toInt(),
    )

    private val warned = HashSet<String>()

    /** True when [soil] has a colour of its own. */
    fun known(soil: String): Boolean = soil in colors

    /** The block colour of a soil; grey for an unknown one, which is logged once so a new soil does not silently look broken. */
    fun color(soil: String): Int {
        colors[soil]?.let { return it }
        if (soil.isNotEmpty() && warned.add(soil)) NyAddOns.logger.warn("Greenhouse soil '{}' has no colour, drawing it grey", soil)
        return UNKNOWN
    }

    /** One letter for colour-blind players: F, S (soul sand), A (sand), M, E, N; "?" when unknown. */
    fun letter(soil: String): String = when (soil) {
        "farmland" -> "F"
        "soul_sand" -> "S"
        "sand" -> "A"
        "mycelium" -> "M"
        "end_stone" -> "E"
        "netherrack" -> "N"
        else -> "?"
    }

    /** Dark or white text, whichever reads better on [soil]. */
    fun letterColor(soil: String): Int {
        val c = color(soil)
        val lum = (0.299 * (c shr 16 and 255) + 0.587 * (c shr 8 and 255) + 0.114 * (c and 255)) / 255
        return if (lum > 0.55) 0xFF101010.toInt() else -1
    }
}
