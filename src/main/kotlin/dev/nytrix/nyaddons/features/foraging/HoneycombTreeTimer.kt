package dev.nytrix.nyaddons.features.foraging

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.config.BeamMode
import dev.nytrix.nyaddons.core.AlertUtils
import dev.nytrix.nyaddons.core.ChatUtils
import dev.nytrix.nyaddons.core.NyEvents
import dev.nytrix.nyaddons.core.SkyBlockData
import dev.nytrix.nyaddons.core.Storage
import dev.nytrix.nyaddons.core.TimeUtils
import dev.nytrix.nyaddons.core.TrackedTree
import dev.nytrix.nyaddons.core.WorldRenderer
import dev.nytrix.nyaddons.features.Feature
import dev.nytrix.nyaddons.gui.Overlay
import dev.nytrix.nyaddons.gui.OverlayManager
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.Level
import kotlin.math.abs

/**
 * Tracks trees the player used a Pot of Honeycomb on. The server shows a `Critter in: 59m 30s`
 * name tag at the tree, but only while the player is nearby, so the end time is stored and
 * counted down by the clock, then corrected whenever the name tag is seen again.
 */
object HoneycombTreeTimer : Feature {

    private val nameTag = Regex("Critter in:\\s*((?:\\d+\\s*[dhms]\\s*)+)")

    private const val SAME_TREE_RADIUS_SQ = 4.0 * 4.0
    private const val COLLECT_RADIUS_SQ = 10.0 * 10.0
    private const val STALE_RADIUS_SQ = 8.0 * 8.0
    private const val STALE_SCANS = 5
    private const val RESYNC_THRESHOLD = 3_000L

    // A finished tree's tag sits at 1s when you walk up to it, which would restart the countdown.
    private const val FINISHED_TAG = 1_000L

    // Above the server's own name tag, so the two do not overlap.
    private const val FLOATING_TEXT_HEIGHT = 3.0

    private const val WAITING_COLOR = 0xFFFF55
    private const val READY_COLOR = 0x55FF55

    private val config get() = NyAddOns.config.foraging.honeycombTrees
    private val trees get() = Storage.data.honeycombTrees

    override fun init() {
        NyEvents.second += ::onSecond
        NyEvents.worldRender += ::onWorldRender
        OverlayManager.register(
            Overlay(
                "Honeycomb Tree Timer", { config.position },
                listOf("§6§lHoneycomb Trees", " §eFig Tree§7: §f12m 4s", " §eHelix Tree§7: §aReady"),
                ::lines,
            ),
        )
    }

    private fun onSecond() {
        if (!config.enabled) return
        if (SkyBlockData.onForagingIsland) scanNameTags()
        sendAlerts()
    }

    private fun scanNameTags() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        val area = SkyBlockData.area ?: return
        val now = System.currentTimeMillis()
        val seen = HashSet<TrackedTree>()

        for (entity in level.entitiesForRendering()) {
            if (entity !is ArmorStand) continue
            val name = entity.customName?.string ?: continue
            val match = nameTag.find(ChatUtils.stripColor(name)) ?: continue
            val left = TimeUtils.parse(match.groupValues[1]) ?: continue
            val tree = trees.firstOrNull {
                it.area == area && abs(it.y - entity.y) < 8 &&
                    (it.x - entity.x) * (it.x - entity.x) + (it.z - entity.z) * (it.z - entity.z) < SAME_TREE_RADIUS_SQ
            }
            if (tree != null) seen += tree
            if (left == FINISHED_TAG) continue

            if (tree == null) {
                val type = treeType(level, area, entity.blockPosition())
                val added = TrackedTree(area, type, entity.x, entity.y, entity.z, now + left)
                trees += added
                seen += added
                Storage.markDirty()
                ChatUtils.chat("Tracking honeycomb on a §6$type§e, ready in §b${TimeUtils.format(left)}§e.")
            } else if (abs(tree.readyAt - (now + left)) > RESYNC_THRESHOLD) {
                tree.readyAt = now + left
                tree.alerted = false
                Storage.markDirty()
            }
        }

        val removed = trees.removeIf { tree ->
            if (tree.area != area) return@removeIf false
            val distanceSq = player.distanceToSqr(tree.x, tree.y, tree.z)
            if (now >= tree.readyAt) return@removeIf tree.alerted && distanceSq < COLLECT_RADIUS_SQ
            // Standing next to a tree that should still be counting down but shows no tag: it is gone.
            if (tree in seen || distanceSq > STALE_RADIUS_SQ) tree.missedScans = 0 else tree.missedScans++
            tree.missedScans >= STALE_SCANS
        }
        if (removed) Storage.markDirty()
    }

    private fun sendAlerts() {
        if (!SkyBlockData.allows(config.alertArea)) return
        val now = System.currentTimeMillis()
        val due = trees.filter { !it.alerted && now >= it.readyAt }
        if (due.isEmpty()) return
        due.forEach { it.alerted = true }
        Storage.markDirty()
        val message = if (due.size == 1) {
            "§aHoneycomb ready! §eA Critter is at your §6${due[0].type} §ein §b${due[0].area}§e."
        } else {
            "§aHoneycomb ready! §eCritters are at §6${due.size} §eof your trees."
        }
        AlertUtils.ready(config.alertChat, config.alertSound, config.alertTitle, message, "§aHoneycomb Ready!")
    }

    private fun lines(): List<String> {
        if (!config.enabled || trees.isEmpty() || !SkyBlockData.allows(config.overlayArea)) return emptyList()
        val now = System.currentTimeMillis()
        return buildList {
            add("§6§lHoneycomb Trees")
            for (tree in trees.sortedBy { it.readyAt }) {
                val island = if (tree.area == SkyBlockData.area) "" else " §7(${tree.area})"
                add(" §e${tree.type}$island§7: ${timeText(tree, now)}")
            }
        }
    }

    private fun onWorldRender(renderer: WorldRenderer) {
        if (!config.enabled || config.beams == BeamMode.OFF) return
        val now = System.currentTimeMillis()
        for (tree in trees) {
            if (tree.area != SkyBlockData.area) continue
            val ready = now >= tree.readyAt
            if (!ready && config.beams == BeamMode.READY_TREES) continue
            renderer.beam(tree.x, tree.y, tree.z, if (ready) READY_COLOR else WAITING_COLOR)
            if (config.floatingText) renderer.text(tree.x, tree.y + FLOATING_TEXT_HEIGHT, tree.z, timeText(tree, now))
        }
    }

    private fun timeText(tree: TrackedTree, now: Long): String {
        val left = tree.readyAt - now
        return if (left <= 0) "§aReady" else TimeUtils.timerColor(left) + TimeUtils.format(left)
    }

    /** Best guess at which tree the honeycomb is on, from the island and the blocks around the name tag. */
    private fun treeType(level: Level, area: String, around: BlockPos): String {
        if (area == SkyBlockData.TORRHUS_CANYON) return "Helix Tree"
        for (pos in BlockPos.betweenClosed(around.offset(-2, -6, -2), around.offset(2, 2, 2))) {
            if ("mangrove" in BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block).path) return "Mangrove Tree"
        }
        return "Fig Tree"
    }
}
