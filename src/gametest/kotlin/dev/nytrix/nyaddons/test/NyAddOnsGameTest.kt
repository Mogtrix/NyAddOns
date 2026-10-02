package dev.nytrix.nyaddons.test

import dev.nytrix.nyaddons.NyAddOns
import dev.nytrix.nyaddons.core.NyEvents
import dev.nytrix.nyaddons.core.TrackedTree
import dev.nytrix.nyaddons.features.hunting.Shard
import dev.nytrix.nyaddons.gui.OverlayManager
import java.lang.management.ManagementFactory
import dev.nytrix.nyaddons.core.Storage
import dev.nytrix.nyaddons.gui.PositionEditorScreen
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import com.google.gson.JsonParser
import dev.nytrix.nyaddons.features.hunting.FusionCalculator
import dev.nytrix.nyaddons.features.hunting.FusionData
import dev.nytrix.nyaddons.features.hunting.FusionParams
import dev.nytrix.nyaddons.features.hunting.FusionRepo
import dev.nytrix.nyaddons.config.FusionTreeStyle
import dev.nytrix.nyaddons.features.hunting.FusionTracker
import dev.nytrix.nyaddons.features.hunting.FusionTree
import dev.nytrix.nyaddons.features.hunting.ShardPickerScreen
import dev.nytrix.nyaddons.features.hunting.ShardRepo
import dev.nytrix.nyaddons.features.hunting.ShardTracker
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.world.SimpleContainer
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import net.minecraft.client.Minecraft
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * Runs the mod in a singleplayer world, standing in for Hypixel with a fake name tag and
 * fake chat lines. Checks the timers and saves screenshots of every piece of UI.
 */
@Suppress("UnstableApiUsage")
class NyAddOnsGameTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        fusionCalculatorMatchesSkyShards()
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

            shardTracker(context, server)

            // The config screen, in a taller window so each page of options fits in few screenshots.
            context.input.resizeWindow(854, 980)
            context.waitTicks(10)
            val guiScale = context.computeOnClient<Double, RuntimeException> { it.window.screenWidth.toDouble() / it.window.guiScaledWidth }
            val width = context.computeOnClient<Int, RuntimeException> { it.window.guiScaledWidth }
            val height = context.computeOnClient<Int, RuntimeException> { it.window.guiScaledHeight }
            val top = (height - minOf(height - 50, 400)) / 2.0
            val optionsX = width / 2.0 + 66

            fun moveTo(x: Double, y: Double) {
                context.input.setCursorPos(x * guiScale, y * guiScale)
                context.waitTick()
            }

            fun clickAt(x: Double, y: Double) {
                moveTo(x, y)
                context.input.pressMouse(0)
                context.waitTicks(5)
            }

            fun openForaging() {
                context.onClient { NyAddOns.openConfig() }
                context.waitFor { it.screen != null }
                context.waitTicks(10)
                clickAt(width / 2.0 - 115, top + 85)
            }

            fun pageThrough(name: String, pages: Int) {
                moveTo(optionsX, top + 200)
                for (page in 1..pages) {
                    context.takeScreenshot("$name-$page")
                    repeat(12) {
                        context.input.scroll(-3.0)
                        context.waitTicks(3)
                    }
                    context.waitTicks(20)
                }
            }

            context.onClient { NyAddOns.openConfig() }
            context.waitFor { it.screen != null }
            context.waitTicks(10)
            context.takeScreenshot("6-config-gui")
            clickAt(width / 2.0 - 115, top + 85)
            context.takeScreenshot("7-config-foraging")

            // First accordion: honeycomb trees.
            clickAt(optionsX, top + 73)
            pageThrough("8-honeycomb-options", 3)
            context.setScreen { null }

            // Second accordion: honeyhives. Close the first one again so the second sits at a known place.
            openForaging()
            clickAt(optionsX, top + 73)
            clickAt(optionsX, top + 98)
            pageThrough("9-honeyhive-options", 2)
            context.setScreen { null }

            // The Hunting tab, third in the list.
            context.onClient { NyAddOns.openConfig() }
            context.waitFor { it.screen != null }
            context.waitTicks(10)
            clickAt(width / 2.0 - 115, top + 100)
            clickAt(optionsX, top + 73)
            pageThrough("f-hunting-options", 2)
            context.setScreen { null }
        }
        System.clearProperty("nyaddons.devArea")
    }

    /**
     * The port of SkyShards' calculator must give the same fusions and materials as the original
     * for every shard. `fusion/reference.json` was produced by running SkyShards' own code on the
     * data files next to it.
     */
    private fun fusionCalculatorMatchesSkyShards() {
        fun resource(name: String) = javaClass.getResourceAsStream("/fusion/$name") ?: error("missing test resource $name")
        val data = FusionData(
            GZIPInputStream(resource("fusion-data.json.gz")).bufferedReader().readText(),
            resource("rates.json").bufferedReader().readText(),
        )
        val reference = JsonParser.parseString(resource("reference.json").bufferedReader().readText()).asJsonObject
        val totals = mapOf("common" to 96.0, "uncommon" to 64.0, "rare" to 48.0, "epic" to 32.0, "legendary" to 24.0)
        val cases = mapOf(
            "plain" to FusionParams(),
            "maxed" to FusionParams(120.0, 10, 10, 10, 10, 10, 10, 10, 10, 10, craftPenalty = 0.8),
            "loops" to FusionParams(crocodileLevel = 10),
            "mixed" to FusionParams(55.0, 7, 3, 5, 2, 4, 6, 1, 3, 5, "t5", excludeChameleon = true, noWoodenBait = true, craftPenalty = 2.0),
        )
        var compared = 0
        for ((name, params) in cases) {
            val calculator = FusionCalculator(data, params)
            val expectedByShard = reference.getAsJsonObject(name)
            for ((index, id) in data.ids.withIndex()) {
                val expected = expectedByShard.getAsJsonObject(id)
                val plan = calculator.plan(id, totals.getValue(data.rarity[index])) ?: error("no plan for $id")
                check(plan.crafts == expected["crafts"].asLong) { "$name $id: ${plan.crafts} fusions, SkyShards says ${expected["crafts"]}" }
                val materials = expected.getAsJsonObject("materials")
                check(plan.materials.keys == materials.keySet()) { "$name $id: materials ${plan.materials.keys}, SkyShards says ${materials.keySet()}" }
                for ((material, amount) in plan.materials) {
                    check(abs(amount - materials[material].asDouble) < 1e-6) { "$name $id: $amount of $material, SkyShards says ${materials[material]}" }
                }
                compared++
            }
        }
        check(compared == cases.size * 322) { "compared $compared plans" }
    }

    private fun headStack(name: String, vararg lore: String) = ItemStack(Items.PLAYER_HEAD).apply {
        set(DataComponents.CUSTOM_NAME, Component.literal(name))
        set(DataComponents.LORE, ItemLore(lore.map { Component.literal(it) }))
    }

    /** Opens a six-row menu with the given title and items, like the ones Hypixel sends. */
    private fun openMenu(context: ClientGameTestContext, title: String, items: Map<Int, ItemStack>) {
        context.setScreen {
            val inventory = Minecraft.getInstance().player!!.inventory
            val container = SimpleContainer(54)
            items.forEach { (slot, stack) -> container.setItem(slot, stack) }
            ContainerScreen(ChestMenu.sixRows(0, inventory, container), inventory, Component.literal(title))
        }
        context.waitForScreen(ContainerScreen::class.java)
    }

    /** The Hunting Box is stood in for by a chest with the same title, item names and lore. */
    private fun shardTracker(context: ClientGameTestContext, server: TestServerContext) {
        context.waitFor({ ShardRepo.loaded }, 600)
        val grove = ShardRepo.byName("Grove") ?: error("Grove is missing from the shard list")
        val hideonring = ShardRepo.byName("Hideonring") ?: error("Hideonring is missing from the shard list")
        check(ShardRepo.totalToMax(grove) == 96 && ShardRepo.totalToMax(hideonring) == 48) { "unexpected totals to max" }

        fun item(slot: Int, name: String, vararg lore: String) =
            "{Slot:${slot}b,id:\"minecraft:player_head\",count:1,components:{\"minecraft:custom_name\":\"$name\"," +
                "\"minecraft:lore\":[${lore.joinToString(",") { "\"$it\"" }}]}}"

        server.runCommand(
            "execute at @p run setblock ~ ~ ~2 minecraft:chest{CustomName:\"Hunting Box\",Items:[" +
                item(10, "Grove", "Nature Elemental VI (Foraging)", "Owned: 15 Shards", "Syphon 3 more to level up!") + "," +
                item(11, "Hideonring", "Accessory Size X (Combat)", "Owned: 1,729 Shards", "Attribute Maxed!") +
                "]}",
        )
        server.runCommand("execute as @p at @s run tp @s ~ ~ ~ 0 30")
        context.waitTicks(10)
        context.input.pressKey { it.keyUse }
        context.waitForScreen(ContainerScreen::class.java)
        context.waitTicks(10)

        // Level 6 is 30 shards, plus 7 of the 10 towards level 7.
        context.onClient {
            val progress = ShardTracker.progress(grove)
            check(progress.owned == 15 && progress.syphoned == 37) { "Grove read as ${progress.owned} owned, ${progress.syphoned} syphoned" }
            check(ShardTracker.neededToMax(grove) == 44) { "Grove needs ${ShardTracker.neededToMax(grove)}, expected 44" }
            check(ShardTracker.progress(hideonring).owned == 1729 && ShardTracker.isMaxed(hideonring)) { "Hideonring not read as maxed" }
            val unowned = ShardRepo.all.first { it !== grove && it !== hideonring }
            check(ShardTracker.progress(unowned).owned == 0) { "shards missing from the box were not set to zero" }
        }

        // The track key over a hovered shard.
        val scale = context.computeOnClient<Double, RuntimeException> { it.window.screenWidth.toDouble() / it.window.guiScaledWidth }
        val (slotX, slotY) = context.computeOnClient<Pair<Int, Int>, RuntimeException> {
            val slot = (it.screen as ContainerScreen).menu.slots[10]
            (it.window.guiScaledWidth - 176) / 2 + slot.x + 8 to (it.window.guiScaledHeight - 168) / 2 + slot.y + 8
        }
        context.input.setCursorPos(slotX * scale, slotY * scale)
        context.waitTicks(2)
        context.input.pressKey(72)
        context.waitTicks(2)
        context.onClient { check(ShardTracker.isTracked(grove)) { "the track key did not track the hovered shard" } }
        context.takeScreenshot("a-hunting-box-key")
        context.setScreen { null }

        // The command, typed like a player would.
        context.onClient { it.connection!!.sendCommand("hunt hideonring") }
        context.waitTicks(2)
        context.onClient { check(ShardTracker.isTracked(hideonring)) { "/hunt did not track the shard" } }

        // Chat keeps the numbers current.
        context.onClient {
            NyEvents.chat.forEach { it("You caught x2 Grove Shards!") }
            check(ShardTracker.progress(grove).owned == 17) { "catch message not counted" }
            NyEvents.chat.forEach { it("+2 Nature Elemental Attribute (Level 6) - 1 more to upgrade!") }
            val progress = ShardTracker.progress(grove)
            check(progress.owned == 15 && progress.syphoned == 39) { "syphon message gave ${progress.owned} owned, ${progress.syphoned} syphoned" }
            NyEvents.chat.forEach { it("LOOT SHARE You received 2 Grove Shards for assisting FallenYeti!") }
            NyEvents.chat.forEach { it("GOOD CATCH! You caught Grove Shard x3!") }
            check(ShardTracker.progress(grove).owned == 20) { "loot share and fishing messages not counted" }
        }
        context.waitTicks(25)
        context.takeScreenshot("b-shard-overlay")

        // The fusion tree for what Grove still needs.
        context.waitFor({ FusionRepo.data != null }, 1200)
        context.waitTicks(30)
        context.waitFor({ FusionTracker.upToDate }, 600)
        context.takeScreenshot("g-fusion-materials")

        // The fusion tree beside a stand-in Fusion Box, with enough of both ingredients for one fusion.
        context.onClient {
            NyEvents.chat.forEach { it("You caught x10 Flitter Shards!") }
            NyEvents.chat.forEach { it("You caught x7 Salmon Shards!") }
        }
        server.runCommand("execute at @p run setblock ~ ~ ~2 minecraft:air")
        server.runCommand(
            "execute at @p run setblock ~ ~ ~2 minecraft:chest{CustomName:\"Fusion Box\",Items:[" +
                item(10, "Flitter", "Owned: 10 Shards") + "," + item(12, "Salmon", "Owned: 7 Shards") + "," +
                item(14, "Grove", "Owned: 20 Shards") + "]}",
        )
        context.waitTicks(10)
        context.input.pressKey { it.keyUse }
        context.waitForScreen(ContainerScreen::class.java)
        context.waitTicks(30)
        context.waitFor({ FusionTracker.upToDate }, 600)
        context.onClient {
            val next = FusionTree.nextIngredients().map { it.name }.toSet()
            check(next == setOf("Flitter", "Salmon")) { "next fusion uses $next, expected Flitter and Salmon" }
        }
        for (style in FusionTreeStyle.entries) {
            context.onClient { NyAddOns.config.hunting.fusionTree.style = style }
            context.waitTicks(3)
            context.takeScreenshot("h-fusion-tree-${style.name.lowercase()}")
        }
        context.onClient { NyAddOns.config.hunting.fusionTree.style = FusionTreeStyle.TREE }
        context.setScreen { null }

        // The same in a window the size people play in, where the tree fits beside the menu.
        context.input.resizeWindow(1280, 720)
        context.onClient {
            it.options.guiScale().set(2)
            it.resizeGui()
            NyAddOns.config.hunting.fusionTree.positioned = false
        }
        context.waitTicks(5)
        context.input.pressKey { it.keyUse }
        context.waitForScreen(ContainerScreen::class.java)
        context.waitTicks(10)
        for (style in FusionTreeStyle.entries) {
            context.onClient { NyAddOns.config.hunting.fusionTree.style = style }
            context.waitTicks(3)
            context.takeScreenshot("i-fusion-tree-wide-${style.name.lowercase()}")
        }
        context.onClient { NyAddOns.config.hunting.fusionTree.style = FusionTreeStyle.TREE }
        context.setScreen { null }
        context.input.resizeWindow(854, 480)
        context.onClient {
            it.options.guiScale().set(0)
            it.resizeGui()
            NyAddOns.config.hunting.fusionTree.positioned = false
        }
        context.waitTicks(5)

        // Shard Fusion, a six-row menu: one ingredient is already in the machine (row 1), so only the other is lit in the list.
        openMenu(context, "Shard Fusion", mapOf(
            10 to headStack("Flitter", "Owned: 10 Shards"), 28 to headStack("Flitter", "Owned: 10 Shards"),
            29 to headStack("Salmon", "Owned: 7 Shards"), 30 to headStack("Grove", "Owned: 22 Shards"),
        ))
        context.waitTicks(15)
        context.onClient {
            val lit = FusionTree.highlightedSlots(it.screen as ContainerScreen)
            check(lit == listOf(29)) { "with Flitter in the machine only Salmon (slot 29) should be lit, got $lit" }
        }
        context.takeScreenshot("j-shard-fusion-highlight")

        // F8 copies what is in the menu.
        context.input.pressKey(297)
        context.waitTicks(5)
        context.onClient {
            val copied = it.keyboardHandler.clipboard
            check(copied.startsWith("Menu \"Shard Fusion\"") && "slot 29" in copied && "name: Salmon" in copied && "lore: Owned: 7 Shards" in copied) {
                "the F8 copy was: ${copied.take(200)}"
            }
        }
        context.setScreen { null }

        // With nothing in the machine yet, both ingredients are lit.
        openMenu(context, "Shard Fusion", mapOf(28 to headStack("Flitter", "Owned: 10 Shards"), 29 to headStack("Salmon", "Owned: 7 Shards")))
        context.waitTicks(15)
        context.onClient {
            val lit = FusionTree.highlightedSlots(it.screen as ContainerScreen)
            check(lit == listOf(28, 29)) { "with an empty machine both ingredients should be lit, got $lit" }
        }
        context.setScreen { null }

        // The Fusion Box has no machine area: whatever is in its list is lit.
        openMenu(context, "Fusion Box", mapOf(10 to headStack("Flitter", "Owned: 10 Shards"), 11 to headStack("Salmon", "Owned: 7 Shards")))
        context.waitTicks(15)
        context.onClient {
            val lit = FusionTree.highlightedSlots(it.screen as ContainerScreen)
            check(lit == listOf(10, 11)) { "in the Fusion Box both ingredients should be lit, got $lit" }
        }
        context.setScreen { null }

        // Confirm Fusion: the lime button is lit once the screen shows the next fusion.
        openMenu(context, "Confirm Fusion", mapOf(
            12 to headStack("Flitter", "Required to fuse: 5"), 14 to headStack("Fusion Ingredient", "Salmon Shard", "Required to fuse: 5"),
            33 to ItemStack(Items.LIME_TERRACOTTA),
        ))
        context.waitTicks(25)
        context.onClient {
            val lit = FusionTree.highlightedSlots(it.screen as ContainerScreen)
            check(lit == listOf(33)) { "the confirm button should be lit, got $lit" }
        }
        context.takeScreenshot("k-confirm-fusion-highlight")
        context.setScreen { null }
        context.onClient {
            check(FusionTree.stillNeeded(listOf("A", "A"), listOf("A")) == setOf("A")) { "two of the same shard, one in the machine" }
            check(FusionTree.stillNeeded(listOf("A", "A"), listOf("A", "A")).isEmpty()) { "two of the same shard, both in the machine" }
            check(FusionTree.stillNeeded(listOf("A", "B"), listOf("A")) == setOf("B")) { "one of two in the machine" }
        }

        // Fusing takes the two ingredients shown on the Confirm Fusion screen off the counts,
        // and the fusions left count down as it happens.
        val leftBefore = LongArray(1)
        context.onClient {
            leftBefore[0] = FusionTracker.totalFusionsLeft(FusionTracker.targets.first { it.shard === grove }.plan.root)
        }
        context.onClient {
            fun stack(name: String, vararg lore: String) = ItemStack(Items.PLAYER_HEAD).apply {
                set(DataComponents.CUSTOM_NAME, Component.literal(name))
                set(DataComponents.LORE, ItemLore(lore.map { Component.literal(it) }))
            }
            val flitter = ShardRepo.byName("Flitter")!!
            val salmon = ShardRepo.byName("Salmon")!!
            ShardTracker.readConfirmFusion(stack("Flitter", "Required to fuse: 5"), stack("Fusion Ingredient", "Salmon Shard", "Required to fuse: 5"))
            NyEvents.chat.forEach { it("FUSION! You obtained Grove Shard x2!") }
            val counts = listOf(flitter, salmon, grove).map { ShardTracker.progress(it).owned }
            check(counts == listOf(5, 2, 22)) { "after a fusion the counts are $counts, expected [5, 2, 22]" }
            NyEvents.chat.forEach { it("PURE REPTILE You received double shards from the fusion!") }
            check(ShardTracker.progress(grove).owned == 24) { "the doubled fusion was not counted" }
        }
        context.waitTicks(15)
        context.onClient {
            val after = FusionTracker.totalFusionsLeft(FusionTracker.targets.first { it.shard === grove }.plan.root)
            check(after < leftBefore[0]) { "fusions left went from ${leftBefore[0]} to $after, expected fewer after fusing" }
        }
        context.takeScreenshot("l-fusions-left")

        // The picker.
        context.onClient { it.connection!!.sendCommand("hunt") }
        context.waitForScreen(ShardPickerScreen::class.java)
        context.waitTicks(40)
        context.takeScreenshot("c-shard-picker")
        context.input.typeChars("accessory")
        context.waitTicks(5)
        context.takeScreenshot("d-shard-picker-search")
        context.setScreen { null }

        // Owning enough to max sends the alert once.
        context.onClient {
            NyEvents.chat.forEach { it("You caught x40 Grove Shards!") }
        }
        context.waitTicks(25)
        context.onClient { check(ShardTracker.progress(grove).alertedMaxable) { "the enough-to-max alert did not fire" } }
        context.takeScreenshot("e-shard-enough-to-max")
        benchmark(context, server, grove)
        server.runCommand("execute at @p run setblock ~ ~ ~2 minecraft:air")
    }

    /** Times the work the mod does every frame, tick, second and chat line, and logs it as `[NyBench]` lines. */
    private fun benchmark(context: ClientGameTestContext, server: TestServerContext, grove: Shard) {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

        fun measure(name: String, runs: Int, block: () -> Unit) {
            repeat(runs / 10) { block() }
            val thread = Thread.currentThread().threadId()
            val bytesBefore = threads.getThreadAllocatedBytes(thread)
            val start = System.nanoTime()
            repeat(runs) { block() }
            val nanos = System.nanoTime() - start
            val bytes = threads.getThreadAllocatedBytes(thread) - bytesBefore
            NyAddOns.logger.info("[NyBench] $name: ${nanos / runs} ns and ${bytes / runs} bytes per call")
        }

        fun placeChest(nbt: String) {
            server.runCommand("execute at @p run setblock ~ ~ ~2 minecraft:air")
            server.runCommand("execute at @p run setblock ~ ~ ~2 minecraft:chest$nbt")
            context.waitTicks(10)
            context.input.pressKey { it.keyUse }
            context.waitForScreen(ContainerScreen::class.java)
            context.waitTicks(10)
        }

        fun item(slot: Int, name: String, vararg lore: String) =
            "{Slot:${slot}b,id:\"minecraft:player_head\",count:1,components:{\"minecraft:custom_name\":\"$name\"," +
                "\"minecraft:lore\":[${lore.joinToString(",") { "\"$it\"" }}]}}"

        // A tree to fuse, with one fusion ready, and a honeycomb tree and hive timer on the HUD.
        context.onClient {
            ShardTracker.progress(grove).apply { owned = 20; syphoned = 39 }
            listOf("Flitter", "Salmon").forEach { ShardTracker.progress(ShardRepo.byName(it)!!).owned = 10 }
            Storage.data.honeycombTrees += TrackedTree("Moonglade Marsh", "Fig Tree", 0.0, -60.0, 30.0, System.currentTimeMillis() + 600_000)
            Storage.data.honeyhiveReadyAt = System.currentTimeMillis() + 600_000
        }
        context.waitTicks(30)
        context.waitFor({ FusionTracker.upToDate }, 600)

        context.onClient {
            measure("HUD overlays, one frame", 10_000) { benchmarkHudFrame() }
            measure("HUD overlays, rebuild (4 times a second)", 10_000) { OverlayManager.invalidate(); benchmarkHudFrame() }
        }

        val shards = (0 until 27).joinToString(",") { item(it, if (it % 2 == 0) "Flitter" else "Salmon", "Owned: 10 Shards") }
        placeChest("{CustomName:\"Fusion Box\",Items:[$shards]}")
        context.onClient { mc ->
            val screen = mc.screen as ContainerScreen
            measure("fusion menu, one frame", 2_000) { FusionTree.benchmarkMenuFrame(screen) }
            measure("fusion menu, rebuild (4 times a second)", 2_000) { OverlayManager.invalidate(); FusionTree.benchmarkMenuFrame(screen) }
        }
        context.setScreen { null }

        // An ordinary chest must not get the fusion tree attached at all.
        val hooksBefore = context.computeOnClient<Int, RuntimeException> { FusionTree.menuHooks }
        placeChest("{Items:[$shards]}")
        context.onClient {
            check(FusionTree.menuHooks == hooksBefore) { "the fusion tree attached itself to an ordinary chest" }
            NyAddOns.logger.info("[NyBench] ordinary chest, one frame: nothing runs")
        }
        context.setScreen { null }

        val box = (0 until 27).joinToString(",") {
            item(it, if (it % 2 == 0) "Flitter" else "Salmon", "Ability I (Combat)", "Owned: 10 Shards", "Syphon 2 more to level up!", "Some Family", "COMMON SHARD (ID C1)")
        }
        placeChest("{CustomName:\"Hunting Box\",Items:[$box]}")
        context.onClient {
            // With nothing changing in the box, it must not be read again.
            val readsBefore = ShardTracker.menuReads
            measure("Hunting Box open, one tick", 8_000) { NyEvents.tick.forEach { it() } }
            check(ShardTracker.menuReads == readsBefore) { "the Hunting Box was read again although nothing in it changed" }
        }
        context.setScreen { null }

        val chat = listOf(
            "[MVP+] Someone: anyone selling 5 Grove Shard cheap", "Guild > Friend: gg", "You earned 12 coins!",
            "[NPC] Baker: Fresh bread today.", "Party > Leader: warp in 5", "+3 Foraging (Fig Log)",
            "RARE DROP! Enchanted Fig Log", "You caught x2 Unknownthing Shards!", "[Lv120] Player: wts armor", "Sending to server mini22A...",
        )
        var line = 0
        context.onClient { measure("one chat line", 20_000) { val text = chat[line++ % chat.size]; NyEvents.chat.forEach { it(text) } } }

        server.runCommand("execute at @p run summon minecraft:armor_stand ~3 ~ ~3 {CustomName:\"Hologram Line\",CustomNameVisible:1b,NoGravity:1b,Tags:[\"bench\"]}")
        repeat(299) { index ->
            server.runCommand(
                "execute at @p run summon minecraft:armor_stand ~${3 + index % 20} ~ ~${3 + index / 20} " +
                    "{CustomName:\"Hologram Line $index\",CustomNameVisible:1b,NoGravity:1b,Tags:[\"bench\"]}",
            )
        }
        context.waitTicks(20)
        context.onClient { measure("once a second, 300 holograms nearby", 500) { NyEvents.second.forEach { it() } } }
        server.runCommand("kill @e[tag=bench]")

        context.onClient {
            fun resource(name: String) = javaClass.getResourceAsStream("/fusion/$name")!!
            val json = GZIPInputStream(resource("fusion-data.json.gz")).bufferedReader().readText()
            val rates = resource("rates.json").bufferedReader().readText()
            FusionData(json, rates)
            val thread = Thread.currentThread().threadId()
            val bytesBefore = threads.getThreadAllocatedBytes(thread)
            val start = System.nanoTime()
            var data: FusionData? = FusionData(json, rates)
            val millis = (System.nanoTime() - start) / 1_000_000
            val allocated = (threads.getThreadAllocatedBytes(thread) - bytesBefore) / 1_048_576
            fun usedHeap(): Long {
                repeat(3) { System.gc(); Thread.sleep(100) }
                return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
            }
            val withData = usedHeap()
            check(data!!.ids.size == 322)
            data = null
            val kept = (withData - usedHeap()) / 1024
            NyAddOns.logger.info("[NyBench] recipe data: $millis ms to load, $allocated MB allocated while loading, about $kept KB kept in memory")
        }
    }

    /** What the HUD does for the overlays every frame, apart from the drawing itself. */
    private fun benchmarkHudFrame() {
        for (overlay in OverlayManager.overlays) {
            if (overlay.onHud) overlay.current()
        }
    }

    private fun ClientGameTestContext.onClient(block: (Minecraft) -> Unit) {
        runOnClient<RuntimeException> { block(it) }
    }
}
