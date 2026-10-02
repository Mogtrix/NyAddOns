package dev.nytrix.nyaddons.features.hunting

import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import dev.nytrix.nyaddons.NyAddOns
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import java.util.UUID

object ShardIcons {

    private val icons = HashMap<String, ItemStack>()

    /** Used where no shard's own icon is available. */
    val generic: ItemStack by lazy { ItemStack(Items.PRISMARINE_SHARD) }

    /** The shard's own head when its skin is known, a plain shard otherwise. */
    fun of(shard: Shard): ItemStack = icons.getOrPut(shard.id + (shard.texture != null)) {
        val texture = shard.texture ?: return@getOrPut generic
        try {
            val properties = PropertyMap(ImmutableMultimap.of("textures", Property("textures", texture)))
            val profile = GameProfile(UUID.nameUUIDFromBytes(shard.id.toByteArray()), "", properties)
            ItemStack(Items.PLAYER_HEAD).apply { set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile)) }
        } catch (e: Exception) {
            NyAddOns.logger.warn("Could not build the icon for ${shard.name}", e)
            generic
        }
    }
}
