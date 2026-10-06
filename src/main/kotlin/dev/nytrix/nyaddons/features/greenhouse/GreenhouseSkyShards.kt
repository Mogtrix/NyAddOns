package dev.nytrix.nyaddons.features.greenhouse

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.Downloads
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.LocalDateTime

/** The SkyShards server could not give an answer ([reason] is a short phrase for the player). Not "no layout fits": that is a null answer. */
class SkyShardsException(val reason: String, val busy: Boolean = false, val timedOut: Boolean = false) : Exception(reason) {
    val text get() = if (busy) SkyShards.BUSY_TEXT else SkyShards.errorText(reason)
}

/** The caller no longer wants the answer (a newer change replaced it). */
class SkyShardsAborted : RuntimeException()

/** One thing to ask for: [count] blocks of mutation [id] exactly, or as many as fit when [count] is null. */
class SkyGoal(val id: String, val count: Int?)

/** A finished layout: [cells] holds a crop or mutation id on every cell of a block, [counts] the mutation blocks by id. */
class SkySolution(private val cells: Array<Array<String?>>, val counts: Map<String, Int>, val usedCells: Int) {
    fun layout(target: String) = GhLayout(cells.size, Array(cells.size) { cells[it].copyOf() }, target)

    internal fun toJson(): JsonObject {
        val o = JsonObject()
        o.add("cells", JsonArray().also { rows -> for (row in cells) rows.add(JsonArray().also { a -> for (c in row) if (c == null) a.add(JsonNull.INSTANCE) else a.add(c) }) })
        o.add("counts", JsonObject().also { c -> for ((k, v) in counts) c.addProperty(k, v) })
        return o
    }

    internal companion object {
        fun fromJson(o: JsonObject): SkySolution {
            val rows = o.getAsJsonArray("cells")
            val cells = Array(rows.size()) { r -> rows[r].asJsonArray.let { a -> Array(a.size()) { c -> a[c].takeIf { !it.isJsonNull }?.asString } } }
            val counts = LinkedHashMap<String, Int>()
            for ((k, v) in o.getAsJsonObject("counts").entrySet()) counts[k] = v.asInt
            return SkySolution(cells, counts, cells.sumOf { row -> row.count { it != null } })
        }
    }
}

/** One finished solve: where the answer came from ("server", "memory" or "disk"), what was asked, how long it took and what came back. */
class SkyTiming(val source: String, val what: String, val millis: Long, val queuePeak: Int, val outcome: String) {
    override fun toString() = "$source $millis ms, $what, queue peak $queuePeak, $outcome"
}

/**
 * The layout solver: the public SkyShards server (api.skyshards.com). A request is a job: POST /greenhouse/jobs with the unlocked
 * cells and the goals, then GET /greenhouse/jobs/{id} until it is completed or failed. Cells and positions are [row, column] from
 * the top left, ids are the keys of the Greenhouse data file.
 *
 * [solve] blocks, so call it from a background thread. It gives up after [TIMEOUT_MILLIS] (queue and solve together, plus up to about 1 s
 * because a call never gets less than 1 s) and cancels the job. Answers are cached, a "nothing fits" answer too; failures are not. The cache is also saved to config/nyaddons/
 * skyshards-cache.json (the last [CACHE_SIZE] answers, never expiring; key = goals + unlocked squares, no player data), so a repeat
 * plan works offline and returns at once. Every solve is timed into skyshards-timing.log next to it ([timings], [benchmark]).
 */
object SkyShards {

    const val BASE_URL = "https://api.skyshards.com"
    const val TIMEOUT_MILLIS = 30_000L
    private const val POLL_MILLIS = 500L
    private const val CALL_TIMEOUT_SECONDS = 10L
    private const val SOLVER_SECONDS = 20
    private const val CACHE_SIZE = 256
    private const val CACHE_FILE = "skyshards-cache.json"
    private const val GRID = 10

    private class Entry(val solution: SkySolution?, val fromDisk: Boolean = false, val at: Long = System.currentTimeMillis())

    /** A cached layout and the time (epoch millis) it was worked out. */
    class Saved(val solution: SkySolution, val at: Long)

    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > CACHE_SIZE
    }

    private var diskLoaded = false
    private val recent = ArrayList<SkyTiming>()

    /** The most recent solves, newest last. */
    val timings: List<SkyTiming> get() = synchronized(recent) { recent.toList() }

    /** For tests: answers every solve itself, without the network or the cache. Null (the default) uses the server. */
    @Volatile
    var testSolver: ((List<SkyGoal>, BooleanArray?) -> SkySolution?)? = null

    /** The red line when the server answered HTTP 429 or 5xx. */
    const val BUSY_TEXT = "SkyShards is busy right now. Your amounts are kept, try again in a moment."

    /** The red line for the planner when [reason] stopped a solve. */
    fun errorText(reason: String) = "SkyShards is unreachable: $reason. Your amounts are kept, try again."

    /**
     * Solves for [goals] on the cells set in [unlocked] (row-major, 100 entries; null allows every cell). Returns null when no layout
     * fits. Throws [SkyShardsException] when the server cannot be used and [SkyShardsAborted] when [abort] turns true. [progress] gets
     * "SkyShards: queued (position N)..." and "SkyShards: solving 40%..." while waiting.
     */
    fun solve(goals: List<SkyGoal>, unlocked: BooleanArray?, abort: () -> Boolean = { false }, progress: (String) -> Unit = {}): SkySolution? =
        solve(goals, unlocked, abort, progress, useCache = true)

    private fun solve(goals: List<SkyGoal>, unlocked: BooleanArray?, abort: () -> Boolean, progress: (String) -> Unit, useCache: Boolean): SkySolution? {
        require(goals.isNotEmpty()) { "no goals" }
        val mask = unlocked?.takeIf { it.size == GRID * GRID }
        testSolver?.let { return it(goals, mask) }
        val key = keyOf(goals, mask)
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000L
        if (useCache) {
            val hit = synchronized(cache) {
                loadDisk()
                cache[key]
            }
            if (hit != null) {
                record(SkyTiming(if (hit.fromDisk) "disk" else "memory", describe(goals), elapsed(), 0, if (hit.solution == null) "no layout" else "layout"))
                return hit.solution
            }
        }
        var queuePeak = 0
        val solution = try {
            fetch(goals, mask, abort) { text ->
                QUEUE_POSITION.find(text)?.let { queuePeak = maxOf(queuePeak, it.groupValues[1].toInt()) }
                progress(text)
            }
        } catch (e: SkyShardsException) {
            record(SkyTiming("server", describe(goals), elapsed(), queuePeak, "failed: ${e.reason}"))
            throw e
        } catch (e: SkyShardsAborted) {
            record(SkyTiming("server", describe(goals), elapsed(), queuePeak, "aborted"))
            throw e
        }
        record(SkyTiming("server", describe(goals), elapsed(), queuePeak, if (solution == null) "no layout" else "layout"))
        synchronized(cache) {
            loadDisk()
            cache[key] = Entry(solution)
            saveDisk()
        }
        return solution
    }

    /** The most recently used cached layout for exactly these unlocked squares (whatever the goals were), or null. For a Max that timed out. */
    fun lastCached(unlocked: BooleanArray?): Saved? {
        val mask = unlocked?.takeIf { it.size == GRID * GRID }
        val suffix = keyOf(emptyList(), mask)
        return synchronized(cache) {
            loadDisk()
            cache.entries.lastOrNull { it.key.endsWith(suffix) && it.value.solution != null }?.value?.let { Saved(it.solution!!, it.at) }
        }
    }

    /** Forgets every cached answer, on disk too. */
    fun clearCache() = synchronized(cache) {
        cache.clear()
        diskLoaded = true
        saveDisk()
    }

    private val QUEUE_POSITION = Regex("position (\\d+)")

    private fun describe(goals: List<SkyGoal>): String = "${goals.size} ${if (goals.size == 1) "goal" else "goals"}" + if (goals.any { it.count == null }) " (max)" else ""

    private fun directory(): File? = try {
        NyAddOns.directory
    } catch (_: Throwable) {
        null
    }

    private fun record(timing: SkyTiming) {
        synchronized(recent) {
            recent += timing
            if (recent.size > 100) recent.removeAt(0)
        }
        try {
            val file = File(directory() ?: return, "skyshards-timing.log")
            file.parentFile.mkdirs()
            if (file.length() > 200_000L) file.delete()
            file.appendText("${LocalDateTime.now().withNano(0)} $timing\n")
        } catch (_: Throwable) {
        }
    }

    /** Reads the saved answers into [cache] (once). Call with [cache] locked. A missing or broken file is ignored. */
    private fun loadDisk() {
        if (diskLoaded) return
        diskLoaded = true
        try {
            val file = File(directory() ?: return, CACHE_FILE)
            if (!file.isFile) return
            val root = JsonParser.parseString(file.readText()) as? JsonObject ?: return
            for (item in root.getAsJsonArray("entries") ?: return) {
                try {
                    val o = item.asJsonObject
                    val solution = if (o.has("solution")) SkySolution.fromJson(o.getAsJsonObject("solution")) else null
                    cache[o.get("key").asString] = Entry(solution, fromDisk = true, at = o.get("at")?.asLong ?: file.lastModified())
                } catch (_: Exception) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** Writes [cache] (oldest first, so the order survives a restart). Call with [cache] locked. */
    private fun saveDisk() {
        try {
            val file = File(directory() ?: return, CACHE_FILE)
            val entries = JsonArray()
            for ((key, entry) in cache) {
                val o = JsonObject()
                o.addProperty("key", key)
                o.addProperty("at", entry.at)
                if (entry.solution != null) o.add("solution", entry.solution.toJson())
                entries.add(o)
            }
            val root = JsonObject()
            root.addProperty("version", 1)
            root.add("entries", entries)
            file.parentFile.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(root.toString())
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Throwable) {
        }
    }

    /**
     * Times SkyShards for the sets in [sets] (for docs/greenhouse-benchmark.md): per set a cold request, the same request again (does
     * the server remember it?) and the same through the cache; then the first set three times at once (queueing). [say] gets one line
     * per run. Blocks for minutes at worst, so call it from a background thread.
     */
    fun benchmark(sets: List<Pair<String, List<SkyGoal>>>, unlocked: BooleanArray?, say: (String) -> Unit) {
        fun run(label: String, goals: List<SkyGoal>, useCache: Boolean) {
            try {
                solve(goals, unlocked, { false }, {}, useCache)
            } catch (_: SkyShardsException) {
            } catch (_: SkyShardsAborted) {
            }
            say("$label: ${timings.last()}")
        }
        for ((name, goals) in sets) {
            run("$name, cold", goals, false)
            run("$name, again", goals, false)
            run("$name, cached", goals, true)
        }
        val first = sets.firstOrNull() ?: return
        val threads = (1..3).map {
            Thread {
                try {
                    solve(first.second, unlocked, { false }, {}, false)
                } catch (_: Exception) {
                }
            }.also {
                it.isDaemon = true
                it.start()
            }
        }
        threads.forEach { it.join() }
        say("${first.first}, 3 at once: " + timings.takeLast(3).joinToString("; ") { "${it.millis} ms (queue peak ${it.queuePeak}, ${it.outcome})" })
    }

    private fun keyOf(goals: List<SkyGoal>, mask: BooleanArray?): String {
        val sb = StringBuilder()
        for (g in goals.sortedBy { it.id }) sb.append(g.id).append('=').append(g.count ?: "max").append(';')
        sb.append('|')
        if (mask == null) sb.append('*') else for (b in mask) sb.append(if (b) '1' else '0')
        return sb.toString()
    }

    private fun body(goals: List<SkyGoal>, mask: BooleanArray?): String {
        val cells = JsonArray()
        for (i in 0 until GRID * GRID) {
            if (mask != null && !mask[i]) continue
            cells.add(JsonArray().also { it.add(i / GRID); it.add(i % GRID) })
        }
        val targets = JsonArray()
        for (g in goals) {
            val t = JsonObject()
            t.addProperty("mutation", g.id)
            if (g.count == null) t.addProperty("maximize", true) else t.addProperty("count", g.count)
            targets.add(t)
        }
        val params = JsonObject()
        params.add("cells", cells)
        params.add("targets", targets)
        params.addProperty("time_limit", SOLVER_SECONDS)
        val root = JsonObject()
        root.addProperty("type", "greenhouse")
        root.add("params", params)
        return root.toString()
    }

    private fun fetch(goals: List<SkyGoal>, mask: BooleanArray?, abort: () -> Boolean, progress: (String) -> Unit): SkySolution? {
        val deadline = System.nanoTime() + TIMEOUT_MILLIS * 1_000_000L
        if (abort()) throw SkyShardsAborted()
        // A call never waits past the deadline, so the whole solve stays within TIMEOUT_MILLIS, except that
        // the 1 s floor below lets the last call run up to about 1 s past it.
        fun left() = ((deadline - System.nanoTime()) / 1_000_000L).coerceIn(1_000L, CALL_TIMEOUT_SECONDS * 1000)
        val submit = call(request("/greenhouse/jobs", left()).POST(HttpRequest.BodyPublishers.ofString(body(goals, mask))).header("Content-Type", "application/json"))
        val id = submit.get("job_id")?.takeIf { it.isJsonPrimitive }?.asString ?: throw SkyShardsException("the answer has no job id")
        var failedPolls = 0
        try {
            while (true) {
                if (abort()) throw SkyShardsAborted()
                if (System.nanoTime() > deadline) throw SkyShardsException("no answer within ${TIMEOUT_MILLIS / 1000} seconds", timedOut = true)
                val job = try {
                    call(request("/greenhouse/jobs/$id", left()).GET()).also { failedPolls = 0 }
                } catch (e: SkyShardsException) {
                    // One lost poll must not throw away a job that is still solving: only the third in a row counts.
                    if (++failedPolls >= 3) throw e
                    sleep()
                    continue
                }
                when (job.get("status")?.takeIf { it.isJsonPrimitive }?.asString) {
                    "completed" -> return try {
                        parse(job.get("result") as? JsonObject ?: throw SkyShardsException("the answer has no layout"))
                    } catch (_: RuntimeException) {
                        throw SkyShardsException("the layout is not readable")
                    }
                    "failed" -> {
                        val error = job.get("error")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                        if (error.contains("No solution found", ignoreCase = true)) return null
                        throw SkyShardsException("the solver failed (${error.lineSequence().firstOrNull().orEmpty().take(120).ifBlank { "no reason given" }})")
                    }
                    "cancelled" -> throw SkyShardsException("the job was cancelled")
                    "queued" -> {
                        val position = job.get("queue_position")?.takeIf { it.isJsonPrimitive }?.asInt
                        progress(if (position == null) "SkyShards: queued..." else "SkyShards: queued (position $position)...")
                    }
                    else -> {
                        val percent = (job.get("progress") as? JsonObject)?.get("percentage")?.takeIf { it.isJsonPrimitive }?.asDouble
                        progress(if (percent == null) "SkyShards: solving..." else "SkyShards: solving ${percent.toInt().coerceIn(0, 100)}%...")
                    }
                }
                sleep()
            }
        } catch (e: Exception) {
            // Stopped waiting (timeout, a newer change, an error): free the server's queue slot.
            cancel(id)
            throw e
        }
    }

    private fun sleep() {
        try {
            Thread.sleep(POLL_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SkyShardsAborted()
        }
    }

    /** Fire and forget on a daemon thread, so the caller reports its error at once instead of waiting on the DELETE. */
    private fun cancel(id: String) {
        Thread {
            try {
                call(request("/greenhouse/jobs/$id", 3_000L).DELETE())
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; name = "NyAddOns-SkyShards-cancel" }.start()
    }

    private fun request(path: String, timeoutMillis: Long = CALL_TIMEOUT_SECONDS * 1000) = HttpRequest.newBuilder(URI.create(BASE_URL + path))
        .timeout(Duration.ofMillis(timeoutMillis))
        .header("Accept", "application/json")
        .header("User-Agent", "NyAddOns")

    private fun call(builder: HttpRequest.Builder): JsonObject {
        val response = try {
            Downloads.client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SkyShardsAborted()
        } catch (e: IOException) {
            throw SkyShardsException(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
        }
        val status = response.statusCode()
        if (status !in 200..299) throw SkyShardsException("HTTP $status", busy = status == 429 || status in 500..599)
        return try {
            JsonParser.parseString(response.body()) as? JsonObject ?: throw SkyShardsException("the answer is not readable")
        } catch (e: com.google.gson.JsonParseException) {
            throw SkyShardsException("the answer is not readable")
        }
    }

    /** Blocks the result lists (crops in `placements`, mutations in `mutations`, each with its top-left `position`) fill onto the grid. */
    private fun parse(result: JsonObject): SkySolution {
        val cells = Array(GRID) { arrayOfNulls<String>(GRID) }
        val counts = LinkedHashMap<String, Int>()
        fun place(array: JsonArray?, nameKey: String, countIt: Boolean) {
            for (item in array ?: return) {
                val o = item as? JsonObject ?: continue
                val name = o.get(nameKey)?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                val position = o.get("position") as? JsonArray ?: continue
                if (position.size() < 2) continue
                val r0 = position[0].asInt
                val c0 = position[1].asInt
                val size = o.get("size")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1
                for (r in r0 until r0 + size) for (c in c0 until c0 + size) if (r in 0 until GRID && c in 0 until GRID) cells[r][c] = name
                if (countIt) counts.merge(name, 1, Int::plus)
            }
        }
        place(result.get("placements") as? JsonArray, "crop", false)
        place(result.get("mutations") as? JsonArray, "mutation", true)
        return SkySolution(cells, counts, cells.sumOf { row -> row.count { it != null } })
    }
}
