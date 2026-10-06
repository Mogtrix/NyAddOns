package dev.nytrix.nyaddons.features.greenhouse

import com.google.common.collect.ImmutableList
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.Downloads
import dev.nytrix.nyaddons.core.NyEvents
import dev.nytrix.nyaddons.core.Safe
import java.io.File
import java.io.Reader

/**
 * The Greenhouse crops and mutations, from the MIT-licensed SkyShards-Greenhouse `data.json`
 * (github.com/Campionnn/SkyShards-Greenhouse, licence kept in the jar as LICENSE_SkyShardsGreenhouse).
 *
 * Loaded lazily by [request] on a background thread, stream-parsed straight into small arrays, and dropped again once
 * nothing has read it for [idleMillis] (five minutes). The next [request] loads it back from the cached file.
 */
object GreenhouseDataImpl : GhData {

    private const val URL = "https://raw.githubusercontent.com/Campionnn/SkyShards-Greenhouse/master/public/greenhouse/data.json"
    private const val DAY = 24 * 60 * 60 * 1000L

    /** How long the data stays in memory after the last read. Tests shorten it. */
    @Volatile
    var idleMillis = 5 * 60 * 1000L

    private class Snapshot(val crops: List<GhCrop>, val mutations: List<GhMutation>) {
        val byId: Map<String, GhMutation> = HashMap<String, GhMutation>(mutations.size * 2).also { map -> mutations.forEach { map[it.id] = it } }
        val names: Map<String, String> = HashMap<String, String>((crops.size + mutations.size) * 2).also { map ->
            crops.forEach { map[it.id] = it.name }
            mutations.forEach { map[it.id] = it.name }
        }
    }

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var lastAccess = 0L

    @Volatile
    private var loading = false

    @Volatile
    private var lastRefreshCheck = 0L

    private var ticking = false

    private val file get() = File(NyAddOns.directory, "greenhouse-data.json")

    /** Wall-clock source, replaceable in tests. */
    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    override val ready get() = snapshot != null

    private fun touch(): Snapshot? {
        lastAccess = clock()
        return snapshot
    }

    override val crops: List<GhCrop> get() = touch()?.crops ?: emptyList()
    override val mutations: List<GhMutation> get() = touch()?.mutations ?: emptyList()
    override fun mutation(id: String): GhMutation? = touch()?.byId?.get(id)
    override fun nameOf(id: String): String = touch()?.names?.get(id) ?: id

    override fun request() {
        lastAccess = clock()
        val loaded = snapshot != null
        if (loaded && clock() - lastRefreshCheck < DAY) return
        synchronized(this) {
            if (loading) return
            loading = true
            if (!ticking) {
                ticking = true
                NyEvents.second.add(::tick)
            }
        }
        Safe.background("greenhouse data") {
            try {
                val target = file
                if (!loaded && target.exists()) readFile(target)
                lastRefreshCheck = clock()
                if (Downloads.refresh(URL, target) || (snapshot == null && target.exists())) readFile(target)
            } catch (e: Exception) {
                NyAddOns.logger.warn("Could not load the Greenhouse data", e)
            } finally {
                loading = false
            }
        }
    }

    private fun readFile(target: File) {
        try {
            target.bufferedReader().use { snapshot = parse(it) }
            lastAccess = clock()
        } catch (e: Exception) {
            NyAddOns.logger.warn("Could not read the Greenhouse data", e)
        }
    }

    /** Frees the data when it has not been read for a while. Once a second. */
    private fun tick() {
        if (snapshot != null && clock() - lastAccess > idleMillis) {
            snapshot = null
            SkyShards.clearCache()
        }
    }

    /** For tests: parses [json] without touching the network or the disk and makes it the loaded data. */
    fun loadFrom(source: Reader) {
        snapshot = parse(source)
        lastAccess = clock()
    }

    /** For tests: runs the idle check as if [idleMillis] had passed since the last read. */
    fun expireNow() {
        lastAccess = clock() - idleMillis - 1
        tick()
    }

    /** For tests: drops the data and resets state. */
    fun clear() {
        snapshot = null
        lastAccess = 0
    }

    private fun parse(source: Reader): Snapshot {
        val crops = ImmutableList.builder<GhCrop>()
        val mutations = ImmutableList.builder<GhMutation>()
        val reader = JsonReader(source)
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "crops" -> {
                    reader.beginObject()
                    while (reader.hasNext()) crops.add(readCrop(reader, reader.nextName().intern()))
                    reader.endObject()
                }
                "mutations" -> {
                    reader.beginObject()
                    while (reader.hasNext()) mutations.add(readMutation(reader, reader.nextName().intern()))
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return Snapshot(crops.build(), mutations.build())
    }

    private fun readCrop(reader: JsonReader, id: String): GhCrop {
        var name = id
        var size = 1
        var soil = ""
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = reader.nextString()
                "size" -> size = reader.nextInt()
                "ground" -> soil = reader.nextString().intern()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return GhCrop(id, name, size, soil)
    }

    private fun readMutation(reader: JsonReader, id: String): GhMutation {
        var name = id
        var rarity = ""
        var size = 1
        var soil = ""
        var stages = 0
        var watering = false
        var requirements: List<GhRequirement> = emptyList()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = reader.nextString()
                "rarity" -> rarity = reader.nextString().intern()
                "size" -> size = reader.nextInt()
                "ground" -> soil = reader.nextString().intern()
                "growth_stages" -> stages = reader.nextInt()
                "requires_watering" -> watering = reader.nextBoolean()
                "requirements" -> {
                    val list = ImmutableList.builder<GhRequirement>()
                    reader.beginArray()
                    while (reader.hasNext()) {
                        var crop = ""
                        var count = 0
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "crop" -> crop = reader.nextString().intern()
                                "count" -> count = reader.nextInt()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        list.add(GhRequirement(crop, count))
                    }
                    reader.endArray()
                    requirements = list.build()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val cost = AnalysisCosts[id]
        return GhMutation(
            id, name, rarity, size, soil, requirements, stages, watering,
            cost?.coins ?: 0L, cost?.copper ?: 0,
        )
    }
}
