package dev.nytrix.nyaddons.features

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.nytrix.nyaddons.features.foraging.HoneycombTreeTimer
import dev.nytrix.nyaddons.features.foraging.HoneyhiveTimer
import dev.nytrix.nyaddons.features.greenhouse.GreenhouseFeature
import dev.nytrix.nyaddons.features.greenhouse.GreenhouseStockFeature
import dev.nytrix.nyaddons.features.hunting.FusionTracker
import dev.nytrix.nyaddons.features.hunting.FusionTree
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
        GreenhouseStockFeature,
        GreenhouseFeature,
    )

    /**
     * Kept in the code but switched off: never initialised, so they do not tick, render, or register
     * overlays, commands or keybinds. To bring one back, move it into [all] and restore its
     * @Category in [dev.nytrix.nyaddons.config.NyConfig].
     */
    val hidden: List<Feature> = listOf(
        HoneycombTreeTimer,
        HoneyhiveTimer,
        ShardTracker,
        FusionTracker,
        FusionTree,
    )
}
