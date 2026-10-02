package dev.nytrix.nyaddons.features.hunting

import com.google.gson.JsonParser
import dev.nytrix.nyaddons.NyAddOns
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

enum class ShardRarity(val color: String) {
    COMMON("§f"),
    UNCOMMON("§a"),
    RARE("§9"),
    EPIC("§5"),
    LEGENDARY("§6"),
}

/**
 * @param id the Bazaar id, `SHARD_GROVE`
 * @param name the name shown in the Hunting Box and in chat, `Grove`
 * @param attribute the attribute it levels, `Nature Elemental`
 * @param code the short id shown in lore, `C1`
 * @param consumable false for the few shards that have no attribute to level
 */
class Shard(
    val id: String,
    val name: String,
    val rarity: ShardRarity,
    val attribute: String,
    val code: String,
    val consumable: Boolean,
    val texture: String?,
) {
    val coloredName get() = rarity.color + name
}

/**
 * The list of every shard. It comes from the NotEnoughUpdates data repository on GitHub, the list
 * SkyHanni and Skyblocker use, and is kept on disk so the mod still works offline.
 */
object ShardRepo {

    private const val SHARDS_URL =
        "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/attribute_shards.json"

    // Only used for the icons in the shard picker.
    private const val ICONS_URL = "https://raw.githubusercontent.com/SkyblockAPI/Repo/main/data/1_21_5/attributes.json"

    private const val MAX_LEVEL = 10

    private class Snapshot(val shards: List<Shard>, val levelling: Map<ShardRarity, List<Int>>) {
        val byId = shards.associateBy { it.id }
        val byName = shards.associateBy { it.name.lowercase() }
        val byAttribute = shards.associateBy { it.attribute.lowercase() }
        val byCode = shards.associateBy { it.code }
    }

    @Volatile
    private var snapshot = Snapshot(emptyList(), emptyMap())

    val loaded get() = snapshot.shards.isNotEmpty()
    val all: List<Shard> get() = snapshot.shards

    fun byId(id: String) = snapshot.byId[id]
    fun byName(name: String) = snapshot.byName[name.trim().lowercase()]
    fun byAttribute(attribute: String) = snapshot.byAttribute[attribute.trim().lowercase()]
    fun byCode(code: String) = snapshot.byCode[code]

    /** Shards needed for each level, first to tenth. */
    private fun steps(shard: Shard) = snapshot.levelling[shard.rarity].orEmpty()

    /** Shards needed in total to max the attribute. */
    fun totalToMax(shard: Shard) = steps(shard).sum()

    /** Shards needed in total to reach [level]. */
    fun totalForLevel(shard: Shard, level: Int) = steps(shard).take(level.coerceIn(0, MAX_LEVEL)).sum()

    /** Shards needed to go from [level] to the next one. */
    fun stepAfter(shard: Shard, level: Int) = steps(shard).getOrElse(level) { 0 }

    /** The level reached after putting [syphoned] shards in. */
    fun levelOf(shard: Shard, syphoned: Int): Int {
        var left = syphoned
        return steps(shard).takeWhile { step -> (left >= step).also { left -= step } }.size
    }

    fun load(directory: File) {
        val shardsFile = File(directory, "attribute_shards.json")
        val iconsFile = File(directory, "shard_icons.json")
        readFiles(shardsFile, iconsFile)
        Thread({
            val refreshed = download(SHARDS_URL, shardsFile)
            // The icon file is large and rarely changes, so it is only fetched once.
            val gotIcons = !iconsFile.exists() && download(ICONS_URL, iconsFile)
            if (refreshed || gotIcons) readFiles(shardsFile, iconsFile)
        }, "NyAddOns shard list").apply { isDaemon = true }.start()
    }

    private fun readFiles(shardsFile: File, iconsFile: File) {
        if (!shardsFile.exists()) return
        try {
            val textures = if (iconsFile.exists()) parseIcons(iconsFile.readText()) else emptyMap()
            snapshot = parse(shardsFile.readText(), textures)
        } catch (e: Exception) {
            NyAddOns.logger.error("Could not read the shard list", e)
        }
    }

    private fun parse(json: String, textures: Map<String, String>): Snapshot {
        val root = JsonParser.parseString(json).asJsonObject
        val unconsumable = root.getAsJsonArray("unconsumable_attributes").map { it.asString }.toSet()
        val levelling = root.getAsJsonObject("attribute_levelling").entrySet().associate { (rarity, steps) ->
            ShardRarity.valueOf(rarity) to steps.asJsonArray.map { it.asInt }
        }
        val shards = root.getAsJsonArray("attributes").mapNotNull { element ->
            val entry = element.asJsonObject
            val id = entry["bazaarName"].asString
            val rarity = ShardRarity.entries.find { it.name == entry["rarity"].asString } ?: return@mapNotNull null
            Shard(
                id, entry["displayName"].asString, rarity, entry["abilityName"].asString,
                entry["shardId"].asString, id !in unconsumable, textures[id],
            )
        }
        return Snapshot(shards, levelling)
    }

    private fun parseIcons(json: String): Map<String, String> =
        JsonParser.parseString(json).asJsonArray.mapNotNull { element ->
            val entry = element.asJsonObject
            val id = entry["shard_id"]?.asString ?: return@mapNotNull null
            val texture = entry["texture"]?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            id to texture
        }.toMap()

    private fun download(url: String, target: File): Boolean = try {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) {
            // Make sure it is JSON before replacing a working copy.
            JsonParser.parseString(response.body())
            target.parentFile.mkdirs()
            target.writeText(response.body())
            true
        } else {
            NyAddOns.logger.warn("Could not download $url: HTTP ${response.statusCode()}")
            false
        }
    } catch (e: Exception) {
        NyAddOns.logger.warn("Could not download $url: $e")
        false
    }
}
