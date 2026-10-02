package dev.nytrix.nyaddons.test

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.NyEvents
import dev.nytrix.nyaddons.core.Storage
import dev.nytrix.nyaddons.gui.PositionEditorScreen
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.Minecraft
import kotlin.math.abs

/**
 * Runs the mod in a singleplayer world, standing in for Hypixel with a fake name tag and
 * fake chat lines. Checks the timers and saves screenshots of every piece of UI.
 */
@Suppress("UnstableApiUsage")
class NyAddOnsGameTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        System.setProperty("nyaddons.devArea", "Moonglade Marsh")
        context.worldBuilder().create().use { world ->
            val server = world.server
            context.onClient { Storage.reset() }
            server.runCommand("execute as @p at @s run tp @s ~ ~ ~ 0 -10")
            world.clientLevel.waitForChunksRender()

            // A lathered tree: the name tag Hypixel shows while the honeycomb is attracting a Critter.
            server.runCommand(
                "execute at @p run summon minecraft:armor_stand ~ ~1 ~6 " +
                    "{CustomName:\"\\u00a7e Critter in: \\u00a7b59m 30s\",CustomNameVisible:1b,NoGravity:1b,Invisible:1b,Tags:[\"honeycomb\"]}",
            )
            context.waitTicks(60)
            context.onClient {
                val trees = Storage.data.honeycombTrees
                check(trees.size == 1) { "expected 1 tracked tree, got ${trees.size}" }
                val left = trees[0].readyAt - System.currentTimeMillis()
                check(abs(left - 3_570_000) < 10_000) { "expected about 59m 30s left, got ${left}ms" }
                check(trees[0].type == "Fig Tree") { "unexpected tree type ${trees[0].type}" }
            }

            // Looting a hive starts the one-hour countdown.
            context.onClient {
                NyEvents.chat.forEach { it("You stick your hand into the honeyhive and feel around...") }
                val left = Storage.data.honeyhiveReadyAt - System.currentTimeMillis()
                check(abs(left - 3_600_000) < 5_000) { "expected about 1h on the hive timer, got ${left}ms" }
            }
            context.waitTicks(20)
            context.takeScreenshot("1-waiting")

            // The server changing the name tag resyncs the timer.
            server.runCommand("data merge entity @e[tag=honeycomb,limit=1] {CustomName:\"Critter in: 2m5s\"}")
            context.waitTicks(40)
            context.onClient {
                val left = Storage.data.honeycombTrees[0].readyAt - System.currentTimeMillis()
                check(abs(left - 125_000) < 10_000) { "expected about 2m 5s after resync, got ${left}ms" }
            }
            context.takeScreenshot("2-resynced")

            // Ready while the player is away: the alert fires and the tree stays listed as Ready.
            server.runCommand("kill @e[tag=honeycomb]")
            server.runCommand("execute as @p at @s run tp @s ~ ~ ~-25")
            context.waitTicks(20)
            context.onClient {
                Storage.data.honeycombTrees[0].readyAt = System.currentTimeMillis() - 1
                Storage.data.honeyhiveReadyAt = System.currentTimeMillis() - 1
            }
            context.waitTicks(40)
            context.onClient {
                check(Storage.data.honeycombTrees.size == 1) { "ready tree was removed while the player was far away" }
                check(Storage.data.honeycombTrees[0].alerted) { "tree alert did not fire" }
                check(Storage.data.honeyhiveAlerted) { "honeyhive alert did not fire" }
            }
            context.takeScreenshot("3-ready")

            // Walking up to the ready tree clears it.
            server.runCommand("execute as @p at @s run tp @s ~ ~ ~22")
            context.waitTicks(40)
            context.onClient {
                check(Storage.data.honeycombTrees.isEmpty()) { "ready tree was not cleared when the player arrived" }
            }

            // Screens.
            context.onClient {
                Storage.data.honeycombTrees.clear()
                NyAddOns.openScreen { PositionEditorScreen() }
            }
            context.waitForScreen(PositionEditorScreen::class.java)
            context.waitTicks(5)
            context.takeScreenshot("4-position-editor")

            // Dragging an overlay moves it.
            val pixelsPerUnit = context.computeOnClient<Double, RuntimeException> { it.window.screenWidth.toDouble() / it.window.guiScaledWidth }
            val treePosition = NyAddOns.config.foraging.honeycombTrees.position
            val startX = treePosition.x
            val startY = treePosition.y
            context.input.setCursorPos((startX + 5) * pixelsPerUnit, (startY + 5) * pixelsPerUnit)
            context.waitTick()
            context.input.holdMouse(0)
            context.waitTick()
            context.input.moveCursor(30 * pixelsPerUnit, 20 * pixelsPerUnit)
            context.waitTick()
            context.input.releaseMouse(0)
            context.waitTick()
            check(treePosition.x == startX + 30 && treePosition.y == startY + 20) {
                "drag moved the overlay to ${treePosition.x},${treePosition.y}, expected ${startX + 30},${startY + 20}"
            }
            context.takeScreenshot("5-dragged")
            context.setScreen { null }

            context.onClient { NyAddOns.openConfig() }
            context.waitFor { it.screen != null }
            context.waitTicks(10)
            context.takeScreenshot("6-config")

            // The Foraging category, second entry in the list on the left.
            val guiScale = context.computeOnClient<Double, RuntimeException> { it.window.screenWidth.toDouble() / it.window.guiScaledWidth }
            val width = context.computeOnClient<Int, RuntimeException> { it.window.guiScaledWidth }
            val height = context.computeOnClient<Int, RuntimeException> { it.window.guiScaledHeight }
            context.input.setCursorPos((width / 2.0 - 115) * guiScale, (height / 2.0 - 10) * guiScale)
            context.waitTick()
            context.input.pressMouse(0)
            context.waitTicks(10)
            context.takeScreenshot("7-config-foraging")
            context.setScreen { null }
        }
        System.clearProperty("nyaddons.devArea")
    }

    private fun ClientGameTestContext.onClient(block: (Minecraft) -> Unit) {
        runOnClient<RuntimeException> { block(it) }
    }
}
