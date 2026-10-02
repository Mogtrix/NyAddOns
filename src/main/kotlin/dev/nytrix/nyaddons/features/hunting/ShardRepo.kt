package dev.nytrix.nyaddons.features.hunting

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.Downloads
import net.minecraft.world.item.ItemStack
import java.io.File

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
) {
    val coloredName = rarity.color + name

    /** Built by [ShardIcons] the first time the shard is drawn. */
    var icon: ItemStack? = null
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

    // The picker's and diagram's icons: shard id to skin texture. Only read when an icon is first drawn.
    @Volatile
    private var texturesFile: File? = null
    private val textures: Map<String, String> by lazy { readTextures(texturesFile) }

    /** The shard's head skin, or null while the icon list is not on disk yet. */
    fun textureOf(shard: Shard): String? = if (texturesFile == null) null else textures[shard.id]

    fun load(directory: File) {
        val shardsFile = File(directory, "attribute_shards.json")
        read(shardsFile)
        Thread({
            if (Downloads.refresh(SHARDS_URL, shardsFile)) read(shardsFile)
            texturesFile = prepareTextures(directory)
        }, "NyAddOns shard list").apply { isDaemon = true }.start()
    }

    private fun read(shardsFile: File) {
        if (!shardsFile.exists()) return
        try {
            snapshot = parse(shardsFile.readText())
        } catch (e: Exception) {
            NyAddOns.logger.error("Could not read the shard list", e)
        }
    }

    private fun parse(json: String): Snapshot {
        val root = JsonParser.parseString(json).asJsonObject
        val unconsumable = root.getAsJsonArray("unconsumable_attributes").map { it.asString }.toSet()
        val levelling = root.getAsJsonObject("attribute_levelling").entrySet().associate { (rarity, steps) ->
            ShardRarity.valueOf(rarity) to steps.asJsonArray.map { it.asInt }
        }
        val shards = root.getAsJsonArray("attributes").mapNotNull { element ->
            val entry = element.asJsonObject
            val id = entry["bazaarName"].asString
            val rarity = ShardRarity.entries.find { it.name == entry["rarity"].asString } ?: return@mapNotNull null
            Shard(id, entry["displayName"].asString, rarity, entry["abilityName"].asString, entry["shardId"].asString, id !in unconsumable)
        }
        return Snapshot(shards, levelling)
    }

    /**
     * The icon source is a 1 MB file of which only two fields per shard are used, so it is cut
     * down to those once and the small copy is what gets read from then on.
     */
    private fun prepareTextures(directory: File): File? {
        val slim = File(directory, "shard_textures.json")
        if (slim.exists()) return slim
        val full = File(directory, "shard_icons.json")
        if (!full.exists()) Downloads.refresh(ICONS_URL, full)
        if (!full.exists()) return null
        return try {
            val result = JsonObject()
            full.bufferedReader().use { source ->
                val reader = JsonReader(source)
                reader.beginArray()
                while (reader.hasNext()) {
                    var id: String? = null
                    var texture: String? = null
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "shard_id" -> id = reader.nextString()
                            "texture" -> if (reader.peek() == JsonToken.STRING) texture = reader.nextString() else reader.skipValue()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    if (id != null && texture != null) result.addProperty(id, texture)
                }
            }
            slim.writeText(result.toString())
            full.delete()
            slim
        } catch (e: Exception) {
            NyAddOns.logger.warn("Could not read the shard icon list", e)
            null
        }
    }

    private fun readTextures(file: File?): Map<String, String> = try {
        JsonParser.parseString(file!!.readText()).asJsonObject.entrySet().associate { it.key to it.value.asString }
    } catch (e: Exception) {
        NyAddOns.logger.warn("Could not read the shard icons", e)
        emptyMap()
    }
}
