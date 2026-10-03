package dev.nytrix.nyaddons.core

import com.google.gson.GsonBuilder
import dev.nytrix.nyaddons.NyAddOns
import net.minecraft.network.chat.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

/** One tree with honeycomb on it. Times are epoch milliseconds so they survive restarts. */
class TrackedTree(
    var area: String = "",
    var type: String = "",
    var x: Double = 0.0,
    var y: Double = 0.0,
    var z: Double = 0.0,
    var readyAt: Long = 0,
    var alerted: Boolean = false,
) {
    @Transient
    var missedScans = 0

    // The floating text, rebuilt when the second changes.
    @Transient
    var label: Component? = null

    @Transient
    var labelSecond = -1L
}

/** What is known about one shard. A null number means the game has not shown it to the mod yet. */
class ShardProgress(
    var owned: Int? = null,
    var syphoned: Int? = null,
    var alertedMaxable: Boolean = false,
)

/** The Greenhouse helper's saved data for one profile. */
class GreenhouseProfile {
    /** Mutation ids the player has analysed (ticked in the window, later also read from menus). */
    var analysed = mutableSetOf<String>()

    /** Sack contents by lower-case item name, and when a sack menu was last read. */
    var sacks = mutableMapOf<String, Int>()
    var sacksUpdatedAt = 0L

    /** How many of each mutation the Planner view should make, by mutation id; a missing id means 0. */
    var planAmounts = mutableMapOf<String, Int>()

    /** The Planner's "One of each" switch: Max grows at most one of every kind. */
    var planOneOfEach = false

    /** The unlocked Greenhouse squares: 100 characters, row-major, `1` unlocked and `0` locked. Empty means the default block. */
    var plots = ""

    /** Unlocked squares the planner must keep empty: same 100 character form, `1` blocked. Empty means none. */
    var blocked = ""

    /** The plot pinned to the screen, a snapshot of what a side panel showed; null when nothing is pinned. */
    var pin: PinnedPlot? = null
}

/**
 * A layout kept for the HUD: [palette] lists the ids used, [cells] has 100 characters (row-major), `.` for an empty square and
 * otherwise the position of the id in [palette] as a character of [PinnedPlot.CHARS]. [title] and [summary] are the panel's text.
 */
class PinnedPlot(
    var title: String = "",
    var summary: String = "",
    var palette: MutableList<String> = mutableListOf(),
    var cells: String = "",
) {
    /** The id on the square at [index] (row * 10 + column), or null when empty. */
    fun idAt(index: Int): String? {
        val c = cells.getOrNull(index) ?: return null
        val i = CHARS.indexOf(c)
        return if (i < 0) null else palette.getOrNull(i)
    }

    fun sameAs(other: PinnedPlot) = cells == other.cells && summary == other.summary && palette == other.palette && title == other.title

    companion object {
        const val CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        /** Packs a 10x10 grid of ids; ids beyond the 62 the alphabet holds are left out. */
        fun of(cells: Array<Array<String?>>, title: String, summary: String): PinnedPlot {
            val palette = mutableListOf<String>()
            val out = StringBuilder(100)
            for (row in cells) for (id in row) {
                if (id == null) {
                    out.append('.')
                    continue
                }
                var i = palette.indexOf(id)
                if (i < 0 && palette.size < CHARS.length) {
                    palette += id
                    i = palette.size - 1
                }
                out.append(if (i < 0) '.' else CHARS[i])
            }
            return PinnedPlot(title, summary, palette, out.toString())
        }
    }
}

/** Data that belongs to one SkyBlock profile. */
class ProfileData {
    var shards = mutableMapOf<String, ShardProgress>()
    var trackedShards = mutableListOf<String>()
    var greenhouse = GreenhouseProfile()
}

/** Everything the mod remembers between sessions. Add a field here when a feature needs to persist something. */
class StorageData {
    var honeycombTrees = mutableListOf<TrackedTree>()
    var honeyhiveReadyAt = 0L
    var honeyhiveAlerted = true
    var profiles = mutableMapOf<String, ProfileData>()
}

object Storage {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private lateinit var file: File
    private var dirty = false
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "NyAddOns save").apply { isDaemon = true } }

    var data = StorageData()
        private set

    /** The data of the SkyBlock profile the player is on. */
    val profile: ProfileData get() = data.profiles.getOrPut(SkyBlockData.profile) { ProfileData() }

    fun load(file: File) {
        this.file = file
        if (!file.exists()) return
        try {
            data = gson.fromJson(file.readText(), StorageData::class.java) ?: StorageData()
        } catch (e: Exception) {
            NyAddOns.logger.error("Could not read ${file.name}, starting with empty data", e)
        }
    }

    fun reset() {
        data = StorageData()
        dirty = true
    }

    /** Clears the foraging timers and leaves everything else alone. */
    fun resetTimers() {
        data.honeycombTrees.clear()
        data.honeyhiveReadyAt = 0
        data.honeyhiveAlerted = true
        dirty = true
    }

    fun markDirty() {
        dirty = true
    }

    /**
     * Writes the data file if anything changed. The file is written on a background thread so a
     * slow disk never stalls a frame; pass [wait] when the game is closing.
     */
    fun saveIfDirty(wait: Boolean = false) {
        if (!dirty) return
        dirty = false
        val json = gson.toJson(data)
        val write = writer.submit {
            try {
                file.parentFile.mkdirs()
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(json)
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                NyAddOns.logger.error("Could not save ${file.name}", e)
            }
        }
        if (wait) write.get()
    }
}
