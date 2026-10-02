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

/** Data that belongs to one SkyBlock profile. */
class ProfileData {
    var shards = mutableMapOf<String, ShardProgress>()
    var trackedShards = mutableListOf<String>()
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
