package dev.nytrix.nyaddons.test.magicfind

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.features.magicfind.MagicFind
import dev.nytrix.nyaddons.features.magicfind.MagicFindScreen
import dev.nytrix.nyaddons.features.magicfind.MagicFindWindowFeature
import dev.nytrix.nyaddons.features.magicfind.MfCategory
import dev.nytrix.nyaddons.features.magicfind.MfData
import dev.nytrix.nyaddons.features.magicfind.MfDrop
import dev.nytrix.nyaddons.features.magicfind.MfMob
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/** The /mf window: defaults applied once, category dropdown, checkboxes, All on / All off, footer counts. */
@Suppress("UnstableApiUsage")
class MagicFindWindowTest : FabricClientGameTest {

    private fun mob(id: String, name: String, cat: String, chance: Double, tier: Int = 0, on: Boolean = false) =
        MfMob(id, name, cat, listOf(MfDrop("Rare Thing of $name", chance, false, false, null), MfDrop("Common Thing", 0.04, false, false, null)), tier, on)

    private val categories = listOf(
        MfCategory("den", "Spider's Den", listOf(
            mob("broodmother", "Broodmother", "den", 0.0002),
            mob("tarantula", "Tarantula Spider", "den", 0.01),
            mob("weaver", "Weaver Spider", "den", 0.03),
        )),
        MfCategory("end", "The End", (1..20).map { mob("endmob$it", "End Mob $it with a Long Name", "end", 0.001 * it) } +
            mob("king_minos", "King Minos", "end", 0.00001, on = true)),
        MfCategory("slayer", "Slayer Bosses", listOf(
            mob("revenant_horror_v", "Revenant Horror V", "slayer", 0.002, tier = 5, on = true),
            mob("tarantula_broodfather_iv", "Tarantula Broodfather IV", "slayer", 0.002, tier = 4),
            mob("tarantula_broodfather_v", "Tarantula Broodfather V", "slayer", 0.002, tier = 5, on = true),
        )),
    )

    private val fakeData = object : MfData {
        override val ready = true
        override val categories = this@MagicFindWindowTest.categories
        override fun mob(name: String): MfMob? = null
        override fun mobNames() = emptyList<String>()
        override fun request() {}
    }

    override fun runTest(context: ClientGameTestContext) {
        val oldData = MagicFind.data
        val cfg = NyAddOns.config.combat.magicFind
        val oldEnabled = context.computeOnClient<Set<String>, RuntimeException> { cfg.enabledMobs.toSet() }
        val oldApplied = cfg.defaultsApplied
        try {
            context.worldBuilder().create().use { run(context) }
        } finally {
            context.onClient {
                MagicFind.data = oldData
                cfg.enabledMobs = oldEnabled.toMutableSet()
                cfg.defaultsApplied = oldApplied
            }
            context.setScreen { null }
            context.input.resizeWindow(854, 480)
        }
    }

    private fun enabled(context: ClientGameTestContext) =
        context.computeOnClient<Set<String>, RuntimeException> { NyAddOns.config.combat.magicFind.enabledMobs.toSet() }

    private fun run(context: ClientGameTestContext) {
        val cfg = NyAddOns.config.combat.magicFind
        context.input.resizeWindow(854, 480)
        context.onClient {
            cfg.enabledMobs = mutableSetOf("preexisting")
            cfg.defaultsApplied = false
            MagicFind.data = fakeData
        }
        // Defaults come from the once-a-second hook, with no window open.
        check(context.computeOnClient<Boolean, RuntimeException> { MagicFindWindowFeature.applyDefaults() }) { "defaults not applied" }
        val defaults = setOf("preexisting", "king_minos", "revenant_horror_v", "tarantula_broodfather_v")
        check(enabled(context) == defaults) { "wrong defaults: ${enabled(context)}" }
        // The user turns one off; opening the window must not re-apply.
        context.onClient { cfg.enabledMobs.remove("king_minos") }
        check(!context.computeOnClient<Boolean, RuntimeException> { MagicFindWindowFeature.applyDefaults() }) { "defaults applied twice" }

        context.onClient { MagicFindScreen.open() }
        context.waitForScreen(MagicFindScreen::class.java)
        context.waitTicks(25)
        check("king_minos" !in enabled(context)) { "reopening re-applied defaults" }
        check(screen(context).header.contains("0") && screen(context).header.contains("of") && screen(context).footer.contains("0")) { "header/footer: ${screen(context).header}" }
        context.takeScreenshot("mf-1-den-854")

        click(context) { it.checkboxCenter(1) }
        check("tarantula" in enabled(context)) { "checkbox did not enable Tarantula" }
        check(NyAddOns.config.combat.magicFind.enabledMobs.contains("tarantula"))
        check(screen(context).header.startsWith("§f1 ")) { "header: ${screen(context).header}" }
        click(context) { it.checkboxCenter(1) }
        check("tarantula" !in enabled(context)) { "checkbox did not disable Tarantula" }

        click(context) { it.allOnCenter() }
        check(enabled(context).containsAll(setOf("broodmother", "tarantula", "weaver", "revenant_horror_v"))) { "All on failed" }
        check(screen(context).header.startsWith("§f3 §7of §f3")) { "header after all on: ${screen(context).header}" }
        context.waitTicks(2)
        context.takeScreenshot("mf-2-den-allon-854")
        click(context) { it.allOffCenter() }
        check(enabled(context).none { it in setOf("broodmother", "tarantula", "weaver") }) { "All off failed" }
        check("revenant_horror_v" in enabled(context)) { "All off touched another category" }

        pick(context, 1)
        check(screen(context).rowCount == 21) { "The End should list 21 mobs" }
        context.takeScreenshot("mf-3-end-854")
        pick(context, 2)
        check(screen(context).rowCount == 3) { "Slayer Bosses should list 3 mobs" }
        context.takeScreenshot("mf-4-slayer-854")
        click(context) { it.checkboxCenter(0) }
        check("revenant_horror_v" !in enabled(context)) { "Slayer checkbox failed" }
        click(context) { it.checkboxCenter(0) }

        // Drop dropdown: the arrow expands a mob's drops, clicking a drop follows it, clicking it again stops.
        pick(context, 0)
        val collapsed = screen(context).rowCount
        click(context) { it.arrowCenter(0) }
        check(screen(context).rowCount > collapsed) { "arrow did not list the drops (${screen(context).rowCount} vs $collapsed)" }
        context.takeScreenshot("mf-8-drops-854")
        val firstId = categories[0].mobs[0].id
        check("broodmother" == firstId) { "fixture changed: $firstId" }
        click(context) { it.dropRowCenter(1) }
        check(cfg.trackedMobs.contains(firstId) && cfg.trackedDrops[firstId] != null) { "clicking a drop did not follow it: ${cfg.trackedDrops}" }
        context.waitTicks(2)
        context.takeScreenshot("mf-9-drop-tracked-854")
        click(context) { it.dropRowCenter(1) }
        check(!cfg.trackedMobs.contains(firstId) && cfg.trackedDrops.isEmpty()) { "clicking the drop again did not stop" }
        click(context) { it.arrowCenter(0) }
        check(screen(context).rowCount == collapsed) { "arrow did not collapse" }
        check(firstId !in enabled(context)) { "arrow toggled the checkbox" }

        context.input.resizeWindow(1280, 720)
        context.waitTicks(10)
        context.takeScreenshot("mf-5-slayer-1280")
        pick(context, 1)
        context.takeScreenshot("mf-6-end-1280")
        click(context) { it.dropdownCenter() }
        context.takeScreenshot("mf-7-dropdown-1280")
        context.setScreen { null }
        context.input.resizeWindow(854, 480)
    }

    private fun ClientGameTestContext.onClient(block: (net.minecraft.client.Minecraft) -> Unit) {
        runOnClient<RuntimeException> { block(it) }
    }

    private fun screenNow() = net.minecraft.client.Minecraft.getInstance().screen as MagicFindScreen

    private fun screen(context: ClientGameTestContext) = context.computeOnClient<MagicFindScreen, RuntimeException> { screenNow() }

    private fun pick(context: ClientGameTestContext, index: Int) {
        click(context) { it.dropdownCenter() }
        click(context) { it.dropdownOptionCenter(index) }
        context.waitTicks(2)
    }

    private fun click(context: ClientGameTestContext, where: (MagicFindScreen) -> IntArray) {
        val (x, y, scale) = context.computeOnClient<Triple<Int, Int, Double>, RuntimeException> {
            val p = where(screenNow())
            Triple(p[0], p[1], it.window.screenWidth.toDouble() / it.window.guiScaledWidth)
        }
        context.input.setCursorPos(x * scale, y * scale)
        context.waitTick()
        context.input.holdMouse(0)
        context.waitTick()
        context.input.releaseMouse(0)
        context.waitTick()
    }
}
