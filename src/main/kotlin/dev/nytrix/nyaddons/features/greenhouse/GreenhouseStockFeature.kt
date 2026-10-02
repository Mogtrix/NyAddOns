package dev.nytrix.nyaddons.features.greenhouse

import dev.nytrix.nyaddons.core.NyEvents
import dev.nytrix.nyaddons.features.Feature
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents

/** Reads sack menus, `[Sacks]` chat lines and the inventory while on the Garden. */
object GreenhouseStockFeature : Feature {
    override fun init() {
        NyEvents.tick += GreenhouseStockImpl::onTick
        NyEvents.tick += GreenhouseProgressReader::onTick
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!overlay) GreenhouseStockImpl.onChat(message)
        }
    }
}
