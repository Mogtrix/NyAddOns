package dev.nytrix.nyaddons.core

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext

/**
 * The listeners of one event. Each is guarded by the [Safe.Site] named after its owner, so a listener that keeps failing is
 * switched off, together with everything else of the same feature, without touching the others.
 */
class EventList<T : Any> {

    class Entry<T : Any>(val listener: T, val site: Safe.Site)

    @Volatile
    @PublishedApi
    internal var entries: List<Entry<T>> = emptyList()

    @Synchronized
    fun add(listener: T) {
        entries = entries + Entry(listener, Safe.site(Safe.nameOf(listener)))
    }

    operator fun plusAssign(listener: T) = add(listener)

    /** Calls [action] with every listener, each one guarded. Allocates nothing. */
    inline fun forEach(action: (T) -> Unit) {
        val list = entries
        for (i in list.indices) {
            val entry = list[i]
            entry.site { action(entry.listener) }
        }
    }
}

/**
 * The hooks features subscribe to. All of them only fire while the player is in SkyBlock.
 */
object NyEvents {

    /** Every client tick. */
    val tick = EventList<() -> Unit>()

    /** Once per second. */
    val second = EventList<() -> Unit>()

    /** Every chat message, with colour codes removed. */
    val chat = EventList<(String) -> Unit>()

    /** Every frame, for drawing beams and text in the world. Wrap the context in a [WorldRenderer] to draw. */
    val worldRender = EventList<(LevelRenderContext) -> Unit>()
}
