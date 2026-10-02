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

    /** Used where no shard's own icon is available. */
    val generic: ItemStack by lazy { ItemStack(Items.PRISMARINE_SHARD) }

    /** The shard's own head when its skin is known, a plain shard otherwise. Built once per shard. */
    fun of(shard: Shard): ItemStack {
        shard.icon?.let { return it }
        // Not remembered while the icon list is still downloading, so the real icon appears once it is there.
        val texture = ShardRepo.textureOf(shard) ?: return generic
        val icon = try {
            val properties = PropertyMap(ImmutableMultimap.of("textures", Property("textures", texture)))
            val profile = GameProfile(UUID.nameUUIDFromBytes(shard.id.toByteArray()), "", properties)
            ItemStack(Items.PLAYER_HEAD).apply { set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile)) }
        } catch (e: Exception) {
            NyAddOns.logger.warn("Could not build the icon for ${shard.name}", e)
            generic
        }
        shard.icon = icon
        return icon
    }
}
