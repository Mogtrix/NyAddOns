package dev.nytrix.nyaddons.core

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.blockentity.BeaconRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import kotlin.math.floor
import kotlin.math.sqrt

/** Draws beams and floating text in the world for one frame. Handed to [NyEvents.worldRender] listeners. */
class WorldRenderer(private val context: LevelRenderContext) {

    private val camera = context.levelState().cameraRenderState
    private val cameraPos: Vec3 = camera.pos

    // Things further than this are pulled in along the line of sight so they are not clipped away.
    private val maxDistance = (Minecraft.getInstance().options.renderDistance().get() * 16 * 0.8).coerceIn(48.0, 192.0)

    /** A beacon beam rising from the block at [x], [y], [z]. [rgb] is 0xRRGGBB. */
    fun beam(x: Double, y: Double, z: Double, rgb: Int) {
        var dx = floor(x) - cameraPos.x
        var dz = floor(z) - cameraPos.z
        val horizontal = sqrt(dx * dx + dz * dz)
        if (horizontal > maxDistance) {
            dx *= maxDistance / horizontal
            dz *= maxDistance / horizontal
        }
        val pose = context.poseStack()
        pose.pushPose()
        try {
            pose.translate(dx, y - cameraPos.y, dz)
            val time = Math.floorMod(context.levelState().gameTime, 40L).toFloat()
            BeaconRenderer.submitBeaconBeam(
                pose, context.submitNodeCollector(), BeaconRenderer.BEAM_LOCATION,
                1f, time, 0, BEAM_HEIGHT, rgb or OPAQUE, 0.2f, 0.25f,
            )
        } finally {
            pose.popPose()
        }
    }

    /** Text that always faces the camera, visible through blocks and kept readable at a distance. */
    fun text(x: Double, y: Double, z: Double, text: Component) {
        var offset = Vec3(x - cameraPos.x, y - cameraPos.y, z - cameraPos.z)
        var distance = offset.length()
        if (distance > maxDistance) {
            offset = offset.scale(maxDistance / distance)
            distance = maxDistance
        }
        val scale = (distance / 6.0).coerceAtLeast(1.0).toFloat()
        val pose = context.poseStack()
        pose.pushPose()
        try {
            pose.translate(offset.x, offset.y, offset.z)
            pose.scale(scale, scale, scale)
            context.submitNodeCollector().submitNameTag(
                pose, Vec3.ZERO, 0, text, true, FULL_BRIGHT, distance * distance, camera,
            )
        } finally {
            pose.popPose()
        }
    }

    /** The camera position this frame, for distance checks. */
    val cameraX get() = cameraPos.x
    val cameraY get() = cameraPos.y
    val cameraZ get() = cameraPos.z

    /** A see-through box from the corner at [x], [y], [z], [sx] east, [sy] up and [sz] south in size. [argb] carries the alpha. */
    fun box(x: Double, y: Double, z: Double, sx: Double, sy: Double, sz: Double, argb: Int) {
        val pose = context.poseStack()
        pose.pushPose()
        try {
            pose.translate(x - cameraPos.x, y - cameraPos.y, z - cameraPos.z)
            context.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.debugQuads()) { entry, buffer ->
                val x1 = sx.toFloat()
                val y1 = sy.toFloat()
                val z1 = sz.toFloat()
                quad(entry, buffer, argb, 0f, y1, 0f, x1, y1, 0f, x1, y1, z1, 0f, y1, z1)
                quad(entry, buffer, argb, 0f, 0f, 0f, 0f, 0f, z1, x1, 0f, z1, x1, 0f, 0f)
                quad(entry, buffer, argb, 0f, 0f, 0f, x1, 0f, 0f, x1, y1, 0f, 0f, y1, 0f)
                quad(entry, buffer, argb, 0f, 0f, z1, 0f, y1, z1, x1, y1, z1, x1, 0f, z1)
                quad(entry, buffer, argb, 0f, 0f, 0f, 0f, y1, 0f, 0f, y1, z1, 0f, 0f, z1)
                quad(entry, buffer, argb, x1, 0f, 0f, x1, 0f, z1, x1, y1, z1, x1, y1, 0f)
            }
        } finally {
            pose.popPose()
        }
    }

    private fun quad(
        entry: PoseStack.Pose, buffer: VertexConsumer, argb: Int,
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float,
    ) {
        buffer.addVertex(entry, ax, ay, az).setColor(argb)
        buffer.addVertex(entry, bx, by, bz).setColor(argb)
        buffer.addVertex(entry, cx, cy, cz).setColor(argb)
        buffer.addVertex(entry, dx, dy, dz).setColor(argb)
    }

    private companion object {
        const val BEAM_HEIGHT = 320
        const val OPAQUE = 0xFF000000.toInt()
        const val FULL_BRIGHT = 0xF000F0
    }
}
