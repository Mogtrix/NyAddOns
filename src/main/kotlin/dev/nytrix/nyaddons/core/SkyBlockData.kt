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
        area = if (!onSkyBlock) null else connection.onlinePlayers.firstNotNullOfOrNull { info ->
            val line = ChatUtils.stripColor(info.tabListDisplayName?.string ?: return@firstNotNullOfOrNull null).trim()
            when {
                line.startsWith("Area: ") -> line.removePrefix("Area: ").trim()
                line.startsWith("Dungeon: ") -> line.removePrefix("Dungeon: ").trim()
                else -> null
            }
        }
    }
}
