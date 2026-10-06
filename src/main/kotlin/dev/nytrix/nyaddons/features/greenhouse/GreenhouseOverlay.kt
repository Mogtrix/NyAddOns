package dev.nytrix.nyaddons.features.greenhouse

import com.mojang.blaze3d.platform.InputConstants
import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.ChatUtils
import dev.nytrix.nyaddons.core.NyEvents
import dev.nytrix.nyaddons.core.OverlayOrigin
import dev.nytrix.nyaddons.core.SkyBlockData
import dev.nytrix.nyaddons.core.Storage
import dev.nytrix.nyaddons.core.WorldRenderer
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import java.awt.Color
import kotlin.math.sqrt

/**
 * The shown Greenhouse plan drawn over the real Greenhouse as see-through blocks. Purely visual: it only reads blocks and draws.
 * The plan's top-left square is put in the world with Align (a key or the settings button); after that each planned block that is
 * still an empty square within the cull distance gets a ghost, tinted per crop, with its name close up. The list of ghosts is
 * rebuilt twice a second, a frame only walks it.
 */
object GreenhouseOverlay {

    private val config get() = NyAddOns.config.garden.greenhouse

    private val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(NyAddOns.MOD_ID, "greenhouse"))
    private val toggleMapping = KeyMapping("key.nyaddons.greenhouse_overlay", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.value, category)
    private val alignMapping = KeyMapping("key.nyaddons.greenhouse_overlay_align", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.value, category)
    private val toggleEdge = Edge()
    private val alignEdge = Edge()

    /** One block of the plan: top-left square, side length, colour, and its name for the label. */
    private class Ghost(val row: Int, val col: Int, val span: Int, val argb: Int, val label: Component)

    private var ghosts = emptyList<Ghost>()

    /** The ghosts that still have an empty square under them; rebuilt by [refresh]. */
    private var open = emptyList<Ghost>()
    private var dirty = true
    private var ticks = 0

    /** How many blocks the shown plan has, and how many of them are still on an empty square; for the tests. */
    val plannedBlocks get() = ghosts.size
    val emptyBlocks get() = open.size

    fun init() {
        KeyMappingHelper.registerKeyMapping(toggleMapping)
        KeyMappingHelper.registerKeyMapping(alignMapping)
        NyEvents.worldRender += ::render
    }

    /** The plan now shown in the window; null clears it. A failed solve never calls this, so the last good plan stays. */
    fun show(layout: GhLayout?) {
        ghosts = if (layout == null) emptyList() else build(layout)
        dirty = true
    }

    /** Puts the plan's top-left square on the block the player stands in. */
    fun align() {
        val player = Minecraft.getInstance().player
        if (player == null) {
            ChatUtils.chat("§cJoin a world first, then stand on the plan's top-left square.")
            return
        }
        val pos = player.blockPosition()
        Storage.profile.greenhouse.overlayOrigin = OverlayOrigin(pos.x, pos.y, pos.z)
        Storage.markDirty()
        dirty = true
        ChatUtils.chat("§aGreenhouse overlay aligned: the plan's top-left square is at §e${pos.x} ${pos.y} ${pos.z}§a.")
    }

    /** Polls the two keys: the settings ones and the ones in Minecraft's Controls. Called every client tick. */
    fun tick(mc: Minecraft) {
        val idle = mc.screen == null && mc.player != null
        val toggle = drain(toggleMapping) or toggleEdge.press(settingsKeyDown(mc, config.overlayKey, idle))
        val align = drain(alignMapping) or alignEdge.press(settingsKeyDown(mc, config.overlayAlignKey, idle))
        if (toggle && idle) {
            config.overlayEnabled = !config.overlayEnabled
            NyAddOns.saveConfig()
        }
        if (align && idle) align()
        if (++ticks % REFRESH_TICKS == 0 || dirty) refresh(mc)
    }

    /** True when [mapping] (the Controls entry) was pressed since the last tick. */
    private fun drain(mapping: KeyMapping): Boolean {
        var pressed = false
        while (mapping.consumeClick()) pressed = true
        return pressed
    }

    private fun settingsKeyDown(mc: Minecraft, key: Int, idle: Boolean) = key != 0 && idle && InputConstants.isKeyDown(mc.window, key)

    /** Turns "is down" into "went down this tick". */
    private class Edge {
        private var was = false

        fun press(down: Boolean): Boolean {
            val pressed = down && !was
            was = down
            return pressed
        }
    }

    private fun active() = config.overlayEnabled && SkyBlockData.area == "Garden" && config.enabled

    private fun build(layout: GhLayout): List<Ghost> {
        val data = Greenhouse.data
        val n = layout.size
        val seen = BooleanArray(n * n)
        val out = ArrayList<Ghost>()
        for (r in 0 until n) for (c in 0 until n) {
            val id = layout.cells[r][c] ?: continue
            if (seen[r * n + c]) continue
            var span = (data.mutation(id)?.size ?: data.crops.firstOrNull { it.id == id }?.size ?: 1).coerceIn(1, 3)
            if (span > 1) {
                var whole = r + span <= n && c + span <= n
                if (whole) for (dr in 0 until span) for (dc in 0 until span) if (layout.cells[r + dr][c + dc] != id || seen[(r + dr) * n + c + dc]) whole = false
                if (!whole) span = 1
            }
            for (dr in 0 until span) for (dc in 0 until span) seen[(r + dr) * n + c + dc] = true
            val name = if (data.ready) data.nameOf(id) else id
            out += Ghost(r, c, span, tint(id), Component.literal(name))
        }
        return out
    }

    /** A steady colour per crop id, see-through. */
    private fun tint(id: String): Int {
        val hue = (id.hashCode() and 0x7FFFFFFF) % 360 / 360f
        return (GHOST_ALPHA shl 24) or (Color.HSBtoRGB(hue, 0.55f, 1f) and 0xFFFFFF)
    }

    /** Rebuilds [open]: the planned blocks whose top-left square is empty right now. Unloaded chunks count as not empty. */
    private fun refresh(mc: Minecraft) {
        dirty = false
        val origin = Storage.profile.greenhouse.overlayOrigin
        val level = mc.level
        if (origin == null || level == null || ghosts.isEmpty() || !active()) {
            open = emptyList()
            return
        }
        val pos = BlockPos.MutableBlockPos()
        open = ghosts.filter { g ->
            pos.set(origin.x + g.col, origin.y, origin.z + g.row)
            level.hasChunkAt(pos) && level.getBlockState(pos).isAir
        }
    }

    private fun render(context: LevelRenderContext) {
        val list = open
        if (list.isEmpty() || !active()) return
        val origin = Storage.profile.greenhouse.overlayOrigin ?: return
        val renderer = WorldRenderer(context)
        val reach = config.overlayDistance.toDouble().coerceIn(8.0, 64.0)
        val reachSq = reach * reach
        // Labels seen edge-on from ground level all land on one screen line, so only the nearest few are
        // drawn, each stacked a little higher than the last, instead of a smear of overlapping names.
        // One label per crop (the nearest block of it), so a plan of mostly Wheat still names its other crops,
        // and none for blocks near or past the screen edge, where the name would be cut off.
        val look = Minecraft.getInstance().player?.lookAngle
        val nearest = HashMap<String, Pair<Double, Ghost>>()
        for (i in list.indices) {
            val g = list[i]
            val x = origin.x + g.col.toDouble()
            val z = origin.z + g.row.toDouble()
            val dx = x + g.span / 2.0 - renderer.cameraX
            val dy = origin.y + 0.5 - renderer.cameraY
            val dz = z + g.span / 2.0 - renderer.cameraZ
            val distSq = dx * dx + dy * dy + dz * dz
            if (distSq > reachSq) continue
            renderer.box(x + INSET, origin.y.toDouble(), z + INSET, g.span - 2 * INSET, HEIGHT, g.span - 2 * INSET, g.argb)
            if (distSq > LABEL_REACH * LABEL_REACH) continue
            if (look != null && distSq > 1.0 && (dx * look.x + dy * look.y + dz * look.z) / sqrt(distSq) < LABEL_VIEW_COS) continue
            val key = g.label.string
            val best = nearest[key]
            if (best == null || distSq < best.first) nearest[key] = distSq to g
        }
        val labelled = nearest.values.sortedBy { it.first }.take(MAX_LABELS)
        for ((rank, entry) in labelled.withIndex()) {
            val g = entry.second
            val x = origin.x + g.col + g.span / 2.0
            val z = origin.z + g.row + g.span / 2.0
            renderer.text(x, origin.y + HEIGHT + 0.4 + rank * LABEL_STACK, z, g.label)
        }
    }

    private const val REFRESH_TICKS = 10
    private const val GHOST_ALPHA = 0x70
    private const val INSET = 0.06
    private const val HEIGHT = 0.9
    private const val LABEL_REACH = 6.0
    private const val MAX_LABELS = 3
    private const val LABEL_VIEW_COS = 0.75
    private const val LABEL_STACK = 0.3
}
