package dev.nytrix.nyaddons.features

import dev.nytrix.nyaddons.features.foraging.HoneycombTreeTimer
import dev.nytrix.nyaddons.features.foraging.HoneyhiveTimer

/**
 * One self-contained feature. In [init], subscribe to [dev.nytrix.nyaddons.core.NyEvents]
 * and register any overlays with [dev.nytrix.nyaddons.gui.OverlayManager].
 */
interface Feature {
    fun init()
}

object Features {

    /** Adding a feature: write the object, give it config options, and add it to this list. */
    val all: List<Feature> = listOf(
        HoneycombTreeTimer,
        HoneyhiveTimer,
    )
}
