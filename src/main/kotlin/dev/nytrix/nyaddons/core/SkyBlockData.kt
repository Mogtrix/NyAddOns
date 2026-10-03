package dev.nytrix.nyaddons.core

import dev.nytrix.nyaddons.config.AreaMode
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot

/** Whether the player is in SkyBlock and on which island, read from the scoreboard and tab list. */
object SkyBlockData {

    const val MOONGLADE_MARSH = "Moonglade Marsh"
    const val TORRHUS_CANYON = "Torrhus Canyon"
    private val foragingAreas = setOf(MOONGLADE_MARSH, TORRHUS_CANYON, "Galatea")

    // Development aid: the system property `nyaddons.devArea` pretends to be on that island anywhere.
    private const val DEV_AREA_PROPERTY = "nyaddons.devArea"

    var onSkyBlock = false
        private set

    /** The island name from the tab list's `Area:` line, or null if it is not shown. */
    var area: String? = null
        private set

    /** The SkyBlock profile name from the tab list's `Profile:` line. Keeps its last value while the line is hidden. */
    var profile = "default"
        private set

    private val profileLine = Regex("^Profile: (\\w+)")

    val onForagingIsland get() = area in foragingAreas

    fun allows(mode: AreaMode) = mode == AreaMode.ALL_ISLANDS || onForagingIsland

    fun update() {
        val devArea = System.getProperty(DEV_AREA_PROPERTY)
        if (devArea != null) {
            onSkyBlock = true
            area = devArea
            return
        }
        val mc = Minecraft.getInstance()
        val level = mc.level
        val connection = mc.connection
        if (level == null || connection == null) {
            onSkyBlock = false
            area = null
            return
        }
        val title = level.scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)?.displayName?.string
        onSkyBlock = title != null && ChatUtils.stripColor(title).uppercase().let { "SKYBLOCK" in it || "SKIBLOCK" in it }
        if (!onSkyBlock) {
            area = null
            return
        }
        // One pass over the tab list, stopping as soon as both lines are found.
        var foundArea: String? = null
        var foundProfile: String? = null
        for (info in connection.onlinePlayers) {
            if (foundArea != null && foundProfile != null) break
            val line = info.tabListDisplayName?.string?.let { ChatUtils.stripColor(it).trim() } ?: continue
            if (foundArea == null) {
                foundArea = when {
                    line.startsWith("Area: ") -> line.removePrefix("Area: ").trim()
                    line.startsWith("Dungeon: ") -> line.removePrefix("Dungeon: ").trim()
                    else -> null
                }
                if (foundArea != null) continue
            }
            if (foundProfile == null) foundProfile = profileLine.find(line)?.groupValues?.get(1)
        }
        area = foundArea
        foundProfile?.let { profile = it }
    }
}
