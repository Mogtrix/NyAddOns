package dev.nytrix.nyaddons.config

import com.google.gson.annotations.Expose

/** Where an overlay sits on screen. Edited through the position editor, not the config screen. */
class Position(
    @Expose @JvmField var x: Int = 10,
    @Expose @JvmField var y: Int = 10,
    @Expose @JvmField var scale: Float = 1f,
) {
    // Gson needs a no-arg constructor to keep defaults for missing fields.
    constructor() : this(10, 10, 1f)
}
