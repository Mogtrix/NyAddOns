package dev.nytrix.nyaddons.features.magicfind

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.Downloads
import dev.nytrix.nyaddons.core.NyEvents
import java.io.File
import java.io.Reader

/**
 * Mob drops from the MIT-licensed SkyblockAPI Repo `mobs.json` and the Bestiary categories from NotEnoughUpdates-REPO `bestiary.json`
 * (both licences are kept in the jar as LICENSE_SkyblockAPI-Repo and LICENSE_NEU-REPO).
 *
 * Loaded lazily by [request] on a background thread, stream-parsed straight into small lists (only drops below 5% and the special
 * ones are kept), and dropped again once nothing has read it for [idleMillis]. The next [request] loads it back from the cached files.
 */
object MagicFindDataImpl : MfData {

    private const val MOBS_URL = "https://raw.githubusercontent.com/SkyblockAPI/Repo/master/data/1_21_5/mobs.json"
    private const val BESTIARY_URL = "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/bestiary.json"
    private const val DAY = 24 * 60 * 60 * 1000L
    const val SLAYER_CATEGORY = "Slayer Bosses"
    private const val OTHER_CATEGORY = "Other"

    /** The Bestiary categories shown in the menu, in menu order (NEU ids). Fishing is flattened from its subcategories. */
    private val curated = linkedMapOf(
        "combat_1" to "Spider's Den",
        "combat_3" to "The End",
        "crimson_isle" to "Crimson Isle",
        "crystal_hollows" to "Crystal Hollows",
        "mining_3" to "Dwarven Mines",
        "mythological_creatures" to "Mythological Creatures",
        "fishing" to "Fishing",
        "kuudra" to "Kuudra",
        "foraging_2" to "Moonglade Marsh",
        "foraging_3" to "Torrhus Canyon",
    )

    /** Normalised base names of the slayer bosses; their entries in the data are `<NAME>_<tier>_BOSS`. */
    private val slayerBases = setOf(
        "revenanthorror", "tarantulabroodfather", "svenpackmaster", "voidgloomseraph", "infernodemonlord", "riftstalkerbloodfiend",
    )
    private val slayerKey = Regex("^[A-Z_]+_(\\d)_BOSS$")
    private val skippedTypes = setOf("NPC", "Rift NPC", "Mayor", "Retired Mayor")
    private val specialCondition = Regex("per\\s*hit|per\\s*summoning\\s*eye", RegexOption.IGNORE_CASE)

    /** How long the data stays in memory after the last read. Tests shorten it. */
    @Volatile
    var idleMillis = 5 * 60 * 1000L

    private class Snapshot(val categories: List<MfCategory>, val byId: Map<String, MfMob>, val slayerMax: Map<String, MfMob>, val names: List<String>)

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var lastAccess = 0L

    @Volatile
    private var loading = false

    @Volatile
    private var lastRefreshCheck = 0L

    private var ticking = false

    private val mobsFile get() = File(NyAddOns.directory, "magicfind-mobs.json")
    private val bestiaryFile get() = File(NyAddOns.directory, "magicfind-bestiary.json")

    /** Wall-clock source, replaceable in tests. */
    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    override val ready get() = snapshot != null

    private fun touch(): Snapshot? {
        lastAccess = clock()
        return snapshot
    }

    override val categories: List<MfCategory> get() = touch()?.categories ?: emptyList()
    override fun mobNames(): List<String> = touch()?.names ?: emptyList()

    override fun mob(name: String): MfMob? {
        val data = touch() ?: return null
        val tokens = cleaned(name).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        data.byId[tokens.joinToString("").filter { it.isLetterOrDigit() }.lowercase()]?.let { return it }
        // A missing or extra tier / level number: drop the last word when it is a number or roman numeral, or look for a slayer's top tier.
        val last = tokens.last()
        val base = if (tokens.size > 1 && (last.all { it.isDigit() } || romanRegex.matches(last.uppercase()))) tokens.dropLast(1) else tokens
        val baseId = base.joinToString("").filter { it.isLetterOrDigit() }.lowercase()
        return data.byId[baseId] ?: data.slayerMax[baseId]
    }

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
        Thread({
            try {
                val mobs = mobsFile
                val bestiary = bestiaryFile
                if (!loaded && mobs.exists() && bestiary.exists()) readFiles()
                lastRefreshCheck = clock()
                val changedMobs = Downloads.refresh(MOBS_URL, mobs)
                val changedBestiary = Downloads.refresh(BESTIARY_URL, bestiary)
                if (changedMobs || changedBestiary || (snapshot == null && mobs.exists() && bestiary.exists())) readFiles()
            } catch (e: Exception) {
                NyAddOns.logger.warn("Could not load the Magic Find data", e)
            } finally {
                loading = false
            }
        }, "NyAddOns magic find data").apply { isDaemon = true }.start()
    }

    private fun readFiles() {
        try {
            val started = System.nanoTime()
            val loaded = bestiaryFile.bufferedReader().use { b -> mobsFile.bufferedReader().use { m -> parse(b, m) } }
            snapshot = loaded
            lastAccess = clock()
            NyAddOns.logger.info("[NyBench] magic find data parsed in ${(System.nanoTime() - started) / 1_000_000} ms: ${loaded.describe()}")
        } catch (e: Exception) {
            NyAddOns.logger.warn("Could not read the Magic Find data", e)
        }
    }

    private fun Snapshot.describe() = "${categories.size} categories, ${byId.values.toSet().size} mobs, ${byId.values.toSet().sumOf { it.drops.size }} drops"

    /** Frees the data when it has not been read for a while. Once a second. */
    private fun tick() {
        if (snapshot != null && clock() - lastAccess > idleMillis) snapshot = null
    }

    /** For tests: parses the two files' contents without touching the network or the disk and makes them the loaded data. */
    fun loadFrom(bestiary: Reader, mobs: Reader) {
        val started = System.nanoTime()
        snapshot = parse(bestiary, mobs)
        lastAccess = clock()
        NyAddOns.logger.info("[NyBench] magic find data parsed in ${(System.nanoTime() - started) / 1_000_000} ms: ${snapshot!!.describe()}")
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

    // ---- name handling ----

    private val romanRegex = Regex("^(I|II|III|IV|V|VI|VII|VIII|IX|X)$")
    private val healthRegex = Regex("[\\d.,]+[kKmMbB]?\\s*/\\s*[\\d.,]+[kKmMbB]?\\S*|[\\d.,]+[kKmMbB]?\\s*[❤♥]\\S*")
    private val levelTag = Regex("\\[[^]]*]|\\([^)]*\\)|\\bLv\\.?\\s?\\d+\\b", RegexOption.IGNORE_CASE)

    /** A name tag or Bestiary name with colour codes, level tags, brackets and health text removed, words kept. */
    private fun cleaned(raw: String): String {
        var s = strip(raw)
        s = levelTag.replace(s, " ")
        s = healthRegex.replace(s, " ")
        return s.replace(Regex("[^A-Za-z0-9' ]"), " ").replace("'", "").replace(Regex("\\s+"), " ").trim()
    }

    /** Lower-case letters and digits only, after removing colour codes, level tags and health text. The id of a mob. */
    fun normalize(raw: String): String = cleaned(raw).filter { it.isLetterOrDigit() }.lowercase()

    private fun strip(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '§' && i + 1 < s.length) i += 2 else out.append(s[i++])
        }
        return out.toString()
    }

    private fun title(id: String): String =
        id.lowercase().split('_').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun roman(level: Int): String = listOf("", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X").getOrElse(level) { level.toString() }

    // ---- parsing ----

    private class Group(val name: String) {
        val drops = ArrayList<MfDrop>(4)
        var eligible = false
        var tier = 0
        fun add(drop: MfDrop, underFive: Boolean) {
            if (underFive) eligible = true
            if (drops.none { it.item == drop.item && it.chance == drop.chance }) drops.add(drop)
        }
    }

    private class Family(val name: String, val key: String)

    private class RawCategory(val id: String, val name: String, val families: List<Family>)

    private fun parse(bestiary: Reader, mobs: Reader): Snapshot {
        val raw = parseBestiary(bestiary)
        val groups = parseMobs(mobs)
        for ((key, extra) in MfOverrides.extraDrops) {
            val group = groups.getOrPut(key) { Group(key.replaceFirstChar(Char::uppercase)) }
            for (drop in extra) group.add(drop, true)
        }
        val byId = HashMap<String, MfMob>(groups.size * 2)
        val categories = ArrayList<MfCategory>()
        val used = HashMap<String, MfMob>() // group key -> mob, so a mob shared by two families is one object
        val allMobs = LinkedHashMap<String, MfMob>()

        for (category in raw) {
            val list = ArrayList<MfMob>()
            for (family in category.families) {
                val keys = MfOverrides.aliases[family.key] ?: listOf(family.key)
                val drops = ArrayList<MfDrop>()
                var eligible = false
                for (key in keys) {
                    val group = groups[key] ?: continue
                    if (group.eligible) eligible = true
                    for (d in group.drops) if (drops.none { it.item == d.item && it.chance == d.chance }) drops.add(d)
                }
                if (!eligible) continue
                val mob = used.getOrPut(family.key) {
                    MfMob(family.key, family.name, category.name, compact(drops), 0, family.key == "kingminos" || family.key == "minosinquisitor")
                }
                if (list.none { it === mob }) list.add(mob)
                allMobs.putIfAbsent(mob.id, mob)
                for (key in keys) used.putIfAbsent(key, mob)
            }
            if (list.isNotEmpty()) categories.add(MfCategory(category.id, category.name, list))
        }

        // Slayer bosses: every tier, the highest one on by default.
        val maxTier = HashMap<String, Int>()
        for ((key, g) in groups) if (g.tier > 0 && g.eligible) maxTier.merge(baseOf(key), g.tier, ::maxOf)
        val slayerMax = HashMap<String, MfMob>()
        val slayers = ArrayList<MfMob>()
        for ((key, g) in groups.entries.sortedWith(compareBy({ baseOf(it.key) }, { it.value.tier }))) {
            if (g.tier == 0 || !g.eligible) continue
            val top = g.tier == maxTier[baseOf(key)]
            val mob = MfMob(key, g.name, SLAYER_CATEGORY, compact(g.drops), g.tier, top)
            slayers.add(mob)
            allMobs[key] = mob
            if (top) slayerMax[baseOf(key)] = mob
        }
        if (slayers.isNotEmpty()) categories.add(MfCategory("slayer_bosses", SLAYER_CATEGORY, slayers))

        // Any other mob with an MF-affected drop, found by name for /ny trackmob.
        for ((key, g) in groups) {
            if (!g.eligible || g.tier > 0 || key in allMobs || used.containsKey(key)) continue
            allMobs[key] = MfMob(key, g.name, OTHER_CATEGORY, compact(g.drops), 0, false)
        }
        for ((key, mob) in used) byId.putIfAbsent(key, mob)
        for ((id, mob) in allMobs) byId.putIfAbsent(id, mob)
        val names = allMobs.values.map { it.name }.distinct().sorted()
        return Snapshot(categories, byId, slayerMax, names)
    }

    private fun baseOf(key: String): String = key.trimEnd('i', 'v', 'x').let { if (it.length >= 4 && it.length < key.length) it else key.trimEnd { c -> c.isDigit() } }

    private fun compact(drops: List<MfDrop>): List<MfDrop> = drops.sortedWith(compareByDescending<MfDrop> { !it.special }.thenByDescending { it.chance }).toTypedArray().asList()

    private fun parseBestiary(source: Reader): List<RawCategory> {
        val result = ArrayList<RawCategory>()
        val reader = JsonReader(source)
        reader.beginObject()
        val found = HashMap<String, RawCategory>()
        while (reader.hasNext()) {
            val id = reader.nextName()
            val name = curated[id]
            if (name == null || reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            val families = ArrayList<Family>()
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName()
                if (reader.peek() != JsonToken.BEGIN_OBJECT && reader.peek() != JsonToken.BEGIN_ARRAY) {
                    reader.skipValue()
                } else if (field == "mobs") {
                    readFamilies(reader, families)
                } else if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                    // A subcategory (Fishing): flatten it into this category.
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "mobs") readFamilies(reader, families) else reader.skipValue()
                    }
                    reader.endObject()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            found[id] = RawCategory(id, name, families)
        }
        reader.endObject()
        for (id in curated.keys) found[id]?.let { result.add(it) }
        return result
    }

    private fun readFamilies(reader: JsonReader, into: MutableList<Family>) {
        reader.beginArray()
        while (reader.hasNext()) {
            var name: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "name") name = reader.nextString() else reader.skipValue()
            }
            reader.endObject()
            if (name != null) {
                val clean = strip(name).trim()
                val key = normalize(clean)
                if (key.isNotEmpty() && into.none { it.key == key }) into.add(Family(clean, key))
            }
        }
        reader.endArray()
    }

    private fun parseMobs(source: Reader): MutableMap<String, Group> {
        val groups = HashMap<String, Group>(512)
        val reader = JsonReader(source)
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            reader.beginObject()
            var name = ""
            var type = ""
            val tables = ArrayList<Pair<String, List<RawDrop>>>(2)
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "name" -> name = reader.nextString()
                    "type" -> type = reader.nextString()
                    "lootTables" -> {
                        reader.beginArray()
                        while (reader.hasNext()) tables.add(readTable(reader))
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (tables.isEmpty() || type in skippedTypes) continue
            val tier = slayerKey.matchEntire(key)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val entryName = strip(name).trim()
            val entryKey = normalize(entryName)
            if (tier > 0) {
                if (baseOf(entryKey) !in slayerBases) continue
                val group = groups.getOrPut(entryKey) { Group(entryName).also { it.tier = tier } }
                for ((_, drops) in tables) for (d in drops) if (d.keep) group.add(d.drop, d.underFive)
                continue
            }
            for ((tableName, drops) in tables) {
                val keys = linkedSetOf(entryKey, normalize(tableName))
                for (k in keys) {
                    if (k.isEmpty()) continue
                    val group = groups.getOrPut(k) { Group(if (k == entryKey) entryName else strip(tableName).substringBefore('(').trim()) }
                    for (d in drops) if (d.keep) group.add(d.drop, d.underFive)
                }
            }
        }
        reader.endObject()
        return groups
    }

    private class RawDrop(val drop: MfDrop, val underFive: Boolean, val keep: Boolean)

    private fun readTable(reader: JsonReader): Pair<String, List<RawDrop>> {
        var name = ""
        val drops = ArrayList<RawDrop>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = reader.nextString()
                "drops" -> {
                    reader.beginArray()
                    while (reader.hasNext()) readDrop(reader)?.let { drops.add(it) }
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return name to drops
    }

    private fun readDrop(reader: JsonReader): RawDrop? {
        var id: String? = null
        var type: String? = null
        var pet: String? = null
        var tier: String? = null
        var level = 0
        var chance = Double.NaN
        var condition: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextString()
                "type" -> type = reader.nextString()
                "pet" -> pet = reader.nextString()
                "tier" -> tier = if (reader.peek() == JsonToken.STRING) reader.nextString() else reader.nextInt().toString()
                "level" -> level = reader.nextInt()
                "condition" -> condition = reader.nextString()
                "chance" -> chance = when (reader.peek()) {
                    JsonToken.NUMBER -> reader.nextDouble()
                    JsonToken.STRING -> reader.nextString().trim().removeSuffix("%").toDoubleOrNull()?.let { it / 100 } ?: Double.NaN
                    else -> { reader.skipValue(); Double.NaN }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (type == "currency") return null
        val item = when (type) {
            "pet" -> "${tier?.let { title(it) + " " } ?: ""}${title(pet ?: id ?: "?")} Pet"
            "enchantment" -> "${title((id ?: "?").removePrefix("ULTIMATE_"))} ${roman(level)}".trim()
            "rune" -> "${title(id ?: "?")} Rune"
            "attribute" -> "${title(id ?: "?")} Shard"
            "potion" -> "${title((id ?: "?").removePrefix("POTION_"))} Potion"
            else -> (id ?: return null).let { if (it.startsWith("DYE_")) "${title(it.removePrefix("DYE_"))} Dye" else title(it) }
        }.intern()
        val unusable = chance.isNaN() || chance <= 0
        val special = unusable || (condition != null && specialCondition.containsMatchIn(condition))
        val underFive = unusable || chance < MfMath.MF_THRESHOLD
        if (!underFive && !special) return RawDrop(MfDrop(item, chance, type == "pet", false, null), false, false)
        val note = if (special) condition?.trim()?.lowercase()?.intern() else condition?.trim()?.intern()
        return RawDrop(MfDrop(item, if (special) 0.0 else chance, type == "pet", special, note), underFive, true)
    }
}
