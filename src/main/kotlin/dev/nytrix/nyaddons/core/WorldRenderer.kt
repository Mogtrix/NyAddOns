package dev.nytrix.nyaddons.core

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.blockentity.BeaconRenderer
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
        pose.translate(dx, y - cameraPos.y, dz)
        val time = Math.floorMod(context.levelState().gameTime, 40L).toFloat()
        BeaconRenderer.submitBeaconBeam(
            pose, context.submitNodeCollector(), BeaconRenderer.BEAM_LOCATION,
            1f, time, 0, BEAM_HEIGHT, rgb or OPAQUE, 0.2f, 0.25f,
        )
        pose.popPose()
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
        pose.translate(offset.x, offset.y, offset.z)
        pose.scale(scale, scale, scale)
        context.submitNodeCollector().submitNameTag(
            pose, Vec3.ZERO, 0, text, true, FULL_BRIGHT, distance * distance, camera,
        )
        pose.popPose()
    }

    private companion object {
        const val BEAM_HEIGHT = 320
        const val OPAQUE = 0xFF000000.toInt()
        const val FULL_BRIGHT = 0xF000F0
    }
}
