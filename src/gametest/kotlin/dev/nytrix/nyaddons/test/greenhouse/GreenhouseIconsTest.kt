package dev.nytrix.nyaddons.test.greenhouse

import dev.nytrix.nyaddons.features.greenhouse.GhIcons
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Draws a grid of Greenhouse icons (vanilla items, heads from a local fixture, and an unknown id) (the allocation check is skipped: the vanilla item draw allocates its own render state). */
@Suppress("UnstableApiUsage")
class GreenhouseIconsTest : FabricClientGameTest {

    private val ids = listOf(
        "wheat", "carrot", "nether_wart", "melon", "cactus", "moonflower", "sunflower", "wild_rose",
        "ashwreath", "cheesebite", "devourer", "glasscorn", "timestalk", "all_in_aloe", "no_such_icon",
    )

    private class IconScreen(val ids: List<String>, val size: Int) : Screen(Component.literal("icons")) {
        override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
            for ((i, id) in ids.withIndex()) GhIcons.draw(graphics, id, 20 + (i % 8) * 40, 20 + (i / 8) * 40, size)
        }
    }

    override fun runTest(context: ClientGameTestContext) {
        // Real NEU skin values (base64 of the textures JSON), copied from the repo; offline fixture.
        val fixture = java.util.Properties().apply {
            GreenhouseIconsTest::class.java.getResourceAsStream("/gh_icons_fixture.properties")!!.use { load(it) }
        }.entries.associate { it.key.toString() to it.value.toString() }
        check(fixture.size >= 4) { "fixture missing" }
        GhIcons.useTextures(fixture)
        context.worldBuilder().create().use {
            context.input.resizeWindow(854, 480)
            context.runOnClient<RuntimeException> { it.setScreen(IconScreen(ids, 32)) }
            context.waitTicks(20)
            context.takeScreenshot("gh-icons-grid")
            check(GhIcons.has("ashwreath") && !GhIcons.has("no_such_icon"))
            context.runOnClient<RuntimeException> { it.setScreen(IconScreen(ids, 16)) }
            context.waitTicks(10)
            context.takeScreenshot("gh-icons-grid-16")
            context.setScreen { null }
        }
    }
}
