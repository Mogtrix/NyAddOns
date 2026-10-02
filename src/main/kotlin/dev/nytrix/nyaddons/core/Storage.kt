package dev.nytrix.nyaddons.core

import com.google.gson.GsonBuilder
import dev.nytrix.nyaddons.NyAddOns
import java.io.File

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
}

/** Everything the mod remembers between sessions. Add a field here when a feature needs to persist something. */
class StorageData {
    var honeycombTrees = mutableListOf<TrackedTree>()
    var honeyhiveReadyAt = 0L
    var honeyhiveAlerted = true
}

object Storage {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private lateinit var file: File
    private var dirty = false

    var data = StorageData()
        private set

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

    fun markDirty() {
        dirty = true
    }

    fun saveIfDirty() {
        if (!dirty) return
        dirty = false
        try {
            file.parentFile.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(gson.toJson(data))
            temp.copyTo(file, overwrite = true)
            temp.delete()
        } catch (e: Exception) {
            NyAddOns.logger.error("Could not save ${file.name}", e)
        }
    }
}
