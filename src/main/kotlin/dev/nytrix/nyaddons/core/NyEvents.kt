package dev.nytrix.nyaddons.core

/**
 * The hooks features subscribe to. All of them only fire while the player is in SkyBlock.
 */
object NyEvents {

    /** Every client tick. */
    val tick = mutableListOf<() -> Unit>()

    /** Once per second. */
    val second = mutableListOf<() -> Unit>()

    /** Every chat message, with colour codes removed. */
    val chat = mutableListOf<(String) -> Unit>()

    /** Every frame, for drawing beams and text in the world. */
    val worldRender = mutableListOf<(WorldRenderer) -> Unit>()
}
