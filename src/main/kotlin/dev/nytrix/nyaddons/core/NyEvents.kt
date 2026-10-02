package dev.nytrix.nyaddons.core

import java.util.concurrent.CopyOnWriteArrayList
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext

/**
 * The hooks features subscribe to. All of them only fire while the player is in SkyBlock.
 */
object NyEvents {

    /** Every client tick. */
    val tick = CopyOnWriteArrayList<() -> Unit>()

    /** Once per second. */
    val second = CopyOnWriteArrayList<() -> Unit>()

    /** Every chat message, with colour codes removed. */
    val chat = CopyOnWriteArrayList<(String) -> Unit>()

    /** Every frame, for drawing beams and text in the world. Wrap the context in a [WorldRenderer] to draw. */
    val worldRender = CopyOnWriteArrayList<(LevelRenderContext) -> Unit>()
}
