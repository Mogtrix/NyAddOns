package dev.nytrix.nyaddons.features

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.nytrix.nyaddons.features.foraging.HoneycombTreeTimer
import dev.nytrix.nyaddons.features.foraging.HoneyhiveTimer
import dev.nytrix.nyaddons.features.hunting.FusionTracker
import dev.nytrix.nyaddons.features.hunting.ShardTracker
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource

typealias Command = LiteralArgumentBuilder<FabricClientCommandSource>

/**
 * One self-contained feature. In [init], subscribe to [dev.nytrix.nyaddons.core.NyEvents]
 * and register any overlays with [dev.nytrix.nyaddons.gui.OverlayManager].
 */
interface Feature {
    fun init()

    /** Commands of its own, like `/hunt`. */
    fun commands(): List<Command> = emptyList()

    /** Commands under `/ny`, like `/ny hunt`. */
    fun subcommands(): List<Command> = emptyList()
}

object Features {

    /** Adding a feature: write the object, give it config options, and add it to this list. */
    val all: List<Feature> = listOf(
        HoneycombTreeTimer,
        HoneyhiveTimer,
        ShardTracker,
        FusionTracker,
    )
}
