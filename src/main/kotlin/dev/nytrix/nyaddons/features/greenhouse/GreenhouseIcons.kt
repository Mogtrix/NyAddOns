package dev.nytrix.nyaddons.features.greenhouse

import com.google.common.collect.ImmutableMultimap
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.Downloads
import dev.nytrix.nyaddons.core.Safe
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Item icons for the Greenhouse crops and mutations: vanilla items for the plain crops, player heads for the rest.
 * The head skins come from the MIT-licensed NotEnoughUpdates-REPO item files (only the ~41 needed ones are fetched,
 * at most once a day, and kept as one small file). Offline or unknown ids get a coloured tile with the first letter.
 */
object GhIcons {

    private const val URL = "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/items/"

    /** Crops and mutations whose NEU item is a player head; the NEU internal name is the id upper-cased. */
    private val HEADS = listOf(
        "fermento",
        "ashwreath", "choconut", "dustgrain", "gloomgourd", "lonelily", "scourroot", "shadevine", "veilshroom", "witherbloom",
        "chocoberry", "cindershade", "coalroot", "creambloom", "duskbloom", "thornshade",
        "blastberry", "cheesebite", "chloronite", "do_not_eat_shroom", "fleshtrap", "magic_jellybean", "noctilume", "snoozling",
        "soggybud", "turtlellini",
        "chorus_fruit", "plantboy_advance", "puffercloud", "shellfruit", "startlevine", "stoplight_petal", "thunderling", "zombud",
        "all_in_aloe", "devourer", "glasscorn", "godseed", "jerryflower", "phantomleaf", "timestalk",
    )

    private val VANILLA: Map<String, Item> = mapOf(
        "wheat" to Items.WHEAT, "potato" to Items.POTATO, "carrot" to Items.CARROT, "pumpkin" to Items.PUMPKIN,
        "melon" to Items.MELON_SLICE, "cocoa_beans" to Items.COCOA_BEANS, "sugar_cane" to Items.SUGAR_CANE,
        "cactus" to Items.CACTUS, "nether_wart" to Items.NETHER_WART, "red_mushroom" to Items.RED_MUSHROOM,
        "brown_mushroom" to Items.BROWN_MUSHROOM, "moonflower" to Items.BLUE_ORCHID, "sunflower" to Items.SUNFLOWER,
        "helianthus" to Items.SUNFLOWER, "wild_rose" to Items.ROSE_BUSH, "fire" to Items.FIRE_CHARGE,
        "dead_plant" to Items.DEAD_BUSH,
    )

    private const val CROP_TILE = 0xFF2A5A34.toInt()
    private const val MUTATION_TILE = 0xFF4A2A6A.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    private val started = AtomicBoolean(false)

    /** Skin textures by id; replaced as a whole, read on the render thread. */
    @Volatile
    private var textures: Map<String, String> = emptyMap()

    // Render thread only.
    private val stacks = HashMap<String, ItemStack?>()
    private val letters = HashMap<String, String>()
    private var stacksFor: Map<String, String>? = null

    /** Starts the background load of the head textures if that has not happened. Safe to call every second. */
    fun request() {
        if (!started.compareAndSet(false, true)) return
        Safe.background("greenhouse icons") { load(NyAddOns.directory) }
    }

    /** Replaces the head textures (id to base64 skin value). For tests; skips the network. */
    fun useTextures(map: Map<String, String>) {
        started.set(true)
        textures = map
    }

    /** True when [id] has a vanilla item or a loaded head. */
    fun has(id: String) = VANILLA.containsKey(id) || textures.containsKey(id)

    /** Draws the icon for a crop or mutation id at [x], [y], [size] pixels wide. Does not allocate once cached. */
    fun draw(graphics: GuiGraphicsExtractor, id: String, x: Int, y: Int, size: Int) {
        val stack = stackOf(id)
        if (stack == null) {
            tile(graphics, id, x, y, size)
            return
        }
        if (size == 16) {
            graphics.item(stack, x, y)
            return
        }
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        val scale = size / 16f
        pose.scale(scale, scale)
        graphics.item(stack, 0, 0)
        pose.popMatrix()
    }

    private fun tile(graphics: GuiGraphicsExtractor, id: String, x: Int, y: Int, size: Int) {
        val color = if (Greenhouse.data.mutation(id) != null) MUTATION_TILE else CROP_TILE
        graphics.fill(x, y, x + size, y + size, color)
        val letter = letters.getOrPut(id) { id.firstOrNull()?.uppercaseChar()?.toString() ?: "?" }
        val font = Minecraft.getInstance().font
        graphics.text(font, letter, x + (size - font.width(letter)) / 2, y + (size - 8) / 2, WHITE, false)
    }

    private fun stackOf(id: String): ItemStack? {
        val current = textures
        if (stacksFor !== current) {
            // New textures arrived: forget the heads we had decided were missing.
            stacksFor = current
            stacks.values.removeAll { it == null }
        }
        stacks[id]?.let { return it }
        if (stacks.containsKey(id)) return null
        val built = build(id, current)
        if (built != null || current.isNotEmpty() || VANILLA.containsKey(id)) stacks[id] = built
        return built
    }

    private fun build(id: String, current: Map<String, String>): ItemStack? {
        VANILLA[id]?.let { return ItemStack(it) }
        val texture = current[id] ?: return null
        return try {
            val properties = PropertyMap(ImmutableMultimap.of("textures", Property("textures", texture)))
            val profile = GameProfile(UUID.nameUUIDFromBytes(("gh_$id").toByteArray()), "", properties)
            ItemStack(Items.PLAYER_HEAD).apply { set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile)) }
        } catch (e: Exception) {
            NyAddOns.logger.warn("Could not build the Greenhouse icon for $id", e)
            null
        }
    }

    private fun load(directory: File) {
        val slim = File(directory, "greenhouse_icons.json")
        val known = HashMap<String, String>()
        read(slim)?.let { known.putAll(it); textures = known.toMap() }
        if (slim.exists() && System.currentTimeMillis() - slim.lastModified() < 24 * 60 * 60 * 1000L) return
        val temp = File(directory, "greenhouse_icon_download.json")
        var changed = false
        for (id in HEADS) {
            temp.delete()
            if (!Downloads.refresh("$URL${id.uppercase()}.json", temp)) continue
            val value = try {
                Regex("Value:\"([^\"]+)\"").find(JsonParser.parseString(temp.readText()).asJsonObject["nbttag"].asString)?.groupValues?.get(1)
            } catch (e: Exception) {
                null
            } ?: continue
            if (known.put(id, value) != value) changed = true
        }
        temp.delete()
        try {
            directory.mkdirs()
            if (changed || !slim.exists()) {
                val json = JsonObject()
                known.forEach { (id, value) -> json.addProperty(id, value) }
                slim.writeText(json.toString())
            } else {
                slim.setLastModified(System.currentTimeMillis())
            }
        } catch (e: Exception) {
            NyAddOns.logger.warn("Could not save the Greenhouse icons", e)
        }
        if (changed) textures = known.toMap()
    }

    private fun read(file: File): Map<String, String>? = try {
        if (!file.exists()) null
        else JsonParser.parseString(file.readText()).asJsonObject.entrySet().associate { it.key to it.value.asString }
    } catch (e: Exception) {
        NyAddOns.logger.warn("Could not read the Greenhouse icons", e)
        null
    }
}
